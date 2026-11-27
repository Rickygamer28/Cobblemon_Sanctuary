package com.rickygamer28.sanctuary.ui

import com.rickygamer28.sanctuary.registry.ModContent
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * The Gathering Basket's own menu: as many slots as the Valley Heart gives it (27, 54, 81 or 108), laid out in
 * [rows] × [cols], with the player's inventory below.
 */
class BasketMenu(id: Int, inv: Inventory, val container: Container, val rows: Int, val cols: Int) :
    AbstractContainerMenu(ModContent.BASKET_MENU.get(), id) {

    /** On the player's side: an empty stand-in of the right size (the server fills it in). */
    constructor(id: Int, inv: Inventory, rows: Int, cols: Int) : this(id, inv, SimpleContainer(rows * cols), rows, cols)

    val slotsCount = rows * cols
    val width = maxOf(176, 14 + cols * 18)
    val playerY = 18 + rows * 18 + 13
    val height = playerY + 83

    init {
        container.startOpen(inv.player)
        val left = (width - cols * 18) / 2 + 1
        for (r in 0 until rows) for (c in 0 until cols) addSlot(Slot(container, r * cols + c, left + c * 18, 18 + r * 18))
        val pl = (width - 9 * 18) / 2 + 1
        for (r in 0 until 3) for (c in 0 until 9) addSlot(Slot(inv, 9 + r * 9 + c, pl + c * 18, playerY + r * 18))
        for (c in 0 until 9) addSlot(Slot(inv, c, pl + c * 18, playerY + 58))
    }

    override fun stillValid(player: Player) = true

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        if (index < slotsCount) {
            if (!moveItemStackTo(stack, slotsCount, slots.size, true)) return ItemStack.EMPTY
        } else if (!moveItemStackTo(stack, 0, slotsCount, false)) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun removed(player: Player) {
        super.removed(player)
        container.stopOpen(player)
    }
}
