package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TownBuildings
import com.rickygamer28.sanctuary.world.TypeLogic
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * The valley's guardian (Golden Heart): the owner names one of their legendary or mythical Pokémon. It gets the
 * Guardian's Rise by the town, comes out there at dawn and at dusk to watch over the valley while residents gather
 * and bow, and residents that share one of its types grow fond of their trainers faster.
 */
object Guardian {
    private val LEGEND = setOf("legendary", "mythical")
    private const val DAY = 24000L

    fun isLegend(pk: Pokemon) = runCatching { pk.species.labels.any { it in LEGEND } }.getOrDefault(false)

    fun unlocked(s: Sanctuary) = Requests.level(s) >= Requests.GOLDEN_LEVEL

    fun of(s: Sanctuary, rt: SanctuaryRuntime): Pokemon? = s.guardian?.let { id -> rt.live[id]?.pokemon ?: Residents.findRoster(rt, id)?.pokemon }

    /** The guardian's types (for the Rise's colours and its blessing). */
    fun types(s: Sanctuary): List<String> = of(s, Residents.runtime(s))?.let { runCatching { TypeLogic.currentTypes(it) }.getOrNull() } ?: emptyList()

    /** Its blessing: residents sharing a type with it grow fond half again as fast. */
    fun blessing(s: Sanctuary, pk: Pokemon): Double {
        if (s.guardian == null || pk.uuid == s.guardian) return 1.0
        val g = types(s)
        return if (g.isNotEmpty() && runCatching { TypeLogic.currentTypes(pk).any { it in g } }.getOrDefault(false)) 1.5 else 1.0
    }

    /** The owner's legendary and mythical Pokémon, for the guardian page. */
    fun page(player: ServerPlayer, s: Sanctuary, level: ServerLevel?, t: net.minecraft.nbt.CompoundTag) {
        t.putBoolean("gUnlocked", unlocked(s))
        t.putInt("gLevel", Requests.GOLDEN_LEVEL)
        s.guardian?.let { t.putUUID("gId", it) }
        val since = (level?.gameTime ?: 0L) - s.guardianSetAt
        if (s.guardian != null && since in 0 until DAY) t.putLong("gWait", (DAY - since) / 1200)
        if (s.owner != player.uuid) return
        val pc = runCatching { Cobblemon.storage.getPC(player) }.getOrNull() ?: return
        t.put("legends", net.minecraft.nbt.ListTag().also { l ->
            for (pk in pc.filter { isLegend(it) }) l.add(net.minecraft.nbt.CompoundTag().also { c ->
                c.putUUID("u", pk.uuid)
                c.putString("name", pk.getDisplayName(false).string)
                c.putString("sp", pk.species.resourceIdentifier.toString())
                c.put("as", net.minecraft.nbt.ListTag().also { a -> pk.aspects.forEach { x -> a.add(net.minecraft.nbt.StringTag.valueOf(x)) } })
                c.putInt("lv", pk.level)
                c.putString("types", runCatching { TypeLogic.currentTypes(pk).joinToString(",") }.getOrDefault(""))
                c.putBoolean("mythical", runCatching { "mythical" in pk.species.labels }.getOrDefault(false))
            })
        })
    }

