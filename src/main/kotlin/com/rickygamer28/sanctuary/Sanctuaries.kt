package com.rickygamer28.sanctuary

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.life.Behaviour
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.Sizes
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.Menus
import com.rickygamer28.sanctuary.ui.Text
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** Creating, finding, changing and removing sanctuaries. */
object Sanctuaries {
    /** Pending invites: invited player -> sanctuary id. */
    private val invites = HashMap<UUID, Pair<UUID, Long>>()

    fun data(level: ServerLevel): SanctuaryData = SanctuaryData.get(level)

    fun all(server: MinecraftServer): List<Pair<ServerLevel, Sanctuary>> =
        server.allLevels.flatMap { level -> data(level).sanctuaries.values.map { level to it } }

    fun find(server: MinecraftServer, id: UUID): Pair<ServerLevel, Sanctuary>? = all(server).firstOrNull { it.second.id == id }

    fun forPlayer(server: MinecraftServer, player: UUID): List<Pair<ServerLevel, Sanctuary>> =
        all(server).filter { it.second.isMember(player) }

    /** The sanctuary the player is standing in, or else the first one they belong to. */
    fun current(player: ServerPlayer): Pair<ServerLevel, Sanctuary>? {
        val level = player.serverLevel()
        data(level).containing(player.blockPosition())?.takeIf { it.isMember(player.uuid) }?.let { return level to it }
        return forPlayer(player.server, player.uuid).firstOrNull()
    }

    // ------------------------------------------------------------------ core block

