package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Migrations: now and then, while someone is in the sanctuary, a group of residents moves together. A flock of
 * Flying types sweeps across the valley to a far habitat, or the Water types gather at the lake. They stay a few
 * minutes and then go home.
 */
object Migrations {
    /** Called every second when players are in the sanctuary. About every 20 minutes, something moves. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if ((now / 20L + (s.id.hashCode() and 63)) % 60L != 0L) return
        if (level.random.nextInt(20) != 0) return
        if (level.random.nextBoolean()) flock(level, s, rt, now) || gathering(level, s, rt, now) else gathering(level, s, rt, now) || flock(level, s, rt, now)
    }

    private fun free(r: LiveResident, now: Long) = !r.leaving && !r.cave && r.followParents == null && !r.napping && now >= r.busyUntil

    /** Up to eight flyers cross the valley together to a habitat far from most of them. */
    fun flock(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long): Boolean {
        val flyers = rt.live.values.filter { it.flyer && free(it, now) }.shuffled().take(8)
        if (flyers.size < 3) return false
        val zones = s.unlocked.filter { it in s.habitats && it != ZoneLayout.HUB }
        val dest = zones.maxByOrNull { z -> flyers.count { it.home != z } + level.random.nextInt(3) } ?: return false
        go(level, s, flyers, dest, now, 20L * 150)
        tell(level, s, "A flock of ${flyers.size} Flying types is crossing the valley to the ${Residents.zoneName(dest)}!")
        return true
    }

    /** The Water types gather at the lake for a while. */
    fun gathering(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long): Boolean {
        if ("water" !in s.unlocked || "water" !in s.habitats) return false
        val swimmers = rt.live.values.filter { free(it, now) && it.home != "water" && runCatching { "water" in TypeLogic.currentTypes(it.pokemon) }.getOrDefault(false) }.shuffled().take(10)
        if (swimmers.size < 3) return false
        go(level, s, swimmers, "water", now, 20L * 180)
        tell(level, s, "The Water types are gathering at the ${Residents.zoneName("water")}!")
        return true
    }

    /** Everyone heads for the same spot in [zone] (a little apart), and stays [stay] ticks. */
    private fun go(level: ServerLevel, s: Sanctuary, group: List<LiveResident>, zone: String, now: Long, stay: Long) {
        val pt = ZoneLayout.randomPoint(s, zone, level.random) ?: return
        for (r in group) {
            r.visitZone = zone
            r.visitUntil = now + stay
            r.waypoints.clear()
            val x = pt.first + level.random.nextInt(9) - 4
            val z = pt.second + level.random.nextInt(9) - 4
            if (level.hasChunk(x shr 4, z shr 4)) Residents.walkTo(r.entity, Residents.surface(level, x, z), 0.6f, 2)
        }
    }

    private fun tell(level: ServerLevel, s: Sanctuary, text: String) {
        s.logEvent(text)
        for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), true)
    }
}
