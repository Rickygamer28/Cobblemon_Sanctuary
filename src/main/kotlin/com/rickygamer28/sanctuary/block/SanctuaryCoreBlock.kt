package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.ui.SanctuaryGuis
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** The heart of a sanctuary. Placing it shows a preview; right-clicking opens its menu. */
class SanctuaryCoreBlock(properties: Properties) : Block(properties) {

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level is ServerLevel && placer is ServerPlayer) Sanctuaries.onCorePlaced(level, pos, placer)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) {
            val s = Sanctuaries.data(level).at(pos)
            if (s != null) SanctuaryGuis.openCore(player, level, s)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel) Sanctuaries.onCoreRemoved(level, pos)
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}
