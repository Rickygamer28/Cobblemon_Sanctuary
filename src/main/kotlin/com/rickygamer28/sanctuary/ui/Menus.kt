package com.rickygamer28.sanctuary.ui

import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.life.Preview
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
import java.util.UUID

/** Chat menus with clickable buttons. */
object Menus {
    private const val PAGE = 8

    // ------------------------------------------------------------------ core

    fun core(player: ServerPlayer, level: ServerLevel, s: Sanctuary) {
        val id = s.id
        if (s.state == SanctuaryState.PREVIEW) {
            if (s.owner != player.uuid) return player.sendSystemMessage(Text.info("${s.ownerName} is planning a sanctuary here."))
            Residents.refreshRoster(level, s, Residents.runtime(s), level.gameTime)
            player.sendSystemMessage(Text.title("Sanctuary preview"))
            player.sendSystemMessage(Text.info("The white outline is its area; the blue one is the most it can grow to (keep it clear). Nothing changes until you confirm. Natural ground inside will be reshaped into biomes."))
            player.sendSystemMessage(Text.info("Pokémon in your PC: ${s.residentCount}"))
            val sizes = SanctuarySize.entries.filter { Sizes.allowed(it) }.map { size ->
                val label = size.label + if (size == s.size) " ✔" else ""
                if (Sizes.unlocked(size, s.residentCount)) Text.button(label, "/sanctuary core $id size ${size.name.lowercase()}", "Radius ${size.radius} blocks")
                else Text.disabled(size.label, "Needs ${Sizes.residentsNeeded(size)} Pokémon")
            }
            player.sendSystemMessage(Text.row(Text.plain("Size:"), *sizes.toTypedArray()))
            player.sendSystemMessage(
                Text.row(
                    Text.button("Confirm", "/sanctuary core $id confirm", "Start building the sanctuary", ChatFormatting.GREEN),
                    Text.button("Cancel", "/sanctuary core $id cancel", "Remove the Core; nothing changes", ChatFormatting.RED),
                ),
            )
            return
        }
        if (!s.isMember(player.uuid)) return player.sendSystemMessage(Text.info("This sanctuary belongs to ${s.ownerName}."))
        val rt = Residents.runtime(s)
        Residents.refreshRoster(level, s, rt, level.gameTime)
        player.sendSystemMessage(Text.title("Sanctuary of ${s.ownerName} (${s.size.label})"))
        val growing = SanctuaryGenerator.progress(s)?.let { " — building biomes: $it%" } ?: ""
        player.sendSystemMessage(Text.info("Residents: ${s.residentCount} (${rt.live.size} out now) · Biomes awake: ${s.unlocked.size}/${ZoneLayout.ring().size + 1}$growing"))
        if (s.members.isNotEmpty()) player.sendSystemMessage(Text.info("Members: ${s.ownerName}, " + s.members.joinToString(", ") { s.memberNames[it] ?: "?" }))
        val buttons = ArrayList<Component>()
        buttons += Text.button("Journal", "/sanctuary journal home 0", "Open the journal")
        buttons += Text.button("Get a Journal", "/sanctuary core $id journal", "Get a replacement Journal")
        if (s.owner == player.uuid) {
            val next = Sizes.nextSize(s.size)
            if (next != null) {
                buttons += if (Sizes.unlocked(next, s.residentCount)) Text.button("Expand to ${next.label}", "/sanctuary core $id expand", "Free: you have ${s.residentCount} Pokémon", ChatFormatting.GREEN)
                else Text.disabled("Expand to ${next.label}", "Needs ${Sizes.residentsNeeded(next)} Pokémon (you have ${s.residentCount})")
            }
            buttons += Text.button("Rebuild biomes", "/sanctuary core $id rebuild", "Go over the whole area again (natural ground only)", ChatFormatting.GOLD)
            buttons += Text.suggest("Invite", "/sanctuary invite ", "Invite a player to share this sanctuary")
            if (Preview.maxAllowed().radius > s.size.radius) buttons += Text.button("Show outline", "/sanctuary core $id outline", "Show the current and maximum area")
        } else {
            buttons += Text.button("Leave", "/sanctuary core $id leave", "Leave this sanctuary", ChatFormatting.RED)
        }
        player.sendSystemMessage(Text.row(*buttons.toTypedArray()))
    }

    // ------------------------------------------------------------------ journal

