package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The caves under the mountains. Each mountain habitat (the snowy peak, the Cave & mountain range, the dragons'
 * crags and the volcano) has its own part: a hall deep inside it and wide tunnels running in from its foot. Next
 * to each other around the town, their halls are joined by long tunnels that dip deep under the borders, so the
 * whole range is one cave system. Everything is sized for the biggest Pokémon (Onix, Steelix, Wailord on land…):
 * tunnels are 11 blocks wide and 11 tall.
 *
 * Valleys planned before 1.9.1 keep their single cave under the Cave & mountain range until a Reshape.
 *
 * The caves are worked out from the region map (the same every time). The mountain is always made thick enough over
 * them ([cover]), and each column is hollowed out while it's dressed ([at]).
 */
object Caves {
    const val ZONE = "rock"

    /** The mountain habitats, in the order they sit around the town (each joined to the next). */
    val MOUNTAINS = listOf("ice", "rock", "dragon", "fire")

    /** What each part of the caves is called. */
    val HALL_NAMES = mapOf("rock" to "The great cave hall", "ice" to "The ice cavern", "dragon" to "The crystal grotto", "fire" to "The magma chamber")

    /** Tunnels: half of the width, and the height in the middle. */
    private const val TUNNEL_HALF = 5.5
    private const val TUNNEL_HIGH = 11.0
    /** Rock left over the cave at least. */
    private const val ROOF = 4
    /** How far the joining tunnels dip under the borders (their middle is this far below the halls' floor). */
    private const val LINK_DEPTH = 20.0

    class Tunnel(val points: List<Pair<Double, Double>>, val zone: String, val link: Link? = null) {
        val length: Double = points.zipWithNext { a, b -> dist(a.first, a.second, b.first, b.second) }.sum()
        val minX = points.minOf { it.first } - 24
        val maxX = points.maxOf { it.first } + 24
        val minZ = points.minOf { it.second } - 24
        val maxZ = points.maxOf { it.second } + 24
    }

    /** A tunnel joining the halls of two neighbouring mountains. */
    class Link(val a: String, val b: String, val ra: Double, val rb: Double)

    class Section(val zone: String, val hallX: Double, val hallZ: Double, val hallR: Double, val hallHigh: Double, val tunnels: List<Tunnel>)

    class Network(
        val key: Long,
        val sections: List<Section>,
        val links: List<Tunnel>,
        /** The cave floor (absolute y). */
        val floor: Int,
        val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int,
    ) {
        fun section(zone: String) = sections.firstOrNull { it.zone == zone }
    }

    /**
     * Where a column is in the cave: its floor and ceiling (absolute y; air from floor to ceil), whether it's a mouth
     * open to the sky, and which mountain's part of the caves it's in.
     */
    class Spot(val floor: Int, val ceil: Int, val mouth: Boolean, val inHall: Boolean, val centre: Double, val zone: String = ZONE, val link: Boolean = false)

    private val cache = HashMap<UUID, Network?>()
    private val keys = HashMap<UUID, Long>()

    private fun dist(ax: Double, az: Double, bx: Double, bz: Double): Double { val dx = ax - bx; val dz = az - bz; return sqrt(dx * dx + dz * dz) }

    /** The cave network, or null while no mountain habitat has a place on the map. */
    fun network(s: Sanctuary): Network? {
        var key = s.layoutSalt * 1_000_003L + s.size.radius + s.caveStyle * 7_919L
        for (z in MOUNTAINS) s.habitats[z]?.let { hb -> key = key * 31 + z.hashCode() + hb.x * 131L + hb.z * 7L }
        if (keys[s.id] == key) return cache[s.id]
        keys[s.id] = key
        val zones = if (s.caveStyle >= 2) MOUNTAINS.filter { it in s.habitats } else listOf(ZONE).filter { it in s.habitats }
        val net = if (zones.isEmpty()) null else build(s, key, zones)
        cache[s.id] = net
        return net
    }

