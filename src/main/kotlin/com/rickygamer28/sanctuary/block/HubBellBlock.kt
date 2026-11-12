package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.Sanctuaries
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Ring it and every resident nearby gathers in the hub for a little while. */
class HubBellBlock(properties: Properties) : Block(properties) {
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (level is ServerLevel && player is ServerPlayer) {
            level.playSound(null, pos, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0f, 1.0f)
            Sanctuaries.ringBell(level, pos, player)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }
}