    fun onCorePlaced(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val data = data(level)
        fun refuse(msg: String) {
            player.sendSystemMessage(Text.error(msg))
            level.removeBlock(pos, false)
            if (!player.isCreative) player.inventory.add(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()))
        }
        if (data.overlapping(pos, SanctuarySize.MAX_RADIUS).isNotEmpty()) {
            refuse("Too close to another sanctuary. Sanctuaries need room to grow.")
            return
        }
        if (forPlayer(player.server, player.uuid).size >= SanctuaryConfig.maxSanctuariesPerPlayer.get()) {
            refuse("You already own or belong to the maximum number of sanctuaries.")
            return
        }
        val s = Sanctuary(
            id = UUID.randomUUID(),
            owner = player.uuid,
            ownerName = player.gameProfile.name,
            center = pos,
            size = Sizes.smallestAllowed(),
            state = SanctuaryState.PREVIEW,
            seed = level.random.nextLong(),
        )
        data.sanctuaries[s.id] = s
        data.setDirty()
        Residents.refreshRoster(level, s, Residents.runtime(s), level.gameTime)
        giveJournalOnce(player)
        player.server.execute { com.rickygamer28.sanctuary.ui.SanctuaryGuis.openCore(player, level, s) }
    }

    fun onCoreRemoved(level: ServerLevel, pos: BlockPos) {
        val data = data(level)
        val s = data.at(pos) ?: return
        Residents.shutdown(s.id)
        data.sanctuaries.remove(s.id)
        data.setDirty()
        level.server.playerList.getPlayer(s.owner)?.sendSystemMessage(Text.info("Your sanctuary was removed. Your Pokémon are back in your PC. The land keeps its look."))
    }

    fun giveJournalOnce(player: ServerPlayer) {
        val tag = player.persistentData
        if (tag.getBoolean("cobblemon_sanctuary_journal")) return
        tag.putBoolean("cobblemon_sanctuary_journal", true)
        giveJournal(player)
    }

    fun giveJournal(player: ServerPlayer) {
        val stack = ItemStack(ModContent.JOURNAL.get())
        if (!player.inventory.add(stack)) player.drop(stack, false)
        player.sendSystemMessage(Text.good("You received a Sanctuary Journal."))
    }

    // ------------------------------------------------------------------ owner actions

    fun setSize(level: ServerLevel, s: Sanctuary, player: ServerPlayer, size: SanctuarySize) {
        if (s.owner != player.uuid) return player.sendSystemMessage(Text.error("Only the owner can do that."))
        if (s.state != SanctuaryState.PREVIEW) return player.sendSystemMessage(Text.error("Use Expand to grow an existing sanctuary."))
        if (!Sizes.unlocked(size, s.residentCount)) {
            return player.sendSystemMessage(Text.error("${size.label} needs ${Sizes.residentsNeeded(size)} Pokémon (you have ${s.residentCount})."))
        }
        s.size = size
        data(level).setDirty()
    }

    fun confirm(level: ServerLevel, s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid || s.state != SanctuaryState.PREVIEW) return
        s.state = SanctuaryState.ACTIVE
        s.generationDirty = true
        s.logEvent("${s.ownerName} founded the sanctuary.")
        data(level).setDirty()
        player.sendSystemMessage(Text.good("The sanctuary is growing! Your Pokémon will move in as their biomes wake up."))
    }

    fun cancel(level: ServerLevel, s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid || s.state != SanctuaryState.PREVIEW) return
        level.removeBlock(s.center, false) // triggers onCoreRemoved
        if (!player.isCreative) player.inventory.add(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()))
        player.sendSystemMessage(Text.info("Sanctuary cancelled. Nothing was changed."))
    }

    fun expand(level: ServerLevel, s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid) return player.sendSystemMessage(Text.error("Only the owner can do that."))
        val next = Sizes.nextSize(s.size) ?: return player.sendSystemMessage(Text.error("Already at the largest size."))
        if (!Sizes.unlocked(next, s.residentCount)) {
            return player.sendSystemMessage(Text.error("${next.label} needs ${Sizes.residentsNeeded(next)} Pokémon living here (now ${s.residentCount})."))
        }
        s.size = next
        s.generationDirty = true
        s.logEvent("The sanctuary grew to ${next.label}!")
        data(level).setDirty()
        player.sendSystemMessage(Text.good("The sanctuary is expanding to ${next.label}."))
    }

    // ------------------------------------------------------------------ members

    fun invite(player: ServerPlayer, target: ServerPlayer) {
        val (_, s) = forPlayer(player.server, player.uuid).firstOrNull { it.second.owner == player.uuid }
            ?: return player.sendSystemMessage(Text.error("You don't own a sanctuary."))
        if (s.isMember(target.uuid)) return player.sendSystemMessage(Text.error("${target.gameProfile.name} already lives there."))
        invites[target.uuid] = s.id to player.server.tickCount.toLong()
        player.sendSystemMessage(Text.good("Invited ${target.gameProfile.name}."))
        target.sendSystemMessage(
            Text.info("${player.gameProfile.name} invited you to share their sanctuary. Your PC Pokémon would live there too. ")
                .append(Text.button("Accept", "/sanctuary accept", "Join the sanctuary", net.minecraft.ChatFormatting.GREEN)),
        )
    }

    fun accept(player: ServerPlayer) {
        val (id, at) = invites.remove(player.uuid) ?: return player.sendSystemMessage(Text.error("You have no invite."))
        if (player.server.tickCount - at > 20L * 60 * 10) return player.sendSystemMessage(Text.error("That invite has expired."))
        if (forPlayer(player.server, player.uuid).size >= SanctuaryConfig.maxSanctuariesPerPlayer.get()) {
            return player.sendSystemMessage(Text.error("You already belong to the maximum number of sanctuaries."))
        }
        val (level, s) = find(player.server, id) ?: return player.sendSystemMessage(Text.error("That sanctuary no longer exists."))
        s.members += player.uuid
        s.memberNames[player.uuid] = player.gameProfile.name
        s.logEvent("${player.gameProfile.name} joined the sanctuary.")
        Residents.runtime(s).rosterDirty = true
        data(level).setDirty()
        player.sendSystemMessage(Text.good("You joined ${s.ownerName}'s sanctuary."))
        giveJournal(player)
    }

    fun leave(player: ServerPlayer, s: Sanctuary, level: ServerLevel) {
        if (s.owner == player.uuid) return player.sendSystemMessage(Text.error("Owners remove a sanctuary by breaking its Core."))
        if (!s.members.remove(player.uuid)) return
        s.memberNames.remove(player.uuid)
        val rt = Residents.runtime(s)
        rt.live.values.filter { it.owner == player.uuid }.forEach { Residents.despawn(rt, it) }
        rt.rosterDirty = true
        s.logEvent("${player.gameProfile.name} left the sanctuary.")
        data(level).setDirty()
        player.sendSystemMessage(Text.info("You left the sanctuary. Your Pokémon are back in your PC."))
    }

    // ------------------------------------------------------------------ hub bell

    fun ringBell(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val s = data(level).containing(pos) ?: return player.sendSystemMessage(Text.error("The bell must be inside a sanctuary."))
        if (!s.isMember(player.uuid)) return player.sendSystemMessage(Text.error("This isn't your sanctuary."))
        val rt = Residents.runtime(s)
        rt.gatherUntil = level.gameTime + 20L * 45
        for (r in rt.live.values) {
            r.visitZone = null
            r.waypoints.clear()
            Behaviour.walkToZone(level, s, r, ZoneLayout.HUB, 0.5f)
        }
        s.logEvent("${player.gameProfile.name} rang the hub bell.")
        player.sendSystemMessage(Text.good("Your Pokémon gather in the hub!"))
    }
}