    private fun build(s: Sanctuary, key: Long, zones: List<String>): Network {
        val floor = BiomeDresser.WATER_Y + 2
        val sections = zones.map { section(s, it) }
        // the joining tunnels: each mountain to the next one around the town (if they're near enough)
        val links = ArrayList<Tunnel>()
        if (s.caveStyle >= 2) for (i in 0 until MOUNTAINS.size - 1) {
            val a = sections.firstOrNull { it.zone == MOUNTAINS[i] } ?: continue
            val b = sections.firstOrNull { it.zone == MOUNTAINS[i + 1] } ?: continue
            if (dist(a.hallX, a.hallZ, b.hallX, b.hallZ) > s.size.radius * 1.4) continue
            links += linkTunnel(s, a, b)
        }
        val all = sections.flatMap { it.tunnels } + links
        val xs = all.flatMap { listOf(it.minX, it.maxX) } + sections.flatMap { listOf(it.hallX - it.hallR - 30, it.hallX + it.hallR + 30) }
        val zs = all.flatMap { listOf(it.minZ, it.maxZ) } + sections.flatMap { listOf(it.hallZ - it.hallR - 30, it.hallZ + it.hallR + 30) }
        return Network(key, sections, links, floor, xs.min().toInt(), xs.max().toInt(), zs.min().toInt(), zs.max().toInt())
    }

    /** One mountain's part: its hall, and the tunnels in from the foot of the mountain. */
    private fun section(s: Sanctuary, zone: String): Section {
        val hb = s.habitats[zone]!!
        val ux = sin(hb.angle)
        val uz = -cos(hb.angle)
        // across the habitat
        val vx = -uz
        val vz = ux
        val size = hb.size()
        // (the Cave & mountain range's part is worked out exactly as it always was)
        val random = if (zone == ZONE) java.util.Random(s.seed xor 0x6A7E5L xor (s.layoutSalt * 0x9E3779B97F4A7C15uL.toLong()))
            else java.util.Random(s.seed xor (zone.hashCode() * 0x5DEECE66DL) xor (s.layoutSalt * 0x9E3779B97F4A7C15uL.toLong()))
        val hallR: Double
        val back: Double
        val count: Int
        when (zone) {
            ZONE -> {
                hallR = (size * 0.17).coerceIn(13.0, 24.0)
                back = (hb.radial * 0.45).coerceAtMost(hb.radial - hallR - 10)
                count = if (size > 90) 3 else 2
            }
            else -> {
                hallR = (size * 0.12).coerceIn(10.0, 16.0)
                // under the snowy summit and the crags at the back; in the volcano, in front of its crater
                back = when (zone) { "fire" -> hb.radial * 0.05; "ice" -> hb.radial * 0.3; else -> hb.radial * 0.35 }.coerceAtMost(hb.radial - hallR - 10)
                count = if (size > 100) 2 else 1
            }
        }
        val hallHigh = if (zone == ZONE) (hallR * 0.75).coerceIn(16.0, 20.0) else (hallR * 0.8).coerceIn(10.0, 14.0)
        val hallX = hb.x + ux * back
        val hallZ = hb.z + uz * back
        val tunnels = ArrayList<Tunnel>()
        for (k in 0 until count) {
            val side = if (count == 1) 0.0 else if (count == 2) (if (k == 0) -1.0 else 1.0) else (k - 1).toDouble()
            val across: Double
            val front: Double
            if (zone == ZONE) {
                across = if (side == 0.0) 0.0 else side * (hb.along * 0.42).coerceAtLeast(22.0)
                // the middle tunnel starts further back, behind the arch
                front = if (side == 0.0) hb.radial * 0.12 else -hb.radial * 0.02
            } else {
                across = if (count == 1) (if (random.nextBoolean()) 1 else -1) * hb.along * 0.18 else side * (hb.along * 0.3).coerceAtLeast(18.0)
                // the volcano's are low on its slopes, towards the town; the others at the foot of their slopes
                front = when (zone) { "fire" -> -hb.radial * 0.45; "ice" -> -hb.radial * 0.3; else -> -hb.radial * 0.25 }
            }
            val ex = hb.x + ux * front + vx * across
            val ez = hb.z + uz * front + vz * across
            // bend on the way in
            val bend = (random.nextDouble() - 0.5) * 24
            val pts = ArrayList<Pair<Double, Double>>()
            val n = 16
            for (i in 0..n) {
                val t = i / n.toDouble()
                val wob = sin(t * PI) * bend + sin(t * PI * 3 + k) * 3.0
                pts += (ex + (hallX - ex) * t + vx * wob) to (ez + (hallZ - ez) * t + vz * wob)
            }
            tunnels += Tunnel(pts, zone)
        }
        return Section(zone, hallX, hallZ, hallR, hallHigh, tunnels)
    }

