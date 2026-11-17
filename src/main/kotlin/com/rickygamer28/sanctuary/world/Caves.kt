package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.util.RandomSource
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The caves of the Cave & mountain habitat: a big hall deep inside the mountain range at the back, and wide tunnels
 * running to it from the foot of the range. Everything is sized for the biggest Pokémon (Onix, Steelix, Golem…):
 * tunnels are 11 blocks wide and 11 tall, the hall is a dome 16–20 blocks high.
 *
 * The cave is worked out from the region map (the same every time). The mountain is always made thick enough over
 * it ([cover]), and each column is hollowed out while it's dressed ([at]).
 */
object Caves {
    const val ZONE = "rock"

    /** Tunnels: half of the width, and the height in the middle. */
    private const val TUNNEL_HALF = 5.5
    private const val TUNNEL_HIGH = 11.0
    /** Rock left over the cave at least. */
    private const val ROOF = 4

    class Tunnel(val points: List<Pair<Double, Double>>) {
        val length: Double = points.zipWithNext { a, b -> dist(a.first, a.second, b.first, b.second) }.sum()
    }

    class Network(
        val key: Long,
        val hallX: Double, val hallZ: Double, val hallR: Double, val hallHigh: Double,
        val tunnels: List<Tunnel>,
        /** The cave floor (absolute y). */
        val floor: Int,
        val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int,
    )

    /** Where a column is in the cave: its floor and ceiling (absolute y; air from floor to ceil), and whether it's a mouth open to the sky. */
    class Spot(val floor: Int, val ceil: Int, val mouth: Boolean, val inHall: Boolean, val centre: Double)

    private val cache = HashMap<UUID, Network?>()
    private val keys = HashMap<UUID, Long>()

    private fun dist(ax: Double, az: Double, bx: Double, bz: Double): Double { val dx = ax - bx; val dz = az - bz; return sqrt(dx * dx + dz * dz) }

    /** The cave network, or null while the Cave & mountain habitat has no place on the map. */
    fun network(s: Sanctuary): Network? {
        val hb = s.habitats[ZONE] ?: return null
        val key = s.layoutSalt * 1_000_003L + hb.x * 131L + hb.z * 7L + s.size.radius
        if (keys[s.id] == key) return cache[s.id]
        keys[s.id] = key
        val ux = sin(hb.angle)
        val uz = -cos(hb.angle)
        // across the habitat
        val vx = -uz
        val vz = ux
        val random = java.util.Random(s.seed xor 0x6A7E5L xor (s.layoutSalt * 0x9E3779B97F4A7C15uL.toLong()))
        // the hall: deep in the range, at the back
        val size = hb.size()
        val hallR = (size * 0.17).coerceIn(13.0, 24.0)
        val back = (hb.radial * 0.45).coerceAtMost(hb.radial - hallR - 10)
        val hallX = hb.x + ux * back
        val hallZ = hb.z + uz * back
        // tunnels from the foot of the range (towards the front, off to the sides of the middle, where the arch stands)
        val count = if (size > 90) 3 else 2
        val tunnels = ArrayList<Tunnel>()
        for (k in 0 until count) {
            val side = if (count == 2) (if (k == 0) -1.0 else 1.0) else (k - 1).toDouble()
            val across = if (side == 0.0) 0.0 else side * (hb.along * 0.42).coerceAtLeast(22.0)
            // the middle tunnel starts further back, behind the arch
            val front = if (side == 0.0) hb.radial * 0.12 else -hb.radial * 0.02
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
            tunnels += Tunnel(pts)
        }
        val reach = hallR + 30
        val xs = tunnels.flatMap { it.points.map { p -> p.first } } + listOf(hallX - reach, hallX + reach)
        val zs = tunnels.flatMap { it.points.map { p -> p.second } } + listOf(hallZ - reach, hallZ + reach)
        val net = Network(
            key, hallX, hallZ, hallR, (hallR * 0.75).coerceIn(16.0, 20.0), tunnels, BiomeDresser.WATER_Y + 2,
            xs.min().toInt() - 24, xs.max().toInt() + 24, zs.min().toInt() - 24, zs.max().toInt() + 24,
        )
        cache[s.id] = net
        return net
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

    private fun live(s: Sanctuary) = ZONE in s.unlocked && com.rickygamer28.sanctuary.SanctuaryConfig.caves.get()

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
        val floor = net.floor + (Noise.value(s.seed + 6262, x / 9.0, z / 9.0) * 1.6).toInt()
        // the hall: a dome
        val dh = dist(xd, zd, net.hallX, net.hallZ)
        val hallEdge = net.hallR + w * 2.5
        var ceil = Int.MIN_VALUE
        var centre = 1.0
        var inHall = false
        if (dh < hallEdge) {
            val f = 1 - (dh / hallEdge) * (dh / hallEdge)
            ceil = floor + (6 + (net.hallHigh - 6) * sqrt(max(0.0, f))).toInt()
            centre = dh / hallEdge
            inHall = true
        }
        // the tunnels: arched passages
        var mouth = false
        for (t in net.tunnels) {
            val (d, along) = nearest(t, xd, zd)
            val half = TUNNEL_HALF + w * 0.8
            if (d >= half) continue
            val k = d / half
            val c = floor + (4 + (TUNNEL_HIGH - 4) * sqrt(max(0.0, 1 - k * k))).toInt()
            if (c > ceil) { ceil = c; centre = min(centre, k) }
            if (along < 0.14) mouth = true
        }
        if (ceil == Int.MIN_VALUE) return null
        return Spot(floor, ceil, mouth && !inHall, inHall, centre)
    }

