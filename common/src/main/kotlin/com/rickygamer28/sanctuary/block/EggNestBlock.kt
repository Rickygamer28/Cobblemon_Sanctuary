package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.ui.SanctuaryGuis
import com.rickygamer28.sanctuary.ui.say
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/** A nest with an egg in it, made by residents. Right-click to open the Nest Registry. */
class EggNestBlock(properties: Properties) : Block(properties) {
    private val shape: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 9.0, 14.0)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = shape

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) {
            val s = Sanctuaries.at(level, pos)
            when {
                s == null -> say(player, "An abandoned nest.")
                !s.isMember(player.uuid) -> say(player, "This nest belongs to ${s.ownerName}'s sanctuary.")
                else -> SanctuaryGuis.send(player, s, "nest", open = true)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
