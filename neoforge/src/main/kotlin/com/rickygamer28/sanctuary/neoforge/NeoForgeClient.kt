package com.rickygamer28.sanctuary.neoforge

import com.rickygamer28.sanctuary.client.Atmosphere
import com.rickygamer28.sanctuary.client.BasketScreen
import com.rickygamer28.sanctuary.client.NoRain
import com.rickygamer28.sanctuary.client.PhotoMode
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.common.NeoForge

/** The client side on NeoForge (only loaded on a client). */
object NeoForgeClient {
    fun init(modBus: IEventBus) {
        modBus.addListener(RegisterMenuScreensEvent::class.java) { e ->
            e.register(ModContent.BASKET_MENU.get()) { menu: BasketMenu, inv: Inventory, title: Component -> BasketScreen(menu, inv, title) }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { NoRain.tick(); Atmosphere.tick() }
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post::class.java) { PhotoMode.frame() }
    }
}
