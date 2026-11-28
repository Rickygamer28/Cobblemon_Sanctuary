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

    /** Time left on a resident's expedition, in minutes (-1 when not known). */
    fun minutesLeft(s: Sanctuary, pokemon: UUID): Long = safe(-1L) { com.rickygamer28.sanctuary.compat.ExpeditionsBridge.minutesLeft(s, pokemon) }
}
