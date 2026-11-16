package com.rickygamer28.sanctuary.world

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map of a sanctuary, laid out like a region from the Pokémon games: a town in the middle, and around it
 * habitats (a forest, a lake and its coast, a cave and mountain, a volcano…) where the types that suit them live.
 * Between and around the habitats lie meadows with routes from the town out to each habitat; the meadows belong to
 * the "hub" zone together with the town, and Normal Pokémon wander them.
 *
 * Each habitat is a rounded area (an ellipse with a wobbly natural border) with a centre and size stored in the
 * sanctuary's plan. The plan is made from how many residents live in each habitat (busy habitats are bigger), and
 * only changes when the owner presses Reshape; a habitat that wakes up later is fitted into a free meadow.
 *
 * Habitats keep the ids of the terrain they're built with ("grass" is the forest, "rock" the cave and mountain…).
 */
object ZoneLayout {
    /** The town and the meadows around the habitats. */
    const val HUB = "normal"

    /** Every terrain the builder knows (some aren't habitats of their own any more, but their builders remain). */
    val BASE_RING = listOf(
        "water", "ground", "rock", "fighting", "fire", "dragon", "dark", "ghost",
        "poison", "bug", "grass", "fairy", "psychic", "steel", "electric", "ice",
    )

    /** The habitats, in the order they sit around the town (so natural neighbours end up side by side). */
    val HABITATS = listOf("water", "ice", "rock", "ground", "fire", "dragon", "ghost", "poison", "grass", "fairy", "electric")

    /** Which habitat each type lives in. */
    private val HOME_OF = mapOf(
        "water" to "water", "ice" to "ice",
        "rock" to "rock", "fighting" to "rock", "steel" to "rock",
        "ground" to "ground", "fire" to "fire", "dragon" to "dragon",
        "ghost" to "ghost", "dark" to "ghost",
        "poison" to "poison",
        "grass" to "grass", "bug" to "grass",
        "fairy" to "fairy", "psychic" to "fairy",
        "electric" to "electric",
        "normal" to HUB,
    )

    /** What each habitat is called. */
    val NAMES = mapOf(
        HUB to "Town & meadows",
        "water" to "Lake & coast", "ice" to "Snowy peak", "rock" to "Cave & mountain", "ground" to "Desert",
        "fire" to "Volcano", "dragon" to "Dragon's den", "ghost" to "Haunted tower", "poison" to "Swamp",
        "grass" to "Forest", "fairy" to "Flower garden", "electric" to "Power plant",
    )

    /** The types living in each habitat, for the Region page. */
    fun typesOf(zone: String): List<String> = HOME_OF.filterValues { it == zone }.keys.toList()

    /** The habitat a type lives in (null for Flying, which roams the skies, and for types other mods add). */
    fun habitatOf(type: String): String? = HOME_OF[type.lowercase()]

    /** Types other mods added (not one of the 18). */
    fun customTypes(): List<String> = runCatching {
        ElementalTypes.all().map { it.name.lowercase() }.filter { it !in BASE_RING && it != HUB && it != TypeLogic.FLYING }
    }.getOrDefault(emptyList())

    /** Every habitat there can be. */
    fun ring(): List<String> = HABITATS

    /** The habitats this sanctuary has on its map right now. */
    fun ring(s: Sanctuary): List<String> = HABITATS.filter { it in s.habitats }

    // ------------------------------------------------------------------ the plan

    /** A habitat on the map: its centre, how far it reaches out from the town (radial) and sideways (along). */
    class Habitat(val x: Int, val z: Int, val radial: Double, val along: Double, val angle: Double) {
        fun size(): Double = sqrt(radial * along)
    }

    private fun inner(s: Sanctuary) = s.size.hubRadius + 4.0
    private fun outer(s: Sanctuary) = s.size.radius - 8.0

