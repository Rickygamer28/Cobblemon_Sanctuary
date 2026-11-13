package com.rickygamer28.sanctuary

import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuarySize
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.life.Behaviour
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.Sizes
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.SanctuaryGuis
import com.rickygamer28.sanctuary.ui.Text
import com.rickygamer28.sanctuary.ui.say
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** Creating, finding, changing and travelling to sanctuaries. */
object Sanctuaries {
    /** How far the mountain rim reaches beyond the sanctuary's edge. */
    const val RIM = 48

    /** Pending invites: invited player -> sanctuary id. */
    private val invites = HashMap<UUID, Pair<UUID, Long>>()

    fun data(server: MinecraftServer): SanctuaryData = SanctuaryData.get(server)
    fun data(level: ServerLevel): SanctuaryData = SanctuaryData.get(level.server)

    fun all(server: MinecraftServer): List<Pair<ServerLevel, Sanctuary>> {
        val level = SanctuaryDimension.level(server) ?: return emptyList()
        return data(server).sanctuaries.values.map { level to it }
    }

    fun find(server: MinecraftServer, id: UUID): Pair<ServerLevel, Sanctuary>? = all(server).firstOrNull { it.second.id == id }

    fun forPlayer(server: MinecraftServer, player: UUID): List<Pair<ServerLevel, Sanctuary>> =
        all(server).filter { it.second.isMember(player) }

    /** The sanctuary the player is in, or else the first one they belong to. */
    fun current(player: ServerPlayer): Pair<ServerLevel, Sanctuary>? {
        if (SanctuaryDimension.isSanctuary(player.level())) {
            val here = data(player.server).near(player.blockPosition(), RIM)
            if (here != null && here.isMember(player.uuid)) return player.serverLevel() to here
        }
        return forPlayer(player.server, player.uuid).firstOrNull { it.second.owner == player.uuid }
            ?: forPlayer(player.server, player.uuid).firstOrNull()
    }

    /** The sanctuary whose valley the position (in the Sanctuary dimension) belongs to. */
    fun at(level: ServerLevel, pos: BlockPos): Sanctuary? =
        if (SanctuaryDimension.isSanctuary(level)) data(level).near(pos, RIM) else null

    // ------------------------------------------------------------------ the Core

    fun onCorePlaced(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val data = data(level)
        fun refuse(msg: String) {
            say(player, msg, ChatFormatting.RED)
            level.removeBlock(pos, false)
            if (!player.isCreative) player.inventory.add(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()))
        }
        if (SanctuaryDimension.isSanctuary(level)) return refuse("Sanctuary Cores are placed in your own world; they lead here.")

