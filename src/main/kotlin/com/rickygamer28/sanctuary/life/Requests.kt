package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.ResidentData
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * Something a resident asked for: a berry ("food"), a visit to a friend ("visit"), some time with you ("cuddle"),
 * or a decoration in its habitat ("decor": a bed, a ball or a trough).
 */
class Request(
    val id: UUID,
    val pokemon: UUID,
    val kind: String,
    /** The berry's item id, the friend's uuid, or the decoration ("bed", "toy", "trough"). */
    val arg: String,
    /** The habitat it's about (where it lives, or where its friend lives). */
    val zone: String,
    val created: Long,
    /** Names when it was asked (shown if the resident can't be found any more). */
    val name: String,
    val argName: String = "",
    /** For a decoration: how many of that kind the habitat had when it was asked. */
    val base: Int = 0,
) {
    fun save(): CompoundTag = CompoundTag().also {
        it.putUUID("Id", id); it.putUUID("Mon", pokemon); it.putString("Kind", kind); it.putString("Arg", arg)
        it.putString("Zone", zone); it.putLong("At", created); it.putString("Name", name); it.putString("ArgName", argName); it.putInt("Base", base)
    }

    companion object {
        fun load(t: CompoundTag) = Request(t.getUUID("Id"), t.getUUID("Mon"), t.getString("Kind"), t.getString("Arg"), t.getString("Zone"),
            t.getLong("At"), t.getString("Name"), t.getString("ArgName"), t.getInt("Base"))
    }
}

/**
 * Residents' requests and the Valley Heart. Now and then a resident asks for something small; doing it makes it
 * fonder of its trainer, earns it a title after a few, and adds to the Valley Heart, whose levels unlock things
 * for the whole valley (a bigger basket, decorated routes, lit landmarks…).
 */
object Requests {
    /** A Valley Heart level: how many points it takes and what it unlocks. */
    class Level(val points: Int, val title: String, val desc: String)

    val LEVELS = listOf(
        Level(3, "A Bigger Basket", "The Gathering Basket holds half as much again of each thing, and residents bring a little more."),
        Level(8, "Decorated Routes", "Benches, flower posts and signposts appear along the routes."),
        Level(15, "Lit Landmarks", "Lantern posts go up around every landmark."),
        Level(25, "An Even Bigger Basket", "Twice the room for each thing, and residents gather more often."),
        Level(40, "Grateful Gifts", "Residents you've helped bring you gifts twice as often."),
        Level(60, "The Biggest Basket", "Three times the room for each thing, and more finds every trip."),
    )

    const val DECOR_LEVEL = 2
    const val LIGHTS_LEVEL = 3
    const val GIFTS_LEVEL = 5

    fun level(s: Sanctuary): Int = LEVELS.count { s.heart >= it.points }

    /** How much more each thing the basket can hold, by Heart level. */
    fun basketRoom(s: Sanctuary): Double = when { level(s) >= 6 -> 3.0; level(s) >= 4 -> 2.0; level(s) >= 1 -> 1.5; else -> 1.0 }
    fun basketExtraFinds(s: Sanctuary): Int = when { level(s) >= 6 -> 2; level(s) >= 1 -> 1; else -> 0 }
    fun basketFaster(s: Sanctuary): Boolean = level(s) >= 4

    private val BERRIES = listOf("oran", "pecha", "cheri", "chesto", "rawst", "aspear", "persim", "leppa", "sitrus", "lum")
    private const val LIFETIME = 24000L * 3
    private val POINTS = mapOf("food" to 1, "cuddle" to 1, "visit" to 2, "decor" to 2)
    private val DECOR_NAMES = mapOf("bed" to "a Pokémon bed", "toy" to "a bouncy ball", "trough" to "a feeding trough")

    // ------------------------------------------------------------------ titles

