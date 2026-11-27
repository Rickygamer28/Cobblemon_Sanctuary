package com.rickygamer28.sanctuary.client

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.SanctuaryDimension
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.client.ParticleStatus
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.sin

/**
 * Each habitat's own air, on the player's screen only: snow drifting down on the peak, ash and embers over the
 * volcano, a sandy haze (now and then a proper sandstorm) in the desert, fireflies in the swamp and the forest at
 * night, petals over the flower garden, morning mist on the lake, wisps around the haunted tower, sparks at the
 * power plant. Nothing real happens: it's only particles, and the sanctuary never has weather.
 */
object Atmosphere {
    /** Every client tick (after it). */
    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (mc.isPaused || level.dimension() != SanctuaryDimension.KEY) return
        if (!runCatching { SanctuaryConfig.atmosphere.get() }.getOrDefault(true)) return
        val amount = when (mc.options.particles().get()) { ParticleStatus.MINIMAL -> return; ParticleStatus.DECREASED -> 0.5; else -> 1.0 }
        val here = player.blockPosition()
        val zone = level.getBiome(here).unwrapKey().orElse(null)?.location()?.takeIf { it.namespace == CobblemonSanctuary.MOD_ID }?.path ?: return
        val r = level.random
        val time = level.dayTime % 24000L
        val night = time in 13000L..23000L
        val dusk = time in 11500L..13500L || time in 22500L..24000L
        val dawn = time >= 22800L || time < 1500L
        val t = level.gameTime.toDouble()
        fun n(base: Double) = count(r, base * amount)
        val px = player.x
        val py = player.y
        val pz = player.z
        when (zone) {
            "ice" -> {
                // steady snowfall, with flurries now and then
                val gust = 0.6 + 0.6 * sin(t / 1300.0)
                repeat(n(4.0 * gust)) { sky(level, r, px, py, pz, 18.0, 4.0, 14.0)?.let { p -> level.addParticle(ParticleTypes.SNOWFLAKE, p[0], p[1], p[2], 0.03 * gust, -0.05, 0.01) } }
            }
            "fire" -> {
                repeat(n(3.0)) { around(level, r, px, py, pz, 14.0, -1.0, 10.0)?.let { p -> level.addParticle(ParticleTypes.ASH, p[0], p[1], p[2], 0.0, 0.0, 0.0) } }
                // embers rising from the ground
                repeat(n(0.4)) { ground(level, r, px, pz, 10.0)?.let { p -> level.addParticle(ParticleTypes.SMALL_FLAME, p[0], p[1] + 0.2, p[2], (r.nextDouble() - 0.5) * 0.01, 0.04 + r.nextDouble() * 0.03, (r.nextDouble() - 0.5) * 0.01) } }
                if (r.nextInt(30) == 0) ground(level, r, px, pz, 12.0)?.let { p -> level.addParticle(ParticleTypes.LAVA, p[0], p[1] + 0.1, p[2], 0.0, 0.0, 0.0) }
            }
            "ground" -> {
                // a light haze of sand, and a sandstorm for a while every so often (by day)
                val storm = !night && sin(t / 3100.0) > 0.75
                val base = if (storm) 10.0 else if (!night) 0.8 else 0.0
                repeat(n(base)) { sky(level, r, px, py, pz, 16.0, -1.0, 6.0)?.let { p -> dust(level, p, 0.18, 0.0) } }
            }
            "poison" -> if (night || dusk) repeat(n(0.6)) { firefly(level, r, px, py, pz) }
            "grass", "bug" -> {
                if (night || dusk) repeat(n(0.35)) { firefly(level, r, px, py, pz) }
                else repeat(n(0.3)) { around(level, r, px, py, pz, 12.0, 0.0, 6.0)?.let { p -> level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, p[0], p[1], p[2], 0.0, 0.0, 0.0) } }
            }
            "fairy" -> repeat(n(1.5)) { sky(level, r, px, py, pz, 14.0, 3.0, 10.0)?.let { p -> level.addParticle(ParticleTypes.CHERRY_LEAVES, p[0], p[1], p[2], 0.0, 0.0, 0.0) } }
            "water" -> {
                if (dawn) repeat(n(1.2)) { water(level, r, px, pz, 16.0)?.let { p -> level.addParticle(ParticleTypes.CLOUD, p[0], p[1] + 0.4, p[2], 0.01, 0.0, 0.0) } }
                // a fish jumping
                if (r.nextInt(50) == 0) water(level, r, px, pz, 14.0)?.let { p -> repeat(8) { level.addParticle(ParticleTypes.SPLASH, p[0] + r.nextGaussian() * 0.2, p[1] + 0.1, p[2] + r.nextGaussian() * 0.2, 0.0, 0.1, 0.0) } }
            }
            "ghost" -> if (night || dusk) repeat(n(0.4)) { around(level, r, px, py, pz, 12.0, -1.0, 4.0)?.let { p -> level.addParticle(ParticleTypes.SOUL, p[0], p[1], p[2], 0.0, 0.02, 0.0) } }
            "dragon" -> {
                repeat(n(0.25)) { around(level, r, px, py, pz, 14.0, 0.0, 8.0)?.let { p -> level.addParticle(ParticleTypes.DRAGON_BREATH, p[0], p[1], p[2], 0.0, 0.005, 0.0) } }
                if (night) repeat(n(0.2)) { around(level, r, px, py, pz, 14.0, 1.0, 10.0)?.let { p -> level.addParticle(ParticleTypes.END_ROD, p[0], p[1], p[2], 0.0, -0.005, 0.0) } }
            }
            "electric" -> repeat(n(if (night) 0.8 else 0.4)) { around(level, r, px, py, pz, 12.0, -1.0, 6.0)?.let { p ->
                repeat(3) { level.addParticle(ParticleTypes.ELECTRIC_SPARK, p[0], p[1], p[2], r.nextGaussian() * 0.1, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1) }
            } }
            "rock" -> repeat(n(0.3)) { around(level, r, px, py, pz, 12.0, 0.0, 6.0)?.let { p -> level.addParticle(ParticleTypes.WHITE_ASH, p[0], p[1], p[2], 0.0, 0.0, 0.0) } }
            "meadow", "normal" -> if (night) repeat(n(0.12)) { firefly(level, r, px, py, pz) }
        }
    }

    /** A whole number of particles for this tick, from an average (0.3 = one about every third tick). */
    private fun count(r: RandomSource, avg: Double): Int {
        if (avg <= 0) return 0
        val whole = avg.toInt()
        return whole + if (r.nextDouble() < avg - whole) 1 else 0
    }

    /** A spot in the open air around the player (under the sky). */
    private fun sky(level: ClientLevel, r: RandomSource, px: Double, py: Double, pz: Double, w: Double, lo: Double, hi: Double): DoubleArray? {
        val p = around(level, r, px, py, pz, w, lo, hi) ?: return null
        return if (level.canSeeSky(BlockPos.containing(p[0], p[1], p[2]))) p else null
    }

    /** A spot of air around the player. */
    private fun around(level: ClientLevel, r: RandomSource, px: Double, py: Double, pz: Double, w: Double, lo: Double, hi: Double): DoubleArray? {
        val x = px + (r.nextDouble() * 2 - 1) * w
        val y = py + lo + r.nextDouble() * (hi - lo)
        val z = pz + (r.nextDouble() * 2 - 1) * w
        return if (level.getBlockState(BlockPos.containing(x, y, z)).isAir) doubleArrayOf(x, y, z) else null
    }

    /** A spot on the open ground near the player. */
    private fun ground(level: ClientLevel, r: RandomSource, px: Double, pz: Double, w: Double): DoubleArray? {
        val x = px + (r.nextDouble() * 2 - 1) * w
        val z = pz + (r.nextDouble() * 2 - 1) * w
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(x, 0.0, z).x, BlockPos.containing(x, 0.0, z).z)
        return doubleArrayOf(x, y.toDouble(), z)
    }

    /** A spot on open water near the player. */
    private fun water(level: ClientLevel, r: RandomSource, px: Double, pz: Double, w: Double): DoubleArray? {
        val p = ground(level, r, px, pz, w) ?: return null
        val below = BlockPos.containing(p[0], p[1] - 1, p[2])
        return if (level.getFluidState(below).`is`(FluidTags.WATER)) p else null
    }

    private fun firefly(level: ClientLevel, r: RandomSource, px: Double, py: Double, pz: Double) {
        around(level, r, px, py, pz, 12.0, -1.0, 3.5)?.let { p ->
            level.addParticle(ParticleTypes.GLOW, p[0], p[1], p[2], (r.nextDouble() - 0.5) * 0.02, 0.005, (r.nextDouble() - 0.5) * 0.02)
        }
    }

    private fun dust(level: ClientLevel, p: DoubleArray, vx: Double, vz: Double) {
        val sand: ParticleOptions = net.minecraft.core.particles.DustParticleOptions(org.joml.Vector3f(0.86f, 0.76f, 0.55f), 1.4f)
        level.addParticle(sand, p[0], p[1], p[2], vx, 0.0, vz)
    }
}
