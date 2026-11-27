package com.rickygamer28.sanctuary.client

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** The Gathering Basket's screen: a chest-style panel that grows with the basket (drawn, so it fits any size). */
class BasketScreen(menu: BasketMenu, inv: Inventory, title: Component) : AbstractContainerScreen<BasketMenu>(menu, inv, title) {
    init {
        imageWidth = menu.width
        imageHeight = menu.height
        inventoryLabelX = (menu.width - 9 * 18) / 2 + 1
        inventoryLabelY = menu.playerY - 11
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        renderTooltip(g, mouseX, mouseY)
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos
        val w = imageWidth
        val h = imageHeight
        // a raised grey panel like a chest's
        g.fill(x, y, x + w, y + h, 0xFF000000.toInt())
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF.toInt())
        g.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555.toInt())
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6.toInt())
        for (slot in menu.slots) {
            val sx = x + slot.x
            val sy = y + slot.y
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF373737.toInt())
            g.fill(sx, sy, sx + 17, sy + 17, 0xFFFFFFFF.toInt())
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B.toInt())
        }
    }

}