    /** Lays out the habitats for [counts] (residents per habitat): busier habitats get more room. */
    fun plan(s: Sanctuary, counts: Map<String, Int>): Map<String, Habitat> {
        val zones = HABITATS.filter { (counts[it] ?: 0) > 0 }
        if (zones.isEmpty()) return emptyMap()
        val random = java.util.Random(s.seed xor (s.layoutSalt * 0x9E3779B97F4A7C15uL.toLong()) xor 0x5A17L)
        val h = inner(s)
        val r = outer(s)
        val band = r - h
        // the habitats fill the land from the town out to the wall, each in its own slice (busier ones get wider
        // slices), leaving meadows with the routes between them
        val weights = zones.map { Math.pow((counts[it] ?: 1).toDouble(), 0.55) }
        val total = weights.sum()
        val minShare = min(0.06, 1.0 / zones.size)
        val shares = weights.map { max(it / total, minShare) }.let { sh -> val t = sh.sum(); sh.map { it / t } }
        var angle = random.nextDouble() * 2 * PI
        val out = LinkedHashMap<String, Habitat>()
        val most = shares.max()
        for ((i, zone) in zones.withIndex()) {
            val slice = shares[i] * 2 * PI
            val theta = angle + slice / 2 + (random.nextDouble() - 0.5) * slice * 0.15
            angle += slice
            val radial = (band / 2 * (0.84 + 0.1 * shares[i] / most)).coerceAtLeast(22.0)
            val d = h + band / 2 + (random.nextDouble() - 0.5) * max(0.0, band - 2 * radial)
            val chord = d * sin(min(slice / 2, PI / 2)) * 1.1 + 6
            val along = chord.coerceIn(radial * 0.6, radial * 1.6)
            out[zone] = Habitat((s.center.x + sin(theta) * d).toInt(), (s.center.z - cos(theta) * d).toInt(), radial, along, theta)
        }
        return out
    }

    /**
     * Fits a habitat that just woke up into the largest free meadow, without moving the others. Returns null if
     * there's no room left (its Pokémon live in the meadows until the next Reshape).
     */
    fun fitIn(s: Sanctuary, zone: String, count: Int): Habitat? {
        val h = inner(s)
        val r = outer(s)
        var best: Habitat? = null
        var bestRoom = 0.0
        for (step in 0 until 96) {
            val theta = 2 * PI * step / 96
            for (k in 1..4) {
                val d = h + (r - h) * k / 5.0
                val x = s.center.x + sin(theta) * d
                val z = s.center.z - cos(theta) * d
                var room = min(d - h, r - d)
                for (o in s.habitats.values) {
                    val dist = sqrt((x - o.x) * (x - o.x) + (z - o.z) * (z - o.z))
                    room = min(room, dist - o.size() * 0.95)
                }
                if (room > bestRoom) {
                    bestRoom = room
                    best = Habitat(x.toInt(), z.toInt(), room, room, theta)
                }
            }
        }
        val b = best ?: return null
        val want = sqrt(PI * (r * r - h * h) * 0.74 / HABITATS.size * Math.pow(count.coerceAtLeast(1).toDouble(), 0.3) / PI)
        val size = min(bestRoom * 0.95, want)
        if (size < 26) return null
        return Habitat(b.x, b.z, size, size, b.angle)
    }

    // ------------------------------------------------------------------ where things are

    data class Hit(
        val zone: String,
        val dist: Double,
        /** Distance in blocks to the nearest edge of this zone (towards [neighbour]). */
        val edgeDistance: Double,
        /** The zone on the other side of the nearest edge (null if there is none nearby). */
        val neighbour: String?,
    )

    fun hubRadiusAt(s: Sanctuary, x: Int, z: Int): Double =
        s.size.hubRadius + (Noise.value(s.seed + 303, x / 18.0, z / 18.0) - 0.5) * 9.0

    /** How far (x, z) is into a habitat: below 1 inside, 1 on its border; and the habitat's size that way. */
    private fun measure(s: Sanctuary, hb: Habitat, x: Double, z: Double): Pair<Double, Double> {
        val dx = x - hb.x
        val dz = z - hb.z
        val ux = sin(hb.angle)
        val uz = -cos(hb.angle)
        val rad = dx * ux + dz * uz
        val side = dx * uz - dz * ux
        val q = sqrt((rad / hb.radial) * (rad / hb.radial) + (side / hb.along) * (side / hb.along))
        val len = sqrt(dx * dx + dz * dz)
        val scale = if (q > 1e-6) len / q else min(hb.radial, hb.along)
        return q to scale
    }

