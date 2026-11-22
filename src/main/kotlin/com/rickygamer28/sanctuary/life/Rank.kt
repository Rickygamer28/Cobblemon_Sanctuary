package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TownBuildings
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * The sanctuary's rank, from a Small Grove to a Legendary Sanctuary: a score from its residents, how many kinds of
 * Pokémon live there, the Valley Heart, requests done, evolutions and the town's buildings.
 */
object Rank {
    class Tier(val score: Int, val name: String)

    val TIERS = listOf(
        Tier(0, "Small Grove"), Tier(150, "Cozy Haven"), Tier(400, "Thriving Sanctuary"),
        Tier(900, "Renowned Sanctuary"), Tier(1800, "Famous Sanctuary"), Tier(3200, "Legendary Sanctuary"),
    )

    /** The score and what it's made of. */
    fun parts(s: Sanctuary, rt: SanctuaryRuntime): List<Pair<String, Int>> {
        val roster = rt.roster
        val species = roster.map { it.pokemon.species.name }.toSet().size
        val buildings = s.landmarks.keys.count { TownBuildings.isBuilding(it) }
        return listOf(
            "Residents (${roster.size})" to roster.size,
            "Kinds of Pokémon (${species} × 3)" to species * 3,
            "Valley Heart level (${Requests.level(s)} × 25)" to Requests.level(s) * 25,
            "Requests done (${s.stats["requests"] ?: 0} × 2)" to (s.stats["requests"] ?: 0) * 2,
            "Evolutions (${s.stats["evolutions"] ?: 0} × 5)" to (s.stats["evolutions"] ?: 0) * 5,
            "Town buildings ($buildings × 10)" to buildings * 10,
        )
    }

    fun score(s: Sanctuary, rt: SanctuaryRuntime) = parts(s, rt).sumOf { it.second }

    fun tier(score: Int): Int = TIERS.indexOfLast { score >= it.score }.coerceAtLeast(0)

    /** Called every second: once a minute, announces a new rank. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if ((now / 20L + (s.id.hashCode() and 63)) % 60L != 0L || rt.roster.isEmpty()) return
        val t = tier(score(s, rt))
        val was = s.stats["rank"] ?: -1
        if (t <= was) { if (was < 0) s.stats["rank"] = t; return }
        s.stats["rank"] = t
        if (was < 0) return
        val name = TIERS[t].name
        s.logEvent("Your sanctuary is now a $name!")
        for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.sendSystemMessage(
            Component.literal("★ ${s.ownerName}'s sanctuary is now a ").withStyle(ChatFormatting.GOLD).append(Component.literal(name).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)).append(Component.literal("!").withStyle(ChatFormatting.GOLD)))
        Sanctuaries.data(level).setDirty()
    }
}
