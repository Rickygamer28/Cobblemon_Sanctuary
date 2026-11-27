package com.rickygamer28.sanctuary.fabric

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.NoBuilding
import com.rickygamer28.sanctuary.Platform
import com.rickygamer28.sanctuary.Reg
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.net.ActionPayload
import com.rickygamer28.sanctuary.net.SanctuaryNet
import com.rickygamer28.sanctuary.net.ViewPayload
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTab

/** What's different on Fabric. */
object FabricPlatform : Platform {
    override val loader = "fabric"

    /** Data kept with each player (Fabric has no per-entity data of its own: an attachment, saved and kept through death). */
    val PLAYER_DATA: AttachmentType<CompoundTag> = AttachmentRegistry.builder<CompoundTag>()
        .persistent(CompoundTag.CODEC)
        .copyOnDeath()
        .initializer { CompoundTag() }
        .buildAndRegister(ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "player_data"))

    override fun isModLoaded(id: String) = FabricLoader.getInstance().isModLoaded(id)
    override fun playerData(player: Player): CompoundTag = (player as AttachmentTarget).getAttachedOrCreate(PLAYER_DATA)
    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) = ServerPlayNetworking.send(player, payload)
    override fun sendToServer(payload: CustomPacketPayload) = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload)

    /** The basket's size travels as one number: rows × 100 + columns. */
    override fun basketMenuType(): MenuType<BasketMenu> =
        ExtendedScreenHandlerType<BasketMenu, Int>({ id: Int, inv: Inventory, size: Int -> BasketMenu(id, inv, size / 100, size % 100) }, ByteBufCodecs.VAR_INT)

    override fun openBasket(player: ServerPlayer, provider: MenuProvider, rows: Int, cols: Int) {
        player.openMenu(object : ExtendedScreenHandlerFactory<Int> {
            override fun getScreenOpeningData(player: ServerPlayer): Int = rows * 100 + cols
            override fun getDisplayName(): Component = provider.displayName
            override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu? = provider.createMenu(id, inv, p)
        })
    }

    override fun tabBuilder(): CreativeModeTab.Builder = FabricItemGroup.builder()
}

object SanctuaryFabric : ModInitializer {
    private fun <T : Any> register(registry: Registry<T>, list: List<Reg<out T>>) {
        for (r in list) r.set(Registry.register(registry, r.key, r.create()))
    }

    override fun onInitialize() {
        Platform.current = FabricPlatform
        register(BuiltInRegistries.BLOCK, ModContent.BLOCKS)
        register(BuiltInRegistries.ITEM, ModContent.ITEMS)
        register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModContent.BLOCK_ENTITIES)
        register(BuiltInRegistries.MENU, ModContent.MENUS)
        register(BuiltInRegistries.CREATIVE_MODE_TAB, ModContent.TABS)
        FabricPlatform.PLAYER_DATA // registered now

        PayloadTypeRegistry.playS2C().register(ViewPayload.TYPE, ViewPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE) { payload, ctx ->
            ctx.server().execute { SanctuaryNet.onAction(ctx.player(), payload.tag) }
        }
        // the settings use NeoForge's config files (Forge Config API Port, inside this jar, brings them to Fabric)
        fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry.INSTANCE.register(
            CobblemonSanctuary.MOD_ID, net.neoforged.fml.config.ModConfig.Type.SERVER, SanctuaryConfig.spec)

        ServerLifecycleEvents.SERVER_STARTING.register { CobblemonSanctuary.serverStarting(it) }
        ServerLifecycleEvents.SERVER_STARTED.register { CobblemonSanctuary.serverStarted() }
        ServerLifecycleEvents.SERVER_STOPPED.register { CobblemonSanctuary.serverStopped() }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, _ -> CobblemonSanctuary.dataReloaded() }
        // before Cobblemon saves the PCs, so no Pokémon is saved still linked to the sanctuary
        val early = ResourceLocation.fromNamespaceAndPath(CobblemonSanctuary.MOD_ID, "before_saves")
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(early, Event.DEFAULT_PHASE)
        ServerLifecycleEvents.SERVER_STOPPING.register(early) { CobblemonSanctuary.serverStopping() }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            CobblemonSanctuary.serverTick(server)
            for (p in server.playerList.players) CobblemonSanctuary.playerTick(p)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> CobblemonSanctuary.registerCommands(dispatcher) }
        UseEntityCallback.EVENT.register { player, _, hand, entity, hit ->
            // the plain interaction only (a click also sends one aimed at a point on the entity)
            if (hit != null) InteractionResult.PASS
            else CobblemonSanctuary.entityInteract(player, entity, player.getItemInHand(hand)) ?: InteractionResult.PASS
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ -> CobblemonSanctuary.allowDamage(entity, source) }
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, state, _ -> CobblemonSanctuary.allowBreak(level, player, pos, state) }
        UseItemCallback.EVENT.register { player, level, hand ->
            val stack = player.getItemInHand(hand)
            if (NoBuilding.allowItemUse(level, player, stack)) InteractionResultHolder.pass(stack) else InteractionResultHolder.fail(stack)
        }
        ServerChunkEvents.CHUNK_LOAD.register { level, chunk -> CobblemonSanctuary.chunkLoaded(level, chunk.pos) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> CobblemonSanctuary.playerLoggedOut(handler.player.uuid) }
        CobblemonSanctuary.LOGGER.info("Cobblemon: Sanctuary loaded (Fabric)")
    }
}
