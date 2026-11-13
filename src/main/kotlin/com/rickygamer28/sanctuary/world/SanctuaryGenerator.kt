package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import kotlin.math.sqrt

/** Every column offset within the largest sanctuary and its mountain rim, sorted from the centre outwards. */
object ColumnOrder {
    val R = SanctuarySize.MAX_RADIUS + Sanctuaries.RIM
    val dx: IntArray
    val dz: IntArray
    private val distSq: IntArray
    private val reverse: IntArray
    private val side = R * 2 + 1

    /** Index of the column at offset (dx, dz) from the centre, or -1 if outside. */
    fun indexOf(dx: Int, dz: Int): Int {
        if (dx < -R || dx > R || dz < -R || dz > R) return -1
        return reverse[(dx + R) * side + (dz + R)]
    }

    init {
        val list = ArrayList<IntArray>()
        for (x in -R..R) for (z in -R..R) {
            val d = x * x + z * z
            if (d <= R * R) list.add(intArrayOf(x, z, d))
        }
        list.sortWith(compareBy<IntArray> { it[2] }.thenBy { it[0] }.thenBy { it[1] })
        dx = IntArray(list.size) { list[it][0] }
        dz = IntArray(list.size) { list[it][1] }
        distSq = IntArray(list.size) { list[it][2] }
        reverse = IntArray(side * side) { -1 }
        for (i in dx.indices) reverse[(dx[i] + R) * side + (dz[i] + R)] = i
    }

    /** Number of columns within [radius]. */
    fun countWithin(radius: Int): Int {
        val target = radius * radius
        var lo = 0
        var hi = distSq.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (distSq[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Builds the valley from the centre outwards, a time-limited number of columns per tick and only in loaded chunks:
 * the biomes, then the wall, then the mountain rim. When a new type arrives or the sanctuary grows it's swept again;
 * finished columns are skipped.
 */
object SanctuaryGenerator {
    private val cursors = HashMap<UUID, Int>()
    private val retryAt = HashMap<UUID, Long>()
    private val missedUnloaded = HashMap<UUID, Boolean>()
    private val recheckMeadow = HashSet<UUID>()

    fun forget(id: UUID) { recheckMeadow.remove(id); cursors.remove(id); retryAt.remove(id); missedUnloaded.remove(id); BiomePainter.forget(id) }

    fun isGrowing(s: Sanctuary) = s.generationDirty || cursors.containsKey(s.id)

    /** A chunk of this sanctuary just loaded: if parts were skipped earlier, try again soon. */
    fun nudge(s: Sanctuary, now: Long) {
        if (missedUnloaded[s.id] != true || cursors.containsKey(s.id)) return
        val soon = now + 40
        val cur = retryAt[s.id]
        if (cur == null || cur > soon) retryAt[s.id] = soon
    }

    private fun total(s: Sanctuary) = ColumnOrder.countWithin(s.size.radius + Sanctuaries.RIM)

    /** Progress of the current building sweep, 0..100, or null when not building. */
    fun progress(s: Sanctuary): Int? {
        val c = cursors[s.id] ?: return if (s.generationDirty) 0 else null
        return (c * 100L / total(s).coerceAtLeast(1)).toInt()
    }

    fun tick(level: ServerLevel, data: SanctuaryData, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE) return
        val now = level.gameTime
        if (now % 10L == 0L) BiomePainter.tick(level, s)
        if (!s.generationDirty && !cursors.containsKey(s.id)) {
            // the valley is built: put up the landmarks of awake biomes
            if (now % 40L == 0L) Landmarks.tick(level, s)
            val retry = retryAt[s.id] ?: return
            if (now < retry) return
            retryAt.remove(s.id)
            s.generationDirty = true
        }
        if (s.generationDirty) {
            s.generationDirty = false
            cursors[s.id] = 0
            missedUnloaded[s.id] = false
            if (s.unlockedChanged) { s.unlockedChanged = false; recheckMeadow += s.id }
        }
        val recheck = s.id in recheckMeadow
        val ring = ZoneLayout.ring()
        val total = total(s)
        val radius = s.size.radius
        var i = cursors[s.id] ?: 0
        var budget = SanctuaryConfig.generationSpeed.get()
        val deadline = System.nanoTime() + SanctuaryConfig.generationMillis.get() * 1_000_000L
        var scanned = 0
        while (budget > 0 && i < total) {
            scanned++
            if ((scanned and 31) == 0 && System.nanoTime() > deadline) break
            val idx = i++
            val meadowDone = s.meadow.get(idx)
            if (s.dressed.get(idx) && !(meadowDone && recheck)) continue
            val ox = ColumnOrder.dx[idx]
            val oz = ColumnOrder.dz[idx]
            val x = s.center.x + ox
            val z = s.center.z + oz
            val dist = sqrt((ox * ox + oz * oz).toDouble())
            val inside = dist <= radius
            val hit = if (inside) ZoneLayout.locate(s, x, z, ring) else null
            if (inside && hit == null) continue
            // a biome that hasn't woken up is a plain meadow until a Pokémon of its type moves in (the wall is always built)
            val asleep = inside && hit!!.zone !in s.unlocked && dist <= radius - 1.6
            if (meadowDone && s.dressed.get(idx)) {
                if (asleep) continue // still asleep: stays a meadow
                s.dressed.clear(idx); s.meadow.clear(idx) // it woke up: build the real biome here
            }
            if (!level.hasChunk(x shr 4, z shr 4)) { missedUnloaded[s.id] = true; continue }
            val ok = try {
                when {
                    !inside -> BiomeDresser.dressRim(level, s, x, z, dist, ring)
                    asleep -> BiomeDresser.dressMeadow(level, s, x, z, hit!!, ring)
                    else -> BiomeDresser.dress(level, s, x, z, hit!!, ring)
                }
            } catch (e: Exception) {
                CobblemonSanctuary.LOGGER.warn("Couldn't build column $x,$z", e)
                true
            }
            if (ok) {
                s.dressed.set(idx)
                if (asleep) s.meadow.set(idx) else s.meadow.clear(idx)
                BiomePainter.mark(s, x, z)
            } else missedUnloaded[s.id] = true
            budget--
        }
        if (i >= total) {
            cursors.remove(s.id)
            recheckMeadow -= s.id
            if (missedUnloaded[s.id] == true) retryAt[s.id] = now + 600
            else if (s.oldWallRadius > 0) s.oldWallRadius = 0
        } else {
            cursors[s.id] = i
        }
        data.setDirty()
    }
}
