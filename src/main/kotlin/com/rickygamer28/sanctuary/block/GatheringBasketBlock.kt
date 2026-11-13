package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.ui.say
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The Gathering Basket: residents bring what they find (their Cobblemon drops, berries, apricorns) to it.
 * There's one storage per sanctuary; every basket placed in the valley opens it.
 */
class GatheringBasketBlock(properties: Properties) : Block(properties) {
    private val shape: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 9.0, 14.0)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = shape

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel || placer !is ServerPlayer) return
        val s = if (SanctuaryDimension.isSanctuary(level)) Sanctuaries.at(level, pos) else null
        if (s == null) return say(placer, "Place the basket inside your sanctuary's valley.", ChatFormatting.GRAY)
        s.basketPos = pos
        Sanctuaries.data(level).setDirty()
        say(placer, "Your residents will bring what they find to this basket.", ChatFormatting.GREEN)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) {
            val s = if (SanctuaryDimension.isSanctuary(level)) Sanctuaries.at(level, pos) else null
            when {
                s == null -> say(player, "This basket only works inside a sanctuary.", ChatFormatting.GRAY)
                !s.isMember(player.uuid) -> say(player, "This basket belongs to ${s.ownerName}'s sanctuary.", ChatFormatting.GRAY)
                else -> {
                    if (s.basketPos == null) { s.basketPos = pos; Sanctuaries.data(level).setDirty() }
                    player.openMenu(SimpleMenuProvider({ id, inv, _ -> ChestMenu.sixRows(id, inv, s.basket) }, Component.literal("Gathering Basket")))
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel && SanctuaryDimension.isSanctuary(level)) {
            // the contents stay with the sanctuary; another basket opens them again
            Sanctuaries.at(level, pos)?.let { if (it.basketPos == pos) { it.basketPos = null; Sanctuaries.data(level).setDirty() } }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}
