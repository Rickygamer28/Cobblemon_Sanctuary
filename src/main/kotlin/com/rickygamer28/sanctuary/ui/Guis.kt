package com.rickygamer28.sanctuary.ui

import com.cobblemon.mod.common.item.PokemonItem
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.Sizes
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.world.SanctuaryGenerator
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

private fun gray(s: String): Component = Component.literal(s).withStyle(ChatFormatting.GRAY)
private fun colored(s: String, c: ChatFormatting): Component = Component.literal(s).withStyle(c)

/** The gem icon for a biome. */
fun zoneIcon(zone: String?): Item = when (zone) {
    null -> Items.FEATHER
    else -> Gui.item("cobblemon:${zone}_gem", Items.PAPER)
}

fun typeName(zone: String) = zone.replaceFirstChar { it.uppercase() }

// ---------------------------------------------------------------------- creating a sanctuary

class PreviewGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary) :
    Gui(player, 3, Component.literal("Create your Sanctuary")) {
    override fun build() {
        SanctuaryDimension.level(level.server)?.let { Residents.refreshRoster(it, s, Residents.runtime(s), it.gameTime) }
        set(4, icon(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()), colored("A valley for your Pokémon", ChatFormatting.GOLD),
            gray("This Core opens a path to your own valley"),
            gray("in the Sanctuary dimension. Your world isn't changed."),
            gray("Every Pokémon in your PC will live there."),
            gray("Pokémon in your PC: ${s.residentCount}")))
        val sizeItems = mapOf(SanctuarySize.SMALL to Items.OAK_SAPLING, SanctuarySize.MEDIUM to Items.AZALEA, SanctuarySize.LARGE to Items.FLOWERING_AZALEA)
        listOf(11, 13, 15).zip(SanctuarySize.entries).forEach { (slot, size) ->
            when {
                !Sizes.allowed(size) -> set(slot, icon(Items.BARRIER, size.label, "Disabled on this server", color = ChatFormatting.DARK_GRAY))
                !Sizes.unlocked(size, s.residentCount) -> set(slot, icon(Items.GRAY_DYE, size.label, "Radius ${size.radius} blocks", "Needs ${Sizes.residentsNeeded(size)} Pokémon", color = ChatFormatting.DARK_GRAY))
                else -> set(slot, icon(sizeItems.getValue(size), size.label + if (s.size == size) " (chosen)" else "", "Radius ${size.radius} blocks", "Click to choose", color = ChatFormatting.GREEN, glow = s.size == size)) { _, _ ->
                    Sanctuaries.setSize(s, player, size)
                    refresh()
                }
            }
        }
        set(21, icon(Items.LIME_CONCRETE, "Create", "Make the valley and travel there", color = ChatFormatting.GREEN)) { _, _ ->
            Sanctuaries.confirm(s, player)
            goTo { HomeGui(player, level, s) }
        }
        set(23, icon(Items.RED_CONCRETE, "Cancel", "Take the Core back", color = ChatFormatting.RED)) { _, _ ->
            Sanctuaries.cancel(level, s, player)
            close()
        }
        fill(FILLER)
    }
}

// ---------------------------------------------------------------------- home (Core and Journal)

class HomeGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary) :
    Gui(player, 4, Component.literal("${s.ownerName}'s Sanctuary")) {
    override fun build() {
        val rt = Residents.runtime(s)
        SanctuaryDimension.level(level.server)?.let { Residents.refreshRoster(it, s, rt, it.gameTime) }
        val owner = s.owner == player.uuid
        val progress = SanctuaryGenerator.progress(s)
        set(4, icon(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()), colored("${s.size.label} Sanctuary", ChatFormatting.GOLD),
            gray("Residents: ${s.residentCount}"),
            gray("Out right now: ${rt.live.size}"),
            gray("Biomes awake: ${s.unlocked.size}/${ZoneLayout.ring().size + 1}"),
            gray(if (progress != null) "Shaping the valley: $progress%" else "The valley is ready"),
            gray("Members: " + (listOf(s.ownerName) + s.members.map { s.memberNames[it] ?: "?" }).joinToString(", "))))

        val inside = SanctuaryDimension.isSanctuary(player.level())
        if (inside) {
            set(0, icon(Items.ENDER_EYE, "Return home", "Go back to where you came from", color = ChatFormatting.LIGHT_PURPLE)) { _, _ ->
                close(); player.server.execute { SanctuaryDimension.returnHome(player) }
            }
        } else {
            set(0, icon(Items.ENDER_PEARL, "Travel to the sanctuary", "Visit your Pokémon's valley", color = ChatFormatting.LIGHT_PURPLE, glow = true)) { _, _ ->
                close(); player.server.execute { Sanctuaries.travel(player, s) }
            }
        }

        set(10, icon(Gui.item("cobblemon:poke_ball", Items.ENDER_PEARL), "Residents", "Everyone living here", "Click to browse", color = ChatFormatting.AQUA)) { _, _ ->
            goTo { ResidentsGui(player, level, s, 0, null) }
        }
        set(12, icon(Items.FILLED_MAP, "Biomes", "The biomes and how big they are", color = ChatFormatting.AQUA)) { _, _ ->
            goTo { BiomesGui(player, level, s) }
        }
        set(14, icon(Items.WRITABLE_BOOK, "Events", "What happened recently", color = ChatFormatting.AQUA)) { _, _ ->
            goTo { EventsGui(player, level, s) }
        }
        set(16, icon(ItemStack(ModContent.JOURNAL.get()), colored("Get a Journal", ChatFormatting.AQUA), gray("A replacement Sanctuary Journal"))) { _, _ ->
            Sanctuaries.giveJournal(player)
        }

        if (owner) {
            val next = Sizes.nextSize(s.size)
            when {
                next == null -> set(20, icon(Items.EMERALD_BLOCK, "Largest size", "Your valley can't grow any bigger", color = ChatFormatting.GREEN))
                Sizes.unlocked(next, s.residentCount) -> set(20, icon(Items.EMERALD, "Expand to ${next.label}", "Free! You have ${s.residentCount} Pokémon", "Radius ${next.radius} blocks", color = ChatFormatting.GREEN, glow = true)) { _, _ ->
                    Sanctuaries.expand(s, player); refresh()
                }
                else -> set(20, icon(Items.GRAY_DYE, "Expand to ${next.label}", "Needs ${Sizes.residentsNeeded(next)} Pokémon", "You have ${s.residentCount}", color = ChatFormatting.DARK_GRAY))
            }
            set(22, icon(Items.BRUSH, "Reshape the valley", "Rebuild every biome from scratch", "(only natural ground; your builds stay)", color = ChatFormatting.GOLD)) { _, _ ->
                s.rebuild(); Sanctuaries.data(level).setDirty()
                say(player, "Reshaping the valley. Stay inside so it stays loaded.")
                refresh()
            }
            set(24, icon(Items.PLAYER_HEAD, "Invite a friend", "Type /sanctuary invite <name>", "Their PC Pokémon will live here too", color = ChatFormatting.AQUA))
        } else {
            set(24, icon(Items.BARRIER, "Leave sanctuary", "Your Pokémon go back to your PC", color = ChatFormatting.RED)) { _, _ ->
                Sanctuaries.leave(player, s); close()
            }
        }
        fill(FILLER)
    }
}

// ---------------------------------------------------------------------- residents list

class ResidentsGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary, val page: Int, val filter: String?) :
    Gui(player, 6, Component.literal(if (filter == null) "Residents" else "Residents — ${Residents.zoneName(filter)}")) {
    override fun build() {
        val rt = Residents.runtime(s)
        if (rt.roster.isEmpty()) Residents.refreshRoster(level, s, rt, level.gameTime)
        val ring = ZoneLayout.ring()
        val all = rt.roster.map { it to TypeLogic.homeZone(it.pokemon, s.residents[it.pokemon.uuid], ring) }
        val list = all.filter { filter == null || it.second == filter }
            .sortedWith(compareBy({ it.second ?: "~" }, { it.first.pokemon.species.name }))
        val pages = ((list.size + 44) / 45).coerceAtLeast(1)
        val p = page.coerceIn(0, pages - 1)
        list.drop(p * 45).take(45).forEachIndexed { i, (e, home) ->
            val pk = e.pokemon
            val out = rt.live.containsKey(pk.uuid)
            set(i, residentIcon(pk, home, out, e.ownerName)) { _, _ -> goTo { ResidentGui(player, level, s, pk.uuid) } }
        }
        set(45, icon(Items.ARROW, "Back", color = ChatFormatting.YELLOW)) { _, _ -> goTo { HomeGui(player, level, s) } }
        if (p > 0) set(47, icon(Items.PAPER, "Previous page", "Page $p of $pages", color = ChatFormatting.YELLOW)) { _, _ -> goTo { ResidentsGui(player, level, s, p - 1, filter) } }
        // filter cycles through the biomes that have residents
        val zones = listOf<String?>(null) + all.map { it.second }.distinct().sortedBy { it ?: "~" }
        val nextFilter = zones[(zones.indexOf(filter) + 1) % zones.size]
        set(49, icon(ItemStack(if (filter == null) Items.COMPASS else zoneIcon(filter)), colored("Showing: " + (if (filter == null) "everyone" else Residents.zoneName(filter)), ChatFormatting.AQUA),
            gray("${list.size} Pokémon"), gray("Click to show: " + (if (nextFilter == null) "everyone" else Residents.zoneName(nextFilter))))) { _, _ ->
            goTo { ResidentsGui(player, level, s, 0, nextFilter) }
        }
        if (p < pages - 1) set(51, icon(Items.PAPER, "Next page", "Page ${p + 2} of $pages", color = ChatFormatting.YELLOW)) { _, _ -> goTo { ResidentsGui(player, level, s, p + 1, filter) } }
        set(53, icon(Items.BARRIER, "Close", color = ChatFormatting.RED)) { _, _ -> close() }
        for (i in 45..53) if (container.getItem(i).isEmpty) set(i, FILLER)
    }
}

fun residentIcon(pk: Pokemon, home: String?, out: Boolean, ownerName: String): ItemStack {
    val types = TypeLogic.currentTypes(pk).joinToString("/") { typeName(it) }
    return Gui.icon(
        PokemonItem.from(pk),
        pk.getDisplayName(false).copy().withStyle(if (out) ChatFormatting.GREEN else ChatFormatting.WHITE),
        gray("Lv.${pk.level} · $types"),
        gray("Home: ${Residents.zoneName(home)}"),
        gray(if (out) "Out in the sanctuary right now" else "Somewhere in the sanctuary"),
        gray("Trainer: $ownerName"),
        colored("Click for more", ChatFormatting.YELLOW),
    )
}

// ---------------------------------------------------------------------- one resident

class ResidentGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary, val pokemonId: UUID) :
    Gui(player, 5, Component.literal("Resident")) {
    override fun build() {
        val rt = Residents.runtime(s)
        val entry = Residents.findRoster(rt, pokemonId)
        val pk = rt.live[pokemonId]?.pokemon ?: entry?.pokemon
        if (pk == null) {
            set(13, icon(Items.BARRIER, "This Pokémon has left the sanctuary", color = ChatFormatting.RED))
            set(36, icon(Items.ARROW, "Back", color = ChatFormatting.YELLOW)) { _, _ -> goTo { ResidentsGui(player, level, s, 0, null) } }
            fill(FILLER)
            return
        }
        val ring = ZoneLayout.ring()
        val owner = pk.getOwnerUUID() == player.uuid
        val home = TypeLogic.homeZone(pk, s.residents[pokemonId], ring)
        val out = rt.live.containsKey(pokemonId)

        // relationships
        val rels = s.relationships.entries.mapNotNull { (k, v) ->
            val parts = k.split('|')
            if (parts.size != 2) return@mapNotNull null
            val other = when (pokemonId.toString()) { parts[0] -> parts[1]; parts[1] -> parts[0]; else -> return@mapNotNull null }
            runCatching { UUID.fromString(other) }.getOrNull()?.let { it to v }
        }
        fun nameOf(u: UUID) = Residents.findRoster(rt, u)?.pokemon?.getDisplayName(false)?.string ?: "someone who left"
        val friends = rels.filter { it.second >= 40 }.sortedByDescending { it.second }.take(4).map { nameOf(it.first) }
        val rivals = rels.filter { it.second <= -30 }.sortedBy { it.second }.take(4).map { nameOf(it.first) }
        val types = TypeLogic.currentTypes(pk).joinToString("/") { typeName(it) }
        set(4, icon(
            PokemonItem.from(pk), pk.getDisplayName(false).copy().withStyle(ChatFormatting.GOLD),
            gray("${pk.species.name} · Lv.${pk.level} · $types"),
            gray("Home: ${Residents.zoneName(home)}"),
            gray("Friendship: ${pk.friendship}"),
            gray("Nature: ${runCatching { typeName(pk.nature.name.path) }.getOrDefault("?")} · Ability: ${runCatching { typeName(pk.ability.name) }.getOrDefault("?")}"),
            colored("Friends: " + friends.ifEmpty { listOf("none yet") }.joinToString(", "), ChatFormatting.LIGHT_PURPLE),
            colored("Rivals: " + rivals.ifEmpty { listOf("none") }.joinToString(", "), ChatFormatting.RED),
            gray(if (out) "Out in the sanctuary right now" else "Not out right now"),
        ))

        set(10, icon(Items.POPPY, "Pet", "Give it some love", if (out) "" else "(it needs to be out and near you)", color = ChatFormatting.LIGHT_PURPLE)) { _, _ ->
            Interactions.act(player, pokemonId, "pet", null); refresh()
        }
        set(11, icon(Items.SLIME_BALL, "Play", "Play with it", color = ChatFormatting.YELLOW)) { _, _ ->
            Interactions.act(player, pokemonId, "play", null); refresh()
        }
        set(12, icon(Gui.item("cobblemon:oran_berry", Items.SWEET_BERRIES), "Feed", "Give it a berry from your inventory", color = ChatFormatting.GREEN)) { _, _ ->
            Interactions.act(player, pokemonId, "feed", null); refresh()
        }
        val hasWhistle = player.inventory.contains(ItemStack(ModContent.WHISTLE.get()))
        set(14, icon(ItemStack(ModContent.WHISTLE.get()), colored("Call", if (hasWhistle) ChatFormatting.AQUA else ChatFormatting.DARK_GRAY),
            gray(if (hasWhistle) "Whistle for it to come to you" else "You need a Whistle in your inventory"))) { _, _ ->
            Interactions.act(player, pokemonId, "call", null); close()
        }
        if (owner) {
            set(16, icon(Gui.item("cobblemon:poke_ball", Items.ENDER_PEARL), "Take with you", "Move it to your party", "Put it back in your PC and it goes home", color = ChatFormatting.GOLD)) { _, _ ->
                Interactions.act(player, pokemonId, "take", null); close()
            }
            val options = TypeLogic.zoneTypes(pk).ifEmpty { listOf(ZoneLayout.HUB) + ring.filter { it in s.unlocked } }.distinct()
            if (options.size > 1) {
                set(22, icon(Items.OAK_SIGN, "Set Home", "Choose which biome it lives in", color = ChatFormatting.WHITE))
                options.take(17).forEachIndexed { i, z ->
                    val slot = 23 + i
                    if (z == home) set(slot, icon(zoneIcon(z), Residents.zoneName(z), "Current home", color = ChatFormatting.GREEN, glow = true))
                    else set(slot, icon(zoneIcon(z), Residents.zoneName(z), "Click to move here", color = ChatFormatting.WHITE)) { _, _ ->
                        Interactions.act(player, pokemonId, "home", z); refresh()
                    }
                }
            }
        }
        set(36, icon(Items.ARROW, "Back", color = ChatFormatting.YELLOW)) { _, _ -> goTo { ResidentsGui(player, level, s, 0, null) } }
        set(44, icon(Items.BARRIER, "Close", color = ChatFormatting.RED)) { _, _ -> close() }
        fill(FILLER)
    }
}