    private fun place(zone: String): String = when (zone) {
        "water" -> "Lake"; "ice" -> "Peak"; "rock" -> "Mountain"; "ground" -> "Desert"; "fire" -> "Volcano"; "dragon" -> "Den"
        "ghost" -> "Tower"; "poison" -> "Swamp"; "grass", "bug" -> "Forest"; "fairy" -> "Garden"; "electric" -> "Power Plant"
        ZoneLayout.HUB, "" -> "Town"
        else -> Residents.zoneName(zone).split(' ', '&').firstOrNull { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Valley"
    }

    /** A resident's title from the requests it had done ("Lake Helper", "Volcano Guardian"), or null. */
    fun title(data: ResidentData?, home: String?): String? {
        val n = data?.helped ?: 0
        return when {
            n >= 6 -> "${place(home ?: "")} Guardian"
            n >= 3 -> "${place(home ?: "")} Helper"
            else -> null
        }
    }

    // ------------------------------------------------------------------ asking

    /** Called every second for every sanctuary. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        // a note over a resident that's waiting for you
        if ((now / 20L) % 3L == 0L) for (r in s.requests) rt.live[r.pokemon]?.entity?.let { e ->
            level.sendParticles(ParticleTypes.NOTE, e.x, e.y + e.bbHeight + 0.6, e.z, 1, 0.1, 0.0, 0.1, 0.0)
        }
        if ((now / 20L + (s.id.hashCode() and 63)) % 30L != 0L) return
        // old requests are given up on; requests from residents who left go
        val gone = s.requests.filter { now - it.created > LIFETIME || rosterOf(rt, it.pokemon) == null }
        for (r in gone) {
            s.requests.remove(r)
            if (now - r.created > LIFETIME) s.logEvent("${nameOf(rt, r.pokemon, r.name)} stopped waiting for ${what(s, rt, r)}.")
        }
        if (!SanctuaryConfig.requestsEnabled.get() || s.requests.size >= SanctuaryConfig.requestMax.get() || rt.roster.isEmpty()) return
        val every = SanctuaryConfig.requestMinutes.get().coerceAtLeast(1) * 60L * 20L
        val last = lastAsked.getOrPut(s.id) { now - every / 2 }
        if (now - last < every) return
        lastAsked[s.id] = now
        ask(level, s, rt, now)?.let {
            s.requests += it
            s.logEvent("${it.name} has a request: ${what(s, rt, it)}.")
            Sanctuaries.data(level).setDirty()
        }
    }

    /** When each sanctuary last had a new request (not saved: after a restart the first one comes a bit sooner). */
    private val lastAsked = HashMap<UUID, Long>()

    private fun rosterOf(rt: SanctuaryRuntime, id: UUID) = rt.live[id]?.pokemon ?: Residents.findRoster(rt, id)?.pokemon

    private fun nameOf(rt: SanctuaryRuntime, id: UUID, fallback: String) = rosterOf(rt, id)?.getDisplayName(false)?.string ?: fallback

    /** Picks a resident (one that's out, most of the time) and what it asks for. */
    private fun ask(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long): Request? {
        val random = level.random
        val asking = s.requests.map { it.pokemon }.toSet()
        val live = rt.live.values.filter { !it.leaving && it.pokemonId !in asking }
        val pk: Pokemon = (if (live.isNotEmpty() && random.nextDouble() < 0.75) live[random.nextInt(live.size)].pokemon
            else rt.roster.filter { it.pokemon.uuid !in asking }.randomOrNull()?.pokemon) ?: return null
        if (Residents.hidden(pk)) return null
        val ring = ZoneLayout.ring(s)
        val home = runCatching { TypeLogic.homeZone(pk, s.residents[pk.uuid], ring, s) }.getOrNull() ?: ZoneLayout.HUB
        val name = pk.getDisplayName(false).string
        val roll = random.nextInt(100)
        if (roll < 20) friendOf(s, rt, pk)?.let { f ->
            val fHome = runCatching { TypeLogic.homeZone(f, s.residents[f.uuid], ring, s) }.getOrNull() ?: ZoneLayout.HUB
            return Request(UUID.randomUUID(), pk.uuid, "visit", f.uuid.toString(), fHome, now, name, f.getDisplayName(false).string)
        }
        if (roll < 40 && (home in ring || home == ZoneLayout.HUB)) {
            val what = listOf("bed", "toy", "trough")[random.nextInt(3)]
            return Request(UUID.randomUUID(), pk.uuid, "decor", what, home, now, name, base = decorCount(s, what, home))
        }
        if (roll < 65) return Request(UUID.randomUUID(), pk.uuid, "cuddle", "", home, now, name)
        val berry = BERRIES.shuffled(java.util.Random(random.nextLong())).map { ResourceLocation.fromNamespaceAndPath("cobblemon", "${it}_berry") }
            .firstOrNull { BuiltInRegistries.ITEM.get(it) != Items.AIR } ?: return Request(UUID.randomUUID(), pk.uuid, "cuddle", "", home, now, name)
        return Request(UUID.randomUUID(), pk.uuid, "food", berry.toString(), home, now, name, ItemStack(BuiltInRegistries.ITEM.get(berry)).hoverName.string)
    }

    /** A good friend of [pk] that lives in the sanctuary, if it has one. */
    private fun friendOf(s: Sanctuary, rt: SanctuaryRuntime, pk: Pokemon): Pokemon? {
        val me = pk.uuid.toString()
        val friends = s.relationships.entries.filter { it.value >= 40 && it.key.contains(me) }.mapNotNull { e ->
            e.key.split('|').firstOrNull { it != me }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }
        return friends.shuffled().firstNotNullOfOrNull { rosterOf(rt, it) }
    }

    /** How many decorations of a kind stand in a habitat. */
    private fun decorCount(s: Sanctuary, what: String, zone: String): Int = s.props.entries.count { (pos, kind) ->
        kind.startsWith("player_$what") && BlockPos.of(pos).let { p -> ZoneLayout.locate(s, p.x, p.z)?.zone ?: ZoneLayout.HUB } == zone
    }

    // ------------------------------------------------------------------ describing

    /** What the request is, in a few words ("a Sitrus Berry", "a visit to its friend Bulbasaur"). */
    fun what(s: Sanctuary, rt: SanctuaryRuntime, r: Request): String = when (r.kind) {
        "food" -> "a ${r.argName}"
        "visit" -> "a visit to its friend ${runCatching { nameOf(rt, UUID.fromString(r.arg), r.argName) }.getOrDefault(r.argName)}"
        "decor" -> "${DECOR_NAMES[r.arg] ?: "a decoration"} in the ${Residents.zoneName(r.zone)}"
        else -> "some time with you"
    }

    /** The full sentence and a hint of how to do it, for the screen. */
    fun describe(s: Sanctuary, rt: SanctuaryRuntime, r: Request): Pair<String, String> {
        val name = nameOf(rt, r.pokemon, r.name)
        val where = Residents.zoneName(r.zone)
        return when (r.kind) {
            "food" -> "$name would love a ${r.argName}." to "Feed it with the Journal (it lives in the $where)."
            "visit" -> {
                val friend = runCatching { nameOf(rt, UUID.fromString(r.arg), r.argName) }.getOrDefault(r.argName)
                "$name wants you to say hi to its friend $friend." to "Pet $friend with the Journal (in the $where)."
            }
            "decor" -> "$name wishes there was ${DECOR_NAMES[r.arg] ?: "a decoration"} in the $where." to "Place one anywhere in the $where."
            else -> "$name wants to spend some time with you." to "Pet it or play with it (it lives in the $where)."
        }
    }

    fun points(r: Request) = POINTS[r.kind] ?: 1

    /** The food a resident asked for, if it's waiting for one. */
    fun wantedFood(s: Sanctuary, pokemon: UUID): Item? = s.requests.firstOrNull { it.pokemon == pokemon && it.kind == "food" }
        ?.let { BuiltInRegistries.ITEM.get(ResourceLocation.parse(it.arg)) }?.takeIf { it != Items.AIR }

    // ------------------------------------------------------------------ doing

    fun onFed(level: ServerLevel, s: Sanctuary, pokemon: UUID, item: Item, player: ServerPlayer) {
        val id = BuiltInRegistries.ITEM.getKey(item).toString()
        s.requests.firstOrNull { it.pokemon == pokemon && it.kind == "food" && it.arg == id }?.let { done(level, s, it, player) }
    }

    fun onPet(level: ServerLevel, s: Sanctuary, pokemon: UUID, player: ServerPlayer) {
        s.requests.firstOrNull { it.kind == "visit" && it.arg == pokemon.toString() }?.let { done(level, s, it, player) }
        onPlayed(level, s, pokemon, player)
    }

    fun onPlayed(level: ServerLevel, s: Sanctuary, pokemon: UUID, player: ServerPlayer) {
        s.requests.firstOrNull { it.pokemon == pokemon && it.kind == "cuddle" }?.let { done(level, s, it, player) }
    }

    /** A decoration was placed in the valley. */
    fun onPlaced(level: ServerLevel, s: Sanctuary, pos: BlockPos, kind: String, player: ServerPlayer?) {
        val zone = ZoneLayout.locate(s, pos.x, pos.z)?.zone ?: ZoneLayout.HUB
        s.requests.firstOrNull { it.kind == "decor" && kind.startsWith("player_${it.arg}") && it.zone == zone }?.let { done(level, s, it, player) }
    }

    private fun done(level: ServerLevel, s: Sanctuary, r: Request, player: ServerPlayer?) {
        s.requests.remove(r)
        val rt = Residents.runtime(s)
        val pk = rosterOf(rt, r.pokemon)
        val name = pk?.getDisplayName(false)?.string ?: r.name
        val data = s.resident(r.pokemon)
        val ring = ZoneLayout.ring(s)
        val home = pk?.let { runCatching { TypeLogic.homeZone(it, data, ring, s) }.getOrNull() }
        val oldTitle = title(data, home)
        data.helped++
        pk?.let { Growth.fonder(s, it, 10) }
        rt.live[r.pokemon]?.entity?.let { Effects.hearts(it); Effects.happy(it); Effects.play(it, Effects.CRY) }
        val before = level(s)
        s.heart += points(r)
        s.stat("requests")
        val who = player?.gameProfile?.name ?: "Someone"
        s.logEvent("$who did $name's request ($name is so happy!).")
        player?.displayClientMessage(Component.literal("$name is so happy! +${points(r)} Valley Heart").withStyle(ChatFormatting.LIGHT_PURPLE), true)
        val newTitle = title(data, home)
        if (newTitle != null && newTitle != oldTitle) s.logEvent("$name is now known as the $newTitle.")
        val after = level(s)
        if (after > before) {
            val lv = LEVELS[after - 1]
            s.logEvent("The Valley Heart grew to level $after: ${lv.title}!")
            for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.sendSystemMessage(
                Component.literal("♥ The Valley Heart grew to level $after: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(lv.title).withStyle(ChatFormatting.GOLD)).append(Component.literal(" — ${lv.desc}").withStyle(ChatFormatting.GRAY)))
        }
        Sanctuaries.data(level).setDirty()
    }
}
