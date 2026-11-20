package com.rickygamer28.sanctuary.ui

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.life.Families
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.Sizes
import com.rickygamer28.sanctuary.net.SanctuaryNet
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.world.SanctuaryGenerator
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

fun typeName(zone: String) = zone.replaceFirstChar { it.uppercase() }

/** The colour of a type (from Cobblemon), as 0xRRGGBB. */
fun typeColor(zone: String?): Int = when (zone) {
    null -> 0x9AB8D8
    else -> runCatching { ElementalTypes.get(zone)?.hue }.getOrNull() ?: 0xA8A878
}

private val lastSaid = HashMap<UUID, Pair<String, Int>>()

/** Short feedback: shown on the Sanctuary screen if it's open, otherwise above the hotbar. */
fun say(player: ServerPlayer, text: String, color: ChatFormatting = ChatFormatting.WHITE) {
    lastSaid[player.uuid] = text to (color.color ?: 0xFFFFFF)
    player.displayClientMessage(Component.literal(text).withStyle(color), true)
}

/** Builds what the Sanctuary screen shows and sends it to the player. */
object SanctuaryGuis {
    private fun json(player: ServerPlayer, c: Component): String = Component.Serializer.toJson(c, player.registryAccess())

    fun openCore(player: ServerPlayer, @Suppress("UNUSED_PARAMETER") level: ServerLevel, s: Sanctuary) {
        when {
            s.state == SanctuaryState.PREVIEW && s.owner == player.uuid -> send(player, s, "create", open = true)
            s.state == SanctuaryState.PREVIEW -> say(player, "${s.ownerName} is planning a sanctuary here.")
            s.isMember(player.uuid) -> send(player, s, "home", open = true)
            else -> say(player, "This sanctuary belongs to ${s.ownerName}.")
        }
    }

    fun openJournal(player: ServerPlayer, section: String = "home") {
        val (level, s) = Sanctuaries.current(player) ?: return say(player, "You don't belong to a sanctuary yet. Craft and place a Sanctuary Core to start one.")
        if (s.state == SanctuaryState.PREVIEW) return openCore(player, level, s)
        send(player, s, if (section == "residents") "residents" else "home", open = true)
    }

    fun openResident(player: ServerPlayer, s: Sanctuary, pokemon: UUID) = send(player, s, "resident", pokemon, open = true)

    // ------------------------------------------------------------------ the residents list, a window at a time

    /** What a player's Residents tab shows: filters, search, and where in the list they are. */
    class ListQuery(val offset: Int = 0, val zone: String = "", val type: String = "", val mine: Boolean = false, val q: String = "")

    private val queries = HashMap<UUID, ListQuery>()

