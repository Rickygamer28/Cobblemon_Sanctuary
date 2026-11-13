package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Life goes on while nobody is in the sanctuary: every minute some residents meet (mostly neighbours and old
 * friends), friendships and rivalries grow, and close friends can lay eggs. The next visitor gets a summary.
 */
object AwayLife {
    private class Circle(val homes: Map<UUID, String?>, val byZone: Map<String?, List<RosterEntry>>, val friends: Map<UUID, List<UUID>>, val byId: Map<UUID, RosterEntry>)

    private fun circles(s: Sanctuary, roster: List<RosterEntry>): Circle {
        val ring = ZoneLayout.ring()
        val homes = roster.associate { it.pokemon.uuid to TypeLogic.homeZone(it.pokemon, s.residents[it.pokemon.uuid], ring) }
        val byId = roster.associateBy { it.pokemon.uuid }
        val friends = HashMap<UUID, MutableList<UUID>>()
        for ((k, v) in s.relationships) {
            if (v < 10) continue
            val parts = k.split('|')
            if (parts.size != 2) continue
            val a = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: continue
            val b = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: continue
            if (a !in byId || b !in byId) continue
            friends.getOrPut(a) { ArrayList() } += b
            friends.getOrPut(b) { ArrayList() } += a
        }
        return Circle(homes, roster.groupBy { homes[it.pokemon.uuid] }, friends, byId)
    }

    /** Called every second while no player is in the sanctuary. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (!SanctuaryConfig.awayLife.get()) return
        if ((now / 20L + (s.id.hashCode() and 63)) % 60L != 0L) return // once a minute
        val roster = rt.roster
        if (roster.size < 2) return
        val random = level.random
        val count = (roster.size / 12.0 * SanctuaryConfig.awayLifeRate.get()).roundToInt().coerceIn(1, 40)
        val c = circles(s, roster)
        repeat(count) {
            val a = roster[random.nextInt(roster.size)]
            val aId = a.pokemon.uuid
            val roll = random.nextDouble()
            val b: RosterEntry? = when {
                roll < 0.5 -> c.friends[aId]?.takeIf { it.isNotEmpty() }?.let { c.byId[it[random.nextInt(it.size)]] }
                roll < 0.85 -> c.byZone[c.homes[aId]]?.takeIf { it.size > 1 }?.let { it[random.nextInt(it.size)] }
                else -> roster[random.nextInt(roster.size)]
            } ?: c.byZone[c.homes[aId]]?.takeIf { it.size > 1 }?.let { it[random.nextInt(it.size)] }
            if (b == null || b.pokemon.uuid == aId) return@repeat
            meet(level, s, a, b, c)
        }
        Sanctuaries.data(level).setDirty()
    }

    private fun meet(level: ServerLevel, s: Sanctuary, a: RosterEntry, b: RosterEntry, c: Circle) {
        val random = level.random
        val friendRate = SanctuaryConfig.friendshipRate.get()
        val rivalRate = SanctuaryConfig.rivalryRate.get()
        val ia = a.pokemon.uuid
        val ib = b.pokemon.uuid
        s.awayMeetings++
        val rel = if (s.hasRelation(ia, ib)) s.relation(ia, ib) else {
            val sa = a.pokemon.species.name.lowercase()
            val sb = b.pokemon.species.name.lowercase()
            val start = when {
                Scenes.isNaturalRival(sa, sb) -> -50
                random.nextDouble() < SanctuaryConfig.personalDislikeChance.get() -> -35
                sa == sb -> 15
                else -> 0
            }
            s.setRelation(ia, ib, start)
            start
        }
        val roll = random.nextDouble()
        fun playFight() {
            if (random.nextDouble() < 0.08) relate(s, a, b, (-6 * rivalRate).roundToInt()) else relate(s, a, b, (5 * friendRate).roundToInt())
        }
        when {
            rel <= -30 -> relate(s, a, b, (-4 * rivalRate).roundToInt())
            rel >= 40 -> when {
                roll < 0.45 -> playFight()
                roll < 0.75 -> {
                    relate(s, a, b, (4 * friendRate).roundToInt())
                    if (Families.tryEgg(level, s, Families.Mate.of(a, c.homes[ia]), Families.Mate.of(b, c.homes[ib]))) s.awayEggs++
                }
                else -> relate(s, a, b, (3 * friendRate).roundToInt())
            }
            else -> if (roll < 0.55) relate(s, a, b, (3 * friendRate).roundToInt()) else playFight()
        }
    }

    private fun relate(s: Sanctuary, a: RosterEntry, b: RosterEntry, delta: Int) {
        val ia = a.pokemon.uuid
        val ib = b.pokemon.uuid
        val before = s.relation(ia, ib)
        val after = s.addRelation(ia, ib, delta)
        val na = a.pokemon.getDisplayName(false).string
        val nb = b.pokemon.getDisplayName(false).string
        if (before < 40 && after >= 40) { s.logEvent("$na and $nb became friends!"); s.awayFriends++ }
        if (before > -30 && after <= -30) { s.logEvent("$na and $nb became rivals."); s.awayRivals++ }
    }

    /** Someone arrived: tell them what happened while they were away. */
    fun welcome(s: Sanctuary, players: List<ServerPlayer>) {
        if (s.awayMeetings == 0) return
        val parts = ArrayList<String>()
        if (s.awayFriends > 0) parts += "${s.awayFriends} new friendship" + if (s.awayFriends > 1) "s" else ""
        if (s.awayRivals > 0) parts += "${s.awayRivals} new rivalr" + if (s.awayRivals > 1) "ies" else "y"
        if (s.awayEggs > 0) parts += "${s.awayEggs} egg" + (if (s.awayEggs > 1) "s" else "") + " laid"
        val text = if (parts.isEmpty()) "While you were away, your Pokémon spent time together (${s.awayMeetings} meetings)."
            else "While you were away: " + parts.joinToString(", ") + "."
        s.logEvent(text)
        for (p in players) if (s.isMember(p.uuid)) {
            p.sendSystemMessage(Component.literal("🌿 $text").withStyle(ChatFormatting.GREEN))
            if (s.awayEggs > 0) p.sendSystemMessage(Component.literal("Open the Nest in your Journal to see the eggs.").withStyle(ChatFormatting.GRAY))
        }
        s.awayMeetings = 0; s.awayFriends = 0; s.awayRivals = 0; s.awayEggs = 0
    }
}
