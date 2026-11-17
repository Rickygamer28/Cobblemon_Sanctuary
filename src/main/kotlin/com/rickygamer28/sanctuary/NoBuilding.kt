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
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
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

    fun onPlace(event: BlockEvent.EntityPlaceEvent) {
        val level = event.level as? Level ?: return
        if (!inSanctuary(level) || ValleyBlocks.isMods(event.placedBlock)) return
        event.isCanceled = true
        tell(event.entity as? Player)
    }

    /** Breaking: only the mod's own pieces (the Core has its own rules). */
    fun onBreak(event: BlockEvent.BreakEvent): Boolean {
        val level = event.level as? Level ?: return false
        if (!inSanctuary(level) || ValleyBlocks.isMods(event.state)) return false
        event.isCanceled = true
        tell(event.player)
        return true
    }

    fun onUseOnBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!inSanctuary(event.level) || !changesWorld(event.itemStack)) return
        event.useItem = TriState.FALSE
        tell(event.entity)
    }

    fun onUseItem(event: PlayerInteractEvent.RightClickItem) {
        if (!inSanctuary(event.level) || !changesWorld(event.itemStack)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
        tell(event.entity)
    }

    /** Tilling, stripping logs, making paths, putting out campfires with a shovel… */
    fun onToolUse(event: BlockEvent.BlockToolModificationEvent) {
        val level = event.level as? Level ?: return
        if (!inSanctuary(level) || event.isSimulated) return
        event.isCanceled = true
        tell(event.player)
    }
}
