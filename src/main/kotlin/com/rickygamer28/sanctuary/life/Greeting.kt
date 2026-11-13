package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Residents that are fond of their trainer run over when the trainer comes by, say hello, follow along for a bit,
 * then go back to what they were doing.
 */
object Greeting {
    private val nextGreet = HashMap<UUID, Long>()
    private val seen = HashMap<UUID, Long>()

    /** Called every second while players are in the sanctuary. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, players: List<ServerPlayer>, now: Long) {
        if (!SanctuaryConfig.greetings.get()) return
        for (p in players) {
            if (!s.isMember(p.uuid)) continue
            // just arrived (or back after a while): a welcome a few seconds later, once residents are out
            val last = seen.put(p.uuid, now)
            if (last == null || now - last > 20L * 60) nextGreet[p.uuid] = now + 20L * 6
            val at = nextGreet[p.uuid] ?: (now + 20L * (120 + level.random.nextInt(180))).also { nextGreet[p.uuid] = it }
            if (now < at) continue
            nextGreet[p.uuid] = now + 20L * (150 + level.random.nextInt(210))
            greet(level, s, rt, p, now)
        }
        if (seen.size > 200) seen.entries.removeIf { now - it.value > 20L * 600 }
    }

    private fun greet(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, p: ServerPlayer, now: Long) {
        val need = SanctuaryConfig.greetFriendship.get()
        val fans = rt.live.values.filter {
            it.owner == p.uuid && !it.leaving && !it.swimmer && !it.napping && it.greetPlayer == null &&
                now >= it.busyUntil && it.entity.distanceToSqr(p) < 48.0 * 48.0 && it.pokemon.friendship >= need
        }.sortedByDescending { it.pokemon.friendship }.take(3)
        if (fans.isEmpty()) return
        for (r in fans) {
            r.greetPlayer = p.uuid
            r.greetUntil = now + 20L * (20 + level.random.nextInt(20))
            r.greeted = false
            r.propSpot = null; r.snackSpot = null; r.waypoints.clear()
            if (r.dozeUntil > 0) { r.dozeUntil = 0; Routines.wake(r) }
            Residents.walkTo(r.entity, p.blockPosition(), 0.6f, 2)
        }
        s.stat("greetings")
        val names = fans.joinToString(", ") { it.name }
        if (level.random.nextInt(3) == 0) s.logEvent("$names came over to say hello to ${p.gameProfile.name}.")
    }

    /** Called every second for a greeting resident; returns true while it's greeting. */
    fun follow(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        val id = r.greetPlayer ?: return false
        val p = level.getPlayerByUUID(id) as? ServerPlayer
        if (p == null || now > r.greetUntil || p.distanceToSqr(r.entity) > 64.0 * 64.0 || !Behaviour.canRoam(s, rt, r, p.blockPosition())) {
            r.greetPlayer = null
            return false
        }
        val d2 = p.distanceToSqr(r.entity)
        if (d2 < 3.5 * 3.5) {
            if (!r.greeted) {
                // hello!
                r.greeted = true
                Residents.stop(r.entity)
                Effects.play(r.entity, Effects.CRY)
                Effects.hearts(r.entity)
                Growth.fonder(s, r.pokemon, 1)
            } else if (level.random.nextInt(6) == 0) Effects.hearts(r.entity)
            r.entity.lookControl.setLookAt(p)
        } else if (d2 > 5.0 * 5.0) {
            // keep up, staying a little behind
            val back = p.position().subtract(p.lookAngle.scale(2.5))
            Residents.walkTo(r.entity, BlockPos.containing(back.x, p.y, back.z), if (d2 > 14.0 * 14.0) 0.75f else 0.5f, 2)
        }
        return true
    }
}
