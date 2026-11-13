package com.rickygamer28.sanctuary.world

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map of a sanctuary: a Normal hub in the middle and a ring of type biomes around it.
 * Angles are measured clockwise from north, so the first ring biome (Water) starts at the top.
 */
object ZoneLayout {
    const val HUB = "normal"

    /** Neighbours in this order are always real dual-type combos. */
    val BASE_RING = listOf(
        "water", "ground", "rock", "fighting", "fire", "dragon", "dark", "ghost",
        "poison", "bug", "grass", "fairy", "psychic", "steel", "electric", "ice",
    )

    /** The ring, plus any custom types added by other mods (appended at the end). */
    @Volatile private var cachedRing: List<String> = BASE_RING
    @Volatile private var cachedTypeCount = -1

    fun ring(): List<String> {
        val count = runCatching { ElementalTypes.count() }.getOrDefault(-1)
        if (count == cachedTypeCount) return cachedRing
        val extra = runCatching {
            ElementalTypes.all().map { it.name.lowercase() }
                .filter { it !in BASE_RING && it != HUB && it != TypeLogic.FLYING }
        }.getOrDefault(emptyList())
        cachedRing = BASE_RING + extra
        cachedTypeCount = count
        return cachedRing
    }

    data class Hit(
        val zone: String,
        val dist: Double,
        /** Distance in blocks to the nearest edge of this ring biome (0 for the hub). */
        val edgeDistance: Double,
        /** The ring biome on the other side of the nearest edge (null in the hub). */
        val neighbour: String?,
    )

    private fun phase(s: Sanctuary, salt: Int): Double =
        ((s.seed xor (salt * 0x9E3779B97F4A7C15uL.toLong())) and 0xFFFF).toDouble() / 0xFFFF * 2 * PI

    /** Organic, curvy borders: the angle is bent by smooth 2D noise. */
    private fun wobble(s: Sanctuary, x: Int, z: Int): Double =
        (Noise.value(s.seed + 101, x / 46.0, z / 46.0) - 0.5) * 26.0 +
            (Noise.value(s.seed + 202, x / 15.0, z / 15.0) - 0.5) * 8.0

    fun hubRadiusAt(s: Sanctuary, x: Int, z: Int): Double =
        s.size.hubRadius + (Noise.value(s.seed + 303, x / 18.0, z / 18.0) - 0.5) * 9.0

    /** Where does the column at (x, z) belong? Null if outside the sanctuary's current size. */
    fun locate(s: Sanctuary, x: Int, z: Int, ring: List<String> = ring()): Hit? {
        val dx = (x - s.center.x).toDouble()
        val dz = (z - s.center.z).toDouble()
        val dist = sqrt(dx * dx + dz * dz)
        if (dist > s.size.radius) return null
        var angle = Math.toDegrees(atan2(dx, -dz))
        if (angle < 0) angle += 360.0
        if (dist <= hubRadiusAt(s, x, z)) return Hit(HUB, dist, 0.0, null)
        val n = ring.size
        val w = 360.0 / n
        var a = (angle + wobble(s, x, z)) % 360.0
        if (a < 0) a += 360.0
        val pos = a / w
        val i = floor(pos).toInt().coerceIn(0, n - 1)
        val f = pos - i
        val edgeDeg = min(f, 1 - f) * w
        val edge = Math.toRadians(edgeDeg) * dist
        val neighbour = if (f < 0.5) ring[(i - 1 + n) % n] else ring[(i + 1) % n]
        return Hit(ring[i], dist, edge, neighbour)
    }

    /** The ring biome in the direction of (x, z), ignoring the hub (used to blend the hub's edge). */
    fun ringZoneAt(s: Sanctuary, x: Int, z: Int, ring: List<String> = ring()): String? {
        if (ring.isEmpty()) return null
        val dx = (x - s.center.x).toDouble()
        val dz = (z - s.center.z).toDouble()
        var angle = Math.toDegrees(atan2(dx, -dz))
        if (angle < 0) angle += 360.0
        val n = ring.size
        var a = (angle + wobble(s, x, z)) % 360.0
        if (a < 0) a += 360.0
        return ring[floor(a / (360.0 / n)).toInt().coerceIn(0, n - 1)]
    }

    fun areNeighbours(a: String, b: String, ring: List<String> = ring()): Boolean {
        if (a == HUB || b == HUB) return true
        val i = ring.indexOf(a)
        val j = ring.indexOf(b)
        if (i < 0 || j < 0) return false
        val d = kotlin.math.abs(i - j)
        return d == 1 || d == ring.size - 1
    }

    /** How far out a biome currently reaches (it grows with the number of Pokémon of that type). */
    /** How far out a biome reaches (the whole way to the wall: every biome is fully built once it wakes up). */
    fun extent(s: Sanctuary, zone: String): Double {
        if (zone == HUB) return s.size.hubRadius.toDouble()
        return s.size.radius - 6.0
    }

    /** The middle of a ring biome (used for volcanoes, peaks and spawning order). */
    fun anchor(s: Sanctuary, zone: String, ring: List<String> = ring()): Pair<Int, Int> {
        if (zone == HUB) return s.center.x to s.center.z
        val i = ring.indexOf(zone).coerceAtLeast(0)
        val a = (i + 0.5) * 2 * PI / ring.size
        val d = (s.size.hubRadius + s.size.radius) / 2.0
        return (s.center.x + sin(a) * d).toInt() to (s.center.z - cos(a) * d).toInt()
    }

    /**
     * A random spot inside a biome (x, z), spread evenly over the biome's whole area
     * (not bunched up near the middle), or null if none was found.
     */
    fun randomPoint(s: Sanctuary, zone: String, random: RandomSource, ring: List<String> = ring()): Pair<Int, Int>? {
        if (zone == HUB) {
            val ang = random.nextDouble() * 2 * PI
            val outer = (s.size.hubRadius - 6).coerceAtLeast(3).toDouble()
            val d = sqrt(9.0 + random.nextDouble() * (outer * outer - 9.0))
            return (s.center.x + sin(ang) * d).toInt() to (s.center.z - cos(ang) * d).toInt()
        }
        val i = ring.indexOf(zone)
        if (i < 0) return null
        val inner = s.size.hubRadius + 6.0
        val outer = extent(s, zone) - 2
        if (outer <= inner + 2) return null
        val w = 360.0 / ring.size
        repeat(24) {
            // area-uniform: more points further out, where there's more room
            val d = sqrt(inner * inner + random.nextDouble() * (outer * outer - inner * inner))
            val raw = Math.toRadians(i * w + w * (-0.2 + random.nextDouble() * 1.4))
            val x = (s.center.x + sin(raw) * d).toInt()
            val z = (s.center.z - cos(raw) * d).toInt()
            if (locate(s, x, z, ring)?.zone == zone) return x to z
        }
        return null
    }
}
