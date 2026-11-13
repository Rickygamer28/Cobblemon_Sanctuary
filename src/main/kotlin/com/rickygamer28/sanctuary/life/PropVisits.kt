package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.data.ResidentData
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.Props
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import kotlin.math.abs

/**
 * Residents visiting the spots of their biome (and their favourite one): walking over, then sitting, splashing,
 * sniffing, training, warming up… for a few seconds. Visits make them a little fonder of their trainer.
 */
object PropVisits {
    private const val RANGE = 40.0

    fun kindOf(id: String?): Props.Kind? = id?.let { Props.KINDS[it] }

    /** "the mossy log in the Grass biome", for the resident screen. */
    fun describe(s: Sanctuary, rd: ResidentData?): String? {
        val pos = rd?.favourite?.let { BlockPos.of(it) } ?: return null
        val kind = kindOf(rd.favouriteKind) ?: return null
        val zone = ZoneLayout.locate(s, pos.x, pos.z)?.zone
        val label = kind.label.replaceFirst(Regex("^(a|an|some) "), "the ")
        return "$label in the ${Residents.zoneName(zone)}"
    }

    private fun valid(level: ServerLevel, s: Sanctuary, pos: BlockPos, kind: String): Boolean =
        if (kind == "berry_tree") pos.asLong() in s.berrySpots else s.props[pos.asLong()] == kind

    private fun likes(r: LiveResident, kind: Props.Kind): Boolean =
        runCatching { TypeLogic.currentTypes(r.pokemon).any { it in kind.types } }.getOrDefault(false)

    // ------------------------------------------------------------------ choosing where to go

    /** Picks a spot to visit (its favourite now and then). Returns true if it set off. */
    fun plan(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        val rd = s.residents[r.pokemonId]
        val here = r.entity.blockPosition()
        // the favourite spot first, if it's still there and it may go there
        val fav = rd?.favourite?.let { BlockPos.of(it) }
        val favKind = rd?.favouriteKind
        if (fav != null && favKind != null && level.random.nextDouble() < (0.3 * r.personality.homebody).coerceAtMost(0.9)) {
            if (level.isLoaded(fav) && fav.distSqr(here) < 120.0 * 120.0) {
                if (!valid(level, s, fav, favKind) || !Props.stillThere(level, fav)) {
                    rd.favourite = null; rd.favouriteKind = null
                } else if (Behaviour.canRoam(s, rt, r, fav)) {
                    return go(level, r, fav, favKind, now)
                }
            }
        }
        // a spot nearby, preferring the kinds its types love
        var total = 0.0
        val options = ArrayList<Pair<Long, Double>>()
        for ((p, kindId) in s.props) {
            val pos = BlockPos.of(p)
            if (abs(pos.x - here.x) > RANGE || abs(pos.z - here.z) > RANGE) continue
            val kind = kindOf(kindId) ?: continue
            if (!level.isLoaded(pos) || !Behaviour.canRoam(s, rt, r, pos)) continue
            val w = (if (likes(r, kind)) 4.0 else 1.0) * (if (p == rd?.favourite) 3.0 else 1.0)
            total += w
            options += p to w
        }
        if (options.isEmpty()) return false
        var roll = level.random.nextDouble() * total
        val pick = options.firstOrNull { roll -= it.second; roll <= 0 }?.first ?: options.last().first
        val pos = BlockPos.of(pick)
        val kindId = s.props[pick] ?: return false
        if (!Props.stillThere(level, pos)) { s.props.remove(pick); return false }
        return go(level, r, pos, kindId, now)
    }

    private fun go(level: ServerLevel, r: LiveResident, pos: BlockPos, kindId: String, now: Long, offset: Int = 0): Boolean {
        val kind = kindOf(kindId) ?: return false
        val stand = standSpot(level, pos, kind, offset) ?: return false
        r.propSpot = pos
        r.propStand = stand
        r.propKind = kindId
        r.propUntil = now + 20L * 40
        r.waypoints.clear()
        Residents.walkTo(r.entity, stand, 0.4f, 1)
        return true
    }

