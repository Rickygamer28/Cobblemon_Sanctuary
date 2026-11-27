package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.BiomeDresser
import com.rickygamer28.sanctuary.world.Caves
import com.rickygamer28.sanctuary.world.TownBuildings
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Things to do out in the habitats: fishing with Water types and their swim races at the lake, crystal mining in the
 * caves, the volcano's hot spring and its evening rumble, the desert's ruins and mirages, the flower garden's
 * butterfly dance, and the dragon's challenge on its crag.
 */
object Activities {
    private fun types(r: LiveResident) = runCatching { TypeLogic.currentTypes(r.pokemon) }.getOrDefault(emptyList())
    private fun free(r: LiveResident, now: Long) = !r.leaving && !r.cave && !r.napping && r.followParents == null && now >= r.busyUntil
    private fun item(ns: String, id: String) = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(ns, id)).takeIf { it != Items.AIR }
    private fun give(level: ServerLevel, p: ServerPlayer, stack: ItemStack) {
        if (!p.inventory.add(stack)) level.addFreshEntity(ItemEntity(level, p.x, p.y + 0.5, p.z, stack))
    }
    private fun tell(players: List<ServerPlayer>, text: String, c: ChatFormatting = ChatFormatting.AQUA) = players.forEach { it.sendSystemMessage(Component.literal(text).withStyle(c)) }
    private fun zoneOf(s: Sanctuary, p: ServerPlayer) = ZoneLayout.locate(s, p.blockX, p.blockZ)?.zone
    private fun near(s: Sanctuary, players: List<ServerPlayer>, zone: String, r: Double): List<ServerPlayer> {
        val (ax, az) = ZoneLayout.anchor(s, zone)
        return players.filter { it.distanceToSqr(ax + 0.5, it.y, az + 0.5) < r * r }
    }
    private fun dayOf(level: ServerLevel) = (level.dayTime / 24000L).toInt()
    private val STONES = listOf("fire_stone", "water_stone", "thunder_stone", "leaf_stone", "moon_stone", "sun_stone", "shiny_stone", "dusk_stone", "dawn_stone", "ice_stone")

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        if (players.isEmpty()) return
        val t = level.dayTime % 24000L
        if ("water" in s.unlocked) { swimRace(level, s, rt, now, players); fishBringer(level, s, rt, now, players) }
        crystals(level, s, rt, now, players)
        if ("fire" in s.unlocked) { spring(level, s, rt, now, players, t); lavaWatch(level, s, rt, now, players, t) }
        if ("ground" in s.unlocked) { ruins(level, s, now, players); mirage(level, s, rt, now, players, t) }
        if ("fairy" in s.unlocked) dance(level, s, rt, now, players, t)
        if ("dragon" in s.unlocked) challenge(level, s, rt, now, players, t)
    }

    // ------------------------------------------------------------------ lake & coast

    /** Fishing near Water-type residents: they help, and you get a little extra. */
    fun onFished(player: net.minecraft.world.entity.player.Player, hook: net.minecraft.world.entity.Entity) {
        val p = player as? ServerPlayer ?: return
        val level = p.serverLevel()
        if (!SanctuaryDimension.isSanctuary(level)) return
        val s = Sanctuaries.at(level, p.blockPosition()) ?: return
        val rt = Residents.runtime(s)
        val helper = rt.live.values.filter { "water" in types(it) && it.entity.distanceToSqr(hook) < 20.0 * 20.0 }.minByOrNull { it.entity.distanceToSqr(hook) } ?: return
        val r = level.random
        val extra = when (r.nextInt(20)) {
            0 -> item("cobblemon", "rare_candy")
            in 1..3 -> Items.NAUTILUS_SHELL
            in 4..9 -> Items.PRISMARINE_CRYSTALS
            else -> item("cobblemon", listOf("oran_berry", "sitrus_berry", "pecha_berry", "rawst_berry")[r.nextInt(4)])
        } ?: Items.COD
        give(level, p, ItemStack(extra))
        Effects.happy(helper.entity)
        Growth.fonder(s, helper.pokemon, 2)
        p.displayClientMessage(Component.literal("${helper.pokemon.getDisplayName(false).string} helped you fish! +${ItemStack(extra).hoverName.string}").withStyle(ChatFormatting.AQUA), true)
    }

    /** Now and then a Water type near you at the lake brings you a fish. */
    private fun fishBringer(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        if ((now / 20L + (s.id.hashCode() and 63)) % 180L != 0L || level.random.nextInt(3) != 0) return
        for (p in players) {
            if (zoneOf(s, p) != "water") continue
            val r = rt.live.values.firstOrNull { "water" in types(it) && it.entity.distanceToSqr(p) < 12.0 * 12.0 } ?: continue
            give(level, p, ItemStack(if (level.random.nextInt(4) == 0) Items.SALMON else Items.COD))
            Effects.happy(r.entity)
            p.displayClientMessage(Component.literal("${r.pokemon.getDisplayName(false).string} brought you a fish!").withStyle(ChatFormatting.AQUA), true)
            return
        }
    }

    private class Race(val ids: List<UUID>, val target: BlockPos, val start: Long)
    private val races = HashMap<UUID, Race>()

    /** Water types race across the lake while you watch. */
    private fun swimRace(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        val race = races[s.id]
        val watchers = near(s, players, "water", 70.0)
        if (race != null) {
            val racers = race.ids.mapNotNull { rt.live[it] }
            if (racers.size < 2 || now - race.start > 20L * 100) { races.remove(s.id); return }
            val winner = racers.firstOrNull { it.entity.blockPosition().closerThan(race.target, 4.0) } ?: return
            races.remove(s.id)
            s.resident(winner.pokemonId).ribbons++
            Effects.hearts(winner.entity); Effects.play(winner.entity, Effects.CRY)
            for (o in racers) o.busyUntil = now + 40
            tell(watchers, "★ ${winner.pokemon.getDisplayName(false).string} won the swim race!", ChatFormatting.GOLD)
            s.logEvent("${winner.pokemon.getDisplayName(false).string} won a swim race across the lake.")
            return
        }
        if (watchers.isEmpty() || (now / 20L + (s.id.hashCode() and 127)) % 120L != 0L || level.random.nextInt(5) != 0) return
        val racers = rt.live.values.filter { free(it, now) && "water" in types(it) && it.home == "water" }.shuffled().take(4)
        if (racers.size < 3) return
        val pt = ZoneLayout.randomPoint(s, "water", level.random) ?: return
        val target = Residents.surface(level, pt.first, pt.second)
        races[s.id] = Race(racers.map { it.pokemonId }, target, now)
        for (r in racers) { r.busyUntil = now + 20L * 100; r.waypoints.clear(); Residents.walkTo(r.entity, target, 0.9f, 1) }
        tell(watchers, "A swim race is starting across the ${Residents.zoneName("water")}! ${racers.joinToString(", ") { it.pokemon.getDisplayName(false).string }} are racing.")
    }

    // ------------------------------------------------------------------ caves: crystal mining

    /** Crystal nodes grow in the cave halls (up to four); Rock types show you where. */
    private fun crystals(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        if ((now / 20L) % 2L == 0L) for (packed in s.crystals.keys) {
            val p = BlockPos.of(packed)
            if (!level.hasChunk(p.x shr 4, p.z shr 4)) continue
            val shown = rt.live.values.any { "rock" in types(it) && it.entity.blockPosition().closerThan(p, 16.0) } || players.any { it.blockPosition().closerThan(p, 6.0) }
            if (shown && players.any { it.blockPosition().closerThan(p, 32.0) }) level.sendParticles(ParticleTypes.END_ROD, p.x + 0.5, p.y + 1.0, p.z + 0.5, 3, 0.2, 0.4, 0.2, 0.01)
        }
        if (s.crystals.size >= 4 || (now / 20L + (s.id.hashCode() and 127)) % 120L != 0L) return
        val hall = Caves.halls(s).randomOrNull() ?: return
        val r = level.random
        repeat(10) {
            val x = (hall.hallX + (r.nextDouble() * 2 - 1) * hall.hallR * 0.7).toInt()
            val z = (hall.hallZ + (r.nextDouble() * 2 - 1) * hall.hallR * 0.7).toInt()
            if (!level.hasChunk(x shr 4, z shr 4)) return@repeat
            // down through the ground into the hall, to its floor
            var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            var inRock = false
            var floor: BlockPos? = null
            for (i in 0 until 90) {
                val here = level.getBlockState(BlockPos(x, y, z))
                if (!inRock) { if (!here.isAir) inRock = true }
                else if (here.isAir && level.getBlockState(BlockPos(x, y - 1, z)).isSolid) { floor = BlockPos(x, y, z); break }
                y--
            }
            val f = floor ?: return@repeat
            if (level.canSeeSky(f)) return@repeat
            level.setBlock(f, Blocks.AMETHYST_CLUSTER.defaultBlockState(), 3)
            s.crystals[f.asLong()] = now
            return
        }
    }

    /** Mining a crystal node (the sanctuary otherwise keeps its blocks). */
    /** Returns true when this was a crystal node: it's mined here (and the normal break is stopped). */
    fun onBreak(lvl: net.minecraft.world.level.Level, player: net.minecraft.world.entity.player.Player?, pos: BlockPos): Boolean {
        val level = lvl as? ServerLevel ?: return false
        if (!SanctuaryDimension.isSanctuary(level)) return false
        val p = player as? ServerPlayer ?: return false
        val s = Sanctuaries.at(level, pos) ?: return false
        if (s.crystals.remove(pos.asLong()) == null) return false
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        level.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 1f, 1f)
        val r = level.random
        give(level, p, ItemStack(Items.AMETHYST_SHARD, 2 + r.nextInt(3)))
        if (r.nextInt(10) < 3) give(level, p, ItemStack(Items.EMERALD))
        if (r.nextInt(100) < 15) item("cobblemon", STONES[r.nextInt(STONES.size)])?.let { give(level, p, ItemStack(it)) }
        if (r.nextInt(100) < 3) give(level, p, ItemStack(Items.DIAMOND))
        s.logEvent("${p.gameProfile.name} mined a crystal in the caves.")
        Sanctuaries.data(level).setDirty()
        return true
    }

    // ------------------------------------------------------------------ volcano

    /** The hot spring: bathing heals you and your party; Fire types soak in it at night. */
    private fun spring(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, t: Long) {
        val packed = s.landmarks[TownBuildings.PREFIX + "spring"]?.takeIf { it != com.rickygamer28.sanctuary.world.Landmarks.NONE } ?: return
        val c = BlockPos.of(packed)
        if (!level.hasChunk(c.x shr 4, c.z shr 4)) return
        if (players.any { it.blockPosition().closerThan(c, 48.0) }) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x + 0.5, c.y + 0.8, c.z + 0.5, 3, 1.2, 0.1, 1.2, 0.01)
        for (p in players) {
            if (!p.blockPosition().closerThan(c, 3.5) || !p.isInWater) continue
            p.addEffect(MobEffectInstance(MobEffects.REGENERATION, 120, 0, true, true))
            if (now % 1200L < 20L) runCatching { Cobblemon.storage.getParty(p).heal() }.onSuccess { p.displayClientMessage(Component.literal("The hot spring restores you and your party.").withStyle(ChatFormatting.GOLD), true) }
        }
        // at night the Fire types come for a soak
        if (t in 14000L..22000L && (now / 20L) % 30L == 0L) for (r in rt.live.values.filter { free(it, now) && "fire" in types(it) }.take(3)) {
            if (r.entity.blockPosition().closerThan(c, 6.0)) continue
            Residents.walkTo(r.entity, c.offset(level.random.nextInt(3) - 1, 0, level.random.nextInt(3) - 1), 0.5f, 1)
            r.busyUntil = now + 20L * 60
        }
    }

    private val rumbled = HashMap<UUID, Int>()

    /** At dusk the volcano rumbles and sends up embers; the Fire types gather to watch. */
    private fun lavaWatch(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, t: Long) {
        if (t !in 12000L..12800L) return
        val peak = BiomeDresser.peakOf(s, "fire") ?: return
        val watchers = players.filter { it.distanceToSqr(peak.first, it.y, peak.second) < 140.0 * 140.0 }
        if (watchers.isEmpty()) return
        val day = dayOf(level)
        if (rumbled[s.id] != day) {
            rumbled[s.id] = day
            Migrations.gather(level, s, rt.live.values.filter { free(it, now) && "fire" in types(it) }.take(8), "fire", now, 20L * 60)
            tell(watchers, "The volcano rumbles! Embers light up the dusk.", ChatFormatting.GOLD)
            s.logEvent("The volcano rumbled at dusk, and the Fire types gathered to watch.")
        }
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, peak.first.toInt(), peak.second.toInt()).toDouble()
        for (p in watchers) {
            level.sendParticles(p, ParticleTypes.LAVA, true, peak.first, top + 2, peak.second, 6, 2.0, 1.0, 2.0, 0.6)
            level.sendParticles(p, ParticleTypes.LARGE_SMOKE, true, peak.first, top + 4, peak.second, 4, 2.0, 2.0, 2.0, 0.08)
            level.sendParticles(p, ParticleTypes.FLAME, true, peak.first, top + 3, peak.second, 10, 2.5, 3.0, 2.5, 0.12)
        }
        if ((now / 20L) % 6L == 0L) level.playSound(null, BlockPos.containing(peak.first, top, peak.second), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 2.5f, 0.4f)
    }

    // ------------------------------------------------------------------ desert

    /** The desert ruins come out of the sand a layer a day; the last day uncovers a chest. */
    private fun ruins(level: ServerLevel, s: Sanctuary, now: Long, players: List<ServerPlayer>) {
        val packed = s.landmarks[TownBuildings.PREFIX + "ruins"]?.takeIf { it != com.rickygamer28.sanctuary.world.Landmarks.NONE } ?: return
        val c = BlockPos.of(packed)
        if (!level.hasChunk(c.x shr 4, c.z shr 4) || players.none { zoneOf(s, it) == "ground" }) return
        val day = dayOf(level)
        if ((s.stats["ruinDay"] ?: -1) == day) return
        s.stats["ruinDay"] = day
        val stage = s.stats["ruinStage"] ?: 0
        if (stage < 4) {
            // one more layer of sand blown away
            for (x in -3..3) for (z in -3..3) {
                val p = c.offset(x, -stage, z)
                if (level.getBlockState(p).`is`(Blocks.SAND)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)
            }
            s.stats["ruinStage"] = stage + 1
            if (stage + 1 == 4) {
                val chest = c.offset(0, -3, 0)
                level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3)
                (level.getBlockEntity(chest) as? ChestBlockEntity)?.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/desert_pyramid")), level.random.nextLong())
                tell(players, "The desert ruins are fully uncovered, and there's a chest at the bottom!", ChatFormatting.GOLD)
                s.logEvent("The desert ruins were uncovered: a chest waits at the bottom.")
            } else {
                tell(players.filter { zoneOf(s, it) == "ground" }, "The wind uncovered more of the desert ruins (${stage + 1}/4).", ChatFormatting.YELLOW)
                s.logEvent("More of the desert ruins came out of the sand (${stage + 1}/4).")
            }
            Sanctuaries.data(level).setDirty()
            return
        }
        // a week after it was emptied, the ruins hold something new
        val chest = c.offset(0, -3, 0)
        val be = level.getBlockEntity(chest) as? ChestBlockEntity ?: return
        if (!be.isEmpty) { s.stats["ruinEmptySince"] = -1; return }
        val since = s.stats["ruinEmptySince"] ?: -1
        if (since < 0) { s.stats["ruinEmptySince"] = day; return }
        if (day - since >= 7) {
            be.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/desert_pyramid")), level.random.nextLong())
            s.stats["ruinEmptySince"] = -1
            s.logEvent("Something new turned up in the desert ruins' chest.")
        }
    }

    private class Mirage(val pos: BlockPos, val day: Int)
    private val mirages = HashMap<UUID, Mirage>()
    private val mirageDay = HashMap<UUID, Int>()

    /** On hot afternoons a shimmering oasis shows far off in the desert; it fades when you reach it (sometimes someone's there). */
    private fun mirage(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, t: Long) {
        val day = dayOf(level)
        for (p in players) {
            val m = mirages[p.uuid]
            if (m != null) {
                if (t !in 6000L..9500L || zoneOf(s, p) != "ground") { mirages.remove(p.uuid); continue }
                val d = p.blockPosition().distSqr(m.pos)
                if (d < 7.0 * 7.0) {
                    mirages.remove(p.uuid)
                    if (level.random.nextInt(3) == 0 && Visitors.arriveNow(level, s, rt, now, "ground"))
                        p.sendSystemMessage(Component.literal("The mirage fades… but someone really was standing there!").withStyle(ChatFormatting.GOLD))
                    else p.displayClientMessage(Component.literal("The oasis shimmers and fades. Just a mirage…").withStyle(ChatFormatting.YELLOW), true)
                    continue
                }
                if (d > 18.0 * 18.0) {
                    level.sendParticles(p, ParticleTypes.SPLASH, true, m.pos.x + 0.5, m.pos.y + 0.6, m.pos.z + 0.5, 30, 3.0, 0.3, 3.0, 0.0)
                    level.sendParticles(p, ParticleTypes.END_ROD, true, m.pos.x + 0.5, m.pos.y + 2.0, m.pos.z + 0.5, 4, 2.5, 1.5, 2.5, 0.0)
                    level.sendParticles(p, ParticleTypes.HAPPY_VILLAGER, true, m.pos.x + 0.5, m.pos.y + 3.0, m.pos.z + 0.5, 6, 1.0, 1.5, 1.0, 0.0)
                }
                continue
            }
            if (t !in 6000L..9000L || zoneOf(s, p) != "ground" || mirageDay[p.uuid] == day || level.random.nextInt(60) != 0) continue
            mirageDay[p.uuid] = day
            val look = p.lookAngle
            val x = (p.x + look.x * 45).toInt()
            val z = (p.z + look.z * 45).toInt()
            if (ZoneLayout.locate(s, x, z)?.zone != "ground" || !level.hasChunk(x shr 4, z shr 4)) continue
            mirages[p.uuid] = Mirage(Residents.surface(level, x, z), day)
            p.displayClientMessage(Component.literal("Is that… an oasis, shimmering in the distance?").withStyle(ChatFormatting.YELLOW), true)
        }
    }

    // ------------------------------------------------------------------ flower garden: the butterfly dance

    private class Dance(val ids: List<UUID>, val center: BlockPos, val start: Long, val joined: MutableSet<UUID> = HashSet())
    private val dances = HashMap<UUID, Dance>()
    private val danceDay = HashMap<UUID, Int>()

    /** At noon the Fairy and Bug types dance in a ring among the flowers; step into the ring to join them. */
    private fun dance(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, t: Long) {
        val d = dances[s.id]
        if (d != null) {
            val dancers = d.ids.mapNotNull { rt.live[it] }
            val tt = (now - d.start) / 20L
            if (tt > 70 || dancers.size < 2) { dances.remove(s.id); dancers.forEach { it.busyUntil = now + 40 }; return }
            if (tt % 3L == 0L) for ((i, r) in dancers.withIndex()) {
                val a = i * Math.PI * 2 / dancers.size + tt / 3.0 * 0.6
                Residents.walkTo(r.entity, Residents.surface(level, d.center.x + (cos(a) * 4).toInt(), d.center.z + (sin(a) * 4).toInt()), 0.7f, 1)
            }
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, d.center.x + 0.5, d.center.y + 3.0, d.center.z + 0.5, 6, 3.0, 1.0, 3.0, 0.0)
            level.sendParticles(ParticleTypes.END_ROD, d.center.x + 0.5, d.center.y + 1.5, d.center.z + 0.5, 2, 3.0, 0.8, 3.0, 0.0)
            for (p in players) if (p.uuid !in d.joined && p.blockPosition().closerThan(d.center, 5.0)) {
                d.joined += p.uuid
                for (r in dancers) { Growth.fonder(s, r.pokemon, 2); Effects.hearts(r.entity) }
                p.displayClientMessage(Component.literal("You joined the butterfly dance! The dancers are delighted.").withStyle(ChatFormatting.LIGHT_PURPLE), true)
            }
            return
        }
        if (t !in 5800L..6600L || danceDay[s.id] == dayOf(level)) return
        val watchers = near(s, players, "fairy", 80.0)
        if (watchers.isEmpty()) return
        val dancers = rt.live.values.filter { free(it, now) && types(it).any { ty -> ty == "fairy" || ty == "bug" } }.shuffled().take(8)
        if (dancers.size < 3) return
        danceDay[s.id] = dayOf(level)
        val pt = ZoneLayout.randomPoint(s, "fairy", level.random) ?: return
        val center = Residents.surface(level, pt.first, pt.second)
        dances[s.id] = Dance(dancers.map { it.pokemonId }, center, now)
        for (r in dancers) { r.busyUntil = now + 20L * 75; r.waypoints.clear() }
        tell(watchers, "The Fairy and Bug types are starting the butterfly dance in the ${Residents.zoneName("fairy")}! Step into the ring to join in.", ChatFormatting.LIGHT_PURPLE)
        s.logEvent("The butterfly dance was held in the ${Residents.zoneName("fairy")}.")
    }

    // ------------------------------------------------------------------ dragon's den: the challenge

    private class Challenge(val id: UUID, val crag: BlockPos, val start: Long)
    private val challenges = HashMap<UUID, Challenge>()
    private val challengeDay = HashMap<UUID, Int>()

    /** Once a day the strongest Dragon type waits atop the highest crag; climb up to it to earn its respect. */
    private fun challenge(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, t: Long) {
        val c = challenges[s.id]
        if (c != null) {
            val champ = rt.live[c.id]
            if (champ == null || now - c.start > 20L * 600) { challenges.remove(s.id); return }
            champ.busyUntil = now + 60
            if (!champ.entity.blockPosition().closerThan(c.crag, 3.0)) champ.entity.teleportTo(c.crag.x + 0.5, c.crag.y.toDouble(), c.crag.z + 0.5)
            if ((now / 20L) % 2L == 0L) level.sendParticles(ParticleTypes.DRAGON_BREATH, c.crag.x + 0.5, c.crag.y + 1.0, c.crag.z + 0.5, 6, 0.6, 0.5, 0.6, 0.01)
            val climber = players.firstOrNull { it.blockPosition().closerThan(c.crag, 3.5) } ?: return
            challenges.remove(s.id)
            Effects.play(champ.entity, Effects.CRY); Effects.happy(champ.entity)
            champ.entity.lookControl.setLookAt(climber)
            val mine = rt.roster.filter { e -> e.pokemon.getOwnerUUID() == climber.uuid && runCatching { "dragon" in TypeLogic.currentTypes(e.pokemon) }.getOrDefault(false) }
            mine.forEach { Growth.fonder(s, it.pokemon, 10) }
            climber.sendSystemMessage(Component.literal("${champ.pokemon.getDisplayName(false).string} bows to you: you've earned the dragons' respect! Your Dragon types grew fonder of you.").withStyle(ChatFormatting.GOLD))
            s.logEvent("${climber.gameProfile.name} climbed the crag and earned ${champ.pokemon.getDisplayName(false).string}'s respect.")
            return
        }
        if (t !in 1000L..11000L || challengeDay[s.id] == dayOf(level) || (now / 20L) % 30L != 0L) return
        if (near(s, players, "dragon", 90.0).isEmpty()) return
        val champ = rt.live.values.filter { free(it, now) && "dragon" in types(it) }.maxByOrNull { it.pokemon.level } ?: return
        challengeDay[s.id] = dayOf(level)
        // the highest point near the den's middle
        val (ax, az) = ZoneLayout.anchor(s, "dragon")
        var best: BlockPos? = null
        repeat(60) {
            val x = ax + level.random.nextInt(57) - 28
            val z = az + level.random.nextInt(57) - 28
            if (!level.hasChunk(x shr 4, z shr 4) || ZoneLayout.locate(s, x, z)?.zone != "dragon") return@repeat
            val p = Residents.surface(level, x, z)
            if (best == null || p.y > best!!.y) best = p
        }
        val crag = best ?: return
        champ.entity.teleportTo(crag.x + 0.5, crag.y.toDouble(), crag.z + 0.5)
        challenges[s.id] = Challenge(champ.pokemonId, crag, now)
        tell(near(s, players, "dragon", 120.0), "${champ.pokemon.getDisplayName(false).string} waits atop the highest crag in the ${Residents.zoneName("dragon")}. Climb up to earn its respect!", ChatFormatting.LIGHT_PURPLE)
    }
}