    /**
     * How high (above the flat ground) the mountain must be at (x, z) to cover the cave with rock: a ridge over each
     * tunnel and a dome over the hall, with sloping shoulders. Near the mouths it drops away, so the tunnels open
     * from a cliff at the foot of the range.
     */
    fun cover(s: Sanctuary, x: Int, z: Int): Double {
        if (!live(s)) return 0.0
        val net = network(s) ?: return 0.0
        if (x < net.minX || x > net.maxX || z < net.minZ || z > net.maxZ) return 0.0
        val xd = x.toDouble()
        val zd = z.toDouble()
        val base = (net.floor - SanctuaryDimension.GROUND_Y).toDouble()
        var need = 0.0
        val dh = dist(xd, zd, net.hallX, net.hallZ)
        need = max(need, (base + net.hallHigh + ROOF + 3) * BiomeDresser.smoothstep((net.hallR + 22 - dh) / 22.0))
        for (t in net.tunnels) {
            val (d, along) = nearest(t, xd, zd)
            val mouthRamp = BiomeDresser.smoothstep((along - 0.03) / 0.13)
            need = max(need, (base + TUNNEL_HIGH + ROOF + 2) * BiomeDresser.smoothstep((TUNNEL_HALF + 16 - d) / 14.0) * mouthRamp)
        }
        return need
    }

    /** How much (x, z) belongs to the flat apron in front of a cave mouth (1 right at it, 0 away from it). */
    fun apron(s: Sanctuary, x: Int, z: Int): Double {
        if (!live(s)) return 0.0
        val net = network(s) ?: return 0.0
        var w = 0.0
        for (t in net.tunnels) {
            val (mx, mz) = t.points.first()
            w = max(w, BiomeDresser.smoothstep((16 - dist(x.toDouble(), z.toDouble(), mx, mz)) / 8.0))
        }
        return w
    }

    /** The cave floor's ground level above the flat ground (where the apron settles). */
    fun floorOffset(s: Sanctuary): Double = ((network(s)?.floor ?: SanctuaryDimension.GROUND_Y + 1) - 1 - SanctuaryDimension.GROUND_Y).toDouble()

    /** A random spot on the cave floor (for residents living in the cave), or null. */
    fun randomFloor(s: Sanctuary, random: RandomSource): net.minecraft.core.BlockPos? {
        val net = network(s) ?: return null
        repeat(10) {
            val inHall = random.nextInt(3) > 0
            val (x, z) = if (inHall) {
                val a = random.nextDouble() * 2 * PI
                val d = sqrt(random.nextDouble()) * (net.hallR - 3)
                (net.hallX + cos(a) * d).toInt() to (net.hallZ + sin(a) * d).toInt()
            } else {
                val t = net.tunnels[random.nextInt(net.tunnels.size)]
                val p = t.points[2 + random.nextInt(t.points.size - 3)]
                p.first.toInt() to p.second.toInt()
            }
            val spot = at(s, x, z) ?: return@repeat
            if (spot.mouth) return@repeat
            return net.minecraft.core.BlockPos(x, spot.floor, z)
        }
        return null
    }

    /** Is [pos] inside the cave (under its ceiling)? */
    fun inside(s: Sanctuary, pos: net.minecraft.core.BlockPos): Boolean {
        val spot = at(s, pos.x, pos.z) ?: return false
        return pos.y in spot.floor - 1..spot.ceil
    }

    fun forget(id: UUID) { cache.remove(id); keys.remove(id) }
}
