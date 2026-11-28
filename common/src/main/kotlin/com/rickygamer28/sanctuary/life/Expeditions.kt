package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.Platform
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Cobblemon Expeditions support. This object never touches Expeditions' own classes, so the mod runs the same
 * without it; everything that does is in [com.rickygamer28.sanctuary.compat.ExpeditionsBridge], reached only when
 * Expeditions is installed. If its API ever changes under us, support switches itself off (with one line in the log).
 */
object Expeditions {
    val present: Boolean by lazy { runCatching { Platform.current.isModLoaded("cobblemon_expeditions") }.getOrDefault(false) }
    @Volatile private var broken = false

    private inline fun <T> safe(default: T, f: () -> T): T {
        if (!present || broken) return default
        return try { f() } catch (e: Throwable) {
            broken = true
            CobblemonSanctuary.LOGGER.warn("Cobblemon Expeditions support switched off (its version may not match): {}", e.toString())
            default
        }
    }

    /** Away on an expedition (it stays in the PC, but isn't out in the valley until it's back). */
    fun isAway(s: Sanctuary, pokemon: UUID) = present && pokemon in s.away

    /** Departures and homecomings, every couple of seconds. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) =
        safe(Unit) { com.rickygamer28.sanctuary.compat.ExpeditionsBridge.tick(level, s, rt, now, players) }

    /** How much stronger a resident is on an expedition that suits its home habitat (from the success calculation). */
    @JvmStatic
    fun homeBonus(snapshot: Any?, preferredTypes: List<*>?): Double =
        if (snapshot == null || preferredTypes == null) 1.0
        else safe(1.0) { com.rickygamer28.sanctuary.compat.ExpeditionsBridge.homeBonus(snapshot, preferredTypes) }

    private val sentHomes = HashMap<UUID, Int>()

    /**
     * Every few seconds: each online player's game is told where their residents live (only what changed), so the
     * Expedition Board can show the home-habitat bonus.
     */
    fun syncHomes(server: net.minecraft.server.MinecraftServer) {
        if (!present || broken) return
        val data = com.rickygamer28.sanctuary.data.SanctuaryData.get(server)
        val bonus = com.rickygamer28.sanctuary.SanctuaryConfig.expeditionHomeBonus.get()
        for (p in server.playerList.players) {
            val h = net.minecraft.nbt.CompoundTag()
            for (s in data.sanctuaries.values) {
                if (!s.isMember(p.uuid)) continue
                val rt = Residents.runtimeOrNull(s.id) ?: continue
                val ring = com.rickygamer28.sanctuary.world.ZoneLayout.ring(s)
                for (e in rt.roster.asSequence() + rt.away.asSequence()) {
                    if (e.owner != p.uuid) continue
                    val home = com.rickygamer28.sanctuary.world.TypeLogic.homeZone(e.pokemon, s.residents[e.pokemon.uuid], ring, s) ?: continue
                    h.putString(e.pokemon.uuid.toString(), "$home|${Residents.zoneName(home)}")
                }
            }
            val hash = h.hashCode() * 31 + bonus
            if (sentHomes[p.uuid] == hash) continue
            sentHomes[p.uuid] = hash
            val t = net.minecraft.nbt.CompoundTag()
            t.putInt("bonus", bonus)
            t.put("h", h)
            runCatching { Platform.current.sendToPlayer(p, com.rickygamer28.sanctuary.net.HomesPayload(t)) }
        }
        sentHomes.keys.retainAll(server.playerList.players.map { it.uuid }.toSet())
    }

    // ---- the Expedition Board (client side, from its mixins)

    @JvmStatic
    fun boardBonus(poke: Any?, exp: Any?): Double =
        if (poke == null || exp == null) 1.0 else safe(1.0) { com.rickygamer28.sanctuary.compat.ExpeditionsClientBridge.bonus(poke, exp) }

    @JvmStatic
    fun rememberHovered(poke: Any?, exp: Any?) {
        if (poke != null) safe(Unit) { com.rickygamer28.sanctuary.compat.ExpeditionsClientBridge.hovered = poke; com.rickygamer28.sanctuary.compat.ExpeditionsClientBridge.select(exp) }
    }

    @JvmStatic
    fun tooltipLines(screen: Any?, lines: MutableList<Any?>?) {
        if (lines == null) return
        safe(Unit) { com.rickygamer28.sanctuary.compat.ExpeditionsClientBridge.addLines(screen, lines) }
    }

    /** Time left on a resident's expedition, in minutes (-1 when not known). */
    fun minutesLeft(s: Sanctuary, pokemon: UUID): Long = safe(-1L) { com.rickygamer28.sanctuary.compat.ExpeditionsBridge.minutesLeft(s, pokemon) }
}
