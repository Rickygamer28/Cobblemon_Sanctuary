package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.Dens
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType

/**
 * A resident going back to rest while someone could see it: it walks into a den of its biome (or, with no den
 * around, walks off out of sight; swimmers dive), and only then is gone.
 */
object Leaving {
    fun start(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long) {
        r.leaving = true
        r.greetPlayer = null
        r.leaveSince = now
        r.busyUntil = Long.MAX_VALUE
        r.propSpot = null; r.activityKind = null; r.snackSpot = null; r.napSpot = null
        r.waypoints.clear()
        if (r.napping || r.dozeUntil > 0) { r.dozeUntil = 0; Routines.wake(r) }
        if (r.swimmer) { r.leaveTarget = null; return }
        val here = r.entity.blockPosition()
        // one of the nearest dens it may walk to (its own biome's first), not always the same one, so they don't queue
        val dens = Dens.all(s)
            .filter { level.isLoaded(it.mouth) && it.mouth.distSqr(here) < 96.0 * 96.0 && Behaviour.canRoam(s, rt, r, it.mouth) }
            .sortedBy { it.mouth.distSqr(here) * (if (it.zone == r.home) 1.0 else 2.5) }
            .filter { Dens.intact(level, it) }
            .take(3).toList()
        val den = dens.randomOrNull()
        // into the hollow, at a random spot across the tunnel's width
        r.leaveTarget = den?.let { if (it.big) it.mouth.relative(it.dir.clockWise, level.random.nextInt(3) - 1) else it.mouth } ?: awayFromPlayers(level, s, r)
        r.leaveTarget?.let { Residents.walkTo(r.entity, it, 0.45f, 0) }
    }

    /** A spot of its biome that nobody can see, to walk off to. */
    private fun awayFromPlayers(level: ServerLevel, s: Sanctuary, r: LiveResident): BlockPos? {
        val players = level.players()
        repeat(16) {
            val pt = ZoneLayout.randomPoint(s, r.home ?: ZoneLayout.HUB, level.random) ?: return@repeat
            if (!level.hasChunk(pt.first shr 4, pt.second shr 4)) return@repeat
            if (Residents.unseen(players, pt.first, pt.second)) return Residents.surface(level, pt.first, pt.second)
        }
        return null
    }

    /** Called every second for a leaving resident. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long) {
        val e = r.entity
        val hidden = SanctuaryConfig.despawnHiddenDistance.get().toDouble()
        val seen = level.players().any { it.distanceToSqr(e) < hidden * hidden }
        val waited = now - r.leaveSince
        // nobody is close enough to see it any more: it just goes
        if (!seen) { Residents.despawn(rt, r, quiet = true); return }
        if (r.swimmer) {
            // dives down out of sight
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, e.x, e.y + 0.3, e.z, 12, 0.3, 0.4, 0.3, 0.05)
            level.sendParticles(ParticleTypes.SPLASH, e.x, e.y + e.bbHeight, e.z, 10, 0.4, 0.1, 0.4, 0.1)
            if (waited >= 40) Residents.despawn(rt, r, quiet = true)
            return
        }
        val target = r.leaveTarget
        if (target != null) {
            if (e.blockPosition().closerThan(target, 2.2) || e.boundingBox.inflate(1.0).contains(target.x + 0.5, target.y + 0.5, target.z + 0.5)) {
                // into the den
                level.sendParticles(ParticleTypes.POOF, target.x + 0.5, target.y + 0.4, target.z + 0.5, 3, 0.2, 0.1, 0.2, 0.01)
                Residents.despawn(rt, r, quiet = true)
                return
            }
            if (!e.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) Residents.walkTo(e, target, 0.45f, 0)
        }
        // stuck (or nowhere to go): try again elsewhere, and after two minutes give up the nice way
        if (waited > 20L * 120 || (target == null && waited > 20L * 20)) { Residents.despawn(rt, r); return }
        if (waited > 0 && waited % (20L * 30) < 20L) start(level, s, rt, r, r.leaveSince)
    }
}
