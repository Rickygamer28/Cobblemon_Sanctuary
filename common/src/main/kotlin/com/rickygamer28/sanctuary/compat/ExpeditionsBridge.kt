package com.rickygamer28.sanctuary.compat

import com.cobblemonexpeditions.CobblemonExpeditions
import com.cobblemonexpeditions.cobblemon.PokemonSnapshot
import com.cobblemonexpeditions.data.ExpeditionRegistry
import com.cobblemonexpeditions.expedition.ExpeditionOutcome
import com.cobblemonexpeditions.player.RewardBundle
import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.AwayTrip
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.life.Effects
import com.rickygamer28.sanctuary.life.Gathering
import com.rickygamer28.sanctuary.life.LiveResident
import com.rickygamer28.sanctuary.life.Requests
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.SanctuaryRuntime
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Everything that uses Cobblemon Expeditions' classes (only loaded when it's installed). */
internal object ExpeditionsBridge {
    /** Residents just back: they come out at the town's Core first, where their friends welcome them. */
    private class Homecoming(val trip: AwayTrip, val outcome: String?, val until: Long)
    private val homecomings = HashMap<UUID, HashMap<UUID, Homecoming>>()

    private fun name(id: ResourceLocation): String =
        ExpeditionRegistry.getExpedition(id)?.display?.name?.takeIf { it.isNotBlank() }
            ?: id.path.split('_', '-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun pokeName(s: Sanctuary, rt: SanctuaryRuntime, id: UUID): String =
        (rt.live[id]?.pokemon ?: Residents.findRoster(rt, id)?.pokemon ?: rt.away.firstOrNull { it.pokemon.uuid == id }?.pokemon)
            ?.getDisplayName(false)?.string ?: "A resident"

    private fun list(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private fun homeOf(s: Sanctuary, rt: SanctuaryRuntime, id: UUID): String? {
        val pk = Residents.findRoster(rt, id)?.pokemon ?: rt.live[id]?.pokemon ?: return null
        return TypeLogic.homeZone(pk, s.residents[id], ZoneLayout.ring(s), s)
    }

    fun homeBonus(snapshot: Any, preferred: List<*>): Double {
        val snap = snapshot as? PokemonSnapshot ?: return 1.0
        val server = CobblemonSanctuary.server ?: return 1.0
        if (!server.isSameThread) return 1.0
        val types = preferred.mapNotNull { (it as? String)?.lowercase() }.toSet()
        for (s in SanctuaryData.get(server).sanctuaries.values) {
            val rt = Residents.runtime(s)
            if (Residents.findRoster(rt, snap.uuid) == null && !rt.live.containsKey(snap.uuid)) continue
            val home = homeOf(s, rt, snap.uuid) ?: return 1.0
            return if (home.lowercase() in types) 1.0 + SanctuaryConfig.expeditionHomeBonus.get() / 100.0 else 1.0
        }
        return 1.0
    }

    fun minutesLeft(s: Sanctuary, id: UUID): Long {
        val trip = s.away[id] ?: return -1
        val mgr = CobblemonExpeditions.manager ?: return -1
        val inst = mgr.get(trip.owner)?.getActiveExpeditions()?.firstOrNull { it.instanceId == trip.instance } ?: return -1
        return (inst.remainingMillis + 59_999) / 60_000
    }

    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>) {
        welcome(level, s, rt, now)
        if ((now / 20L) % 2L != 0L) return
        val mgr = CobblemonExpeditions.manager ?: return
        // off on a new expedition
        for (member in s.allMembers()) {
            val pd = mgr.get(member) ?: continue
            for (inst in pd.getActiveExpeditions()) {
                val team = inst.pokemonUuids.filter { it !in s.away && (Residents.findRoster(rt, it) != null || rt.live.containsKey(it)) }
                if (team.isEmpty()) continue
                val name = name(inst.expeditionId)
                val def = ExpeditionRegistry.getExpedition(inst.expeditionId)
                val suited = def?.preferredTypes?.map { it.lowercase() }?.toSet().orEmpty()
                val names = team.map { pokeName(s, rt, it) }
                val homeGround = team.filter { homeOf(s, rt, it)?.lowercase() in suited }.map { pokeName(s, rt, it) }
                sendOff(level, s, rt, now, players, team, name)
                for (id in team) s.away[id] = AwayTrip(inst.instanceId, inst.expeditionId.toString(), name, member, now)
                s.logEvent("${list(names)} set off on $name.")
                val extra = if (homeGround.isNotEmpty()) " ${list(homeGround)} ${if (homeGround.size == 1) "knows" else "know"} that kind of place from home (+${SanctuaryConfig.expeditionHomeBonus.get()}%)." else ""
                tell(players, "✈ ${list(names)} set off on $name! Their friends came to see them off.$extra", ChatFormatting.AQUA)
                rt.rosterDirty = true
                com.rickygamer28.sanctuary.Sanctuaries.data(level).setDirty()
            }
        }
        // back home (only judged when the trainer's expedition data is loaded: offline trainers' trips wait)
        val back = s.away.entries.filter { (id, trip) ->
            val pd = mgr.get(trip.owner) ?: return@filter false
            pd.getActiveExpeditions().none { it.instanceId == trip.instance && id in it.pokemonUuids }
        }.map { it.key to it.value }
        for ((instance, group) in back.groupBy { it.second.instance }) {
            val trip = group.first().second
            val ids = group.map { it.first }
            ids.forEach { s.away.remove(it) }
            val bundle: RewardBundle? = mgr.get(trip.owner)?.getRewardInbox()?.firstOrNull { it.sourceInstanceId == instance }
            homecoming(level, s, rt, now, players, ids, trip, bundle)
            if (bundle != null) loot(s, trip, bundle)
            rt.rosterDirty = true
            com.rickygamer28.sanctuary.Sanctuaries.data(level).setDirty()
        }
    }

    private fun tell(players: List<ServerPlayer>, text: String, c: ChatFormatting) =
        players.forEach { it.sendSystemMessage(Component.literal(text).withStyle(c)) }

    /** Its friends come over to see it off before it goes. */
    private fun sendOff(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, team: List<UUID>, name: String) {
        for (id in team) {
            val r = rt.live[id] ?: continue
            r.busyUntil = now + 20L * 12 // stays a moment for its send-off, then heads off
            r.waypoints.clear()
            Effects.happy(r.entity)
            friendsOf(s, rt, id, now).take(4).forEach { f ->
                Residents.walkTo(f.entity, r.entity.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2), 0.6f, 2)
                f.busyUntil = now + 20L * 12
            }
        }
    }

