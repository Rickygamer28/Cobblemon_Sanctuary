package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType

/** Keeps residents living in their biomes: staying home, roaming, commuting, flocking and meeting each other. */
object Behaviour {

    /** Used by Cobblemon's wander AI (through the tethering) to decide whether a spot is allowed. */
    /** Is the top of the column at (x, z) water? */
    fun isWater(level: ServerLevel, x: Int, z: Int): Boolean {
        val y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1
        return level.getFluidState(BlockPos(x, y, z)).`is`(net.minecraft.tags.FluidTags.WATER)
    }

    private fun wet(level: net.minecraft.world.level.Level, pos: BlockPos) =
        level.getFluidState(pos).`is`(net.minecraft.tags.FluidTags.WATER) || level.getFluidState(pos.below()).`is`(net.minecraft.tags.FluidTags.WATER)

    fun canRoam(s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, pos: BlockPos): Boolean {
        if (r.swimmer) return wet(r.entity.level(), pos) && ZoneLayout.locate(s, pos.x, pos.z) != null
        val hit = ZoneLayout.locate(s, pos.x, pos.z) ?: return false
        val now = r.entity.level().gameTime
        val home = r.home
        if (now < rt.gatherUntil) return hit.zone == ZoneLayout.HUB || hit.zone == home
        if (home == null || r.migrating) return true
        if (r.visitZone != null && now < r.visitUntil) return hit.zone == home || hit.zone == ZoneLayout.HUB || hit.zone == r.visitZone
        if (hit.zone == home) return true
        val second = r.second
        // dual types like to hang around the shared border with their second biome
        if (second != null && hit.zone == second && hit.neighbour == home && hit.edgeDistance < 12) return true
        // Normal dual types: the hub and the inner edge of their other biome
        if (home == ZoneLayout.HUB && second != null && hit.zone == second && hit.dist < s.size.hubRadius + 14) return true
        if (second == ZoneLayout.HUB && hit.zone == ZoneLayout.HUB) return true
        return false
    }

    fun tickSecond(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val random = level.random
        val residents = rt.live.values.toList()

        for (r in residents) {
            val e = r.entity
            if (e.isRemoved) continue
            if (r.swimmer) { swim(level, s, rt, r, now); continue }
            if (PropVisits.tickActivity(level, s, rt, r, now)) continue
            if (now < r.busyUntil) continue
            if (Routines.tick(level, s, rt, r, now)) continue

            // Cobblemon lets some Pokémon hunt each other; in the sanctuary that becomes play-fighting
            val target = e.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
            if (target is PokemonEntity) {
                e.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
                val other = rt.live[target.pokemon.uuid]
                if (other != null && now >= other.busyUntil) { Scenes.start(level, s, rt, r, other, Scenes.Kind.PLAY_FIGHT, now); continue }
            }

            // follow waypoints (commutes go through the hub)
            if (r.waypoints.isNotEmpty()) {
                val wp = r.waypoints.first()
                if (e.blockPosition().distManhattan(wp) < 6 || random.nextInt(40) == 0) {
                    r.waypoints.removeFirst()
                    r.waypoints.firstOrNull()?.let { Residents.walkTo(e, it, 0.45f, 2) }
                } else if (!e.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                    Residents.walkTo(e, wp, 0.45f, 2)
                }
                continue
            }

            // visits end
            if (r.visitZone != null && now >= r.visitUntil) {
                r.visitZone = null
                goHome(level, s, r, viaHub = true)
                continue
            }
            if (r.migrating && now >= r.migrateUntil) {
                r.migrating = false
                goHome(level, s, r, viaHub = false)
                continue
            }

            // stray outside where it belongs: walk back
            if (!canRoam(s, rt, r, e.blockPosition())) {
                if (now - r.lastLeash > 100 || !e.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                    r.lastLeash = now
                    if (now < rt.gatherUntil) walkToZone(level, s, r, ZoneLayout.HUB) else goHome(level, s, r, viaHub = false)
                }
                continue
            }

            if (Residents.isSleeping(e)) continue
            if (PropVisits.shyness(level, s, rt, r, now)) continue

            // explore the whole biome now and then, not just the area around the spawn point
            if (random.nextInt((45 / r.personality.explore).toInt().coerceAtLeast(12)) == 0 && !e.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                val zone = r.visitZone ?: r.home ?: ZoneLayout.HUB
                walkToZone(level, s, r, zone)
                continue
            }

            // dual types whose biomes don't touch commute through the hub
            val home = r.home
            val second = r.second
            if (home != null && second != null && second in s.unlocked && !ZoneLayout.areNeighbours(home, second) && random.nextInt(900) == 0) {
                r.visitZone = second
                r.visitUntil = now + 20L * (60 + random.nextInt(60))
                commute(level, s, r, second)
                s.logEvent("${r.name} went to visit the ${Residents.zoneName(second)}.")
                continue
            }
        }

        flocks(level, s, rt, now)
        avoidRivals(s, rt, now)
        Scenes.findMeetings(level, s, rt, now)
    }

