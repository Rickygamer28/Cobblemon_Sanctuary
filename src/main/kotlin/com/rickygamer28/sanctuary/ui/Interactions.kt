package com.rickygamer28.sanctuary.ui

import com.cobblemon.mod.common.Cobblemon
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.life.Behaviour
import com.rickygamer28.sanctuary.life.Effects
import com.rickygamer28.sanctuary.life.LiveResident
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.RosterEntry
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** What players can do with residents (from the Journal or by right-clicking a resident with it). */
object Interactions {
    private val BERRIES: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"))
    private const val PET_COOLDOWN = 20L * 60 * 5
    private const val FEED_COOLDOWN = 20L * 60 * 10

    private class Found(val level: ServerLevel, val s: Sanctuary, val entry: RosterEntry?, val live: LiveResident?)

    private fun find(player: ServerPlayer, pokemonId: UUID): Found? {
        for ((level, s) in Sanctuaries.forPlayer(player.server, player.uuid)) {
            val rt = Residents.runtime(s)
            val live = rt.live[pokemonId]
            val entry = Residents.findRoster(rt, pokemonId)
            if (live != null || entry != null) return Found(level, s, entry, live)
        }
        return null
    }

    fun act(player: ServerPlayer, pokemonId: UUID, action: String, arg: String?) {
        val f = find(player, pokemonId) ?: return say(player, "That Pokémon doesn't live in your sanctuary.", net.minecraft.ChatFormatting.RED)
        val pokemon = f.live?.pokemon ?: f.entry?.pokemon ?: return
        val now = f.level.gameTime
        val data = f.s.resident(pokemonId)
        val owner = pokemon.getOwnerUUID() == player.uuid
        val name = pokemon.getDisplayName(false).string
        val live = f.live
        when (action) {
            "menu", "summary" -> return SanctuaryGuis.openResident(player, f.s, pokemonId)
            "pet" -> {
                if (live == null) return say(player, "$name isn't out right now. Go find it in the ${Residents.zoneName(TypeLogic.homeZone(pokemon, data, ZoneLayout.ring()))}!", net.minecraft.ChatFormatting.GRAY)
                if (!near(player, live)) return say(player, "Get closer to $name first.", net.minecraft.ChatFormatting.GRAY)
                Residents.stop(live.entity)
                live.entity.lookControl.setLookAt(player)
                Effects.play(live.entity, Effects.CRY)
                Effects.hearts(live.entity)
                if (now - data.lastPet >= PET_COOLDOWN) {
                    data.lastPet = now
                    if (owner) pokemon.incrementFriendship(2)
                    say(player, "$name loves it!", net.minecraft.ChatFormatting.GREEN)
                } else say(player, "$name enjoys the attention.", net.minecraft.ChatFormatting.GRAY)
            }
            "play" -> {
                if (live == null || !near(player, live)) return say(player, "Get close to $name first.", net.minecraft.ChatFormatting.GRAY)
                live.busyUntil = now + 60
                Residents.walkTo(live.entity, player.blockPosition(), 0.55f, 2)
                live.entity.after(1.0f) { Effects.play(live.entity, Effects.ATTACK); Effects.sparks(live.entity) }
                live.entity.after(2.5f) { Effects.play(live.entity, Effects.CRY); Effects.happy(live.entity) }
                if (now - data.lastPlay >= PET_COOLDOWN) {
                    data.lastPlay = now
                    if (owner) pokemon.incrementFriendship(1)
                }
                say(player, "$name plays with you!", net.minecraft.ChatFormatting.GREEN)
            }
            "feed" -> {
                if (live == null || !near(player, live)) return say(player, "Get close to $name first.", net.minecraft.ChatFormatting.GRAY)
                val slot = (0 until player.inventory.containerSize).firstOrNull { player.inventory.getItem(it).`is`(BERRIES) }
                    ?: return say(player, "You need a berry in your inventory.", net.minecraft.ChatFormatting.RED)
                val berry = player.inventory.getItem(slot)
                val berryName = berry.hoverName.string
                if (!player.isCreative) berry.shrink(1)
                Residents.stop(live.entity)
                live.entity.lookControl.setLookAt(player)
                Effects.play(live.entity, Effects.CRY)
                Effects.happy(live.entity)
                if (now - data.lastFeed >= FEED_COOLDOWN) {
                    data.lastFeed = now
                    if (owner) pokemon.incrementFriendship(3)
                }
                say(player, "$name happily eats the $berryName.", net.minecraft.ChatFormatting.GREEN)
                // nearby residents notice the food
                Residents.runtime(f.s).live.values.filter { it !== live && it.entity.distanceToSqr(live.entity) < 100 && now >= it.busyUntil }.take(3)
                    .forEach { Residents.walkTo(it.entity, player.blockPosition(), 0.45f, 3) }
            }
            "home" -> {
                if (!owner) return say(player, "Only its trainer can change its home.", net.minecraft.ChatFormatting.RED)
                val zone = arg ?: return
                val ring = ZoneLayout.ring()
                val options = TypeLogic.zoneTypes(pokemon).ifEmpty { listOf(ZoneLayout.HUB) + ring }
                if (zone !in options) return say(player, "$name can't live there.", net.minecraft.ChatFormatting.RED)
                data.homeChoice = zone
                if (live != null) {
                    live.home = zone
                    live.second = TypeLogic.secondZone(pokemon, zone)
                    Behaviour.commute(f.level, f.s, live, zone)
                }
                f.s.logEvent("$name moved to the ${Residents.zoneName(zone)}.")
                say(player, "$name will now live in the ${Residents.zoneName(zone)}.", net.minecraft.ChatFormatting.GREEN)
            }
            "take" -> {
                if (!owner) return say(player, "Only its trainer can take it.", net.minecraft.ChatFormatting.RED)
                val party = Cobblemon.storage.getParty(player)
                if (party.occupied() >= 6) return say(player, "Your party is full.", net.minecraft.ChatFormatting.RED)
                val rt = Residents.runtime(f.s)
                if (live != null) Residents.despawn(rt, live)
                pokemon.tetheringId = null
                val pc = Cobblemon.storage.getPC(player)
                if (!pc.remove(pokemon)) return say(player, "Couldn't take $name out of your PC.", net.minecraft.ChatFormatting.RED)
                if (!party.add(pokemon)) {
                    pc.add(pokemon)
                    return say(player, "Couldn't add $name to your party.", net.minecraft.ChatFormatting.RED)
                }
                rt.rosterDirty = true
                say(player, "$name joined your party. Put it back in your PC and it goes home.", net.minecraft.ChatFormatting.GREEN)
            }
            "call" -> {
                if (!player.inventory.contains(ItemStack(ModContent.WHISTLE.get()))) return say(player, "You need a Whistle.", net.minecraft.ChatFormatting.RED)
                val here = Sanctuaries.at(player.serverLevel(), player.blockPosition())
                if (here == null || here.id != f.s.id) return say(player, "You need to be inside the sanctuary.", net.minecraft.ChatFormatting.RED)
                val rt = Residents.runtime(f.s)
                val target = live ?: f.entry?.let { Residents.spawn(f.level, f.s, rt, it, Residents.surface(f.level, player.blockX + 2, player.blockZ + 2)) }
                    ?: return say(player, "$name can't come right now.", net.minecraft.ChatFormatting.RED)
                val zone = ZoneLayout.locate(f.s, player.blockX, player.blockZ)?.zone
                target.visitZone = zone
                target.visitUntil = now + 20L * 90
                target.waypoints.clear()
                Residents.walkTo(target.entity, player.blockPosition(), 0.6f, 2)
                player.serverLevel().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_FLUTE.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1.6f)
                say(player, "You whistle for $name!", net.minecraft.ChatFormatting.GREEN)
            }
            else -> say(player, "Unknown action.", net.minecraft.ChatFormatting.RED)
        }
        Sanctuaries.data(f.level).setDirty()
    }

    private fun near(player: ServerPlayer, r: LiveResident) = r.entity.level() == player.level() && r.entity.distanceToSqr(player) < 12.0 * 12.0
}