    /** The owner's legendary and mythical Pokémon, as clickable lines in chat. */
    fun list(player: ServerPlayer, s: Sanctuary) {
        if (!unlocked(s)) return player.sendSystemMessage(Component.literal("A guardian can be chosen at Valley Heart level ${Requests.GOLDEN_LEVEL} (the Golden Heart).").withStyle(ChatFormatting.GRAY))
        if (s.owner != player.uuid) return player.sendSystemMessage(Component.literal("Only the sanctuary's owner can choose its guardian.").withStyle(ChatFormatting.GRAY))
        val pc = runCatching { Cobblemon.storage.getPC(player) }.getOrNull()
        val legends = pc?.filter { isLegend(it) }.orEmpty()
        if (legends.isEmpty()) return player.sendSystemMessage(Component.literal("You have no legendary or mythical Pokémon in your PC yet. The Guardian's Rise waits for one.").withStyle(ChatFormatting.GRAY))
        player.sendSystemMessage(Component.literal("Choose the valley's guardian (click one):").withStyle(ChatFormatting.GOLD))
        for (pk in legends) {
            val chosen = pk.uuid == s.guardian
            player.sendSystemMessage(Component.literal(if (chosen) "  ★ " else "  ☆ ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("${pk.getDisplayName(false).string} (Lv.${pk.level})").withStyle { st ->
                    st.withColor(if (chosen) ChatFormatting.YELLOW else ChatFormatting.WHITE)
                        .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sanctuary guardian ${pk.uuid}"))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Make it the guardian")))
                }))
        }
    }

    /** The owner names [id] the guardian. */
    fun choose(player: ServerPlayer, level: ServerLevel, s: Sanctuary, id: UUID) {
        if (!unlocked(s) || s.owner != player.uuid) return list(player, s)
        val pk = runCatching { Cobblemon.storage.getPC(player) }.getOrNull()?.firstOrNull { it.uuid == id }
        if (pk == null || !isLegend(pk)) return player.sendSystemMessage(Component.literal("That Pokémon isn't a legendary in your PC.").withStyle(ChatFormatting.RED))
        if (s.guardian == id) return player.sendSystemMessage(Component.literal("${pk.getDisplayName(false).string} is already the guardian.").withStyle(ChatFormatting.GRAY))
        val since = level.gameTime - s.guardianSetAt
        if (s.guardian != null && since in 0 until DAY) return player.sendSystemMessage(Component.literal("The guardian can change once a day (in ${(DAY - since) / 1200} minutes).").withStyle(ChatFormatting.GRAY))
        s.guardian = id
        s.guardianSetAt = level.gameTime
        s.logEvent("${pk.getDisplayName(false).string} is now the valley's guardian!")
        player.sendSystemMessage(Component.literal("★ ${pk.getDisplayName(false).string} is now the valley's guardian! It watches from the Guardian's Rise at dawn and dusk.").withStyle(ChatFormatting.GOLD))
        // the Rise takes its colours
        TownBuildings.restyle(s, "rise")
        Sanctuaries.data(level).setDirty()
    }

    /** Called every second: at dawn and dusk the guardian comes out on the Rise, and residents nearby bow. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        val id = s.guardian ?: return
        if (players.isEmpty() || !unlocked(s)) return
        glow(level, s, rt, players)
        val rise = s.landmarks[TownBuildings.PREFIX + "rise"]?.takeIf { it != com.rickygamer28.sanctuary.world.Landmarks.NONE }?.let { BlockPos.of(it) } ?: return
        if (!level.hasChunk(rise.x shr 4, rise.z shr 4)) return
        val t = level.dayTime % DAY
        val watch = t >= 23300L || t < 700L || t in 11800L..12800L
        if (!watch) { rt.guardianOut = false; return }
        val top = rise.above(2)
        val entry = Residents.findRoster(rt, id) ?: return
        val live = rt.live[id] ?: Residents.spawn(level, s, rt, entry, top) ?: return
        val e = live.entity
        if (e.distanceToSqr(top.x + 0.5, top.y.toDouble(), top.z + 0.5) > 4.0) {
            e.teleportTo(top.x + 0.5, top.y.toDouble(), top.z + 0.5)
            Residents.stop(e)
        }
        live.busyUntil = now + 60
        if (!rt.guardianOut) {
            rt.guardianOut = true
            val name = live.pokemon.getDisplayName(false).string
            s.logEvent("$name watches over the valley from the Guardian's Rise at ${if (t < 6000L || t >= 23000L) "dawn" else "dusk"}.")
            level.sendParticles(ParticleTypes.END_ROD, e.x, e.y + e.bbHeight / 2, e.z, 60, 1.0, 1.2, 1.0, 0.05)
            level.playSound(null, e.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1.0f, 0.8f)
            Effects.play(e, Effects.CRY)
        }
        // residents nearby turn to it and bow
        if ((now / 20L) % 4L == 0L) for (r in rt.live.values) {
            if (r.pokemonId == id || r.entity.distanceToSqr(e) > 24.0 * 24.0) continue
            r.entity.lookControl.setLookAt(e)
            if (level.random.nextInt(6) == 0) Effects.happy(r.entity)
        }
        if ((now / 20L) % 2L == 0L) level.sendParticles(ParticleTypes.END_ROD, e.x, e.y + e.bbHeight + 0.4, e.z, 3, 0.4, 0.2, 0.4, 0.0)
    }

    /** The guardian's soft glow: motes of light drifting around anyone in its home habitat. */
    private fun glow(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, players: List<ServerPlayer>) {
        val pk = of(s, rt) ?: return
        val home = runCatching { TypeLogic.homeZone(pk, s.residents[pk.uuid], com.rickygamer28.sanctuary.world.ZoneLayout.ring(s), s) }.getOrNull() ?: return
        for (p in players) {
            if (com.rickygamer28.sanctuary.world.ZoneLayout.locate(s, p.blockX, p.blockZ)?.zone != home) continue
            repeat(6) {
                val x = p.x + (level.random.nextDouble() * 2 - 1) * 14
                val z = p.z + (level.random.nextDouble() * 2 - 1) * 14
                val y = p.y + level.random.nextDouble() * 6 - 1
                level.sendParticles(p, ParticleTypes.END_ROD, false, x, y, z, 1, 0.0, 0.02, 0.0, 0.005)
            }
        }
    }
}
