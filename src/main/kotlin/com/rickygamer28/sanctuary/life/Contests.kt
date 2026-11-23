package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Move contests at the town's plaza: now and then four residents gather, take turns showing off a move while the
 * others watch, and the crowd picks a winner, who gets a ribbon (shown on its page).
 */
object Contests {
    private class Contest(val ids: List<UUID>, val start: Long)
    private val running = HashMap<UUID, Contest>()

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        val c = running[s.id]
        if (c != null) { run(level, s, rt, now, c, players); return }
        if (players.isEmpty() || (now / 20L + (s.id.hashCode() and 127)) % 120L != 0L || level.random.nextInt(12) != 0) return
        val near = players.any { it.distanceToSqr(s.center.x + 0.5, it.y, s.center.z + 0.5) < (s.size.hubRadius + 40.0).let { r -> r * r } }
        if (!near) return
        val group = rt.live.values.filter { !it.leaving && !it.cave && !it.napping && !it.flyer && it.followParents == null && now >= it.busyUntil }.shuffled().take(4)
        if (group.size < 3) return
        running[s.id] = Contest(group.map { it.pokemonId }, now)
        for ((i, r) in group.withIndex()) {
            val a = i * Math.PI * 2 / group.size
            val x = s.center.x + (cos(a) * 4).toInt()
            val z = s.center.z + (sin(a) * 4).toInt()
            r.busyUntil = now + 20L * 90
            r.waypoints.clear()
            Residents.walkTo(r.entity, Residents.surface(level, x, z), 0.6f, 1)
        }
        val text = "A move contest is starting at the town's plaza!"
        s.logEvent(text)
        for (p in players) p.displayClientMessage(Component.literal("★ $text").withStyle(ChatFormatting.GOLD), false)
    }

    private fun run(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, c: Contest, players: List<ServerPlayer>) {
        val t = (now - c.start) / 20L
        val group = c.ids.mapNotNull { rt.live[it] }
        if (group.size < 2) { running.remove(s.id); return }
        if (t in 25..70 && t % 6L == 1L) {
            // one performs, the rest watch
            val r = group[((t - 25) / 6 % group.size).toInt()]
            Effects.play(r.entity, Effects.SHOW_OFF)
            level.sendParticles(ParticleTypes.FIREWORK, r.entity.x, r.entity.y + r.entity.bbHeight + 0.4, r.entity.z, 12, 0.3, 0.3, 0.3, 0.05)
            for (o in group) if (o !== r) o.entity.lookControl.setLookAt(r.entity)
        }
        if (t >= 75) {
            running.remove(s.id)
            val winner = group.maxByOrNull { it.pokemon.level + it.pokemon.friendship / 10 + level.random.nextInt(30) } ?: return
            val data = s.resident(winner.pokemonId)
            data.ribbons++
            Effects.hearts(winner.entity); Effects.happy(winner.entity); Effects.play(winner.entity, Effects.CRY)
            for (o in group) if (o !== winner) Effects.happy(o.entity)
            val name = winner.pokemon.getDisplayName(false).string
            val text = "$name won the move contest! (${data.ribbons} ribbon${if (data.ribbons == 1) "" else "s"})"
            s.logEvent(text)
            for (p in players) p.displayClientMessage(Component.literal("★ $text").withStyle(ChatFormatting.GOLD), false)
            for (r in group) r.busyUntil = now + 40
            Sanctuaries.data(level).setDirty()
        }
    }
}
