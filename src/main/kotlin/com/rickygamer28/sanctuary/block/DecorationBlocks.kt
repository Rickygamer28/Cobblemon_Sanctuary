package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.ui.say
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A decoration players place in their valley (a bed, a ball, a feeding trough): residents use it like the biome's
 * own spots, and every resident likes it.
 */
abstract class DecorationBlock(properties: Properties, private val kind: String, private val shape: VoxelShape) : HorizontalDirectionalBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)) }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(FACING) }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = shape

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level !is ServerLevel) return
        val s = if (SanctuaryDimension.isSanctuary(level)) Sanctuaries.at(level, pos) else null
        if (s == null) {
            if (placer is ServerPlayer) say(placer, "Residents can only use this inside your sanctuary's valley.", ChatFormatting.GRAY)
            return
        }
        s.props[pos.asLong()] = kind
        Sanctuaries.data(level).setDirty()
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel && SanctuaryDimension.isSanctuary(level)) {
            Sanctuaries.at(level, pos)?.let { if (it.props.remove(pos.asLong()) != null) Sanctuaries.data(level).setDirty() }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}

class PokemonBedBlock(properties: Properties) : DecorationBlock(properties, "player_bed", box(0.0, 0.0, 0.0, 16.0, 6.0, 16.0)) {
    override fun codec() = simpleCodec(::PokemonBedBlock)
}

class BouncyBallBlock(properties: Properties) : DecorationBlock(properties, "player_toy", box(4.0, 0.0, 4.0, 12.0, 8.0, 12.0)) {
    override fun codec() = simpleCodec(::BouncyBallBlock)
}

/** Fill it with berries (or apples, sweet berries…); residents come to eat from it. */
class FeedingTroughBlock(properties: Properties) : DecorationBlock(properties, "player_trough", box(1.0, 0.0, 3.0, 15.0, 7.0, 13.0)) {
    override fun codec() = simpleCodec(::FeedingTroughBlock)

    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FOOD, 0)) }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(FACING, FOOD) }

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): ItemInteractionResult {
        if (!isFood(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val food = state.getValue(FOOD)
        if (food >= MAX) {
            if (player is ServerPlayer) say(player, "The trough is full.", ChatFormatting.GRAY)
            return ItemInteractionResult.sidedSuccess(level.isClientSide)
        }
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(FOOD, food + 1), UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 1f, 1f)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        const val MAX = 4
        val FOOD: IntegerProperty = IntegerProperty.create("food", 0, MAX)
        private val BERRIES: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"))

        fun isFood(stack: ItemStack) = stack.`is`(BERRIES) || stack.`is`(Items.APPLE) || stack.`is`(Items.SWEET_BERRIES) ||
            stack.`is`(Items.GLOW_BERRIES) || stack.`is`(Items.CARROT) || stack.`is`(Items.WHEAT) || stack.`is`(ItemTags.FISHES)

        /** How much food is in the trough at [pos] (0 if it isn't a trough). */
        fun food(level: Level, pos: BlockPos): Int {
            val st = level.getBlockState(pos)
            return if (st.block is FeedingTroughBlock) st.getValue(FOOD) else 0
        }

        /** A resident ate a portion. */
        fun eat(level: Level, pos: BlockPos) {
            val st = level.getBlockState(pos)
            if (st.block is FeedingTroughBlock && st.getValue(FOOD) > 0) level.setBlock(pos, st.setValue(FOOD, st.getValue(FOOD) - 1), UPDATE_ALL)
        }
    }
}
