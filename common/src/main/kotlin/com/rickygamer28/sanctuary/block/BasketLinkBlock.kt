package com.rickygamer28.sanctuary.block

import com.mojang.serialization.MapCodec
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.life.Gathering
import com.rickygamer28.sanctuary.life.Requests
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.level.BlockGetter
import java.util.UUID

/**
 * The Basket Link (Valley Heart level 8): a little basket you can place anywhere, even outside the sanctuary, that
 * opens the sanctuary's Gathering Basket. Hoppers under it pull the basket's items out (nothing can be put in).
 * It's tied to the sanctuary of whoever placed it.
 */
class BasketLinkBlock(props: BlockBehaviour.Properties) : BaseEntityBlock(props) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState) = RenderShape.MODEL
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape = SHAPE
    override fun newBlockEntity(pos: BlockPos, state: BlockState) = BasketLinkBlockEntity(pos, state)

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        val player = placer as? ServerPlayer ?: return
        val mine = Sanctuaries.forPlayer(player.server, player.uuid)
        val s = (mine.firstOrNull { it.second.owner == player.uuid } ?: mine.firstOrNull())?.second
        val be = level.getBlockEntity(pos) as? BasketLinkBlockEntity ?: return
        if (s == null) { player.displayClientMessage(Component.literal("You don't have a sanctuary for this to link to.").withStyle(ChatFormatting.RED), true); return }
        be.sanctuary = s.id
        be.setChanged()
        player.displayClientMessage(Component.literal("Linked to ${s.ownerName}'s Gathering Basket.").withStyle(ChatFormatting.GREEN), true)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val sp = player as? ServerPlayer ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? BasketLinkBlockEntity ?: return InteractionResult.PASS
        val s = be.linked(sp.server) ?: run { sp.displayClientMessage(Component.literal("This Basket Link isn't linked to a sanctuary.").withStyle(ChatFormatting.RED), true); return InteractionResult.CONSUME }
        when {
            !s.isMember(sp.uuid) -> sp.displayClientMessage(Component.literal("This basket belongs to ${s.ownerName}'s sanctuary.").withStyle(ChatFormatting.RED), true)
            Requests.level(s) < Requests.LINK_LEVEL -> sp.displayClientMessage(Component.literal("The Basket Link works from Valley Heart level ${Requests.LINK_LEVEL}.").withStyle(ChatFormatting.RED), true)
            else -> {
                val (rows, cols) = Gathering.layout(s)
                com.rickygamer28.sanctuary.Platform.current.openBasket(sp, SimpleMenuProvider({ id, inv, _ -> BasketMenu(id, inv, s.basket, rows, cols) },
                    Component.literal("Gathering Basket (${rows * cols} slots)")), rows, cols)
            }
        }
        return InteractionResult.CONSUME
    }

    companion object {
        val CODEC: MapCodec<BasketLinkBlock> = simpleCodec(::BasketLinkBlock)
        private val SHAPE: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 9.0, 14.0)
    }
}

/** The Basket Link's side of things: which sanctuary it's tied to, and the basket's slots for hoppers to pull from. */
class BasketLinkBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModContent.BASKET_LINK_ENTITY.get(), pos, state), WorldlyContainer {
    var sanctuary: UUID? = null

    fun linked(server: net.minecraft.server.MinecraftServer): Sanctuary? = sanctuary?.let { SanctuaryData.get(server).sanctuaries[it] }

    /** The basket, when this is linked and the Heart has the link. */
    private fun basket(): Pair<Sanctuary, Int>? {
        val server = (level as? net.minecraft.server.level.ServerLevel)?.server ?: return null
        val s = linked(server) ?: return null
        if (Requests.level(s) < Requests.LINK_LEVEL) return null
        return s to Gathering.slots(s)
    }

    override fun getContainerSize(): Int = basket()?.second ?: 0
    override fun isEmpty(): Boolean = basket()?.let { (s, n) -> (0 until n).all { s.basket.getItem(it).isEmpty } } ?: true
    override fun getItem(slot: Int): ItemStack = basket()?.let { (s, n) -> if (slot in 0 until n) s.basket.getItem(slot) else ItemStack.EMPTY } ?: ItemStack.EMPTY
    override fun removeItem(slot: Int, amount: Int): ItemStack = basket()?.let { (s, n) ->
        if (slot !in 0 until n) ItemStack.EMPTY else s.basket.removeItem(slot, amount).also { if (!it.isEmpty) dirty(s) }
    } ?: ItemStack.EMPTY
    override fun removeItemNoUpdate(slot: Int): ItemStack = basket()?.let { (s, n) ->
        if (slot !in 0 until n) ItemStack.EMPTY else s.basket.removeItemNoUpdate(slot).also { dirty(s) }
    } ?: ItemStack.EMPTY
    override fun setItem(slot: Int, stack: ItemStack) { basket()?.let { (s, n) -> if (slot in 0 until n) { s.basket.setItem(slot, stack); dirty(s) } } }
    override fun stillValid(player: Player) = true
    override fun clearContent() {}

    // hoppers may take out, never put in (the basket is for what the residents find)
    override fun getSlotsForFace(side: Direction): IntArray = IntArray(getContainerSize()) { it }
    override fun canPlaceItemThroughFace(index: Int, stack: ItemStack, side: Direction?) = false
    override fun canTakeItemThroughFace(index: Int, stack: ItemStack, side: Direction) = true
    override fun canPlaceItem(slot: Int, stack: ItemStack) = false

    private fun dirty(s: Sanctuary) {
        s.basket.setChanged()
        (level as? net.minecraft.server.level.ServerLevel)?.server?.let { Sanctuaries.data(it).setDirty() }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        sanctuary?.let { tag.putUUID("Sanctuary", it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        sanctuary = if (tag.hasUUID("Sanctuary")) tag.getUUID("Sanctuary") else null
    }
}
