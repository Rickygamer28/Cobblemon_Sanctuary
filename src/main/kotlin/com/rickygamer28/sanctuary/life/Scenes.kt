package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import kotlin.math.roundToInt

/** A short interaction between two residents, played out with their own animations. */
class Scene(
    val a: LiveResident,
    val b: LiveResident,
    val kind: Scenes.Kind,
    val start: Long,
    val steps: List<Pair<Int, (Scene) -> Unit>>,
    val length: Int,
) {
    var next = 0
}

object Scenes {
    enum class Kind { GREET, PLAY_FIGHT, NUZZLE, STANDOFF, TAG }

    /** Species that are natural rivals (by species name). */
    private val NATURAL_RIVALS = listOf(
        "zangoose" to "seviper", "heatmor" to "durant", "mongoose" to "seviper", "sharpedo" to "wailord",
        "lunatone" to "solrock", "volbeat" to "illumise", "plusle" to "minun", "throh" to "sawk",
        "mightyena" to "absol", "ekans" to "pidgey", "arbok" to "pidgeot", "persian" to "raichu",
        "gyarados" to "dragonite", "palkia" to "dialga", "groudon" to "kyogre", "xerneas" to "yveltal",
    )

    fun isNaturalRival(a: String, b: String): Boolean =
        NATURAL_RIVALS.any { (x, y) -> (x == a && y == b) || (x == b && y == a) }

    // ------------------------------------------------------------------ finding meetings

    fun findMeetings(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val baseChance = SanctuaryConfig.meetingChance.get()
        val night = Routines.isNight(level)
        val free = rt.live.values.filter {
            !it.napping && it.napSpot == null && it.snackSpot == null &&
            now >= it.busyUntil && now >= it.nextMeetAt && !it.migrating && it.waypoints.isEmpty() &&
                !it.entity.isRemoved && !Residents.isSleeping(it.entity) && it.entity.onGround()
        }
        val used = HashSet<LiveResident>()
        for (i in free.indices) {
            val a = free[i]
            if (a in used) continue
            for (j in i + 1 until free.size) {
                val b = free[j]
                if (b in used) continue
                if (a.entity.distanceToSqr(b.entity) > 4.5 * 4.5) continue
                // night owls (Ghost, Dark) are livelier at night, everyone else by day
                val owls = Routines.nocturnal(a) && Routines.nocturnal(b)
                val chance = baseChance * if (night) (if (owls) 2.0 else 0.25) else (if (owls) 0.5 else 1.0)
                if (level.random.nextDouble() > chance) continue
                val rel = firstImpression(s, a, b, level)
                val roll = level.random.nextDouble()
                val kind = when {
                    rel <= -30 -> Kind.STANDOFF
                    rel >= 40 -> if (roll < 0.35) Kind.PLAY_FIGHT else if (roll < 0.6) Kind.NUZZLE else if (roll < 0.8) Kind.TAG else Kind.GREET
                    else -> if (roll < 0.45) Kind.GREET else if (roll < 0.8) Kind.PLAY_FIGHT else Kind.TAG
                }
                start(level, s, rt, a, b, kind, now)
                used += a; used += b
                break
            }
        }
    }

    private fun firstImpression(s: Sanctuary, a: LiveResident, b: LiveResident, level: ServerLevel): Int {
        if (s.hasRelation(a.pokemonId, b.pokemonId)) return s.relation(a.pokemonId, b.pokemonId)
        val sa = a.pokemon.species.name.lowercase()
        val sb = b.pokemon.species.name.lowercase()
        val start = when {
            isNaturalRival(sa, sb) -> -50
            level.random.nextDouble() < SanctuaryConfig.personalDislikeChance.get() -> -35
            sa == sb -> 15 // same kind get along
            else -> 0
        }
        s.setRelation(a.pokemonId, b.pokemonId, start)
        return start
    }

    // ------------------------------------------------------------------ scene engine

    private fun at(t: Int, f: (Scene) -> Unit): Pair<Int, (Scene) -> Unit> = t to f