    fun setQuery(player: ServerPlayer, v: String) {
        val p = v.split('\t')
        queries[player.uuid] = ListQuery(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1) ?: "", p.getOrNull(2) ?: "", p.getOrNull(3) == "1", p.getOrNull(4)?.lowercase()?.trim() ?: "")
    }

    /** A resident, ready for filtering and sorting (made once per roster read). */
    private class Row(val e: com.rickygamer28.sanctuary.life.RosterEntry, val name: String, val species: String, val home: String, val types: List<String>)

    private val rows = HashMap<UUID, Pair<Long, List<Row>>>()

    private fun rowsOf(s: Sanctuary, rt: com.rickygamer28.sanctuary.life.SanctuaryRuntime, ring: List<String>): List<Row> {
        rows[s.id]?.takeIf { it.first == rt.rosterAt }?.let { return it.second }
        val list = rt.roster.map { e ->
            val pk = e.pokemon
            Row(e, (pk.nickname?.string ?: pk.species.name).lowercase(), pk.species.resourceIdentifier.path,
                runCatching { TypeLogic.homeZone(pk, s.residents[pk.uuid], ring, s) }.getOrNull() ?: "",
                runCatching { TypeLogic.currentTypes(pk) }.getOrDefault(emptyList()))
        }.sortedWith(compareBy({ it.home }, { it.name }))
        rows[s.id] = rt.rosterAt to list
        return list
    }

    /** How many residents are sent at a time; the screen asks for the next stretch as it's scrolled. */
    private const val WINDOW = 150

    private fun residentsWindow(player: ServerPlayer, s: Sanctuary, rt: com.rickygamer28.sanctuary.life.SanctuaryRuntime, ring: List<String>, t: CompoundTag) {
        val q = queries[player.uuid] ?: ListQuery()
        val all = rowsOf(s, rt, ring)
        val shown = all.filter { r ->
            (q.zone.isEmpty() || r.home == q.zone) && (!q.mine || r.e.pokemon.getOwnerUUID() == player.uuid) && (q.type.isEmpty() || q.type in r.types) &&
                (q.q.isEmpty() || r.name.contains(q.q) || r.species.contains(q.q) || r.types.any { it.contains(q.q) })
        }
        val from = q.offset.coerceIn(0, maxOf(0, shown.size - 1))
        t.putInt("monsTotal", shown.size)
        t.putInt("monsAll", all.size)
        t.putInt("monsFrom", from)
        t.putString("monsKey", "${q.zone}\t${q.type}\t${q.mine}\t${q.q}")
        // (what the filters offer: every home and every type, with how many)
        t.put("monsZones", CompoundTag().also { c -> all.groupingBy { it.home }.eachCount().forEach { (k, n) -> c.putInt(k, n) } })
        t.put("monsTypes", CompoundTag().also { c -> all.flatMap { it.types }.groupingBy { it }.eachCount().forEach { (k, n) -> c.putInt(k, n) } })
        t.put("mons", ListTag().also { list ->
            for (r in shown.subList(from, minOf(shown.size, from + WINDOW))) {
                val pk = r.e.pokemon
                list.add(CompoundTag().also {
                    it.putUUID("u", pk.uuid)
                    it.putString("sp", pk.species.resourceIdentifier.toString())
                    it.put("as", ListTag().also { a -> pk.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
                    it.putString("n", json(player, pk.getDisplayName(false)))
                    it.putInt("l", pk.level)
                    it.putString("h", r.home)
                    it.putBoolean("o", rt.live.containsKey(pk.uuid))
                    it.putBoolean("m", pk.getOwnerUUID() == player.uuid)
                    it.putBoolean("sh", pk.shiny)
                    it.putString("tr", r.e.ownerName)
                    it.put("t", ListTag().also { l -> r.types.forEach { x -> l.add(StringTag.valueOf(x)) } })
                })
            }
        })
    }

    fun close(player: ServerPlayer) = SanctuaryNet.send(player, CompoundTag().also { it.putBoolean("close", true) })

    fun send(player: ServerPlayer, s: Sanctuary, page: String, pokemon: UUID? = null, open: Boolean = false) {
        val rt = Residents.runtime(s)
        // (a freshly opened screen starts with no filters)
        if (open) queries.remove(player.uuid)
        // (the roster is fresh enough if it was read in the last couple of seconds, or is still being counted)
        SanctuaryDimension.level(player.server)?.let {
            if (rt.roster.isEmpty() || (page != "resident" && rt.counting == null && it.gameTime - rt.rosterAt > 40)) Residents.refreshRoster(it, s, rt, it.gameTime)
        }
        val t = CompoundTag()
        t.putBoolean("open", open)
        t.putString("page", page)
        t.putString("id", s.id.toString())
        t.putBoolean("isOwner", s.owner == player.uuid)
        t.putString("ownerName", s.ownerName)
        t.putInt("size", s.size.ordinal)
        t.putString("sizeLabel", s.size.label)
        t.putInt("radius", s.size.radius)
        t.putBoolean("preview", s.state == SanctuaryState.PREVIEW)
        t.putInt("residents", s.residentCount)
        t.putInt("live", rt.live.values.count { !it.leaving })
        t.putInt("capNow", Residents.spawnCap(s))
        t.put("basketOff", ListTag().also { l -> s.basketOff.forEach { l.add(StringTag.valueOf(it)) } })
        t.put("goals", ListTag().also { list ->
            for (g in com.rickygamer28.sanctuary.life.Milestones.GOALS) list.add(CompoundTag().also {
                it.putString("id", g.id)
                it.putString("title", g.title)
                it.putString("desc", g.desc)
                it.putInt("have", com.rickygamer28.sanctuary.life.Milestones.value(g, s, rt).coerceAtMost(g.need))
                it.putInt("need", g.need)
                it.putBoolean("reached", g.id in s.reached)
                it.putBoolean("claimed", g.id in s.claimed)
                it.put("rewards", ListTag().also { rl -> g.rewards.forEach { rw -> rl.add(StringTag.valueOf("${rw.count}|${rw.id}")) } })
            })
        })
        run {
            val R = com.rickygamer28.sanctuary.life.Requests
            val lv = R.level(s)
            t.put("heart", CompoundTag().also { h ->
                h.putInt("points", s.heart)
                h.putInt("level", lv)
                h.putInt("prev", if (lv == 0) 0 else R.LEVELS[lv - 1].points)
                h.putInt("next", R.LEVELS.getOrNull(lv)?.points ?: -1)
                h.put("levels", ListTag().also { l -> R.LEVELS.forEach { x -> l.add(StringTag.valueOf("${x.points}|${x.title}|${x.desc}")) } })
            })
            t.put("requests", ListTag().also { l ->
                val now = SanctuaryDimension.level(player.server)?.gameTime ?: 0L
                for (r in s.requests) l.add(CompoundTag().also { c ->
                    val (text, how) = R.describe(s, rt, r)
                    c.putUUID("u", r.pokemon)
                    c.putString("text", text)
                    c.putString("how", how)
                    c.putString("zone", r.zone)
                    c.putInt("pts", R.points(r))
                    c.putLong("left", ((24000L * 3 - (now - r.created)) / 1200L).coerceAtLeast(0))
                    (rt.live[r.pokemon]?.pokemon ?: Residents.findRoster(rt, r.pokemon)?.pokemon)?.let { pk ->
                        c.putString("sp", pk.species.resourceIdentifier.toString())
                        c.put("as", ListTag().also { a -> pk.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
                    }
                })
            })
        }
        t.putInt("crowd", s.crowd)
        t.putBoolean("crowdAllowed", com.rickygamer28.sanctuary.SanctuaryConfig.allowBigCrowds.get())
        run {
            val base = Residents.spawnCap(s.size)
            val more = (base * com.rickygamer28.sanctuary.SanctuaryConfig.crowdMoreMultiplier.get()).toInt()
            val all = minOf(maxOf(base, com.rickygamer28.sanctuary.SanctuaryConfig.crowdEveryoneMax.get()), maxOf(base, s.residentCount))
            t.putIntArray("crowdCaps", intArrayOf(base, more, all))
        }
        t.putInt("progress", SanctuaryGenerator.progress(s) ?: -1)
        t.putInt("minutesLeft", SanctuaryGenerator.minutesLeft(s) ?: -1)
        t.putBoolean("inside", SanctuaryDimension.isSanctuary(player.level()))
        val ring = ZoneLayout.ring(s)
        t.putInt("awake", s.unlocked.size)
        t.putInt("zones", ZoneLayout.HABITATS.size + 1 + ZoneLayout.customZones(s).size)
        Sizes.nextSize(s.size)?.let { next ->
            t.putString("nextLabel", next.label)
            t.putInt("nextNeeded", Sizes.residentsNeeded(next))
            t.putInt("nextRadius", next.radius)
            t.putBoolean("nextAllowed", Sizes.allowed(next))
        }
        t.put("sizes", ListTag().also { list ->
            for (size in SanctuarySize.entries) list.add(CompoundTag().also {
                it.putString("label", size.label)
                it.putInt("radius", size.radius)
                it.putBoolean("allowed", Sizes.allowed(size))
                it.putBoolean("unlocked", Sizes.unlocked(size, s.residentCount))
                it.putInt("needed", Sizes.residentsNeeded(size))
                it.putInt("cap", Residents.spawnCap(size))
            })
        })
        t.put("members", ListTag().also { list ->
            list.add(CompoundTag().also { it.putString("name", s.ownerName); it.putUUID("u", s.owner); it.putBoolean("owner", true) })
            for (m in s.members) list.add(CompoundTag().also { it.putString("name", s.memberNames[m] ?: "?"); it.putUUID("u", m); it.putBoolean("owner", false) })
        })
        if (s.owner == player.uuid) {
            t.put("online", ListTag().also { list ->
                player.server.playerList.players.filter { !s.isMember(it.uuid) }.take(24).forEach { list.add(StringTag.valueOf(it.gameProfile.name)) }
            })
        }
        t.put("biomes", ListTag().also { list ->
            // the habitats, the custom types' own habitats, then custom types that don't have one (yet)
            val customPlaced = ZoneLayout.customZones(s)
            val customOthers = s.customCounts.keys.filter { it !in customPlaced }.sorted()
            for (zone in listOf(ZoneLayout.HUB) + ZoneLayout.HABITATS + customPlaced + customOthers) list.add(CompoundTag().also {
                val custom = com.rickygamer28.sanctuary.world.CustomTypes.isCustom(zone)
                val placed = zone == ZoneLayout.HUB || zone in s.habitats
                val pocket = if (custom && !placed) ZoneLayout.customPocket(s, zone) else null
                val count = if (custom && !placed) s.customCounts[zone] ?: 0 else s.typeCounts[zone] ?: 0
                it.putString("zone", zone)
                it.putString("name", if (custom && !placed) (if (pocket?.sub == true) com.rickygamer28.sanctuary.world.CustomTypes.subName(zone)
                    else com.rickygamer28.sanctuary.world.CustomTypes.typeName(zone) + " type") else Residents.zoneName(zone))
                it.putInt("color", typeColor(zone))
                it.putBoolean("awake", zone in s.unlocked)
                it.putBoolean("placed", placed)
                it.putString("types", if (custom) com.rickygamer28.sanctuary.world.CustomTypes.typeName(zone)
                    else ZoneLayout.typesOf(zone).joinToString(" · ") { ty -> ty.replaceFirstChar { c -> c.uppercase() } })
                it.putInt("count", if (zone == ZoneLayout.HUB) rosterHomes(s).count { h -> h == ZoneLayout.HUB } else count)
                if (custom) {
                    // where its Pokémon live, and what the owner chose
                    val home = com.rickygamer28.sanctuary.world.CustomTypes.zoneFor(s, zone)
                    it.putBoolean("custom", true)
                    it.putString("home", home)
                    it.putString("homeName", Residents.zoneName(home))
                    it.putString("choice", com.rickygamer28.sanctuary.world.CustomTypes.choice(s, zone) ?: com.rickygamer28.sanctuary.world.CustomTypes.OWN)
                    it.putInt("need", com.rickygamer28.sanctuary.world.CustomTypes.habitatNeed(s.typeCounts))
                    it.putInt("subNeed", com.rickygamer28.sanctuary.world.CustomTypes.subMin())
                    it.putInt("typeCount", s.customCounts[zone] ?: 0)
                    it.putBoolean("noRoom", zone in s.noRoomFor)
                    it.putBoolean("ownOn", runCatching { com.rickygamer28.sanctuary.SanctuaryConfig.customTypeBiomes.get() }.getOrDefault(true) &&
                        !com.rickygamer28.sanctuary.world.CustomTypes.townOnly(zone))
                    it.putInt("pocketNeed", com.rickygamer28.sanctuary.world.CustomTypes.pocketMin())
                    it.putBoolean("townOnly", com.rickygamer28.sanctuary.world.CustomTypes.townOnly(zone))
                    if (pocket != null) { it.putBoolean("pocket", true); it.putBoolean("sub", pocket.sub); it.putString("pocketIn", Residents.zoneName(pocket.host)) }
                }
                // how lively the biome is: its share of the residents, compared with the busiest biome
                val busiest = (s.typeCounts.values.maxOrNull() ?: 1).coerceAtLeast(1)
                val pct = if (zone == ZoneLayout.HUB) 100 else (count * 100 / busiest).coerceIn(0, 100)
                it.putInt("pct", pct)
                // its look, and the looks the owner can choose from
                com.rickygamer28.sanctuary.world.Looks.of(zone)?.takeIf { placed || pocket != null }?.let { looks ->
                    it.putInt("look", com.rickygamer28.sanctuary.world.BiomeDresser.lookOf(s, zone))
                    it.put("looks", ListTag().also { l -> looks.forEach { (n, d) -> l.add(StringTag.valueOf("$n|$d")) } })
                    // the fourth look waits for the Valley Heart
                    if (looks.size > com.rickygamer28.sanctuary.world.Remix.INDEX && com.rickygamer28.sanctuary.life.Requests.level(s) < com.rickygamer28.sanctuary.life.Requests.LOOKS_LEVEL)
                        it.putInt("lockFrom", com.rickygamer28.sanctuary.world.Remix.INDEX)
                }
            })
        })
        // the map of the region (worked out once, kept until the region changes)
        run {
            val m = RegionMap.build(s)
            for (k in m.allKeys) t.put(k, m.get(k)!!.copy())
            val r = s.size.radius
            if (SanctuaryDimension.isSanctuary(player.level())) {
                t.putFloat("mapX", ((player.x - s.center.x) / r).toFloat())
                t.putFloat("mapZ", ((player.z - s.center.z) / r).toFloat())
                t.putFloat("mapYaw", player.yRot)
            }
        }
        t.put("events", ListTag().also { list -> s.events.toList().reversed().forEach { list.add(StringTag.valueOf(it)) } })
        t.putBoolean("hasWhistle", player.inventory.contains(ItemStack(ModContent.WHISTLE.get())))
        t.putBoolean("hasBasket", s.basketPos != null)
        t.putInt("basketItems", com.rickygamer28.sanctuary.life.Gathering.itemCount(s))
        t.putBoolean("eggsOn", com.rickygamer28.sanctuary.SanctuaryConfig.eggsEnabled.get())
        t.put("eggs", eggs(player, s, ring))

        if (page == "residents") residentsWindow(player, s, rt, ring, t)
        if (page == "resident" && pokemon != null) {
            val pk = rt.live[pokemon]?.pokemon ?: Residents.findRoster(rt, pokemon)?.pokemon
            if (pk != null) t.put("mon", detail(player, s, pk, ring))
            else t.putString("gone", "This Pokémon has left the sanctuary.")
            t.putUUID("monId", pokemon)
        }
        lastSaid.remove(player.uuid)?.let { (text, color) -> t.putString("toast", text); t.putInt("toastColor", color) }
        SanctuaryNet.send(player, t)
    }

    private fun eggs(player: ServerPlayer, s: Sanctuary, ring: List<String>): ListTag {
        val list = ListTag()
        val access = player.registryAccess()
        val now = SanctuaryDimension.level(player.server)?.gameTime ?: 0L
        for (egg in s.eggs.values.toList().asReversed().take(80)) {
            val baby = runCatching { Pokemon().loadFromNBT(access, egg.pokemon) }.getOrNull() ?: continue
            list.add(CompoundTag().also {
                it.putUUID("u", egg.id)
                it.putBoolean("mine", Families.canManage(s, egg, player))
                it.putString("owner", egg.ownerName)
                it.putString("zone", egg.zone)
                it.putBoolean("nest", egg.nest != null)
                it.putLong("age", ((now - egg.laidAt) / 1200L).coerceAtLeast(0))
                it.putInt("cycles", egg.cycles)
                it.putInt("steps", egg.cycles * com.rickygamer28.sanctuary.SanctuaryConfig.eggStepsPerCycle.get())
                for ((key, parent) in listOf("mom" to egg.mother, "dad" to egg.father)) {
                    it.put(key, CompoundTag().also { p ->
                        p.putString("n", parent.name)
                        p.putString("sp", parent.species)
                        p.put("as", ListTag().also { a -> parent.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
                    })
                }
                it.putString("sp", baby.species.resourceIdentifier.toString())
                it.put("as", ListTag().also { a -> baby.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
                it.putString("species", json(player, baby.species.translatedName))
                it.putString("gender", baby.gender.name)
                it.putBoolean("sh", baby.shiny)
                it.putString("nature", runCatching { baby.nature.displayName }.getOrDefault(""))
                it.putString("ability", runCatching { baby.ability.name }.getOrDefault(""))
                it.put("t", ListTag().also { l -> TypeLogic.currentTypes(baby).forEach { x -> l.add(StringTag.valueOf(x)) } })
                val stats = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
                it.putIntArray("ivs", stats.map { st -> baby.ivs.getOrDefault(st) }.toIntArray())
                it.putString("ball", runCatching { baby.caughtBall.name.path }.getOrDefault("poke_ball"))
                it.put("moves", ListTag().also { l -> baby.moveSet.getMoves().forEach { m -> l.add(StringTag.valueOf(json(player, m.displayName))) } })
            })
        }
        return list
    }

    private fun rosterHomes(s: Sanctuary): List<String?> {
        val ring = ZoneLayout.ring(s)
        return Residents.runtime(s).roster.map { TypeLogic.homeZone(it.pokemon, s.residents[it.pokemon.uuid], ring, s) }
    }

    private fun detail(player: ServerPlayer, s: Sanctuary, pk: Pokemon, ring: List<String>): CompoundTag {
        val rt = Residents.runtime(s)
        val t = CompoundTag()
        val id = pk.uuid
        t.putUUID("u", id)
        t.putString("sp", pk.species.resourceIdentifier.toString())
        t.put("as", ListTag().also { a -> pk.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
        t.putString("n", json(player, pk.getDisplayName(false)))
        t.putString("species", json(player, pk.species.translatedName))
        t.putInt("dex", pk.species.nationalPokedexNumber)
        t.putInt("l", pk.level)
        t.putBoolean("sh", pk.shiny)
        t.putString("gender", pk.gender.name)
        t.putBoolean("m", pk.getOwnerUUID() == player.uuid)
        t.putBoolean("o", rt.live.containsKey(id))
        t.putString("tr", Residents.findRoster(rt, id)?.ownerName ?: s.ownerName)
        val home = TypeLogic.homeZone(pk, s.residents[id], ring, s)
        t.putString("h", home ?: "")
        t.put("t", ListTag().also { l -> TypeLogic.currentTypes(pk).forEach { x -> l.add(StringTag.valueOf(x)) } })
        t.putInt("friendship", pk.friendship)
        t.putString("nature", runCatching { pk.nature.displayName }.getOrDefault(""))
        t.putString("ability", runCatching { pk.ability.name }.getOrDefault(""))
        com.rickygamer28.sanctuary.life.Personality.of(pk).let { p -> t.putString("pers", p.label); t.putString("persB", p.blurb) }
        com.rickygamer28.sanctuary.life.PropVisits.describe(s, s.residents[id])?.let { t.putString("fav", it) }
        run {
            val home = runCatching { TypeLogic.homeZone(pk, s.residents[id], ring, s) }.getOrNull()
            com.rickygamer28.sanctuary.life.Requests.title(s.residents[id], home)?.let { t.putString("title", it) }
            s.requests.firstOrNull { it.pokemon == id }?.let { r ->
                val (text, how) = com.rickygamer28.sanctuary.life.Requests.describe(s, rt, r)
                t.putString("req", text); t.putString("reqHow", how)
            }
            com.rickygamer28.sanctuary.life.Evolving.status(s, pk)?.let { t.putString("evo", it) }
            t.putBoolean("stay", s.residents[id]?.noEvolve == true)
        }
        t.putInt("hpNow", pk.currentHealth)
        val stats = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)
        t.putIntArray("stats", intArrayOf(pk.maxHealth, pk.attack, pk.defence, pk.specialAttack, pk.specialDefence, pk.speed))
        t.putIntArray("ivs", stats.map { pk.ivs.getOrDefault(it) }.toIntArray())
        t.putIntArray("evs", stats.map { pk.evs.getOrDefault(it) }.toIntArray())
        t.put("moves", ListTag().also { list ->
            for (m in pk.moveSet.getMoves()) list.add(CompoundTag().also {
                it.putString("n", json(player, m.displayName))
                it.putString("t", m.type.name)
                it.putInt("pp", m.currentPp)
                it.putInt("max", m.maxPp)
            })
        })
        // friends and rivals
        val rels = s.relationships.entries.mapNotNull { (k, v) ->
            val parts = k.split('|')
            if (parts.size != 2) return@mapNotNull null
            val other = when (id.toString()) { parts[0] -> parts[1]; parts[1] -> parts[0]; else -> return@mapNotNull null }
            runCatching { UUID.fromString(other) }.getOrNull()?.let { it to v }
        }
        fun people(list: List<Pair<UUID, Int>>) = ListTag().also { l ->
            for ((u, v) in list) l.add(CompoundTag().also {
                val other = Residents.findRoster(rt, u)?.pokemon
                it.putString("n", if (other != null) json(player, other.getDisplayName(false)) else json(player, Component.literal("someone who left")))
                it.putInt("v", v)
            })
        }
        t.put("friends", people(rels.filter { it.second >= 40 }.sortedByDescending { it.second }.take(5)))
        t.put("rivals", people(rels.filter { it.second <= -30 }.sortedBy { it.second }.take(5)))
        fun nameOf(u: UUID): String = Residents.findRoster(rt, u)?.pokemon?.let { json(player, it.getDisplayName(false)) }
            ?: s.eggs.values.firstNotNullOfOrNull { e -> listOf(e.mother, e.father).firstOrNull { it.id == u }?.name }?.let { json(player, Component.literal(it)) }
            ?: json(player, Component.literal("someone who left"))
        s.families[id]?.let { (a, b) -> t.put("parents", ListTag().also { l -> l.add(StringTag.valueOf(nameOf(a))); l.add(StringTag.valueOf(nameOf(b))) }) }
        t.put("children", ListTag().also { l ->
            s.families.entries.filter { it.value.first == id || it.value.second == id }.take(6).forEach { l.add(StringTag.valueOf(nameOf(it.key))) }
        })
        val options = if (TypeLogic.waterOnly(pk) && "water" in ring) listOf("water") else TypeLogic.zoneTypes(pk, s).ifEmpty { listOf(ZoneLayout.HUB) + ring.filter { it in s.unlocked } }.distinct()
        t.put("homes", ListTag().also { l -> options.forEach { l.add(StringTag.valueOf(it)) } })
        return t
    }
}

/** Handles the buttons pressed on the Sanctuary screen. */
object ScreenActions {
    /** When each sanctuary's habitat look was last changed (a short wait between changes). */
    private val lookChanged = HashMap<UUID, Long>()

    fun handle(player: ServerPlayer, t: CompoundTag) {
        val id = runCatching { UUID.fromString(t.getString("id")) }.getOrNull() ?: return
        val (level, s) = Sanctuaries.find(player.server, id) ?: return SanctuaryGuis.close(player)
        if (!s.isMember(player.uuid)) return SanctuaryGuis.close(player)
        val owner = s.owner == player.uuid
        val page = t.getString("page").ifEmpty { "home" }
        val pokemon = if (t.hasUUID("p")) t.getUUID("p") else null
        val v = t.getString("v")
        fun refresh(p: String = page) = SanctuaryGuis.send(player, s, p, pokemon)
        fun ownerOnly(): Boolean { if (!owner) say(player, "Only the owner can do that.", ChatFormatting.RED); return owner }

        when (t.getString("a")) {
            "view" -> refresh()
            // the Residents tab's filters, search and scroll position: the matching stretch of the list
            "list" -> { SanctuaryGuis.setQuery(player, v); refresh("residents") }
            "size" -> {
                if (ownerOnly() && s.state == SanctuaryState.PREVIEW) runCatching { SanctuarySize.valueOf(v) }.getOrNull()?.let { Sanctuaries.setSize(s, player, it) }
                refresh("create")
            }
            "create" -> {
                if (ownerOnly() && s.state == SanctuaryState.PREVIEW) Sanctuaries.confirm(s, player)
                refresh(if (s.state == SanctuaryState.PREVIEW) "create" else "home")
            }
            "cancel" -> {
                if (ownerOnly() && s.state == SanctuaryState.PREVIEW) Sanctuaries.cancel(level, s, player)
                SanctuaryGuis.close(player)
            }
            "travel" -> { SanctuaryGuis.close(player); player.server.execute { Sanctuaries.travel(player, s) } }
            "return" -> { SanctuaryGuis.close(player); player.server.execute { SanctuaryDimension.returnHome(player) } }
            "expand" -> { if (ownerOnly()) Sanctuaries.expand(s, player); refresh("home") }
            "claim" -> {
                val msg = com.rickygamer28.sanctuary.life.Milestones.claim(level, s, player, v)
                say(player, msg, if (msg.startsWith("Collected")) ChatFormatting.GREEN else ChatFormatting.GRAY)
                refresh("goals")
            }
            "basket" -> {
                if (ownerOnly() && v in setOf("drops", "berries", "apricorns", "jobs")) {
                    if (!s.basketOff.remove(v)) s.basketOff += v
                    Sanctuaries.data(level).setDirty()
                }
                refresh("home")
            }
            "crowd" -> {
                val c = v.toIntOrNull()?.coerceIn(0, 2) ?: 0
                if (ownerOnly()) {
                    if (c > 0 && !com.rickygamer28.sanctuary.SanctuaryConfig.allowBigCrowds.get()) say(player, "Bigger crowds are turned off on this server.", ChatFormatting.RED)
                    else {
                        s.crowd = c
                        Sanctuaries.data(level).setDirty()
                        say(player, when (c) { 1 -> "More Pokémon will be out at once."; 2 -> "Every resident will come out."; else -> "Back to the normal number of Pokémon out." }, ChatFormatting.GREEN)
                    }
                }
                refresh("home")
            }
            "reshape" -> {
                if (ownerOnly()) {
                    s.reshape()
                    Sanctuaries.data(level).setDirty()
                    say(player, "Reshaping the valley. Stay inside so it stays loaded.", ChatFormatting.GREEN)
                }
                refresh("home")
            }
            "repair" -> {
                if (ownerOnly()) {
                    val here = player.serverLevel()
                    if (!SanctuaryDimension.isSanctuary(here) || Sanctuaries.current(player)?.second?.id != s.id) {
                        say(player, "Stand in your valley, where you want the land repaired.", ChatFormatting.RED)
                    } else {
                        val n = com.rickygamer28.sanctuary.world.SanctuaryGenerator.repairArea(here, s, player.blockX, player.blockZ, 40)
                        Sanctuaries.data(level).setDirty()
                        say(player, if (n == 0) "There's nothing built around here to repair yet." else "Rebuilding the land around you (about 40 blocks). Give it a moment.", if (n == 0) ChatFormatting.GRAY else ChatFormatting.GREEN)
                    }
                }
                refresh("home")
            }
            "customHome" -> {
                // the owner picks where a custom type's Pokémon live, and so where its pocket and sub-biome go:
                // automatically, in a habitat they pick (a mountain too), or in the town (nothing of its own)
                val type = v.substringBefore(':')
                val choice = v.substringAfter(':')
                val valid = choice == com.rickygamer28.sanctuary.world.CustomTypes.OWN || choice == ZoneLayout.HUB || choice in ZoneLayout.HABITATS
                if (ownerOnly() && com.rickygamer28.sanctuary.world.CustomTypes.isCustom(type) && valid) {
                    val before = ZoneLayout.pockets(s)
                    s.customHomes[type] = choice
                    s.noRoomFor.remove(type)
                    Residents.runtime(s).rosterDirty = true
                    s.generationDirty = true
                    Residents.rebuildChanged(s, before)
                    Sanctuaries.data(level).setDirty()
                    val name = com.rickygamer28.sanctuary.world.CustomTypes.typeName(type)
                    say(player, when (choice) {
                        com.rickygamer28.sanctuary.world.CustomTypes.OWN -> "$name Pokémon live where it suits them; their pocket and sub-biome go there."
                        ZoneLayout.HUB -> "$name Pokémon now live in the town (no pocket or sub-biome of their own)."
                        else -> "$name Pokémon now live in the ${Residents.zoneName(choice)}; their pocket and sub-biome go there."
                    }, ChatFormatting.GREEN)
                }
                refresh("biomes")
            }
            "look" -> {
                // the owner picks a habitat's look: only that habitat (and the strip along its borders) is built again
                val zone = v.substringBefore(':')
                val look = v.substringAfter(':').toIntOrNull()
                val looks = com.rickygamer28.sanctuary.world.Looks.of(zone)?.takeIf { zone == ZoneLayout.HUB || zone in s.habitats || zone in s.customPockets || zone in s.customSubs }
                val now = level.gameTime
                when {
                    !ownerOnly() -> {}
                    looks == null || look == null || look !in looks.indices -> {}
                    look == com.rickygamer28.sanctuary.world.BiomeDresser.lookOf(s, zone) -> {}
                    look >= com.rickygamer28.sanctuary.world.Remix.INDEX && com.rickygamer28.sanctuary.life.Requests.level(s) < com.rickygamer28.sanctuary.life.Requests.LOOKS_LEVEL ->
                        say(player, "That look unlocks at Valley Heart level ${com.rickygamer28.sanctuary.life.Requests.LOOKS_LEVEL}.", ChatFormatting.RED)
                    now - (lookChanged[s.id] ?: -100_000L) < 1200 ->
                        say(player, "A habitat's look was just changed. Wait about a minute before the next change.", ChatFormatting.RED)
                    else -> {
                        lookChanged[s.id] = now
                        s.variants[zone] = look
                        s.rebuildZones += zone
                        // its landmark is built again in the new look
                        s.landmarks.remove(zone)?.let { if (it != Long.MIN_VALUE) s.staleLandmarks[zone] = it }
                        // the town's buildings are put up again on its new ground
                        if (zone == ZoneLayout.HUB) com.rickygamer28.sanctuary.world.TownBuildings.rebuildAll(s)
                        s.generationDirty = true
                        com.rickygamer28.sanctuary.ui.RegionMap.forget(s.id)
                        Sanctuaries.data(level).setDirty()
                        say(player, "${Residents.zoneName(zone)} is becoming ${looks[look].first}. Only that habitat is being rebuilt.", ChatFormatting.GREEN)
                    }
                }
                refresh("biomes")
            }
            "respawn" -> {
                if (ownerOnly()) {
                    // everyone out goes home and comes out again on proper ground (a few at a time, as usual)
                    val rt = Residents.runtime(s)
                    val n = rt.live.size
                    Residents.despawnAll(rt)
                    say(player, if (n == 0) "No Pokémon are out right now." else "Sent $n Pokémon home. They'll come back out in a moment.", ChatFormatting.GREEN)
                }
                refresh("home")
            }
            "journal" -> { Sanctuaries.giveJournal(player); say(player, "Here's a new Journal.", ChatFormatting.GREEN); refresh() }
            "invite" -> {
                if (ownerOnly()) {
                    val target = player.server.playerList.getPlayerByName(v)
                    if (target == null) say(player, "$v isn't online.", ChatFormatting.RED)
                    else { Sanctuaries.invite(player, target); say(player, "Invited ${target.gameProfile.name}. They can accept in chat.", ChatFormatting.GREEN) }
                }
                refresh("members")
            }
            "kick" -> {
                if (ownerOnly() && pokemon != null) Sanctuaries.removeMember(s, pokemon)?.let { say(player, "$it no longer lives here.", ChatFormatting.YELLOW) }
                SanctuaryGuis.send(player, s, "members")
            }
            "leave" -> { if (!owner) Sanctuaries.leave(player, s); SanctuaryGuis.close(player) }
            "egg" -> {
                val egg = pokemon?.let { s.eggs[it] }
                when {
                    egg == null -> say(player, "That egg isn't here anymore.", ChatFormatting.GRAY)
                    !Families.canManage(s, egg, player) -> say(player, "Only ${egg.ownerName} can decide about this egg.", ChatFormatting.RED)
                    v == "hatch" -> Families.hatchInNest(s, egg, player)
                    v == "pickup" -> Families.pickUp(s, egg, player)
                    v == "discard" -> Families.discard(s, egg, player)
                }
                SanctuaryGuis.send(player, s, "nest")
            }
            "act" -> {
                val target = pokemon ?: return
                val action = v
                Interactions.act(player, target, action, t.getString("w").ifEmpty { null })
                if (action == "call" || action == "take") SanctuaryGuis.close(player)
                else SanctuaryGuis.send(player, s, "resident", target)
            }
        }
    }
}