    fun journal(player: ServerPlayer, section: String, page: Int) {
        val (level, s) = Sanctuaries.current(player)
            ?: return player.sendSystemMessage(Text.info("You don't belong to a sanctuary yet. Craft and place a Sanctuary Core to start one."))
        val rt = Residents.runtime(s)
        if (rt.roster.isEmpty()) Residents.refreshRoster(level, s, rt, level.gameTime)
        val nav = Text.row(
            Text.button("Overview", "/sanctuary journal home 0", "Overview"),
            Text.button("Residents", "/sanctuary journal residents 0", "Everyone living here"),
            Text.button("Biomes", "/sanctuary journal biomes 0", "The biomes and how big they are"),
            Text.button("Events", "/sanctuary journal events 0", "What happened recently"),
        )
        when (section) {
            "residents" -> residents(player, s, page)
            "biomes" -> biomes(player, s)
            "events" -> {
                player.sendSystemMessage(Text.title("Sanctuary Journal — Events"))
                if (s.events.isEmpty()) player.sendSystemMessage(Text.info("Nothing has happened yet."))
                s.events.toList().takeLast(15).forEach { player.sendSystemMessage(Text.info("• $it")) }
            }
            else -> {
                player.sendSystemMessage(Text.title("Sanctuary Journal — ${s.ownerName}'s sanctuary"))
                player.sendSystemMessage(Text.info("${s.size.label} · ${s.residentCount} residents · ${rt.live.size} out now · ${s.unlocked.size} biomes awake"))
                val recent = s.events.toList().takeLast(5)
                if (recent.isNotEmpty()) {
                    player.sendSystemMessage(Text.plain("Recently:"))
                    recent.forEach { player.sendSystemMessage(Text.info("• $it")) }
                }
                player.sendSystemMessage(Text.info("Tip: right-click a resident while holding the Journal to interact with it."))
            }
        }
        player.sendSystemMessage(nav)
    }

    private fun residents(player: ServerPlayer, s: Sanctuary, page: Int) {
        val rt = Residents.runtime(s)
        val ring = ZoneLayout.ring()
        val list = rt.roster.sortedWith(compareBy({ TypeLogic.homeZone(it.pokemon, s.residents[it.pokemon.uuid], ring) ?: "~" }, { it.pokemon.species.name }))
        val pages = ((list.size + PAGE - 1) / PAGE).coerceAtLeast(1)
        val p = page.coerceIn(0, pages - 1)
        val whistle = player.inventory.contains(net.minecraft.world.item.ItemStack(ModContent.WHISTLE.get()))
        player.sendSystemMessage(Text.title("Residents (${list.size}) — page ${p + 1}/$pages"))
        for (e in list.drop(p * PAGE).take(PAGE)) {
            val pk = e.pokemon
            val home = TypeLogic.homeZone(pk, s.residents[pk.uuid], ring)
            val out = if (rt.live.containsKey(pk.uuid)) " (out)" else ""
            val line = Component.literal("• ").append(pk.getDisplayName(false)).append(Text.info(" Lv.${pk.level} — ${Residents.zoneName(home)}$out "))
            line.append(Text.button("Info", "/sanctuary act ${pk.uuid} summary", "Show details"))
            if (whistle) line.append(" ").append(Text.button("Call", "/sanctuary act ${pk.uuid} call", "Whistle for this Pokémon", ChatFormatting.YELLOW))
            player.sendSystemMessage(line)
        }
        val prev = if (p > 0) Text.button("<", "/sanctuary journal residents ${p - 1}", "Previous page") else Text.disabled("<", "")
        val next = if (p < pages - 1) Text.button(">", "/sanctuary journal residents ${p + 1}", "Next page") else Text.disabled(">", "")
        player.sendSystemMessage(Text.row(prev, next))
    }

    private fun biomes(player: ServerPlayer, s: Sanctuary) {
        player.sendSystemMessage(Text.title("Biomes"))
        val hubCount = s.typeCounts[ZoneLayout.HUB] ?: 0
        player.sendSystemMessage(Text.good("• Normal hub — $hubCount Pokémon"))
        for (zone in ZoneLayout.ring()) {
            val count = s.typeCounts[zone] ?: 0
            if (zone in s.unlocked) {
                val ext = ZoneLayout.extent(s, zone)
                val pct = (((ext - s.size.hubRadius) / (s.size.radius - s.size.hubRadius)) * 100).toInt()
                player.sendSystemMessage(Text.good("• ${Residents.zoneName(zone)} — $count Pokémon, $pct% grown"))
            } else {
                player.sendSystemMessage(Text.info("• ${Residents.zoneName(zone)} — asleep (needs a ${zone.replaceFirstChar { it.uppercase() }}-type Pokémon)"))
            }
        }
    }