    fun start(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, a: LiveResident, b: LiveResident, kind: Kind, now: Long) {
        val steps = ArrayList<Pair<Int, (Scene) -> Unit>>()
        val r = level.random
        val friendRate = SanctuaryConfig.friendshipRate.get()
        val rivalRate = SanctuaryConfig.rivalryRate.get()
        fun change(delta: Double) = { _: Scene -> relate(s, a, b, delta.roundToInt()) }

        val length: Int
        when (kind) {
            Kind.GREET -> {
                steps += at(8) { _ -> Effects.play(a.entity, Effects.CRY) }
                steps += at(30) { _ -> Effects.play(b.entity, Effects.CRY) }
                steps += at(50) { _ -> if (s.relation(a.pokemonId, b.pokemonId) >= 20) { Effects.hearts(a.entity); Effects.hearts(b.entity) } }
                steps += at(55, change(3 * friendRate))
                length = 60
            }
            Kind.NUZZLE -> {
                steps += at(0) { _ -> Residents.walkTo(a.entity, b.entity.blockPosition(), 0.35f, 1) }
                steps += at(20) { _ -> Effects.play(a.entity, Effects.CRY); Effects.play(b.entity, Effects.CRY); Effects.hearts(a.entity); Effects.hearts(b.entity) }
                steps += at(45) { _ -> Effects.hearts(a.entity); Effects.hearts(b.entity) }
                steps += at(55, change(4 * friendRate))
                steps += at(58) { scene -> Families.afterNuzzle(level, s, scene.a, scene.b) }
                length = 60
            }
            Kind.PLAY_FIGHT -> {
                var t = 6
                val rounds = 1 + r.nextInt(3)
                var attacker = a
                var defender = b
                repeat(rounds) {
                    val atk = attacker
                    val def = defender
                    val moves = if (r.nextBoolean()) Effects.ATTACK else Effects.ATTACK_SPECIAL
                    steps += at(t) { _ -> Residents.walkTo(atk.entity, def.entity.blockPosition(), 0.5f, 1) }
                    steps += at(t + 10) { _ -> Effects.play(atk.entity, moves); MegaCompat.maybeMega(atk.entity, s) }
                    steps += at(t + 22) { _ -> Effects.sparks(def.entity); Effects.play(def.entity, Effects.HIT) }
                    t += 40
                    attacker = def
                    defender = atk
                }
                steps += at(t) { _ -> Effects.play(a.entity, Effects.CRY); Effects.play(b.entity, Effects.CRY) }
                val soured = r.nextDouble() < 0.08
                steps += at(t + 10) { scene ->
                    if (soured) {
                        Effects.angry(a.entity); Effects.angry(b.entity)
                        relate(s, scene.a, scene.b, (-6 * rivalRate).roundToInt())
                    } else {
                        Effects.happy(a.entity); Effects.happy(b.entity)
                        relate(s, scene.a, scene.b, (5 * friendRate).roundToInt())
                        if (r.nextInt(6) == 0) s.logEvent("${a.name} and ${b.name} play-fought in the ${Residents.zoneName(zoneOf(s, a))}.")
                    }
                }
                length = t + 15
            }
            Kind.TAG -> {
                // one chases, the other runs; when it's caught they swap
                steps += at(0) { _ -> Effects.play(a.entity, Effects.CRY); Effects.happy(a.entity) }
                var t = 6
                for (round in 0..1) {
                    val chaser = if (round == 0) a else b
                    val runner = if (round == 0) b else a
                    repeat(4) {
                        steps += at(t) { _ ->
                            val away = runner.entity.position().subtract(chaser.entity.position()).normalize()
                            val side = if (r.nextBoolean()) 1.0 else -1.0
                            val dest = runner.entity.position().add(away.scale(7.0)).add(away.z * 3 * side, 0.0, -away.x * 3 * side)
                            Residents.walkTo(runner.entity, BlockPos.containing(dest), 0.8f, 1)
                            Residents.walkTo(chaser.entity, runner.entity.blockPosition(), 0.8f, 1)
                        }
                        t += 14
                    }
                    steps += at(t) { _ -> Effects.play(chaser.entity, Effects.CRY); Effects.happy(runner.entity) } // tagged!
                    t += 10
                }
                steps += at(t) { _ -> Effects.happy(a.entity); Effects.happy(b.entity) }
                steps += at(t + 5, change(4 * friendRate))
                if (r.nextInt(8) == 0) steps += at(t + 6) { _ -> s.logEvent("${a.name} and ${b.name} played tag in the ${Residents.zoneName(zoneOf(s, a))}.") }
                length = t + 10
            }
            Kind.STANDOFF -> {
                steps += at(8) { _ -> Effects.play(a.entity, Effects.SHOW_OFF); Effects.angry(a.entity) }
                steps += at(30) { _ -> Effects.play(b.entity, Effects.SHOW_OFF); Effects.angry(b.entity) }
                steps += at(50) { _ -> Effects.play(a.entity, Effects.CRY); Effects.play(b.entity, Effects.CRY) }
                steps += at(62) { scene ->
                    val loser = if (r.nextBoolean()) scene.a else scene.b
                    val winner = if (loser === scene.a) scene.b else scene.a
                    val away = loser.entity.position().subtract(winner.entity.position()).normalize().scale(10.0)
                    Residents.walkTo(loser.entity, BlockPos.containing(loser.entity.position().add(away)), 0.55f, 2)
                    relate(s, scene.a, scene.b, (-4 * rivalRate).roundToInt())
                    if (r.nextInt(3) == 0) s.logEvent("${loser.name} backed away from ${winner.name} in the ${Residents.zoneName(zoneOf(s, loser))}.")
                }
                length = 70
            }
        }
        steps.sortBy { it.first }
        val scene = Scene(a, b, kind, now, steps, length)
        a.busyUntil = now + length
        b.busyUntil = now + length
        if (kind != Kind.PLAY_FIGHT && kind != Kind.NUZZLE && kind != Kind.TAG) { Residents.stop(a.entity); Residents.stop(b.entity) }
        rt.scenes += scene
    }

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (rt.scenes.isEmpty()) return
        val it = rt.scenes.iterator()
        while (it.hasNext()) {
            val sc = it.next()
            if (sc.a.entity.isRemoved || sc.b.entity.isRemoved) { finish(sc, now, level); it.remove(); continue }
            val t = (now - sc.start).toInt()
            if (t % 10 == 0) {
                sc.a.entity.brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(sc.b.entity, true))
                sc.b.entity.brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(sc.a.entity, true))
                sc.a.entity.lookControl.setLookAt(sc.b.entity)
                sc.b.entity.lookControl.setLookAt(sc.a.entity)
            }
            while (sc.next < sc.steps.size && sc.steps[sc.next].first <= t) {
                runCatching { sc.steps[sc.next].second(sc) }
                sc.next++
            }
            if (t >= sc.length) { finish(sc, now, level); it.remove() }
        }
    }

    private fun finish(sc: Scene, now: Long, level: ServerLevel) {
        for (r in listOf(sc.a, sc.b)) {
            r.busyUntil = 0
            r.nextMeetAt = now + 600 + level.random.nextInt(1200)
        }
    }

    private fun relate(s: Sanctuary, a: LiveResident, b: LiveResident, delta: Int) {
        val before = s.relation(a.pokemonId, b.pokemonId)
        val after = s.addRelation(a.pokemonId, b.pokemonId, delta)
        if (before < 40 && after >= 40) s.logEvent("${a.name} and ${b.name} became friends!")
        if (before > -30 && after <= -30) s.logEvent("${a.name} and ${b.name} became rivals.")
    }

    private fun zoneOf(s: Sanctuary, r: LiveResident): String? =
        ZoneLayout.locate(s, r.entity.blockX, r.entity.blockZ)?.zone ?: r.home
}