    /** Where does the column at (x, z) belong? Null if outside the sanctuary's current size. */
    fun locate(s: Sanctuary, x: Int, z: Int, ring: List<String> = HABITATS): Hit? {
        val dx = (x - s.center.x).toDouble()
        val dz = (z - s.center.z).toDouble()
        val dist = sqrt(dx * dx + dz * dz)
        if (dist > s.size.radius) return null
        if (s.habitats.isEmpty() || dist <= hubRadiusAt(s, x, z) - 2) return Hit(HUB, dist, 99.0, null)
        // natural, wobbly borders: bend the point with smooth noise before measuring
        val wx = warpX(s, x, z)
        val wz = warpZ(s, x, z)
        var bestZone: String? = null
        var bestQ = Double.MAX_VALUE
        var bestScale = 1.0
        var nextZone: String? = null
        var nextQ = Double.MAX_VALUE
        var nextScale = 1.0
        for ((zone, hb) in s.habitats) {
            val (q, scale) = measure(s, hb, wx, wz)
            if (q < bestQ) {
                nextZone = bestZone; nextQ = bestQ; nextScale = bestScale
                bestZone = zone; bestQ = q; bestScale = scale
            } else if (q < nextQ) {
                nextZone = zone; nextQ = q; nextScale = scale
            }
        }
        val zone = bestZone ?: return Hit(HUB, dist, 99.0, null)
        // habitats never reach into the town
        val town = dist - hubRadiusAt(s, x, z)
        if (bestQ <= 1.0 && town > 0) {
            val toMeadow = (1.0 - bestQ) * bestScale
            val toNext = if (nextZone != null) (nextQ - bestQ) * (bestScale + nextScale) / 4.0 else Double.MAX_VALUE
            val toTown = town
            return when {
                toNext < toMeadow && toNext <= toTown -> Hit(zone, dist, toNext, nextZone)
                toTown < toMeadow -> Hit(zone, dist, toTown, HUB)
                else -> Hit(zone, dist, toMeadow, HUB)
            }
        }
        // the meadows: the nearest habitat is the neighbour
        return Hit(HUB, dist, max(0.0, (bestQ - 1.0) * bestScale), zone)
    }

    private fun warpX(s: Sanctuary, x: Int, z: Int) =
        x + (Noise.value(s.seed + 101, x / 44.0, z / 44.0) - 0.5) * 30.0 + (Noise.value(s.seed + 202, x / 13.0, z / 13.0) - 0.5) * 7.0
    private fun warpZ(s: Sanctuary, x: Int, z: Int) =
        z + (Noise.value(s.seed + 111, x / 44.0, z / 44.0) - 0.5) * 30.0 + (Noise.value(s.seed + 212, x / 13.0, z / 13.0) - 0.5) * 7.0

    /**
     * Where (x, z) sits in a habitat's own shape, for its terrain: [q] is 0 in the middle and 1 on its (wobbly) border,
     * [dist] the straight distance from its middle, [minR] its smaller radius, [size] its overall size and [angle] the
     * direction from its middle.
     */
    class Local(val q: Double, val dist: Double, val minR: Double, val size: Double, val angle: Double)

    fun local(s: Sanctuary, zone: String, x: Int, z: Int): Local? {
        val hb = s.habitats[zone] ?: return null
        val (q, _) = measure(s, hb, warpX(s, x, z), warpZ(s, x, z))
        val dx = (x - hb.x).toDouble()
        val dz = (z - hb.z).toDouble()
        return Local(q, sqrt(dx * dx + dz * dz), min(hb.radial, hb.along), hb.size(), kotlin.math.atan2(dz, dx))
    }

    // ------------------------------------------------------------------ pockets