    // ------------------------------------------------------------------ residents

    fun resident(player: ServerPlayer, s: Sanctuary, pokemon: Pokemon) {
        val owner = pokemon.getOwnerUUID() == player.uuid
        val id = pokemon.uuid
        val ring = ZoneLayout.ring()
        val home = TypeLogic.homeZone(pokemon, s.residents[id], ring)
        player.sendSystemMessage(Component.empty().append(pokemon.getDisplayName(false).copy().withStyle(ChatFormatting.GOLD)).append(Text.info(" — lives in the ${Residents.zoneName(home)}")))
        val row = arrayListOf<Component>(
            Text.button("Pet", "/sanctuary act $id pet", "Pet it", ChatFormatting.LIGHT_PURPLE),
            Text.button("Play", "/sanctuary act $id play", "Play with it", ChatFormatting.YELLOW),
            Text.button("Feed", "/sanctuary act $id feed", "Give it a berry from your inventory", ChatFormatting.GREEN),
            Text.button("Info", "/sanctuary act $id summary", "Show details"),
        )
        if (owner) row += Text.button("Take with you", "/sanctuary act $id take", "Move it to your party", ChatFormatting.GOLD)
        player.sendSystemMessage(Text.row(*row.toTypedArray()))
        if (owner) {
            val options = TypeLogic.zoneTypes(pokemon).ifEmpty { listOf(ZoneLayout.HUB) + ring.filter { it in s.unlocked } }
            if (options.size > 1) {
                val homes = options.distinct().map { z ->
                    if (z == home) Text.disabled(Residents.zoneName(z) + " ✔", "Current home")
                    else Text.button(Residents.zoneName(z), "/sanctuary act $id home $z", "Move its home here")
                }
                player.sendSystemMessage(Text.row(Text.plain("Set Home:"), *homes.toTypedArray()))
            }
        }
    }

    fun summary(player: ServerPlayer, s: Sanctuary, pokemon: Pokemon) {
        val rt = Residents.runtime(s)
        val ring = ZoneLayout.ring()
        val id = pokemon.uuid
        val home = TypeLogic.homeZone(pokemon, s.residents[id], ring)
        player.sendSystemMessage(Component.empty().append(pokemon.getDisplayName(false).copy().withStyle(ChatFormatting.GOLD)).append(Text.info(" — ${pokemon.species.name}, Lv.${pokemon.level}")))
        val types = TypeLogic.currentTypes(pokemon).joinToString("/") { it.replaceFirstChar { c -> c.uppercase() } }
        player.sendSystemMessage(Text.info("Type: $types · Home: ${Residents.zoneName(home)} · Friendship: ${pokemon.friendship}"))
        player.sendSystemMessage(Text.info("Nature: ${runCatching { pokemon.nature.name.path }.getOrDefault("?")} · Ability: ${runCatching { pokemon.ability.name }.getOrDefault("?")}"))
        val rels = s.relationships.entries.mapNotNull { (k, v) ->
            val parts = k.split('|')
            if (parts.size != 2) return@mapNotNull null
            val other = when (id.toString()) { parts[0] -> parts[1]; parts[1] -> parts[0]; else -> return@mapNotNull null }
            runCatching { UUID.fromString(other) }.getOrNull()?.let { it to v }
        }
        fun nameOf(u: UUID) = Residents.findRoster(rt, u)?.pokemon?.getDisplayName(false)?.string ?: "someone who left"
        val friends = rels.filter { it.second >= 40 }.sortedByDescending { it.second }.take(4).map { nameOf(it.first) }
        val rivals = rels.filter { it.second <= -30 }.sortedBy { it.second }.take(4).map { nameOf(it.first) }
        player.sendSystemMessage(Text.info("Friends: " + friends.ifEmpty { listOf("none yet") }.joinToString(", ")))
        player.sendSystemMessage(Text.info("Rivals: " + rivals.ifEmpty { listOf("none") }.joinToString(", ")))
    }
}