// ---------------------------------------------------------------------- biomes

class BiomesGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary) :
    Gui(player, 3, Component.literal("Biomes")) {
    override fun build() {
        val zones = listOf(ZoneLayout.HUB) + ZoneLayout.ring()
        zones.take(26).forEachIndexed { i, zone ->
            val count = s.typeCounts[zone] ?: 0
            if (zone in s.unlocked) {
                val pct = if (zone == ZoneLayout.HUB) 100 else (((ZoneLayout.extent(s, zone) - s.size.hubRadius) / (s.size.radius - s.size.hubRadius)) * 100).toInt()
                set(i, icon(zoneIcon(zone), Residents.zoneName(zone), "$count Pokémon live here", "$pct% grown", color = ChatFormatting.GREEN)) { _, _ ->
                    goTo { ResidentsGui(player, level, s, 0, zone) }
                }
            } else {
                set(i, icon(Items.GRAY_DYE, Residents.zoneName(zone), "Asleep", "Needs a ${typeName(zone)}-type Pokémon", color = ChatFormatting.DARK_GRAY))
            }
        }
        set(26, icon(Items.ARROW, "Back", color = ChatFormatting.YELLOW)) { _, _ -> goTo { HomeGui(player, level, s) } }
        fill(FILLER)
    }
}

// ---------------------------------------------------------------------- events

class EventsGui(player: ServerPlayer, val level: ServerLevel, val s: Sanctuary) :
    Gui(player, 5, Component.literal("Events")) {
    override fun build() {
        val events = s.events.toList().reversed()
        if (events.isEmpty()) set(13, icon(Items.PAPER, "Nothing has happened yet", color = ChatFormatting.GRAY))
        events.take(36).forEachIndexed { i, e ->
            set(i, icon(Items.PAPER, e, if (i == 0) "Most recent" else "", color = ChatFormatting.WHITE))
        }
        set(36, icon(Items.ARROW, "Back", color = ChatFormatting.YELLOW)) { _, _ -> goTo { HomeGui(player, level, s) } }
        fill(FILLER)
    }
}

/** Opens the right window for a player and sanctuary. */
object SanctuaryGuis {
    fun openCore(player: ServerPlayer, level: ServerLevel, s: Sanctuary) {
        when {
            s.state == SanctuaryState.PREVIEW && s.owner == player.uuid -> PreviewGui(player, level, s).open()
            s.state == SanctuaryState.PREVIEW -> say(player, "${s.ownerName} is planning a sanctuary here.")
            s.isMember(player.uuid) -> HomeGui(player, level, s).open()
            else -> say(player, "This sanctuary belongs to ${s.ownerName}.")
        }
    }

    fun openJournal(player: ServerPlayer, section: String = "home") {
        val (level, s) = Sanctuaries.current(player) ?: return say(player, "You don't belong to a sanctuary yet. Craft and place a Sanctuary Core to start one.")
        if (s.state == SanctuaryState.PREVIEW) return openCore(player, level, s)
        when (section) {
            "residents" -> ResidentsGui(player, level, s, 0, null).open()
            else -> HomeGui(player, level, s).open()
        }
    }
}

/** Short feedback above the hotbar. */
fun say(player: ServerPlayer, text: String, color: ChatFormatting = ChatFormatting.WHITE) {
    player.displayClientMessage(Component.literal(text).withStyle(color), true)
}