    /**
     * A little spot of one habitat inside another area: a pond in the forest, a rocky outcrop out in the meadow, a
     * patch of flowers, a snowy crag on the mountain… Pokémon of the pocket's habitat can live there, so walking
     * through the region you meet more kinds of Pokémon than just the ones of the habitat you're in.
     */
    class Pocket(val x: Int, val z: Int, val r: Int, val zone: String, val host: String, val salt: Int) {
        private var levelKey = Int.MIN_VALUE
        private var cached = 0
        /** The ground level the pocket is built at (where the host's ground is at its middle). */
        fun level(s: Sanctuary): Int {
            val key = s.unlocked.hashCode()
            if (key != levelKey) { cached = BiomeDresser.baseHeight(s, x, z); levelKey = key }
            return cached
        }
        /** Built only while both its habitat and the ground around it are awake. */
        fun live(s: Sanctuary) = zone in s.unlocked && (host == HUB || host in s.unlocked)
    }

    /** Which pockets each area can have. */
    private val POCKETS_IN: Map<String, List<String>> = mapOf(
        HUB to listOf("water", "rock", "fairy", "grass", "electric", "ghost", "poison", "ground", "water", "grass"),
        "grass" to listOf("water", "fairy", "poison", "ghost", "rock", "electric"),
        "fairy" to listOf("water", "grass", "electric", "poison"),
        "water" to listOf("grass", "rock", "fairy", "electric"),
        "rock" to listOf("ice", "ground", "fire", "water", "dragon", "electric"),
        "ground" to listOf("water", "rock", "fire", "dragon"),
        "fire" to listOf("rock", "ground", "dragon"),
        "ice" to listOf("rock", "dragon", "water"),
        "dragon" to listOf("rock", "fire", "ice", "water"),
        "ghost" to listOf("poison", "grass", "rock", "water"),
        "poison" to listOf("water", "ghost", "grass", "fairy"),
        "electric" to listOf("rock", "ground", "water", "grass"),
    )

    /** Mountains only get pockets on their lower slopes. */
    private val STEEP = setOf("ice", "fire", "rock", "dragon")

    private class PocketCache(val key: Long, val all: List<Pocket>, val grid: Map<Long, List<Pocket>>)
    private val pocketCache = HashMap<java.util.UUID, PocketCache>()
    private const val POCKET_CELL = 32

    private fun pocketKey(s: Sanctuary): Long {
        var k = s.layoutSalt.toLong() * 1_000_003L + s.size.radius
        for ((zone, hb) in s.habitats) k = k * 31 + zone.hashCode() * 7L + hb.x * 131L + hb.z
        return k
    }

    /** All the pockets of the region (worked out from the map, so the same every time). */
    fun pockets(s: Sanctuary): List<Pocket> = pocketCache(s).all

