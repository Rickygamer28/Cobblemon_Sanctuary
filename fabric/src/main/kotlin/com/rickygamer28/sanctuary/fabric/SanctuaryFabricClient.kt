package com.rickygamer28.sanctuary.fabric

import com.rickygamer28.sanctuary.client.Atmosphere
import com.rickygamer28.sanctuary.client.BasketScreen
import com.rickygamer28.sanctuary.client.ClientHooks
import com.rickygamer28.sanctuary.client.NoRain
import com.rickygamer28.sanctuary.client.PhotoMode
import com.rickygamer28.sanctuary.net.ViewPayload
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

object SanctuaryFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ViewPayload.TYPE) { payload, ctx -> ctx.client().execute { ClientHooks.onView(payload.tag) } }
        MenuScreens.register(ModContent.BASKET_MENU.get()) { menu: BasketMenu, inv: Inventory, title: Component -> BasketScreen(menu, inv, title) }
        ClientTickEvents.END_CLIENT_TICK.register { NoRain.tick(); Atmosphere.tick() }
        WorldRenderEvents.LAST.register { PhotoMode.frame() }
    }
}
