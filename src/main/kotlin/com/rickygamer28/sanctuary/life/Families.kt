package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.egg.EggGroup
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.EggData
import com.rickygamer28.sanctuary.data.EggParent
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.say
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt

/**
 * Families and eggs. Two residents who are very close can start a family: when they nuzzle, they may lay an egg
 * in a nest in the mother's biome. Eggs never hatch on their own; the trainer decides in the Nest Registry
 * (hatch in the nest, pick it up, or let it go). Picked-up eggs hatch by walking or in an Incubator.
 */
object Families {
    private val STATS = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
    private const val FOLLOW_TICKS = 20L * 60 * 10

    // ------------------------------------------------------------------ who can have eggs

    private val LEGEND_LABELS = setOf("legendary", "mythical", "ultra_beast")

    fun isLegendary(p: Pokemon): Boolean =
        runCatching { (p.species.labels + p.form.labels).any { it.lowercase() in LEGEND_LABELS } }.getOrDefault(false)

    fun canBreed(a: Pokemon, b: Pokemon): Boolean {
        val ga = a.form.eggGroups
        val gb = b.form.eggGroups
        // legendaries only start a family with another of the same species (Zapdos with Zapdos)
        if (isLegendary(a) || isLegendary(b)) {
            if (!(isLegendary(a) && isLegendary(b)) || a.species != b.species) return false
            return (a.gender == Gender.GENDERLESS && b.gender == Gender.GENDERLESS) ||
                (a.gender == Gender.MALE && b.gender == Gender.FEMALE) || (a.gender == Gender.FEMALE && b.gender == Gender.MALE)
        }
        if (EggGroup.UNDISCOVERED in ga || EggGroup.UNDISCOVERED in gb) return false
        val dittoA = EggGroup.DITTO in ga
        val dittoB = EggGroup.DITTO in gb
        if (dittoA && dittoB) return false
        if (dittoA || dittoB) return true
        if (ga.none { it in gb }) return false
        return (a.gender == Gender.MALE && b.gender == Gender.FEMALE) || (a.gender == Gender.FEMALE && b.gender == Gender.MALE)
    }

    /** A parent-to-be: a resident that is out in the valley, or one living there while nobody is around. */
    class Mate(val id: UUID, val pokemon: Pokemon, val owner: UUID, val ownerName: String, val home: String?, val entity: PokemonEntity?) {
        val name: String get() = pokemon.getDisplayName(false).string

        companion object {
            fun of(r: LiveResident, s: Sanctuary) = Mate(r.pokemonId, r.pokemon, r.owner,
                Residents.findRoster(Residents.runtime(s), r.pokemonId)?.ownerName ?: s.ownerName, r.home, r.entity)
            fun of(e: RosterEntry, home: String?) = Mate(e.pokemon.uuid, e.pokemon, e.owner, e.ownerName, home, null)
        }
    }

    /** The parent whose species the baby takes: the mother, or the one that isn't Ditto. */
    private fun mother(a: Mate, b: Mate): Pair<Mate, Mate> {
        val pa = a.pokemon
        val pb = b.pokemon
        return when {
            EggGroup.DITTO in pa.form.eggGroups -> b to a
            EggGroup.DITTO in pb.form.eggGroups -> a to b
            pa.gender == Gender.FEMALE -> a to b
            pb.gender == Gender.FEMALE -> b to a
            else -> a to b
        }
    }

    /** First stage of the mother's line, in the same regional form. */
    fun babyForm(mother: Pokemon): FormData {
        val name = mother.form.name.lowercase()
        var form = if (name.startsWith("mega") || name == "primal") mother.species.standardForm else mother.form
        repeat(10) {
            val pre = runCatching { form.preEvolution }.getOrNull() ?: return form
            form = pre.form
        }
        return form
    }

    private fun parentOf(r: Mate) = EggParent(r.id, r.name, r.pokemon.species.resourceIdentifier.toString(), r.pokemon.aspects.toList())

    // ------------------------------------------------------------------ laying

