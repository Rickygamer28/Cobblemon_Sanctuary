package com.rickygamer28.sanctuary.block

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.life.Families
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.say
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Keeps a Pokémon Egg warm until it hatches. Right-click with an egg to put it in, sneak + right-click to take it out. */
class IncubatorBlock(properties: Properties) : Block(properties), EntityBlock {
    companion object {
        val HAS_EGG: BooleanProperty = BooleanProperty.create("has_egg")
    }

    init { registerDefaultState(stateDefinition.any().setValue(HAS_EGG, false)) }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) { builder.add(HAS_EGG) }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = IncubatorBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? {
        if (level.isClientSide || type != ModContent.INCUBATOR_ENTITY.get()) return null
        return BlockEntityTicker { l, _, _, be -> (be as? IncubatorBlockEntity)?.serverTick(l as ServerLevel) }
    }

    override fun useItemOn(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hitResult: BlockHitResult): ItemInteractionResult {
        if (!stack.`is`(ModContent.POKEMON_EGG.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val be = level.getBlockEntity(pos) as? IncubatorBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (!be.egg.isEmpty) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (level is ServerLevel && player is ServerPlayer) {
            be.insert(stack.split(1), player.uuid)
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1f, 1f)
            say(player, "The egg is warm and cosy. It will hatch in about ${be.minutesLeft()} min.", ChatFormatting.GREEN)
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val be = level.getBlockEntity(pos) as? IncubatorBlockEntity ?: return InteractionResult.PASS
        if (level is ServerLevel && player is ServerPlayer) {
            when {
                be.egg.isEmpty -> say(player, "Put a Pokémon Egg in to keep it warm.", ChatFormatting.GRAY)
                player.isShiftKeyDown -> {
                    val egg = be.takeOut()
                    if (!player.inventory.add(egg)) player.drop(egg, false)
                    say(player, "You took the egg out. Its progress is kept.", ChatFormatting.GRAY)
                }
                else -> say(player, "Hatching: ${(Families.progress(be.egg) * 100).toInt()}% (about ${be.minutesLeft()} min left). Sneak + right-click to take it out.", ChatFormatting.GOLD)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? IncubatorBlockEntity)?.let { if (!it.egg.isEmpty) Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, it.egg) }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
}

class IncubatorBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModContent.INCUBATOR_ENTITY.get(), pos, state) {
    var egg: ItemStack = ItemStack.EMPTY
        private set
    private var placer: UUID? = null

    /** Egg steps per second in the Incubator. */
    private fun stepsPerSecond() = SanctuaryConfig.eggStepsPerCycle.get().toDouble() / SanctuaryConfig.incubatorSecondsPerCycle.get()

    fun minutesLeft(): Int {
        val left = (1 - Families.progress(egg)) * (Families.eggTag(egg)?.let { Families.stepsNeeded(it) } ?: 0)
        return ((left / stepsPerSecond()) / 60).toInt() + 1
    }

    fun insert(stack: ItemStack, who: UUID) {
        egg = stack
        placer = who
        updateState()
    }

    fun takeOut(): ItemStack {
        val out = egg
        egg = ItemStack.EMPTY
        updateState()
        return out
    }

    private fun updateState() {
        setChanged()
        val lvl = level ?: return
        val st = lvl.getBlockState(worldPosition)
        if (st.hasProperty(IncubatorBlock.HAS_EGG) && st.getValue(IncubatorBlock.HAS_EGG) == egg.isEmpty) {
            lvl.setBlock(worldPosition, st.setValue(IncubatorBlock.HAS_EGG, !egg.isEmpty), 3)
        }
    }

    fun serverTick(level: ServerLevel) {
        if (egg.isEmpty || level.gameTime % 20L != 0L) return
        if (level.random.nextInt(6) == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, worldPosition.x + 0.5, worldPosition.y + 0.9, worldPosition.z + 0.5, 1, 0.15, 0.05, 0.15, 0.0)
        if (Families.addSteps(egg, stepsPerSecond())) {
            val receiver = placer?.let { level.server.playerList.getPlayer(it) }
            if (Families.hatchItem(level.server, egg, receiver, level, Vec3.atCenterOf(worldPosition).add(0.0, 0.5, 0.0)) != null) {
                egg = ItemStack.EMPTY
                updateState()
            }
        }
        setChanged()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        if (!egg.isEmpty) tag.put("Egg", egg.save(registries))
        placer?.let { tag.putUUID("Placer", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        egg = if (tag.contains("Egg")) ItemStack.parse(registries, tag.getCompound("Egg")).orElse(ItemStack.EMPTY) else ItemStack.EMPTY
        placer = if (tag.hasUUID("Placer")) tag.getUUID("Placer") else null
    }
}
