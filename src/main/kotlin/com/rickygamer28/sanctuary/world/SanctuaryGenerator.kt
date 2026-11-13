package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/** Every column offset within the largest sanctuary, sorted from the centre outwards. */
object ColumnOrder {
    val dx: IntArray
    val dz: IntArray
    private val distSq: IntArray

    init {
        val r = SanctuarySize.MAX_RADIUS
        val list = ArrayList<IntArray>()
        for (x in -r..r) for (z in -r..r) {
            val d = x * x + z * z
            if (d <= r * r) list.add(intArrayOf(x, z, d))
        }
        list.sortWith(compareBy<IntArray> { it[2] }.thenBy { it[0] }.thenBy { it[1] })
        dx = IntArray(list.size) { list[it][0] }
        dz = IntArray(list.size) { list[it][1] }
        distSq = IntArray(list.size) { list[it][2] }
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
 * Grows the biomes outwards from the centre, a few columns per tick, only in loaded chunks.
 * When a new type arrives or a biome grows, the sanctuary is swept again; already-dressed columns are skipped.
 */
object SanctuaryGenerator {
    private val cursors = HashMap<UUID, Int>()
    private val retryAt = HashMap<UUID, Long>()
    private val missedUnloaded = HashMap<UUID, Boolean>()

    fun forget(id: UUID) { cursors.remove(id); retryAt.remove(id); missedUnloaded.remove(id) }

    fun isGrowing(s: Sanctuary) = s.generationDirty || cursors.containsKey(s.id)

    /** Progress of the current building sweep, 0..100, or null when not building. */
    fun progress(s: Sanctuary): Int? {
        val c = cursors[s.id] ?: return if (s.generationDirty) 0 else null
        val total = ColumnOrder.countWithin(s.size.radius).coerceAtLeast(1)
        return (c * 100L / total).toInt()
    }

    fun tick(level: ServerLevel, data: SanctuaryData, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE) return
        val now = level.gameTime
        if (!s.generationDirty && !cursors.containsKey(s.id)) {
            val retry = retryAt[s.id] ?: return
            if (now < retry) return
            retryAt.remove(s.id)
            s.generationDirty = true
        }
        if (s.generationDirty) {
            s.generationDirty = false
            cursors[s.id] = 0
            missedUnloaded[s.id] = false
        }
        val ring = ZoneLayout.ring()
        val total = ColumnOrder.countWithin(s.size.radius)
        var i = cursors[s.id] ?: 0
        var budget = SanctuaryConfig.generationSpeed.get()
        val deadline = System.nanoTime() + SanctuaryConfig.generationMillis.get() * 1_000_000L
        var scanned = 0
        val extents = HashMap<String, Double>()
        while (budget > 0 && i < total) {
            scanned++
            if ((scanned and 63) == 0 && System.nanoTime() > deadline) break
            val idx = i++
            if (s.dressed.get(idx)) continue
            val x = s.center.x + ColumnOrder.dx[idx]
            val z = s.center.z + ColumnOrder.dz[idx]
            val hit = ZoneLayout.locate(s, x, z, ring) ?: continue
            if (hit.zone !in s.unlocked) continue
            val ext = extents.getOrPut(hit.zone) { ZoneLayout.extent(s, hit.zone) }
            if (hit.zone != ZoneLayout.HUB && hit.dist > ext) continue
            if (!level.hasChunk(x shr 4, z shr 4)) { missedUnloaded[s.id] = true; continue }
            val ok = try {
                BiomeDresser.dress(level, s, x, z, hit, ring)
            } catch (e: Exception) {
                com.rickygamer28.sanctuary.CobblemonSanctuary.LOGGER.warn("Couldn't dress column $x,$z", e)
                true
            }
            if (ok) s.dressed.set(idx)
            budget--
        }
        if (i >= total) {
            cursors.remove(s.id)
            if (missedUnloaded[s.id] == true) retryAt[s.id] = now + 600
        } else {
            cursors[s.id] = i
        }
        data.setDirty()
    }

    @Suppress("unused")
    fun columnPos(s: Sanctuary, idx: Int) = BlockPos(s.center.x + ColumnOrder.dx[idx], s.center.y, s.center.z + ColumnOrder.dz[idx])
}