    /** A tunnel from one hall to the next, following the ring of mountains (not cutting in towards the town). */
    private fun linkTunnel(s: Sanctuary, a: Section, b: Section): Tunnel {
        val cx = s.center.x.toDouble()
        val cz = s.center.z.toDouble()
        val aa = atan2(a.hallZ - cz, a.hallX - cx)
        var ab = atan2(b.hallZ - cz, b.hallX - cx)
        // the short way round
        while (ab - aa > PI) ab -= 2 * PI
        while (ab - aa < -PI) ab += 2 * PI
        val da = dist(a.hallX, a.hallZ, cx, cz)
        val db = dist(b.hallX, b.hallZ, cx, cz)
        val random = java.util.Random(s.seed xor (a.zone.hashCode() * 31L + b.zone.hashCode()) xor s.layoutSalt.toLong())
        val wobA = random.nextDouble() * 6
        val pts = ArrayList<Pair<Double, Double>>()
        val n = 40
        for (i in 0..n) {
            val t = i / n.toDouble()
            val ang = aa + (ab - aa) * t
            // a little further out in the middle (under the high back of the range), and winding a bit
            val d = da + (db - da) * t + sin(t * PI) * 8 + sin(t * PI * 4 + wobA) * 4
            pts += (cx + cos(ang) * d) to (cz + sin(ang) * d)
        }
        return Tunnel(pts, a.zone, Link(a.zone, b.zone, a.hallR, b.hallR))
    }

    /** Nearest point of a tunnel: distance to it, and how far along it is (0 at the mouth, 1 at the hall). */
    private fun nearest(t: Tunnel, x: Double, z: Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        var walked = 0.0
        for (i in 0 until t.points.size - 1) {
            val (ax, az) = t.points[i]
            val (bx, bz) = t.points[i + 1]
            val dx = bx - ax
            val dz = bz - az
            val len2 = dx * dx + dz * dz
            val u = if (len2 < 1e-9) 0.0 else (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0.0, 1.0)
            val d = dist(x, z, ax + dx * u, az + dz * u)
            val seg = sqrt(len2)
            if (d < best) { best = d; bestAlong = (walked + seg * u) / t.length }
            walked += seg
        }
        return best to bestAlong
    }

    /** How far a joining tunnel's floor is below the halls' at [along] (0 at its ends, [LINK_DEPTH] in the middle). */
    private fun linkDip(t: Tunnel, along: Double): Double {
        val l = t.link ?: return 0.0
        val fromA = along * t.length - l.ra
        val fromB = (1 - along) * t.length - l.rb
        // a long, even slope down (about one block in two) and up again
        return LINK_DEPTH * BiomeDresser.smoothstep((min(fromA, fromB) - 2) / 40.0)
    }

    private fun sectionLive(s: Sanctuary, zone: String) = zone in s.unlocked
    private fun linkLive(s: Sanctuary, t: Tunnel) = t.link!!.let { it.a in s.unlocked && it.b in s.unlocked }

    private fun live(s: Sanctuary) = com.rickygamer28.sanctuary.SanctuaryConfig.caves.get() && (if (s.caveStyle >= 2) MOUNTAINS.any { it in s.unlocked } else ZONE in s.unlocked)

    /** Wobble of the walls, so they aren't perfectly smooth. */
    private fun wobble(s: Sanctuary, x: Int, z: Int) = (Noise.value(s.seed + 6161, x / 6.0, z / 6.0) - 0.5) * 2.0

