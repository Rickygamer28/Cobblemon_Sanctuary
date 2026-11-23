package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.BiomeDresser
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Night events: now and then on a night someone's in the sanctuary, a meteor shower crosses the sky (residents
 * gather in the town to watch) or an aurora shimmers over the snowy peak (residents gather there).
 */
object NightEvents {
    private class Event(val kind: String, val until: Long)
    private val running = HashMap<java.util.UUID, Event>()
    private val lastNight = HashMap<java.util.UUID, Long>()

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        val t = level.dayTime % 24000L
        val night = t in 13500L..22500L
        val e = running[s.id]
        if (e != null) {
            if (now > e.until || !night) { running.remove(s.id); return }
            show(level, s, e.kind, players)
            return
        }
        if (!night || players.isEmpty()) return
        val day = level.dayTime / 24000L
        if (lastNight[s.id] == day) return
        if ((now / 20L + (s.id.hashCode() and 63)) % 60L != 0L) return
        lastNight[s.id] = day
        if (level.random.nextInt(3) != 0) return
        val aurora = "ice" in s.unlocked && level.random.nextBoolean()
        val kind = if (aurora) "aurora" else "meteors"
        running[s.id] = Event(kind, now + 20L * 150)
        val where = if (aurora) "ice" else ZoneLayout.HUB
        val crowd = rt.live.values.filter { !it.leaving && !it.cave && !it.napping && it.followParents == null && now >= it.busyUntil }.shuffled().take(12)
        Migrations.gather(level, s, crowd, where, now, 20L * 150)
        val text = if (aurora) "An aurora is shimmering over the ${Residents.zoneName("ice")}! Residents are gathering to watch." else "A meteor shower is crossing the sky! Residents are gathering in the town to watch."
        s.logEvent(text)
        for (p in players) p.sendSystemMessage(Component.literal("✦ $text").withStyle(ChatFormatting.LIGHT_PURPLE))
    }

    private fun show(level: ServerLevel, s: Sanctuary, kind: String, players: List<ServerPlayer>) {
        val r = level.random
        if (kind == "meteors") for (p in players) repeat(2) {
            // a streak high up, falling across the sky
            val x = p.x + (r.nextDouble() - 0.5) * 120
            val z = p.z + (r.nextDouble() - 0.5) * 120
            val y = p.y + 50 + r.nextDouble() * 20
            for (i in 0 until 6) level.sendParticles(p, ParticleTypes.END_ROD, true, x - i * 1.2, y + i * 0.8, z + i * 0.4, 0, -1.2, -0.8, -0.4, 0.6)
            if (r.nextInt(3) == 0) level.sendParticles(p, ParticleTypes.FIREWORK, true, x, y, z, 0, -1.0, -0.7, -0.3, 0.7)
        }
        else {
            val peak = BiomeDresser.peakOf(s, "ice") ?: return
            val colours = listOf(Vector3f(0.3f, 1f, 0.6f), Vector3f(0.3f, 0.8f, 1f), Vector3f(0.7f, 0.4f, 1f))
            val sky = 140.0
            for (p in players) repeat(40) {
                // curtains of light that slowly ripple
                val a = r.nextDouble() * Math.PI * 2
                val d = 30 + r.nextDouble() * 50
                val x = peak.first + cos(a) * d
                val z = peak.second + sin(a) * d * 0.4
                val y = sky + sin(a * 3 + level.gameTime / 40.0) * 8 + r.nextDouble() * 18
                level.sendParticles(p, DustParticleOptions(colours[r.nextInt(colours.size)], 3.5f), true, x, y, z, 1, 0.0, 2.0, 0.0, 0.0)
            }
        }
    }
}