    private fun friendsOf(s: Sanctuary, rt: SanctuaryRuntime, id: UUID, now: Long): List<LiveResident> {
        val me = rt.live[id] ?: return emptyList()
        return rt.live.values.filter { it.pokemonId != id && !it.leaving && now >= it.busyUntil && s.relation(id, it.pokemonId) >= 10 && it.entity.distanceToSqr(me.entity) < 64.0 * 64.0 }
            .sortedByDescending { s.relation(id, it.pokemonId) }
    }

    private fun outcomeWords(o: ExpeditionOutcome?): String = when (o) {
        ExpeditionOutcome.GREAT -> "a great success"
        ExpeditionOutcome.SUCCESS -> "a success"
        ExpeditionOutcome.PARTIAL -> "a partial success"
        ExpeditionOutcome.FAIL -> "a tough trip, but they're safe"
        null -> "home early"
    }

    private fun homecoming(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long, players: List<ServerPlayer>, ids: List<UUID>, trip: AwayTrip, bundle: RewardBundle?) {
        val outcome = bundle?.outcome
        val names = ids.map { pokeName(s, rt, it) }
        // the team grew closer on the road, and each one has another trip behind it
        for (a in ids) for (b in ids) if (a < b) s.addRelation(a, b, if (outcome == ExpeditionOutcome.FAIL) 4 else 8)
        val explorers = ArrayList<String>()
        for (id in ids) {
            val rd = s.resident(id)
            rd.expeditions++
            if (rd.expeditions == EXPLORER) explorers += pokeName(s, rt, id)
        }
        // a good trip warms the whole valley
        val pts = when (outcome) { ExpeditionOutcome.GREAT -> 4; ExpeditionOutcome.SUCCESS -> 2; ExpeditionOutcome.PARTIAL -> 1; else -> 0 }
        if (pts > 0) Requests.setHeart(level, s, s.heart + pts)
        val story = "${list(names)} came back from ${trip.name}: ${outcomeWords(outcome)}!" + if (pts > 0) " (+$pts Valley Heart)" else ""
        s.logEvent(story)
        tell(players, "✈ $story", if (outcome == ExpeditionOutcome.FAIL) ChatFormatting.YELLOW else ChatFormatting.GOLD)
        if (explorers.isNotEmpty()) {
            s.logEvent("${list(explorers)} earned the Explorer ribbon ($EXPLORER expeditions).")
            tell(players, "★ ${list(explorers)} earned the Explorer ribbon!", ChatFormatting.LIGHT_PURPLE)
        }
        val map = homecomings.getOrPut(s.id) { HashMap() }
        for (id in ids) map[id] = Homecoming(trip, outcome?.name, now + 20L * 60)
    }

    /** Back from the trip: it appears at the town's Core, and its friends come to welcome it. */
    private fun welcome(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val map = homecomings[s.id] ?: return
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val (id, h) = it.next()
            if (now > h.until) { it.remove(); continue }
            val r = rt.live[id] ?: continue
            it.remove()
            val gate = Residents.surface(level, s.center.x + level.random.nextInt(9) - 4, s.center.z + 6 + level.random.nextInt(4))
            r.entity.teleportTo(gate.x + 0.5, gate.y.toDouble(), gate.z + 0.5)
            r.busyUntil = now + 20L * 15
            r.waypoints.clear()
            Effects.hearts(r.entity)
            Effects.play(r.entity, Effects.CRY)
            friendsOf(s, rt, id, now).filter { it.entity.distanceToSqr(r.entity) < 160.0 * 160.0 }.take(5).forEach { f ->
                Residents.walkTo(f.entity, gate.offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2), 0.7f, 2)
                f.busyUntil = now + 20L * 15
            }
        }
    }

    /** With the setting on, the loot goes straight into the Gathering Basket (what doesn't fit stays to claim). */
    private fun loot(s: Sanctuary, trip: AwayTrip, bundle: RewardBundle) {
        if (!SanctuaryConfig.expeditionLootToBasket.get() || s.basketPos == null) return
        var moved = 0
        for (stack in bundle.items) {
            if (stack.isEmpty) continue
            val n = Gathering.give(s, stack)
            if (n > 0) { stack.shrink(n); moved += n }
        }
        if (moved > 0) {
            CobblemonExpeditions.manager?.let { m -> m.get(trip.owner)?.let { pd -> runCatching { m.flush(pd) } } }
            s.logEvent("The team put $moved items from ${trip.name} in the Gathering Basket.")
        }
    }

    const val EXPLORER = 5
}
