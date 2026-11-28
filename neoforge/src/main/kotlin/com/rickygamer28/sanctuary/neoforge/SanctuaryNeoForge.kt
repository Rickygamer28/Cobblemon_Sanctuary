package com.rickygamer28.sanctuary.neoforge

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.NoBuilding
import com.rickygamer28.sanctuary.Platform
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.net.ActionPayload
import com.rickygamer28.sanctuary.net.SanctuaryNet
import com.rickygamer28.sanctuary.net.ViewPayload
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.BasketMenu
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTab
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.RegisterEvent

/** What's different on NeoForge. */
object NeoForgePlatform : Platform {
    override val loader = "neoforge"
    override fun isModLoaded(id: String) = ModList.get().isLoaded(id)
    override fun playerData(player: Player): CompoundTag = player.persistentData
    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) = PacketDistributor.sendToPlayer(player, payload)
    override fun sendToServer(payload: CustomPacketPayload) = PacketDistributor.sendToServer(payload)
    override fun basketMenuType(): MenuType<BasketMenu> = IMenuTypeExtension.create { id, inv, buf -> BasketMenu(id, inv, buf.readVarInt(), buf.readVarInt()) }
    override fun openBasket(player: ServerPlayer, provider: MenuProvider, rows: Int, cols: Int) {
        player.openMenu(provider) { buf -> buf.writeVarInt(rows); buf.writeVarInt(cols) }
    }
    override fun tabBuilder(): CreativeModeTab.Builder = CreativeModeTab.builder()
}

@Mod(CobblemonSanctuary.MOD_ID)
class SanctuaryNeoForge(modBus: IEventBus, container: ModContainer) {
    init {
        Platform.current = NeoForgePlatform
        modBus.addListener(RegisterEvent::class.java) { register(it) }
        modBus.addListener(RegisterPayloadHandlersEvent::class.java) { e ->
            val registrar = e.registrar("1")
            registrar.playToClient(ViewPayload.TYPE, ViewPayload.CODEC) { payload, ctx ->
                ctx.enqueueWork { com.rickygamer28.sanctuary.client.ClientHooks.onView(payload.tag) }
            }
            registrar.playToServer(ActionPayload.TYPE, ActionPayload.CODEC) { payload, ctx ->
                ctx.enqueueWork { (ctx.player() as? ServerPlayer)?.let { SanctuaryNet.onAction(it, payload.tag) } }
            }
        }
        container.registerConfig(ModConfig.Type.SERVER, SanctuaryConfig.spec)

        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerStartingEvent::class.java) { CobblemonSanctuary.serverStarting(it.server) }
        bus.addListener(ServerStartedEvent::class.java) { CobblemonSanctuary.serverStarted() }
        bus.addListener(ServerStoppedEvent::class.java) { CobblemonSanctuary.serverStopped() }
        bus.addListener(OnDatapackSyncEvent::class.java) { if (it.player == null) CobblemonSanctuary.dataReloaded() }
        bus.addListener(ServerTickEvent.Post::class.java) { CobblemonSanctuary.serverTick(it.server) }
        bus.addListener(RegisterCommandsEvent::class.java) { CobblemonSanctuary.registerCommands(it.dispatcher) }
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java) { e ->
            CobblemonSanctuary.entityInteract(e.entity, e.target, e.itemStack)?.let { e.isCanceled = true; e.cancellationResult = it }
        }
        bus.addListener(EntityJoinLevelEvent::class.java) { e -> if (!CobblemonSanctuary.allowJoin(e.entity, e.level)) e.isCanceled = true }
        bus.addListener(LivingIncomingDamageEvent::class.java) { e -> if (!CobblemonSanctuary.allowDamage(e.entity, e.source)) e.isCanceled = true }
        bus.addListener(BlockEvent.BreakEvent::class.java) { e ->
            val level = e.level as? net.minecraft.world.level.Level ?: return@addListener
            if (!CobblemonSanctuary.allowBreak(level, e.player, e.pos, e.state)) e.isCanceled = true
        }
        bus.addListener(ItemFishedEvent::class.java) { e -> CobblemonSanctuary.fished(e.entity, e.hookEntity) }
        // nobody builds in a sanctuary (the mod's own beds, balls, nests… still go down)
        bus.addListener(BlockEvent.EntityPlaceEvent::class.java) { e ->
            val level = e.level as? net.minecraft.world.level.Level ?: return@addListener
            if (!NoBuilding.allowPlace(level, e.entity, e.placedBlock, e.pos)) e.isCanceled = true
        }
        bus.addListener(PlayerInteractEvent.RightClickBlock::class.java) { e ->
            if (!NoBuilding.allowItemUse(e.level, e.entity, e.itemStack)) e.useItem = TriState.FALSE
        }
        bus.addListener(PlayerInteractEvent.RightClickItem::class.java) { e ->
            if (!NoBuilding.allowItemUse(e.level, e.entity, e.itemStack)) { e.isCanceled = true; e.cancellationResult = net.minecraft.world.InteractionResult.FAIL }
        }
        bus.addListener(BlockEvent.BlockToolModificationEvent::class.java) { e ->
            val level = e.level as? net.minecraft.world.level.Level ?: return@addListener
            if (!e.isSimulated && !NoBuilding.allowToolUse(level, e.player)) e.isCanceled = true
        }
        // before Cobblemon saves the PCs, so no Pokémon is saved still linked to the sanctuary
        bus.addListener(EventPriority.HIGHEST, ServerStoppingEvent::class.java) { CobblemonSanctuary.serverStopping() }
        bus.addListener(ChunkEvent.Load::class.java) { e -> (e.level as? net.minecraft.world.level.Level)?.let { CobblemonSanctuary.chunkLoaded(it, e.chunk.pos) } }
        bus.addListener(PlayerTickEvent.Post::class.java) { CobblemonSanctuary.playerTick(it.entity) }
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { CobblemonSanctuary.playerLoggedOut(it.entity.uuid) }
        if (FMLEnvironment.dist == Dist.CLIENT) NeoForgeClient.init(modBus)
        CobblemonSanctuary.LOGGER.info("Cobblemon: Sanctuary loaded (NeoForge)")
    }

    private fun register(e: RegisterEvent) {
        e.register(Registries.BLOCK) { h -> ModContent.BLOCKS.forEach { r -> val v = r.create(); h.register(r.key, v); r.set(v) } }
        e.register(Registries.ITEM) { h -> ModContent.ITEMS.forEach { r -> val v = r.create(); h.register(r.key, v); r.set(v) } }
        e.register(Registries.BLOCK_ENTITY_TYPE) { h -> ModContent.BLOCK_ENTITIES.forEach { r -> val v = r.create(); h.register(r.key, v); r.set(v) } }
        e.register(Registries.MENU) { h -> ModContent.MENUS.forEach { r -> val v = r.create(); h.register(r.key, v); r.set(v) } }
        e.register(Registries.CREATIVE_MODE_TAB) { h -> ModContent.TABS.forEach { r -> val v = r.create(); h.register(r.key, v); r.set(v) } }
    }
}