    /** The cave at column (x, z), or null where there's solid rock (or no cave). */
    fun at(s: Sanctuary, x: Int, z: Int): Spot? {
        if (!live(s)) return null
        val net = network(s) ?: return null
        if (x < net.minX || x > net.maxX || z < net.minZ || z > net.maxZ) return null
        val xd = x.toDouble()
        val zd = z.toDouble()
        val w = wobble(s, x, z)
        val baseFloor = net.floor + (Noise.value(s.seed + 6262, x / 9.0, z / 9.0) * 1.6).toInt()
        var floor = Int.MAX_VALUE
        var ceil = Int.MIN_VALUE
        var centre = 1.0
        var inHall = false
        var zone: String? = null
        var link = false
        var mouth = false
        // the halls: domes
        for (sec in net.sections) {
            if (!sectionLive(s, sec.zone)) continue
            val dh = dist(xd, zd, sec.hallX, sec.hallZ)
            val hallEdge = sec.hallR + w * 2.5
            if (dh >= hallEdge) continue
            val f = 1 - (dh / hallEdge) * (dh / hallEdge)
            val c = baseFloor + (6 + (sec.hallHigh - 6) * sqrt(max(0.0, f))).toInt()
            if (c > ceil) { ceil = c; zone = sec.zone }
            floor = min(floor, baseFloor)
            centre = min(centre, dh / hallEdge)
            inHall = true
        }
        // the tunnels: arched passages
        for (sec in net.sections) {
            if (!sectionLive(s, sec.zone)) continue
            for (t in sec.tunnels) {
                if (xd < t.minX || xd > t.maxX || zd < t.minZ || zd > t.maxZ) continue
                val (d, along) = nearest(t, xd, zd)
                val half = TUNNEL_HALF + w * 0.8
                if (d >= half) continue
                val k = d / half
                val c = baseFloor + (4 + (TUNNEL_HIGH - 4) * sqrt(max(0.0, 1 - k * k))).toInt()
                // (as older versions worked it out, so their caves keep their look)
                if (c > ceil) { ceil = c; centre = min(centre, k); if (zone == null || !inHall) zone = sec.zone }
                floor = min(floor, baseFloor)
                if (along < 0.14) mouth = true
            }
        }
        for (t in net.links) {
            if (!linkLive(s, t)) continue
            if (xd < t.minX || xd > t.maxX || zd < t.minZ || zd > t.maxZ) continue
            val (d, along) = nearest(t, xd, zd)
            val half = TUNNEL_HALF + w * 0.8
            if (d >= half) continue
            val k = d / half
            val f = baseFloor - linkDip(t, along).toInt()
            val c = f + (4 + (TUNNEL_HIGH - 4) * sqrt(max(0.0, 1 - k * k))).toInt()
            if (c > ceil) ceil = c
            if (f < floor) floor = f
            if (zone == null) { zone = if (along < 0.5) t.link!!.a else t.link!!.b; link = true }
            centre = min(centre, k)
        }
        if (ceil == Int.MIN_VALUE || zone == null) return null
        return Spot(floor, ceil, mouth && !inHall, inHall, centre, zone, link && !inHall)
    }

    /**
     * How high (above the flat ground) the mountain must be at (x, z) to cover the cave with rock: a ridge over each
     * tunnel and a dome over each hall, with sloping shoulders. Near the mouths it drops away, so the tunnels open
     * from a cliff at the foot of the mountain; where the joining tunnels run deep, plain ground covers them.
     */
    fun cover(s: Sanctuary, x: Int, z: Int): Double {
        if (!live(s)) return 0.0
        val net = network(s) ?: return 0.0
        if (x < net.minX || x > net.maxX || z < net.minZ || z > net.maxZ) return 0.0
        val xd = x.toDouble()
        val zd = z.toDouble()
        val base = (net.floor - SanctuaryDimension.GROUND_Y).toDouble()
        var need = 0.0
        for (sec in net.sections) {
            if (!sectionLive(s, sec.zone)) continue
            val dh = dist(xd, zd, sec.hallX, sec.hallZ)
            need = max(need, (base + sec.hallHigh + ROOF + 3) * BiomeDresser.smoothstep((sec.hallR + 22 - dh) / 22.0))
            for (t in sec.tunnels) {
                if (xd < t.minX || xd > t.maxX || zd < t.minZ || zd > t.maxZ) continue
                val (d, along) = nearest(t, xd, zd)
                val mouthRamp = BiomeDresser.smoothstep((along - 0.03) / 0.13)
                need = max(need, (base + TUNNEL_HIGH + ROOF + 2) * BiomeDresser.smoothstep((TUNNEL_HALF + 16 - d) / 14.0) * mouthRamp)
            }
        }
        for (t in net.links) {
            if (!linkLive(s, t)) continue
            if (xd < t.minX || xd > t.maxX || zd < t.minZ || zd > t.maxZ) continue
            val (d, along) = nearest(t, xd, zd)
            val h = base - linkDip(t, along) + TUNNEL_HIGH + ROOF + 2
            if (h > 0) need = max(need, h * BiomeDresser.smoothstep((TUNNEL_HALF + 16 - d) / 14.0))
        }
        return need
    }