    /** Called at the end of a nuzzle between two residents that are out in the valley. */
    fun afterNuzzle(level: ServerLevel, s: Sanctuary, a: LiveResident, b: LiveResident) {
        if (a.entity.isRemoved || b.entity.isRemoved) return
        if (a.followUntil > level.gameTime || b.followUntil > level.gameTime) return // babies are too young
        tryEgg(level, s, Mate.of(a, s), Mate.of(b, s))
    }

    /** Two close friends nuzzled: maybe they start a family. Returns true if an egg was laid. */
    fun tryEgg(level: ServerLevel, s: Sanctuary, a: Mate, b: Mate): Boolean {
        if (!SanctuaryConfig.eggsEnabled.get()) return false
        if (s.relation(a.id, b.id) < SanctuaryConfig.familyFriendship.get()) return false
        if (s.eggs.size >= SanctuaryConfig.maxEggs.get()) return false
        val key = Sanctuary.key(a.id, b.id)
        val now = level.gameTime
        val last = s.mates[key]
        if (last != null && now - last < SanctuaryConfig.eggCooldownMinutes.get() * 1200L) return false
        if (!runCatching { canBreed(a.pokemon, b.pokemon) }.getOrDefault(false)) return false
        if (level.random.nextDouble() >= SanctuaryConfig.eggChance.get()) return false
        s.mates[key] = now
        return runCatching { layEgg(level, s, a, b); true }.onFailure { CobblemonSanctuary.LOGGER.warn("Couldn't lay an egg", it) }.getOrDefault(false)
    }

    private fun layEgg(level: ServerLevel, s: Sanctuary, a: Mate, b: Mate) {
        val (mom, dad) = mother(a, b)
        val baby = makeBaby(mom.pokemon, dad.pokemon, level)
        val momEntity = mom.entity?.takeIf { !it.isRemoved }
        val zone = mom.home ?: momEntity?.let { ZoneLayout.locate(s, it.blockX, it.blockZ)?.zone } ?: ZoneLayout.HUB
        // out in the valley: the nest goes right there; otherwise it's made the next time someone is around
        val nest = momEntity?.let { placeNest(level, s, it.blockPosition()) }
        val cycles = runCatching { baby.species.eggCycles }.getOrDefault(20).coerceIn(1, 120)
        val egg = EggData(UUID.randomUUID(), mom.owner, mom.ownerName, baby.saveToNBT(level.registryAccess(), CompoundTag()),
            parentOf(mom), parentOf(dad), zone, nest, level.gameTime, cycles)
        s.eggs[egg.id] = egg
        if (momEntity != null) {
            Effects.hearts(momEntity); dad.entity?.let { Effects.hearts(it) }
            Effects.play(momEntity, Effects.CRY)
            level.playSound(null, momEntity.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1f, 0.8f)
        }
        s.logEvent("${mom.name} and ${dad.name} had an egg in the ${Residents.zoneName(zone)}!")
        Sanctuaries.data(level).setDirty()
        level.server.playerList.getPlayer(egg.owner)?.let {
            it.sendSystemMessage(Component.literal("🥚 ${mom.name} and ${dad.name} had an egg! Open the Nest in your Journal.").withStyle(ChatFormatting.LIGHT_PURPLE))
        }
    }

