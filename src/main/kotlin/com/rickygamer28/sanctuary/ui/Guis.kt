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

    fun close(player: ServerPlayer) = SanctuaryNet.send(player, CompoundTag().also { it.putBoolean("close", true) })

    fun send(player: ServerPlayer, s: Sanctuary, page: String, pokemon: UUID? = null, open: Boolean = false) {
        val rt = Residents.runtime(s)
        SanctuaryDimension.level(player.server)?.let { if (rt.roster.isEmpty() || page != "resident") Residents.refreshRoster(it, s, rt, it.gameTime) }
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
        t.putInt("live", rt.live.size)
        t.putInt("progress", SanctuaryGenerator.progress(s) ?: -1)
        t.putBoolean("inside", SanctuaryDimension.isSanctuary(player.level()))
        val ring = ZoneLayout.ring()
        t.putInt("awake", s.unlocked.size)
        t.putInt("zones", ring.size + 1)
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
            for (zone in listOf(ZoneLayout.HUB) + ring) list.add(CompoundTag().also {
                val count = s.typeCounts[zone] ?: 0
                it.putString("zone", zone)
                it.putString("name", Residents.zoneName(zone))
                it.putInt("color", typeColor(zone))
                it.putBoolean("awake", zone in s.unlocked)
                it.putInt("count", if (zone == ZoneLayout.HUB) rosterHomes(s).count { h -> h == ZoneLayout.HUB } else count)
                // how lively the biome is: its share of the residents, compared with the busiest biome
                val busiest = (s.typeCounts.values.maxOrNull() ?: 1).coerceAtLeast(1)
                val pct = if (zone == ZoneLayout.HUB) 100 else (count * 100 / busiest).coerceIn(0, 100)
                it.putInt("pct", pct)
            })
        })
        t.put("events", ListTag().also { list -> s.events.toList().reversed().forEach { list.add(StringTag.valueOf(it)) } })
        t.putBoolean("hasWhistle", player.inventory.contains(ItemStack(ModContent.WHISTLE.get())))
        t.putBoolean("hasBasket", s.basketPos != null)
        t.putInt("basketItems", com.rickygamer28.sanctuary.life.Gathering.itemCount(s))
        t.putBoolean("eggsOn", com.rickygamer28.sanctuary.SanctuaryConfig.eggsEnabled.get())
        t.put("eggs", eggs(player, s, ring))

        if (page == "residents") {
            t.put("mons", ListTag().also { list ->
                for (e in rt.roster.take(2000)) {
                    val pk = e.pokemon
                    list.add(CompoundTag().also {
                        it.putUUID("u", pk.uuid)
                        it.putString("sp", pk.species.resourceIdentifier.toString())
                        it.put("as", ListTag().also { a -> pk.aspects.forEach { x -> a.add(StringTag.valueOf(x)) } })
                        it.putString("n", json(player, pk.getDisplayName(false)))
                        it.putInt("l", pk.level)
                        it.putString("h", TypeLogic.homeZone(pk, s.residents[pk.uuid], ring) ?: "")
                        it.putBoolean("o", rt.live.containsKey(pk.uuid))
                        it.putBoolean("m", pk.getOwnerUUID() == player.uuid)
                        it.putBoolean("sh", pk.shiny)
                        it.putString("tr", e.ownerName)
                        it.put("t", ListTag().also { l -> TypeLogic.currentTypes(pk).forEach { x -> l.add(StringTag.valueOf(x)) } })
                    })
                }
            })
        }
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
        val ring = ZoneLayout.ring()
        return Residents.runtime(s).roster.map { TypeLogic.homeZone(it.pokemon, s.residents[it.pokemon.uuid], ring) }
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
        val home = TypeLogic.homeZone(pk, s.residents[id], ring)
        t.putString("h", home ?: "")
        t.put("t", ListTag().also { l -> TypeLogic.currentTypes(pk).forEach { x -> l.add(StringTag.valueOf(x)) } })
        t.putInt("friendship", pk.friendship)
        t.putString("nature", runCatching { pk.nature.displayName }.getOrDefault(""))
        t.putString("ability", runCatching { pk.ability.name }.getOrDefault(""))
        com.rickygamer28.sanctuary.life.Personality.of(pk).let { p -> t.putString("pers", p.label); t.putString("persB", p.blurb) }
        com.rickygamer28.sanctuary.life.PropVisits.describe(s, s.residents[id])?.let { t.putString("fav", it) }
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
        val options = if (TypeLogic.waterOnly(pk) && "water" in ring) listOf("water") else TypeLogic.zoneTypes(pk).ifEmpty { listOf(ZoneLayout.HUB) + ring.filter { it in s.unlocked } }.distinct()
        t.put("homes", ListTag().also { l -> options.forEach { l.add(StringTag.valueOf(it)) } })
        return t
    }
}

/** Handles the buttons pressed on the Sanctuary screen. */
object ScreenActions {
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
            "reshape" -> {
                if (ownerOnly()) {
                    s.rebuild()
                    Sanctuaries.data(level).setDirty()
                    say(player, "Reshaping the valley. Stay inside so it stays loaded.", ChatFormatting.GREEN)
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
