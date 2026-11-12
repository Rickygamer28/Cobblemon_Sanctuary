package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuarySize
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/** Draws the outlines shown before a sanctuary is confirmed (and while expanding is possible). */
object Preview {
    private val MAX_DUST = DustParticleOptions(Vector3f(0.55f, 0.75f, 1.0f), 1.6f)

    fun draw(level: ServerLevel, s: Sanctuary, players: List<ServerPlayer>) {
        if (players.isEmpty()) return
        ring(level, s, s.size.radius, players, chosen = true)
        val max = maxAllowed()
        if (max.radius > s.size.radius) ring(level, s, max.radius, players, chosen = false)
        ring(level, s, s.size.hubRadius, players, chosen = false)
    }

    fun maxAllowed(): SanctuarySize = SanctuarySize.entries.lastOrNull { Sizes.allowed(it) } ?: SanctuarySize.SMALL

    private fun ring(level: ServerLevel, s: Sanctuary, radius: Int, players: List<ServerPlayer>, chosen: Boolean) {
        val steps = (radius * 2 * Math.PI / 3).toInt().coerceAtLeast(16)
        for (i in 0 until steps) {
            val a = i * 2 * Math.PI / steps
            val x = s.center.x + 0.5 + sin(a) * radius
            val z = s.center.z + 0.5 - cos(a) * radius
            for (p in players) {
                val dx = p.x - x
                val dz = p.z - z
                if (dx * dx + dz * dz > 80.0 * 80.0) continue
                if (!level.hasChunk(x.toInt() shr 4, z.toInt() shr 4)) continue
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()) + 1.2
                if (chosen) level.sendParticles(p, ParticleTypes.END_ROD, true, x, y, z, 1, 0.0, 0.3, 0.0, 0.0)
                else level.sendParticles(p, MAX_DUST, true, x, y, z, 1, 0.0, 0.2, 0.0, 0.0)
            }
        }
    }
}