    /** Water-only residents: swim around the lakes, and never stay stranded on land. */
    private fun swim(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long) {
        val e = r.entity
        r.visitZone = null
        r.waypoints.clear()
        if (e.isInWater || wet(level, e.blockPosition())) {
            r.strandedSince = 0L
            if (level.random.nextInt(40) == 0 && !e.brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)) swimSomewhere(level, s, r)
            return
        }
        if (r.strandedSince == 0L) r.strandedSince = now
        // on land: head for the water; if it can't get there soon, it goes back to resting (and comes out in the water)
        if (now - r.strandedSince > 20L * 8) Residents.despawn(rt, r) else if ((now - r.strandedSince) % 60L < 20L) swimSomewhere(level, s, r, near = true)
    }

    private fun swimSomewhere(level: ServerLevel, s: Sanctuary, r: LiveResident, near: Boolean = false) {
        val random = level.random
        repeat(12) {
            val pt = if (near) (r.entity.blockX + random.nextInt(25) - 12) to (r.entity.blockZ + random.nextInt(25) - 12)
                else ZoneLayout.randomPoint(s, "water", random) ?: return@repeat
            if (!level.hasChunk(pt.first shr 4, pt.second shr 4) || !isWater(level, pt.first, pt.second)) return@repeat
            Residents.walkTo(r.entity, Residents.surface(level, pt.first, pt.second).below(), 0.5f, 2)
            return
        }
    }

    fun goHome(level: ServerLevel, s: Sanctuary, r: LiveResident, viaHub: Boolean) {
        val home = r.home ?: return
        if (viaHub) commute(level, s, r, home) else walkToZone(level, s, r, home)
    }

    fun walkToZone(level: ServerLevel, s: Sanctuary, r: LiveResident, zone: String, speed: Float = 0.4f) {
        val pt = ZoneLayout.randomPoint(s, zone, level.random) ?: return
        val pos = Residents.surface(level, pt.first, pt.second)
        val target = if (r.migrating && r.entity.canFly()) pos.above(10) else pos
        Residents.walkTo(r.entity, target, speed, 2)
    }

    /** Walk through the hub to another biome. */
    fun commute(level: ServerLevel, s: Sanctuary, r: LiveResident, zone: String) {
        r.waypoints.clear()
        ZoneLayout.randomPoint(s, ZoneLayout.HUB, level.random)?.let { r.waypoints += Residents.surface(level, it.first, it.second) }
        ZoneLayout.randomPoint(s, zone, level.random)?.let { r.waypoints += Residents.surface(level, it.first, it.second) }
        r.waypoints.firstOrNull()?.let { Residents.walkTo(r.entity, it, 0.45f, 2) }
    }

    /** Flying types sometimes take off together and fly out over other biomes, then come home. */
    private fun flocks(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val random = level.random
        if (Routines.isNight(level)) return // flocks rest at night
        val byZone = rt.live.values.filter {
            it.flyer && !it.migrating && !it.swimmer && !it.napping && it.napSpot == null && it.snackSpot == null && it.propSpot == null &&
                it.dozeUntil == 0L && now >= it.busyUntil && it.home != null
        }.groupBy { it.home!! }
        for ((zone, flock) in byZone) {
            if (random.nextInt(if (flock.size >= 2) 400 else 1200) != 0) continue
            val destinations = s.unlocked.filter { it != zone }
            if (destinations.isEmpty()) continue
            val dest = destinations.random()
            val pt = ZoneLayout.randomPoint(s, dest, random) ?: continue
            val centre = Residents.surface(level, pt.first, pt.second).above(12)
            val until = now + 20L * (40 + random.nextInt(40))
            for (r in flock) {
                r.migrating = true
                r.migrateUntil = until
                if (r.entity.canFly()) runCatching { r.entity.setFlying(true) }
                Residents.walkTo(r.entity, centre.offset(random.nextInt(7) - 3, random.nextInt(3), random.nextInt(7) - 3), 0.6f, 3)
            }
            val names = flock.take(3).joinToString(", ") { it.name } + if (flock.size > 3) " and friends" else ""
            s.logEvent("$names flew out over the ${Residents.zoneName(dest)}.")
        }
        // keep migrating flocks circling over their destination
        for (r in rt.live.values) {
            if (!r.migrating || now >= r.migrateUntil) continue
            if (!r.entity.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET) && random.nextInt(4) == 0) {
                val p = r.entity.blockPosition()
                Residents.walkTo(r.entity, p.offset(random.nextInt(17) - 8, random.nextInt(5) - 2, random.nextInt(17) - 8), 0.5f, 3)
            }
        }
    }

    /** Pokémon that really dislike each other keep their distance. */
    private fun avoidRivals(s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val list = rt.live.values.filter { now >= it.busyUntil && !it.napping && !it.swimmer }
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]
            val b = list[j]
            if (a.entity.distanceToSqr(b.entity) > 36.0) continue
            if (s.relation(a.pokemonId, b.pokemonId) > -60) continue
            val mover = if (a.entity.random.nextBoolean()) a else b
            val other = if (mover === a) b else a
            val away = mover.entity.position().subtract(other.entity.position()).normalize().scale(8.0)
            Residents.walkTo(mover.entity, BlockPos.containing(mover.entity.position().add(away)), 0.5f, 2)
        }
    }

    @Suppress("unused")
    private fun friendshipRate() = SanctuaryConfig.friendshipRate.get()
}
