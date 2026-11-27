package com.rickygamer28.sanctuary

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import com.rickygamer28.sanctuary.ui.BasketMenu

/** What the loader (NeoForge or Fabric) does differently. Set by the loader's entry point before anything else runs. */
interface Platform {
    val loader: String
    fun isModLoaded(id: String): Boolean
    /** Data kept with a player (saved with them, kept through death). */
    fun playerData(player: Player): CompoundTag
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
    fun sendToServer(payload: CustomPacketPayload)
    /** The basket's menu type (its size travels with it when it opens). */
    fun basketMenuType(): MenuType<BasketMenu>
    fun openBasket(player: ServerPlayer, provider: MenuProvider, rows: Int, cols: Int)
    /** A builder for the mod's creative tab. */
    fun tabBuilder(): net.minecraft.world.item.CreativeModeTab.Builder

    companion object {
        lateinit var current: Platform
    }
}

/** One thing to register (a block, an item…): made and registered by the loader, then reached with [get]. */
class Reg<T : Any>(val id: String, val create: () -> T) {
    private var value: T? = null
    val key: net.minecraft.resources.ResourceLocation get() = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, id)
    @Suppress("UNCHECKED_CAST")
    fun set(v: Any) { value = v as T }
    fun get(): T = value ?: error("$id isn't registered yet")
}
