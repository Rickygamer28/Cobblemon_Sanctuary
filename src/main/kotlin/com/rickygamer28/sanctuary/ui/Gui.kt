package com.rickygamer28.sanctuary.ui

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/**
 * A server-side window built from a chest screen: icons you click, nothing you can take.
 * Works for every player without extra client mods.
 */
abstract class Gui(val player: ServerPlayer, val rows: Int, val title: Component) {
    val container = SimpleContainer(rows * 9)
    private val actions = HashMap<Int, (ClickType, Int) -> Unit>()
    private var menu: Menu? = null

    /** Fill the slots with [set]. Called when opened and on [refresh]. */
    abstract fun build()

    fun set(slot: Int, stack: ItemStack, action: ((ClickType, Int) -> Unit)? = null) {
        if (slot !in 0 until rows * 9) return
        container.setItem(slot, stack)
        if (action != null) actions[slot] = action else actions.remove(slot)
    }

    fun onClick(slot: Int, type: ClickType, button: Int) {
        val action = actions[slot] ?: return
        player.playNotifySound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), net.minecraft.sounds.SoundSource.MASTER, 0.4f, 1.2f)
        action(type, button)
    }

    fun open() {
        clear()
        build()
        val type = when (rows) { 1 -> MenuType.GENERIC_9x1; 2 -> MenuType.GENERIC_9x2; 3 -> MenuType.GENERIC_9x3; 4 -> MenuType.GENERIC_9x4; 5 -> MenuType.GENERIC_9x5; else -> MenuType.GENERIC_9x6 }
        player.openMenu(SimpleMenuProvider({ id, inv, _ -> Menu(type, id, inv, this).also { menu = it } }, title))
    }

    fun refresh() {
        clear()
        build()
        menu?.broadcastChanges()
    }

    /** Switch to another window on the next tick (safe to call from a click). */
    fun goTo(next: () -> Gui) {
        player.server.execute { next().open() }
    }

    fun close() = player.server.execute { player.closeContainer() }

    private fun clear() {
        actions.clear()
        for (i in 0 until container.containerSize) container.setItem(i, ItemStack.EMPTY)
    }

    fun fill(stack: ItemStack) {
        for (i in 0 until container.containerSize) if (container.getItem(i).isEmpty) container.setItem(i, stack.copy())
    }

    class Menu(type: MenuType<ChestMenu>, id: Int, inv: Inventory, val gui: Gui) : ChestMenu(type, id, inv, gui.container, gui.rows) {
        override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
            if (slotId in 0 until gui.rows * 9) gui.onClick(slotId, clickType, button)
            carried = ItemStack.EMPTY
            sendAllDataToRemote()
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(player: Player): Boolean = true
    }

    companion object {
        fun item(id: String, fallback: Item = Items.PAPER): Item {
            val item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))
            return if (item == Items.AIR) fallback else item
        }

        /** An icon with a name and description lines. */
        fun icon(stack: ItemStack, name: Component, vararg lore: Component, glow: Boolean = false): ItemStack {
            val s = stack.copy()
            s.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle { it.withItalic(false) })
            if (lore.isNotEmpty()) s.set(DataComponents.LORE, ItemLore(lore.map { l -> Component.empty().append(l).withStyle { it.withItalic(false) } }))
            s.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE)
            if (glow) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            return s
        }

        fun icon(item: Item, name: String, vararg lore: String, color: ChatFormatting = ChatFormatting.WHITE, glow: Boolean = false): ItemStack =
            icon(ItemStack(item), Component.literal(name).withStyle(color), *lore.map { Component.literal(it).withStyle(ChatFormatting.GRAY) }.toTypedArray(), glow = glow)

        val FILLER: ItemStack get() = icon(ItemStack(Items.GRAY_STAINED_GLASS_PANE), Component.literal(" "))
    }
}