    /**
     * How much (x, z) belongs to the flat apron in front of a cave mouth (1 right at it, 0 away from it).
     * [only]: just the mouths of that mountain's part ([ZONE] for the Cave & mountain range, whose terrain has the
     * apron built in), or with [except], all but that one's.
     */
    fun apron(s: Sanctuary, x: Int, z: Int, only: String? = ZONE, except: String? = null): Double {
        if (!live(s)) return 0.0
        val net = network(s) ?: return 0.0
        var w = 0.0
        for (sec in net.sections) {
            if (only != null && sec.zone != only) continue
            if (except != null && sec.zone == except) continue
            if (!sectionLive(s, sec.zone)) continue
            for (t in sec.tunnels) {
                val (mx, mz) = t.points.first()
                w = max(w, BiomeDresser.smoothstep((16 - dist(x.toDouble(), z.toDouble(), mx, mz)) / 8.0))
            }
        }
        return w
    }

    /** The cave floor's ground level above the flat ground (where the apron settles). */
    fun floorOffset(s: Sanctuary): Double = ((network(s)?.floor ?: SanctuaryDimension.GROUND_Y + 1) - 1 - SanctuaryDimension.GROUND_Y).toDouble()

    /** Does [zone] have its own part of the caves (and is it open)? */
    fun hasSection(s: Sanctuary, zone: String): Boolean = live(s) && sectionLive(s, zone) && network(s)?.section(zone) != null

    /** The cave entrances of the open parts: (zone, x, z). */
    fun entrances(s: Sanctuary): List<Triple<String, Double, Double>> {
        if (!live(s)) return emptyList()
        val net = network(s) ?: return emptyList()
        return net.sections.filter { sectionLive(s, it.zone) }.flatMap { sec -> sec.tunnels.map { t -> Triple(sec.zone, t.points.first().first, t.points.first().second) } }
    }

    /** The halls of the open parts. */
    fun halls(s: Sanctuary): List<Section> {
        if (!live(s)) return emptyList()
        return network(s)?.sections?.filter { sectionLive(s, it.zone) } ?: emptyList()
    }

    /**
     * A random spot on the cave floor (for residents living in the caves), or null. With [zone], in that
     * mountain's part of the caves (its hall and tunnels); otherwise anywhere, the joining tunnels too.
     */
    fun randomFloor(s: Sanctuary, random: RandomSource, zone: String? = null): BlockPos? {
        val net = network(s) ?: return null
        val sections = net.sections.filter { sectionLive(s, it.zone) && (zone == null || it.zone == zone) }
        if (sections.isEmpty()) return null
        val links = if (zone == null) net.links.filter { linkLive(s, it) } else emptyList()
        repeat(10) {
            val (x, z) = if (links.isNotEmpty() && random.nextInt(4) == 0) {
                val t = links[random.nextInt(links.size)]
                val p = t.points[4 + random.nextInt(t.points.size - 8)]
                p.first.toInt() to p.second.toInt()
            } else {
                val sec = sections[random.nextInt(sections.size)]
                if (random.nextInt(3) > 0) {
                    val a = random.nextDouble() * 2 * PI
                    val d = sqrt(random.nextDouble()) * (sec.hallR - 3)
                    (sec.hallX + cos(a) * d).toInt() to (sec.hallZ + sin(a) * d).toInt()
                } else {
                    val t = sec.tunnels[random.nextInt(sec.tunnels.size)]
                    val p = t.points[2 + random.nextInt(t.points.size - 3)]
                    p.first.toInt() to p.second.toInt()
                }
            }
            val spot = at(s, x, z) ?: return@repeat
            if (spot.mouth) return@repeat
            return BlockPos(x, spot.floor, z)
        }
        return null
    }

    /** Is [pos] inside the cave (under its ceiling)? */
    fun inside(s: Sanctuary, pos: BlockPos): Boolean {
        val spot = at(s, pos.x, pos.z) ?: return false
        return pos.y in spot.floor - 1..spot.ceil
    }

    fun forget(id: UUID) { cache.remove(id); keys.remove(id) }
}