    private fun pocketCache(s: Sanctuary): PocketCache {
        val key = pocketKey(s)
        pocketCache[s.id]?.takeIf { it.key == key }?.let { return it }
        val list = ArrayList<Pocket>()
        if (s.planned && s.habitats.isNotEmpty()) {
            val random = java.util.Random(s.seed xor (s.layoutSalt * 0x2545F4914F6CDD1DL) xor 0x9067L)
            val cell = 44
            val n = s.size.radius / cell + 1
            for (gx in -n..n) for (gz in -n..n) {
                val x = s.center.x + gx * cell + random.nextInt(cell * 3 / 4) - cell * 3 / 8
                val z = s.center.z + gz * cell + random.nextInt(cell * 3 / 4) - cell * 3 / 8
                val pick = random.nextInt(1000)
                val r = 6 + random.nextInt(5)
                val dist = distance(x, z, s.center.x, s.center.z)
                if (dist < s.size.hubRadius + 16 || dist > s.size.radius - 18 - r) continue
                val hit = locate(s, x, z) ?: continue
                if (hit.edgeDistance < r + 6) continue
                val host = hit.zone
                if (host != HUB) {
                    val l = local(s, host, x, z) ?: continue
                    if (l.dist < 22) continue
                }
                // keep the routes clear (they run roughly from the town to each habitat's middle)
                if (nearRouteLine(s, x, z, 24.0 + r)) continue
                // never overlapping another pocket (each spot must belong to one pocket only)
                if (list.any { distance(it.x, it.z, x, z) < it.r + r + 14 }) continue
                // on fairly level dry land: not in a lake, not on a slope it would have to be cut into
                // (measured as if every habitat were awake, so the pockets don't change when one wakes up)
                val heights = (0 until 8).map { k ->
                    val a = k * PI / 4
                    BiomeDresser.baseHeight(s, (x + cos(a) * (r + 3)).toInt(), (z + sin(a) * (r + 3)).toInt(), asIfAwake = true)
                } + BiomeDresser.baseHeight(s, x, z, asIfAwake = true)
                if (heights.min() < BiomeDresser.WATER_Y + 1 || heights.max() - heights.min() > 4) continue
                val options = POCKETS_IN[host] ?: continue
                val zone = options[pick % options.size]
                list += Pocket(x, z, r, zone, host, pick)
            }
        }
        val grid = HashMap<Long, MutableList<Pocket>>()
        for (p in list) {
            val reach = p.r + 6
            for (cx in Math.floorDiv(p.x - reach, POCKET_CELL)..Math.floorDiv(p.x + reach, POCKET_CELL))
                for (cz in Math.floorDiv(p.z - reach, POCKET_CELL)..Math.floorDiv(p.z + reach, POCKET_CELL))
                    grid.getOrPut(cx.toLong() * 1_000_003L + cz) { ArrayList() } += p
        }
        return PocketCache(key, list, grid).also { pocketCache[s.id] = it }
    }

    private fun nearRouteLine(s: Sanctuary, x: Int, z: Int, within: Double): Boolean {
        val px = (x - s.center.x).toDouble()
        val pz = (z - s.center.z).toDouble()
        for (hb in s.habitats.values) {
            val ax = (hb.x - s.center.x).toDouble()
            val az = (hb.z - s.center.z).toDouble()
            val len2 = ax * ax + az * az
            if (len2 < 1) continue
            val t = ((px * ax + pz * az) / len2).coerceIn(0.0, 1.0)
            val dx = px - ax * t
            val dz = pz - az * t
            if (dx * dx + dz * dz < within * within) return true
        }
        return false
    }

    /**
     * The pocket at (x, z), if any, and how far into it the point is (with a wobbly edge): below [Pocket.r] inside,
     * up to r + 5 in the band where it melts into the ground around it.
     */
    fun pocketAt(s: Sanctuary, x: Int, z: Int): Pair<Pocket, Double>? {
        val list = pocketCache(s).grid[Math.floorDiv(x, POCKET_CELL).toLong() * 1_000_003L + Math.floorDiv(z, POCKET_CELL)] ?: return null
        for (p in list) {
            val d = distance(x, z, p.x, p.z)
            if (d > p.r + 7) continue
            val wobble = 1 + (Noise.value(s.seed + 4040 + p.salt, x / 5.0, z / 5.0) - 0.5) * 0.4
            val dn = d * wobble
            if (dn <= p.r + 5) return p to dn
        }
        return null
    }

    /** A random spot in one of the living pockets of [zone] (null if it has none). */
    fun pocketPoint(s: Sanctuary, zone: String, random: RandomSource, near: Pair<Int, Int>? = null): Pair<Pocket, Pair<Int, Int>>? {
        val live = pockets(s).filter { it.zone == zone && it.live(s) }
        if (live.isEmpty()) return null
        val p = if (near != null) live.minBy { distance(it.x, it.z, near.first, near.second) + random.nextInt(60) } else live[random.nextInt(live.size)]
        val ang = random.nextDouble() * 2 * PI
        val d = sqrt(random.nextDouble()) * (p.r - 1)
        return p to ((p.x + cos(ang) * d).toInt() to (p.z + sin(ang) * d).toInt())
    }

