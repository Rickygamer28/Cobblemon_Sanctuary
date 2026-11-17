package com.rickygamer28.sanctuary.ui

import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.BiomeDresser
import com.rickygamer28.sanctuary.world.Caves
import com.rickygamer28.sanctuary.world.Landmarks
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID

/**
 * The map on the Region page: for each cell of an [N]×[N] grid over the valley, which area it is (a pocket shows
 * as its own habitat), how high the ground is (for hill shading), and what's there (a route, a pocket, water, a
 * cave below, a cave mouth). Plus markers: landmarks, the volcano and the snowy summit, the cave entrances.
 * Worked out once and kept until the region changes.
 */
object RegionMap {
    const val N = 80
    const val ROUTE = 1
    const val POCKET = 2
    const val CAVE = 4
    const val WATER = 8
    const val MOUTH = 16

    private class Cached(val key: Long, val tag: CompoundTag)
    private val cache = HashMap<UUID, Cached>()

    private fun key(s: Sanctuary): Long {
        var k = s.layoutSalt * 1_000_003L + s.size.radius * 31L + s.unlocked.hashCode() * 7L + s.landmarks.hashCode()
        for ((zone, hb) in s.habitats) k = k * 31 + zone.hashCode() + hb.x * 131L + hb.z
        return k
    }

    fun build(s: Sanctuary): CompoundTag {
        val key = key(s)
        cache[s.id]?.takeIf { it.key == key }?.let { return it.tag }
        val zones = ByteArray(N * N)
        val heights = ByteArray(N * N)
        val flags = ByteArray(N * N)
        val r = s.size.radius
        val g = SanctuaryDimension.GROUND_Y
        for (i in 0 until N) for (j in 0 until N) {
            val x = s.center.x - r + ((i + 0.5) * 2 * r / N).toInt()
            val z = s.center.z - r + ((j + 0.5) * 2 * r / N).toInt()
            val hit = ZoneLayout.locate(s, x, z) ?: continue
            val c = j * N + i
            var f = 0
            var zone = hit.zone
            val pocket = ZoneLayout.pocketAt(s, x, z)?.takeIf { it.first.live(s) && it.second <= it.first.r }
            if (pocket != null) { zone = pocket.first.zone; f = f or POCKET }
            val k = ZoneLayout.HABITATS.indexOf(zone)
            zones[c] = (if (k >= 0 && zone in s.unlocked) k + 2 else 1).toByte()
            val h = BiomeDresser.targetHeight(s, x, z, ZoneLayout.HABITATS)
            heights[c] = (h - g + 40).coerceIn(0, 255).toByte()
            if (h < BiomeDresser.WATER_Y || BiomeDresser.pondLevel(s, x, z) != null) f = f or WATER
            if (hit.zone in s.unlocked && hit.dist > s.size.hubRadius - 4 && BiomeDresser.routeDistance(s, x, z) < 3.5) f = f or ROUTE
            Caves.at(s, x, z)?.let { f = f or (if (it.mouth) MOUTH else CAVE) }
            flags[c] = f.toByte()
        }
        val marks = ListTag()
        fun mark(kind: String, name: String, x: Double, z: Double, zone: String) = marks.add(CompoundTag().also {
            it.putString("k", kind); it.putString("n", name); it.putString("zone", zone)
            it.putFloat("x", ((x - s.center.x) / r).toFloat()); it.putFloat("z", ((z - s.center.z) / r).toFloat())
        })
        for ((zone, pos) in s.landmarks) if (pos != Landmarks.NONE && zone in s.unlocked) {
            val p = BlockPos.of(pos)
            mark("landmark", "${ZoneLayout.NAMES[zone] ?: zone}: landmark", p.x + 0.5, p.z + 0.5, zone)
        }
        if ("fire" in s.unlocked) BiomeDresser.peakOf(s, "fire")?.let { mark("peak", "The volcano's crater", it.first, it.second, "fire") }
        if ("ice" in s.unlocked) BiomeDresser.peakOf(s, "ice")?.let { mark("peak", "The snowy summit", it.first, it.second, "ice") }
        for ((zone, ex, ez) in Caves.entrances(s)) mark("cave", "Cave entrance", ex, ez, zone)
        for (h in Caves.halls(s)) mark("hall", "${Caves.HALL_NAMES[h.zone] ?: "A cave hall"} (underground)", h.hallX, h.hallZ, h.zone)
        val tag = CompoundTag()
        tag.putByteArray("map", zones)
        tag.putByteArray("mapH", heights)
        tag.putByteArray("mapF", flags)
        tag.put("mapMarks", marks)
        cache[s.id] = Cached(key, tag)
        return tag
    }

    fun forget(id: UUID) { cache.remove(id) }
}