    /** Eggs laid while nobody was around get their nest once their biome is loaded. */
    private fun placeMissingNests(level: ServerLevel, s: Sanctuary) {
        var placed = 0
        for (egg in s.eggs.values) {
            if (egg.nest != null || placed >= 2) continue
            val pt = ZoneLayout.randomPoint(s, egg.zone, level.random) ?: continue
            if (!level.hasChunk(pt.first shr 4, pt.second shr 4)) continue
            egg.nest = placeNest(level, s, BlockPos(pt.first, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pt.first, pt.second), pt.second))
            placed++
        }
        if (placed > 0) Sanctuaries.data(level).setDirty()
    }

    fun makeBaby(mother: Pokemon, father: Pokemon, level: ServerLevel): Pokemon {
        val form = babyForm(mother)
        val aspects = form.aspects.joinToString(" ")
        val baby = PokemonProperties.parse("species=${form.species.resourceIdentifier} level=1 $aspects".trim()).create()
        if (baby.species != form.species) { baby.species = form.species; baby.level = 1; baby.initialize() }
        val random = level.random
        // three stats come from the parents
        for (stat in STATS.shuffled().take(3)) {
            val from = if (random.nextBoolean()) mother else father
            baby.setIV(stat, from.ivs.getOrDefault(stat))
        }
        val rate = runCatching { Cobblemon.config.shinyRate }.getOrDefault(8192f).coerceAtLeast(1f)
        val odds = if (mother.getOwnerUUID() != father.getOwnerUUID()) rate / 2f else rate // parents from different trainers: luckier
        baby.shiny = random.nextFloat() < 1f / odds
        runCatching { baby.setFriendship(120, true) }
        return baby
    }

    private fun placeNest(level: ServerLevel, s: Sanctuary, near: BlockPos): BlockPos? {
        val random = level.random
        repeat(10) { i ->
            val x = near.x + if (i == 0) 0 else random.nextInt(7) - 3
            val z = near.z + if (i == 0) 0 else random.nextInt(7) - 3
            val hit = ZoneLayout.locate(s, x, z) ?: return@repeat
            if (hit.dist > s.size.radius - 6) return@repeat
            val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            val pos = BlockPos(x, y, z)
            val here = level.getBlockState(pos)
            val below = level.getBlockState(pos.below())
            if ((here.isAir || here.canBeReplaced()) && here.fluidState.isEmpty && below.isFaceSturdy(level, pos.below(), Direction.UP)) {
                level.setBlock(pos, ModContent.EGG_NEST.get().defaultBlockState(), 3)
                return pos
            }
        }
        return null
    }

    private fun removeNest(s: Sanctuary, egg: EggData) {
        val pos = egg.nest ?: return
        egg.nest = null
        val server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() ?: return
        val level = SanctuaryDimension.level(server) ?: return
        if (!level.isLoaded(pos)) return
        // another egg may share the spot
        if (s.eggs.values.any { it !== egg && it.nest == pos }) return
        if (level.getBlockState(pos).`is`(ModContent.EGG_NEST.get())) level.removeBlock(pos, false)
    }

    // ------------------------------------------------------------------ the Nest Registry buttons

    fun canManage(s: Sanctuary, egg: EggData, player: ServerPlayer) = egg.owner == player.uuid || s.owner == player.uuid

    /** Hatches the egg right away in its nest. The baby comes out and follows its parents around for a while. */
    fun hatchInNest(s: Sanctuary, egg: EggData, player: ServerPlayer) {
        val server = player.server
        val level = SanctuaryDimension.level(server) ?: return
        val baby = runCatching { Pokemon().loadFromNBT(level.registryAccess(), egg.pokemon) }.getOrNull()
            ?: return say(player, "This egg seems to be broken.", ChatFormatting.RED)
        runCatching { baby.setOriginalTrainer(egg.owner) }
        val pc = runCatching { Cobblemon.storage.getPC(egg.owner, level.registryAccess()) }.getOrNull()
            ?: return say(player, "Couldn't reach the trainer's PC.", ChatFormatting.RED)
        if (!pc.add(baby)) return say(player, "The trainer's PC is full.", ChatFormatting.RED)
        val nest = egg.nest
        s.eggs.remove(egg.id)
        removeNest(s, egg)
        recordFamily(s, baby, egg)
        s.logEvent("A baby ${baby.species.translatedName.string} hatched! Its parents are ${egg.mother.name} and ${egg.father.name}.")
        Sanctuaries.data(server).setDirty()
        // bring it out right away
        val rt = Residents.runtime(s)
        val now = level.gameTime
        Residents.refreshRoster(level, s, rt, now)
        val at = nest ?: rt.live[egg.mother.id]?.entity?.blockPosition()
        if (at != null && level.isLoaded(at)) {
            hatchEffects(level, at)
            val entry = Residents.findRoster(rt, baby.uuid)
            val live = entry?.let { Residents.spawn(level, s, rt, it, at) }
            if (live != null) {
                live.followParents = egg.mother.id to egg.father.id
                live.followUntil = now + FOLLOW_TICKS
                Effects.play(live.entity, Effects.CRY)
            }
        }
        say(player, "${baby.species.translatedName.string} hatched! It lives in the sanctuary now.", ChatFormatting.GREEN)
    }

    fun pickUp(s: Sanctuary, egg: EggData, player: ServerPlayer) {
        val stack = eggItem(s, egg)
        s.eggs.remove(egg.id)
        removeNest(s, egg)
        if (!player.inventory.add(stack)) player.drop(stack, false)
        Sanctuaries.data(player.server).setDirty()
        say(player, "You picked up the egg. Walk with it or put it in an Incubator to hatch it.", ChatFormatting.GREEN)
    }

    fun discard(s: Sanctuary, egg: EggData, player: ServerPlayer) {
        s.eggs.remove(egg.id)
        removeNest(s, egg)
        Sanctuaries.data(player.server).setDirty()
        say(player, "The egg was let go.", ChatFormatting.GRAY)
    }

    private fun recordFamily(s: Sanctuary, baby: Pokemon, egg: EggData) {
        s.families[baby.uuid] = egg.mother.id to egg.father.id
        s.setRelation(baby.uuid, egg.mother.id, 70)
        s.setRelation(baby.uuid, egg.father.id, 70)
        if (s.families.size > 5000) s.families.keys.take(500).toList().forEach { s.families.remove(it) }
    }

    private fun hatchEffects(level: ServerLevel, at: BlockPos) {
        level.playSound(null, at, SoundEvents.SNIFFER_EGG_HATCH, SoundSource.NEUTRAL, 1f, 1.2f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x + 0.5, at.y + 0.6, at.z + 0.5, 20, 0.5, 0.4, 0.5, 0.05)
        level.sendParticles(ParticleTypes.HEART, at.x + 0.5, at.y + 1.0, at.z + 0.5, 4, 0.4, 0.3, 0.4, 0.02)
    }

    // ------------------------------------------------------------------ egg items

    fun eggTag(stack: ItemStack): CompoundTag? = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.takeIf { it.contains("Egg") }?.getCompound("Egg")

    private fun setEggTag(stack: ItemStack, egg: CompoundTag) {
        val root = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        root.put("Egg", egg)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root))
    }

    fun stepsNeeded(egg: CompoundTag) = egg.getInt("Cycles").coerceAtLeast(1) * SanctuaryConfig.eggStepsPerCycle.get()

    fun eggItem(s: Sanctuary, egg: EggData): ItemStack {
        val stack = ItemStack(ModContent.POKEMON_EGG.get())
        val t = CompoundTag()
        t.put("Pokemon", egg.pokemon)
        t.putUUID("Owner", egg.owner)
        t.putString("OwnerName", egg.ownerName)
        t.putUUID("MotherId", egg.mother.id)
        t.putUUID("FatherId", egg.father.id)
        t.putString("Mother", egg.mother.name)
        t.putString("Father", egg.father.name)
        t.putInt("Cycles", egg.cycles)
        t.putDouble("Steps", 0.0)
        t.putString("Sanctuary", s.id.toString())
        setEggTag(stack, t)
        return stack
    }

    /** Adds progress to an egg item. Returns true when it's ready to hatch. */
    fun addSteps(stack: ItemStack, steps: Double): Boolean {
        val egg = eggTag(stack) ?: return false
        val now = egg.getDouble("Steps") + steps
        egg.putDouble("Steps", now)
        setEggTag(stack, egg)
        return now >= stepsNeeded(egg)
    }

    fun progress(stack: ItemStack): Double {
        val egg = eggTag(stack) ?: return 0.0
        return (egg.getDouble("Steps") / stepsNeeded(egg)).coerceIn(0.0, 1.0)
    }

    /**
     * Hatches an egg item. The baby goes to [receiver]'s party if there's room (else their PC);
     * without a receiver online it goes to the egg owner's PC.
     */
    fun hatchItem(server: MinecraftServer, stack: ItemStack, receiver: ServerPlayer?, level: ServerLevel?, at: Vec3?): Pokemon? {
        val egg = eggTag(stack) ?: return null
        val access: RegistryAccess = server.registryAccess()
        val baby = runCatching { Pokemon().loadFromNBT(access, egg.getCompound("Pokemon")) }.getOrNull() ?: return null
        val owner = if (egg.hasUUID("Owner")) egg.getUUID("Owner") else receiver?.uuid ?: return null
        val target = receiver ?: server.playerList.getPlayer(owner)
        runCatching { baby.setOriginalTrainer(target?.uuid ?: owner) }
        val stored = if (target != null) {
            val party = Cobblemon.storage.getParty(target)
            (party.occupied() < 6 && party.add(baby)) || Cobblemon.storage.getPC(target).add(baby)
        } else {
            runCatching { Cobblemon.storage.getPC(owner, access).add(baby) }.getOrDefault(false)
        }
        if (!stored) return null
        // remember the family
        runCatching {
            val sid = UUID.fromString(egg.getString("Sanctuary"))
            Sanctuaries.find(server, sid)?.second?.let { s ->
                s.families[baby.uuid] = egg.getUUID("MotherId") to egg.getUUID("FatherId")
                s.setRelation(baby.uuid, egg.getUUID("MotherId"), 70)
                s.setRelation(baby.uuid, egg.getUUID("FatherId"), 70)
                s.logEvent("${egg.getString("Mother")} and ${egg.getString("Father")}'s egg hatched into ${baby.species.translatedName.string}!")
                Residents.runtime(s).rosterDirty = true
                Sanctuaries.data(server).setDirty()
            }
        }
        if (level != null && at != null) hatchEffects(level, BlockPos.containing(at))
        val name = baby.species.translatedName.string
        target?.sendSystemMessage(Component.literal("🐣 Your egg hatched into $name!").withStyle(ChatFormatting.GREEN))
        return baby
    }

    // ------------------------------------------------------------------ walking

    private val lastPos = HashMap<UUID, Vec3>()

    fun onPlayerTick(player: Player) {
        if (player !is ServerPlayer || player.tickCount % 20 != 0) return
        val pos = player.position()
        val last = lastPos.put(player.uuid, pos) ?: return
        val dx = pos.x - last.x
        val dz = pos.z - last.z
        val moved = sqrt(dx * dx + dz * dz)
        if (moved < 0.2 || moved > 40 || player.isSpectator) return
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.`is`(ModContent.POKEMON_EGG.get())) continue
            if (addSteps(stack, moved)) {
                val copy = stack.copyWithCount(1)
                if (hatchItem(player.server, copy, player, player.serverLevel(), pos) != null) stack.shrink(1)
            }
        }
    }

    fun forgetPlayer(id: UUID) { lastPos.remove(id) }

    // ------------------------------------------------------------------ newborns follow their parents

    fun tickSecond(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (now % 100L < 20L) placeMissingNests(level, s)
        for (r in rt.live.values) {
            val parents = r.followParents ?: continue
            if (now >= r.followUntil) { r.followParents = null; r.visitZone = null; continue }
            if (now < r.busyUntil) continue
            val parent = rt.live[parents.first] ?: rt.live[parents.second] ?: continue
            if (parent.entity.isRemoved) continue
            r.visitZone = parent.home ?: ZoneLayout.locate(s, parent.entity.blockX, parent.entity.blockZ)?.zone
            r.visitUntil = r.followUntil
            r.waypoints.clear()
            if (r.entity.distanceToSqr(parent.entity) > 9.0) Residents.walkTo(r.entity, parent.entity.blockPosition(), 0.5f, 2)
            if (level.random.nextInt(25) == 0) { Effects.hearts(r.entity); Effects.play(r.entity, Effects.CRY) }
        }
    }
}
