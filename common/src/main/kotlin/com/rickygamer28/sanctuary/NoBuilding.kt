package com.rickygamer28.sanctuary

import com.rickygamer28.sanctuary.world.ValleyBlocks
import net.minecraft.ChatFormatting
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ArmorStandItem
import net.minecraft.world.item.BoneMealItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.EndCrystalItem
import net.minecraft.world.item.FireChargeItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.item.HangingEntityItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SolidBucketItem
import net.minecraft.world.level.Level
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Nobody builds in a sanctuary: the valley is the Pokémon's, and the builder looks after it. Players can't place or
 * break blocks there, pour or scoop fluids, light fires, change blocks with tools, or put up item frames and armour
 * stands. The mod's own pieces still work (Pokémon beds, bouncy balls, feeding troughs, the egg nest, the incubator,
 * the basket, the bell): those can be placed and picked up. Opening, using and talking to things is all fine.
 */
object NoBuilding {
    private val lastTold = HashMap<UUID, Long>()

    private fun inSanctuary(level: Level) = SanctuaryDimension.isSanctuary(level)

    /**
     * When a break or a placement is stopped, the player's game has often already changed more than the one block
     * (the other half of a door, bed or tall flower). The server only re-sends that one block, so the rest would stay
     * invisible but solid: everything around it is sent again (next tick, after the server's own answer).
     */
    fun resync(level: Level, player: Player?, pos: BlockPos) {
        val p = player as? ServerPlayer ?: return
        val sl = level as? net.minecraft.server.level.ServerLevel ?: return
        sl.server.execute {
            for (dx in -1..1) for (dy in -2..2) for (dz in -1..1) {
                val q = pos.offset(dx, dy, dz)
                if (!sl.isLoaded(q)) continue
                p.connection.send(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(sl, q))
                sl.getBlockEntity(q)?.let { be -> be.updatePacket?.let { pk -> p.connection.send(pk) } }
            }
        }
    }

    private fun tell(player: Player?) {
        val p = player as? ServerPlayer ?: return
        val now = p.level().gameTime
        if (now - (lastTold[p.uuid] ?: -1000L) < 100) return
        lastTold[p.uuid] = now
        com.rickygamer28.sanctuary.ui.say(p, "You can't build in the sanctuary: the valley belongs to the Pokémon. (Beds, balls, troughs and nests can still be placed.)", ChatFormatting.RED)
    }

    /** Items that change the world without placing a block (fluids, fire, frames, stands, bone meal). */
    private fun changesWorld(stack: ItemStack): Boolean {
        val item = stack.item
        return item is BucketItem || item is SolidBucketItem || item is FlintAndSteelItem || item is FireChargeItem ||
            item is HangingEntityItem || item is ArmorStandItem || item is EndCrystalItem || item is BoneMealItem
    }

    /** May [entity] place [placed] here? (false: it's stopped, and they're told why) */
    fun allowPlace(level: Level, entity: net.minecraft.world.entity.Entity?, placed: BlockState, pos: BlockPos): Boolean {
        if (!inSanctuary(level) || ValleyBlocks.isMods(placed)) return true
        tell(entity as? Player)
        resync(level, entity as? Player, pos)
        return false
    }

    /** Breaking: only the mod's own pieces (the Core has its own rules). Returns false to stop it. */
    fun allowBreak(level: Level, player: Player?, state: BlockState): Boolean {
        if (!inSanctuary(level) || ValleyBlocks.isMods(state)) return true
        tell(player)
        return false
    }

    /** Using [stack] (on a block or in the air): false for what would change the world (buckets, fire, frames…). */
    fun allowItemUse(level: Level, player: Player?, stack: ItemStack): Boolean {
        if (!inSanctuary(level) || !changesWorld(stack)) return true
        tell(player)
        return false
    }

    /** Tilling, stripping logs, making paths, putting out campfires with a shovel… (false: stopped) */
    fun allowToolUse(level: Level, player: Player?): Boolean {
        if (!inSanctuary(level)) return true
        tell(player)
        return false
    }

    /** Items whose use on a block changes it with a tool (the loader without a tool event checks these itself). */
    fun isModifyingTool(stack: ItemStack): Boolean {
        val item = stack.item
        return item is net.minecraft.world.item.AxeItem || item is net.minecraft.world.item.HoeItem || item is net.minecraft.world.item.ShovelItem ||
            item is net.minecraft.world.item.HoneycombItem
    }
}
