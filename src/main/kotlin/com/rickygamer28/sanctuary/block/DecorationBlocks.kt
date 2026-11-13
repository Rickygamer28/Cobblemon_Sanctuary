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

/**
 * A plush bed in three sizes, for small, big and huge Pokémon: 1×1, 2×2 or 3×3 blocks. The block placed holds the
 * whole model; the other blocks of a bigger bed are invisible parts that point back to it.
 */
open class PokemonBedBlock(properties: Properties, val size: Int) :
    DecorationBlock(properties, kindFor(size), box(0.0, 0.0, 0.0, 16.0, 5.0, 16.0)) {
    override fun codec(): com.mojang.serialization.MapCodec<out HorizontalDirectionalBlock> = simpleCodec { PokemonBedBlock(it, 1) }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val state = super.getStateForPlacement(context) ?: return null
        val level = context.level
        for (off in cells(size, state.getValue(FACING))) {
            if (off.x == 0 && off.z == 0) continue
            if (!level.getBlockState(context.clickedPos.offset(off)).canBeReplaced(context)) return null
        }
        return state
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        for (off in cells(size, state.getValue(FACING))) {
            if (off.x == 0 && off.z == 0) continue
            val part = com.rickygamer28.sanctuary.registry.ModContent.BED_PART.get().defaultBlockState()
                .setValue(BedPartBlock.DX, 2 - off.x).setValue(BedPartBlock.DZ, 2 - off.z)
            level.setBlock(pos.offset(off), part, UPDATE_ALL)
        }
        super.setPlacedBy(level, pos, state, placer, stack)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            for (off in cells(size, state.getValue(FACING))) {
                if (off.x == 0 && off.z == 0) continue
                val p = pos.offset(off)
                if (BedPartBlock.masterOf(level, p) == pos) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), UPDATE_ALL)
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        fun kindFor(size: Int) = when (size) { 2 -> "player_bed_large"; 3 -> "player_bed_huge"; else -> "player_bed" }

        /** The blocks a bed covers, relative to the one placed (it turns with the bed). */
        fun cells(size: Int, facing: Direction): List<BlockPos> {
            val base = when (size) {
                2 -> listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
                3 -> (-1..1).flatMap { x -> (-1..1).map { z -> x to z } }
                else -> listOf(0 to 0)
            }
            val turns = when (facing) { Direction.EAST -> 1; Direction.SOUTH -> 2; Direction.WEST -> 3; else -> 0 }
            return base.map { (x0, z0) ->
                var x = x0; var z = z0
                repeat(turns) { val t = x; x = -z; z = t }
                BlockPos(x, 0, z)
            }
        }
    }
}

class LargePokemonBedBlock(properties: Properties) : PokemonBedBlock(properties, 2) {
    override fun codec(): com.mojang.serialization.MapCodec<out HorizontalDirectionalBlock> = simpleCodec(::LargePokemonBedBlock)
}

class HugePokemonBedBlock(properties: Properties) : PokemonBedBlock(properties, 3) {
    override fun codec(): com.mojang.serialization.MapCodec<out HorizontalDirectionalBlock> = simpleCodec(::HugePokemonBedBlock)
}

/** An invisible part of a big bed; breaking it breaks the bed. */
class BedPartBlock(properties: Properties) : Block(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(DX, 2).setValue(DZ, 2)) }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(DX, DZ) }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun getCloneItemStack(level: net.minecraft.world.level.LevelReader, pos: BlockPos, state: BlockState): ItemStack =
        masterOf(level, pos)?.let { level.getBlockState(it).block.asItem().defaultInstance } ?: ItemStack.EMPTY

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        masterOf(level, pos)?.let { if (!level.isClientSide) level.destroyBlock(it, !player.abilities.instabuild, player) }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block) && !level.isClientSide) {
            val m = BlockPos(pos.x - 2 + state.getValue(DX), pos.y, pos.z - 2 + state.getValue(DZ))
            if (level.getBlockState(m).block is PokemonBedBlock) level.destroyBlock(m, true)
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        /** Offset to the bed's main block, +2 (so 0..4). */
        val DX: IntegerProperty = IntegerProperty.create("dx", 0, 4)
        val DZ: IntegerProperty = IntegerProperty.create("dz", 0, 4)
        private val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 5.0, 16.0)

        /** The main block of the bed this part belongs to, if it's still there. */
        fun masterOf(level: BlockGetter, pos: BlockPos): BlockPos? {
            val st = level.getBlockState(pos)
            if (st.block !is BedPartBlock) return null
            val m = BlockPos(pos.x - 2 + st.getValue(DX), pos.y, pos.z - 2 + st.getValue(DZ))
            return if (level.getBlockState(m).block is PokemonBedBlock) m else null
        }
    }
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