        val owned = forPlayer(level.server, player.uuid).firstOrNull { it.second.owner == player.uuid }?.second
        if (owned != null) {
            // move the portal of your existing sanctuary here
            owned.portalDim = level.dimension().location().toString()
            owned.portalPos = pos
            data.setDirty()
            giveJournalOnce(player)
            say(player, "This Core now leads to your sanctuary.", ChatFormatting.GREEN)
            player.server.execute { SanctuaryGuis.openCore(player, level, owned) }
            return
        }
        if (forPlayer(level.server, player.uuid).size >= SanctuaryConfig.maxSanctuariesPerPlayer.get()) {
            return refuse("You already belong to a sanctuary. Use your Journal to travel there.")
        }
        val slot = data.nextSlot++
        val s = Sanctuary(
            id = UUID.randomUUID(),
            owner = player.uuid,
            ownerName = player.gameProfile.name,
            center = SanctuaryDimension.centerForSlot(slot),
            size = Sizes.smallestAllowed(),
            state = SanctuaryState.PREVIEW,
            seed = level.random.nextLong(),
            slot = slot,
        )
        s.portalDim = level.dimension().location().toString()
        s.portalPos = pos
        data.sanctuaries[s.id] = s
        s.basket.addListener { data.setDirty() }
        data.setDirty()
        SanctuaryDimension.level(level.server)?.let { Residents.refreshRoster(it, s, Residents.runtime(s), it.gameTime) }
        giveJournalOnce(player)
        player.server.execute { SanctuaryGuis.openCore(player, level, s) }
    }

    /** Breaking a portal Core only disconnects it; the sanctuary and its Pokémon stay. */
    fun onCoreRemoved(level: ServerLevel, pos: BlockPos) {
        if (SanctuaryDimension.isSanctuary(level)) return
        val data = data(level)
        val s = data.atPortal(level.dimension().location().toString(), pos) ?: return
        s.portalDim = null
        s.portalPos = null
        if (s.state == SanctuaryState.PREVIEW) {
            Residents.shutdown(s.id)
            data.sanctuaries.remove(s.id)
        }
        data.setDirty()
    }

    /** The sanctuary a Core block leads to (portal in your world) or belongs to (return Core in the hub). */
    fun forCore(level: ServerLevel, pos: BlockPos): Sanctuary? {
        val data = data(level)
        return if (SanctuaryDimension.isSanctuary(level)) data.at(pos) ?: data.near(pos, 4)
        else data.atPortal(level.dimension().location().toString(), pos)
    }

    /** Keeps the return Core standing in the middle of the hub. */
    fun ensureCenterCore(level: ServerLevel, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE || !level.isLoaded(s.center)) return
        val state = level.getBlockState(s.center)
        if (!state.`is`(ModContent.SANCTUARY_CORE.get())) level.setBlock(s.center, ModContent.SANCTUARY_CORE.get().defaultBlockState(), 3)
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
        say(player, "You received a Sanctuary Journal.", ChatFormatting.GREEN)
    }

    // ------------------------------------------------------------------ owner actions

    fun setSize(s: Sanctuary, player: ServerPlayer, size: SanctuarySize) {
        if (s.owner != player.uuid || s.state != SanctuaryState.PREVIEW) return
        if (!Sizes.unlocked(size, s.residentCount)) {
            return say(player, "${size.label} needs ${Sizes.residentsNeeded(size)} Pokémon (you have ${s.residentCount}).", ChatFormatting.RED)
        }
        s.size = size
        data(player.server).setDirty()
    }

    fun confirm(s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid || s.state != SanctuaryState.PREVIEW) return
        s.state = SanctuaryState.ACTIVE
        s.rebuild()
        s.logEvent("${s.ownerName} founded the sanctuary.")
        data(player.server).setDirty()
        player.sendSystemMessage(Text.good("Your sanctuary valley is being made! Right-click the Core (or use the Journal) to travel there."))
    }

    fun cancel(level: ServerLevel, s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid || s.state != SanctuaryState.PREVIEW) return
        val portal = s.portalPos
        if (portal != null && !SanctuaryDimension.isSanctuary(level)) level.removeBlock(portal, false) // removes the record too
        if (!player.isCreative) player.inventory.add(ItemStack(ModContent.SANCTUARY_CORE_ITEM.get()))
        say(player, "Sanctuary cancelled.", ChatFormatting.GRAY)
    }

    fun expand(s: Sanctuary, player: ServerPlayer) {
        if (s.owner != player.uuid) return say(player, "Only the owner can do that.", ChatFormatting.RED)
        val next = Sizes.nextSize(s.size) ?: return say(player, "Already at the largest size.", ChatFormatting.RED)
        if (!Sizes.unlocked(next, s.residentCount)) {
            return say(player, "${next.label} needs ${Sizes.residentsNeeded(next)} Pokémon living here (now ${s.residentCount}).", ChatFormatting.RED)
        }
        s.oldWallRadius = s.size.radius
        s.size = next
        s.rebuild()
        s.logEvent("The sanctuary grew to ${next.label}!")
        data(player.server).setDirty()
        say(player, "The valley is growing to ${next.label}.", ChatFormatting.GREEN)
    }

    fun travel(player: ServerPlayer, s: Sanctuary) {
        if (s.state != SanctuaryState.ACTIVE) return say(player, "Confirm the sanctuary first.", ChatFormatting.RED)
        if (!s.isMember(player.uuid)) return say(player, "This isn't your sanctuary.", ChatFormatting.RED)
        SanctuaryDimension.travel(player, s)
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
                .append(Text.button("Accept", "/sanctuary accept", "Join the sanctuary", ChatFormatting.GREEN)),
        )
    }

    fun accept(player: ServerPlayer) {
        val (id, at) = invites.remove(player.uuid) ?: return player.sendSystemMessage(Text.error("You have no invite."))
        if (player.server.tickCount - at > 20L * 60 * 10) return player.sendSystemMessage(Text.error("That invite has expired."))
        if (forPlayer(player.server, player.uuid).size >= SanctuaryConfig.maxSanctuariesPerPlayer.get()) {
            return player.sendSystemMessage(Text.error("You already belong to the maximum number of sanctuaries."))
        }
        val (_, s) = find(player.server, id) ?: return player.sendSystemMessage(Text.error("That sanctuary no longer exists."))
        s.members += player.uuid
        s.memberNames[player.uuid] = player.gameProfile.name
        s.logEvent("${player.gameProfile.name} joined the sanctuary.")
        Residents.runtime(s).rosterDirty = true
        data(player.server).setDirty()
        player.sendSystemMessage(Text.good("You joined ${s.ownerName}'s sanctuary. Use your Journal to travel there."))
        giveJournal(player)
    }

    fun leave(player: ServerPlayer, s: Sanctuary) {
        if (s.owner == player.uuid) return say(player, "Owners can't leave their own sanctuary.", ChatFormatting.RED)
        if (!s.members.remove(player.uuid)) return
        s.memberNames.remove(player.uuid)
        val rt = Residents.runtime(s)
        rt.live.values.filter { it.owner == player.uuid }.forEach { Residents.despawn(rt, it) }
        rt.rosterDirty = true
        s.logEvent("${player.gameProfile.name} left the sanctuary.")
        data(player.server).setDirty()
        say(player, "You left the sanctuary. Your Pokémon are back in your PC.", ChatFormatting.GRAY)
    }

    /** The owner removes a member. Returns their name, or null if they weren't a member. */
    fun removeMember(s: Sanctuary, member: UUID): String? {
        if (member == s.owner || !s.members.remove(member)) return null
        val name = s.memberNames.remove(member) ?: "?"
        val rt = Residents.runtime(s)
        rt.live.values.filter { it.owner == member }.forEach { Residents.despawn(rt, it) }
        rt.rosterDirty = true
        s.logEvent("$name no longer lives in the sanctuary.")
        return name
    }

    // ------------------------------------------------------------------ hub bell

    fun ringBell(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val s = at(level, pos) ?: return say(player, "The bell must be inside your sanctuary.", ChatFormatting.RED)
        if (!s.isMember(player.uuid)) return say(player, "This isn't your sanctuary.", ChatFormatting.RED)
        val rt = Residents.runtime(s)
        rt.gatherUntil = level.gameTime + 20L * 45
        for (r in rt.live.values) {
            r.visitZone = null
            r.waypoints.clear()
            Behaviour.walkToZone(level, s, r, ZoneLayout.HUB, 0.5f)
        }
        s.logEvent("${player.gameProfile.name} rang the hub bell.")
        say(player, "Your Pokémon gather in the hub!", ChatFormatting.GREEN)
    }
}