    /** Where to stand: on top of it, or on the ground next to it. */
    private fun standSpot(level: ServerLevel, pos: BlockPos, kind: Props.Kind, offset: Int): BlockPos? {
        if (kind.perch) {
            val dx = if (offset == 0) 0 else (offset % 3) - 1
            val dz = if (offset == 0) 0 else ((offset / 3) % 3) - 1
            return Residents.surface(level, pos.x + dx, pos.z + dz)
        }
        val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).let { if (offset > 0) it.drop(offset % 4) + it.take(offset % 4) else it.shuffled() }
        for (dist in 1..3) for ((dx, dz) in sides) {
            val p = Residents.surface(level, pos.x + dx * dist, pos.z + dz * dist)
            if (abs(p.y - pos.y) <= 2 && level.getFluidState(p).isEmpty && level.getBlockState(p).isAir) return p
        }
        return null
    }

    // ------------------------------------------------------------------ walking there (called by the routines)

    /** Returns true while it's on its way to a spot. */
    fun going(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        val spot = r.propSpot ?: return false
        val stand = r.propStand ?: spot
        when {
            now > r.propUntil -> clear(r)
            r.entity.blockPosition().closerThan(stand, 1.8) || r.entity.blockPosition().closerThan(spot, 2.2) -> arrive(level, s, rt, r, spot, now)
            !r.entity.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET) -> Residents.walkTo(r.entity, stand, 0.4f, 1)
        }
        return true
    }

    private fun clear(r: LiveResident) {
        r.propSpot = null; r.propStand = null; r.propKind = null
    }

    private fun arrive(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, spot: BlockPos, now: Long) {
        val kindId = r.propKind
        clear(r)
        val kind = kindOf(kindId) ?: return
        Residents.stop(r.entity)
        r.activityKind = kind.id
        r.activityAt = spot
        r.activityUntil = now + 20L * (6 + level.random.nextInt(7))
        r.busyUntil = r.activityUntil
        r.entity.lookControl.setLookAt(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5)
        // friends nearby come along to a picnic or a campfire
        if (kind.gathering) {
            var n = 0
            for (o in rt.live.values) {
                if (n >= 3) break
                if (o === r || o.propSpot != null || o.activityKind != null || o.napping || o.swimmer || now < o.busyUntil) continue
                if (o.entity.distanceToSqr(r.entity) > 14.0 * 14.0 || s.relation(r.pokemonId, o.pokemonId) < 0) continue
                if (!Behaviour.canRoam(s, rt, o, spot)) continue
                if (go(level, o, spot, kind.id, now, offset = ++n)) o.propUntil = now + 20L * 20
            }
        }
    }

    // ------------------------------------------------------------------ what it does there

    /** Plays the visit; called every second before anything else. Returns true while it's busy at a spot. */
    fun tickActivity(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        val kindId = r.activityKind ?: return false
        val kind = kindOf(kindId)
        val at = r.activityAt
        if (kind == null || at == null || r.entity.isRemoved) { r.activityKind = null; return false }
        if (now >= r.activityUntil) {
            finish(level, s, rt, r, kind, at, now)
            return false
        }
        r.entity.lookControl.setLookAt(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        val second = ((r.activityUntil - now) / 20L).toInt()
        act(level, r, kind, at, second)
        return true
    }

    private fun act(level: ServerLevel, r: LiveResident, kind: Props.Kind, at: BlockPos, second: Int) {
        val e = r.entity
        val cx = at.x + 0.5
        val cy = at.y + 0.6
        val cz = at.z + 0.5
        fun near(type: ParticleOptions, count: Int, spread: Double = 0.4, speed: Double = 0.02) =
            level.sendParticles(type, cx, cy, cz, count, spread, 0.3, spread, speed)
        val anim = second % 3 == 0
        when (kind.action) {
            Props.Action.SIT -> { if (second % 4 == 0) Effects.play(e, Effects.CRY); if (second % 3 == 1) Effects.happy(e) }
            Props.Action.NAP -> Routines.keepAsleep(r)
            Props.Action.WARM -> { near(ParticleTypes.FLAME, 2, 0.3, 0.01); if (anim) Effects.play(e, listOf("status", "cry")); if (second % 4 == 1) Effects.happy(e) }
            Props.Action.SPLASH -> { near(ParticleTypes.SPLASH, 12, 0.5, 0.1); if (anim) Effects.play(e, listOf("status", "physical", "cry")) }
            Props.Action.SNIFF -> { near(ParticleTypes.CHERRY_LEAVES, 2, 0.6); if (anim) Effects.play(e, listOf("status", "cry")); if (second % 4 == 2) Effects.happy(e) }
            Props.Action.ROLL -> { near(BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(at)), 10, 0.5, 0.1); if (anim) Effects.play(e, listOf("physical", "status", "cry")) }
            Props.Action.TRAIN -> { if (second % 2 == 0) { Effects.play(e, Effects.ATTACK); near(ParticleTypes.CRIT, 8, 0.3, 0.2) } }
            Props.Action.CHARGE -> { near(ParticleTypes.ELECTRIC_SPARK, 10, 0.3, 0.3); if (anim) Effects.play(e, Effects.ATTACK_SPECIAL) }
            Props.Action.SHIMMER -> { near(ParticleTypes.ENCHANT, 12, 0.6, 0.5); if (anim) Effects.play(e, listOf("status", "special", "cry")) }
            Props.Action.HAUNT -> { near(ParticleTypes.SOUL, 2, 0.4, 0.02); if (anim) Effects.play(e, listOf("status", "cry")) }
            Props.Action.PICNIC -> { if (second % 2 == 0) Effects.hearts(e); if (anim) Effects.play(e, listOf("eat", "status", "cry")) }
            Props.Action.DIG -> { near(BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(at)), 8, 0.4, 0.1); if (anim) Effects.play(e, listOf("physical", "status", "cry")) }
        }
    }

    private fun finish(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, kind: Props.Kind, at: BlockPos, now: Long) {
        r.activityKind = null
        r.activityAt = null
        r.busyUntil = 0
        if (kind.action == Props.Action.NAP) Routines.wake(r)
        // a nice time: a little fonder of its trainer (at most every ten minutes)
        if (now - r.lastFondAt > 20L * 600) {
            r.lastFondAt = now
            Growth.fonder(s, r.pokemon, 1)
        }
        // sharing a spot brings them closer
        for (o in rt.live.values) {
            if (o === r || o.activityAt != at) continue
            s.addRelation(r.pokemonId, o.pokemonId, 1)
        }
        considerFavourite(level, s, r, at, kind)
        if (level.random.nextInt(25) == 0) {
            val zone = ZoneLayout.locate(s, at.x, at.z)?.zone
            s.logEvent("${r.name} spent some time at ${kind.label} in the ${Residents.zoneName(zone)}.")
        }
    }

    /** It may pick the spot as its favourite (more likely for the kinds its types love). */
    fun considerFavourite(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos, kind: Props.Kind) {
        val rd = s.resident(r.pokemonId)
        if (rd.favourite == at.asLong()) return
        val loves = likes(r, kind)
        val chance = when {
            rd.favourite == null -> if (loves) 0.6 else 0.25
            loves -> 0.06
            else -> 0.0
        }
        if (level.random.nextDouble() >= chance) return
        rd.favourite = at.asLong()
        rd.favouriteKind = kind.id
        val zone = ZoneLayout.locate(s, at.x, at.z)?.zone
        s.logEvent("${r.name} found a favourite spot: ${kind.label.replaceFirst(Regex("^(a|an|some) "), "the ")} in the ${Residents.zoneName(zone)}.")
    }

    // ------------------------------------------------------------------ beds and shyness

    /** A soft spot near [from] to sleep on (a mossy mound, a snow pile…), or null. */
    fun bedNear(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, from: BlockPos): BlockPos? =
        s.props.entries.asSequence()
            .filter { kindOf(it.value)?.bed == true }
            .map { BlockPos.of(it.key) }
            .filter { abs(it.x - from.x) < 24 && abs(it.z - from.z) < 24 && level.isLoaded(it) && Behaviour.canRoam(s, rt, r, it) }
            .minByOrNull { it.distSqr(from) }
            ?.let { Residents.surface(level, it.x, it.z) }

    /** Shy residents slip away from crowds, back to their favourite spot if they have one. */
    fun shyness(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, now: Long): Boolean {
        if (!r.personality.shy || level.random.nextInt(10) != 0) return false
        val crowd = rt.live.values.count { it !== r && it.entity.distanceToSqr(r.entity) < 6.0 * 6.0 }
        if (crowd < 3) return false
        if (plan(level, s, rt, r, now)) return true
        val e = r.entity
        val others = rt.live.values.filter { it !== r && it.entity.distanceToSqr(e) < 36.0 }
        val cx = others.map { it.entity.x }.average()
        val cz = others.map { it.entity.z }.average()
        val away = e.position().subtract(cx, e.y, cz).normalize().scale(12.0)
        val dest = BlockPos.containing(e.position().add(away))
        if (!Behaviour.canRoam(s, rt, r, dest)) return false
        Residents.walkTo(e, Residents.surface(level, dest.x, dest.z), 0.45f, 2)
        return true
    }
}