    /** A random spot near the edge of [zone] where it meets another habitat or the meadows (null if none was found). */
    fun borderPoint(s: Sanctuary, zone: String, random: RandomSource): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestEdge = Double.MAX_VALUE
        repeat(8) {
            val pt = randomPoint(s, zone, random) ?: return@repeat
            val hit = locate(s, pt.first, pt.second) ?: return@repeat
            if (hit.zone == zone && hit.edgeDistance < bestEdge) { best = pt; bestEdge = hit.edgeDistance }
        }
        return best
    }

    /** Do two habitats touch? */
    fun areNeighbours(s: Sanctuary, a: String, b: String): Boolean {
        if (a == HUB || b == HUB) return true
        val ha = s.habitats[a] ?: return false
        val hb = s.habitats[b] ?: return false
        val d = sqrt(((ha.x - hb.x) * (ha.x - hb.x) + (ha.z - hb.z) * (ha.z - hb.z)).toDouble())
        return d < (ha.size() + hb.size()) * 1.15
    }

    /** How far out a zone reaches from the centre. */
    fun extent(s: Sanctuary, zone: String): Double {
        if (zone == HUB) return s.size.hubRadius.toDouble()
        val hb = s.habitats[zone] ?: return s.size.hubRadius.toDouble()
        val d = sqrt(((hb.x - s.center.x) * (hb.x - s.center.x) + (hb.z - s.center.z) * (hb.z - s.center.z)).toDouble())
        return d + hb.radial
    }

    /** The middle of a habitat (where its landmark stands). */
    fun anchor(s: Sanctuary, zone: String, ring: List<String> = HABITATS): Pair<Int, Int> {
        val hb = s.habitats[zone] ?: return s.center.x to s.center.z
        return hb.x to hb.z
    }

    /** A random spot inside a zone, spread evenly over its area, or null if none was found. */
    fun randomPoint(s: Sanctuary, zone: String, random: RandomSource, ring: List<String> = HABITATS, front: Boolean = true): Pair<Int, Int>? {
        if (zone == HUB) {
            // half in the town, half out in the meadows along the routes
            if (s.habitats.isEmpty() || random.nextBoolean()) {
                val ang = random.nextDouble() * 2 * PI
                val outerR = (s.size.hubRadius - 6).coerceAtLeast(3).toDouble()
                val d = sqrt(9.0 + random.nextDouble() * (outerR * outerR - 9.0))
                return (s.center.x + sin(ang) * d).toInt() to (s.center.z - cos(ang) * d).toInt()
            }
            repeat(12) {
                val ang = random.nextDouble() * 2 * PI
                val r = outer(s)
                val h = s.size.hubRadius.toDouble()
                val d = sqrt(h * h + random.nextDouble() * (r * r - h * h))
                val x = (s.center.x + sin(ang) * d).toInt()
                val z = (s.center.z - cos(ang) * d).toInt()
                if (locate(s, x, z)?.zone == HUB) return x to z
            }
            return null
        }
        val hb = s.habitats[zone] ?: return null
        // in mountain habitats residents mostly keep to the low ground at the front, where you can see them
        if (front && zone in STEEP && random.nextInt(10) < 7) {
            val a = randomPoint(s, zone, random, ring, front = false) ?: return null
            val b = randomPoint(s, zone, random, ring, front = false) ?: return a
            fun backOf(p: Pair<Int, Int>) = (p.first - hb.x) * sin(hb.angle) - (p.second - hb.z) * cos(hb.angle)
            return if (backOf(a) < backOf(b)) a else b
        }
        repeat(12) {
            val ang = random.nextDouble() * 2 * PI
            val k = sqrt(random.nextDouble()) * 0.95
            val ux = sin(hb.angle)
            val uz = -cos(hb.angle)
            val rad = cos(ang) * hb.radial * k
            val side = sin(ang) * hb.along * k
            val x = (hb.x + rad * ux + side * uz).toInt()
            val z = (hb.z + rad * uz - side * ux).toInt()
            val hit = locate(s, x, z)
            if (hit?.zone == zone && hit.dist < s.size.radius - 4) return x to z
        }
        return null
    }

    /** Distance between two points. */
    fun distance(ax: Int, az: Int, bx: Int, bz: Int): Double = sqrt(((ax - bx) * (ax - bx) + (az - bz) * (az - bz)).toDouble())
}
