package com.rickygamer28.sanctuary

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.logging.LogUtils
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.SanctuaryTethering
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.Interactions
import com.rickygamer28.sanctuary.ui.Text
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.Logger

@Mod(CobblemonSanctuary.MOD_ID)
class CobblemonSanctuary(modBus: IEventBus, container: ModContainer) {
    companion object {
        const val MOD_ID = "cobblemon_sanctuary"
        val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        ModContent.register(modBus)
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent::class.java) { com.rickygamer28.sanctuary.net.SanctuaryNet.register(it) }
        container.registerConfig(ModConfig.Type.SERVER, SanctuaryConfig.spec)

        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { onServerTick(it) }
        bus.addListener(RegisterCommandsEvent::class.java) { onRegisterCommands(it) }
        bus.addListener(PlayerInteractEvent.EntityInteract::class.java) { onEntityInteract(it) }
        bus.addListener(EntityJoinLevelEvent::class.java) { onEntityJoin(it) }
        bus.addListener(BlockEvent.BreakEvent::class.java) { onBlockBreak(it) }
        bus.addListener(ServerStoppingEvent::class.java) { onServerStopping(it) }
        bus.addListener(ChunkEvent.Load::class.java) { onChunkLoad(it) }
        bus.addListener(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post::class.java) { com.rickygamer28.sanctuary.life.Families.onPlayerTick(it.entity) }
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent::class.java) { com.rickygamer28.sanctuary.life.Families.forgetPlayer(it.entity.uuid) }
        LOGGER.info("Cobblemon: Sanctuary loaded")
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val level = SanctuaryDimension.level(event.server) ?: return
        val data = SanctuaryData.get(event.server)
        if (data.sanctuaries.isEmpty()) return
        for (s in data.sanctuaries.values.toList()) {
            try {
                Residents.tick(level, data, s)
            } catch (e: Exception) {
                LOGGER.error("Sanctuary tick failed for ${s.id}", e)
            }
        }
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) = SanctuaryCommand.register(event.dispatcher)

    /** Right-clicking a resident while holding the Journal opens its menu. */
    private fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
        val target = event.target as? PokemonEntity ?: return
        if (!event.itemStack.`is`(ModContent.JOURNAL.get())) return
        val tethering = target.tethering as? SanctuaryTethering ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        val player = event.entity as? ServerPlayer ?: return
        val found = Sanctuaries.find(player.server, tethering.sanctuaryId)
        if (found == null || !found.second.isMember(player.uuid)) {
            player.sendSystemMessage(Text.info("${target.pokemon.getDisplayName(false).string} lives in someone else's sanctuary."))
            return
        }
        Interactions.act(player, target.pokemon.uuid, "menu", null)
    }

    /**
     * Residents saved with the world come back with a plain tethering; drop them and let the sanctuary respawn them.
     * Also keeps wild Pokémon out of the sanctuaries.
     */
    private fun onEntityJoin(event: EntityJoinLevelEvent) {
        val entity = event.entity as? PokemonEntity ?: return
        val level = event.level as? ServerLevel ?: return
        val tethering = entity.tethering
        if (tethering == null) {
            if (SanctuaryDimension.isSanctuary(level) && SanctuaryConfig.blockWildSpawns.get() &&
                runCatching { entity.pokemon.isWild() && !entity.isBattleClone() }.getOrDefault(false)
            ) event.isCanceled = true
            return
        }
        if (tethering is SanctuaryTethering) return
        // a real pasture has its Pasture Block at pasturePos; ours (old or new) never does
        val pos = tethering.pasturePos
        val isRealPasture = level.isLoaded(pos) && level.getBlockState(pos).`is`(com.cobblemon.mod.common.CobblemonBlocks.PASTURE)
        val wide = tethering.maxRoamPos.x - tethering.minRoamPos.x > 120
        if (isRealPasture || (!wide && !level.isLoaded(pos))) return
        event.isCanceled = true
        if (entity.pokemon.tetheringId == tethering.tetheringId) entity.pokemon.tetheringId = null
    }

    /** The return Core in a sanctuary's hub can't be broken; portal Cores only by their owner. */
    private fun onBlockBreak(event: BlockEvent.BreakEvent) {
        val level = event.level as? ServerLevel ?: return
        if (!event.state.`is`(ModContent.SANCTUARY_CORE.get())) return
        val player = event.player
        if (SanctuaryDimension.isSanctuary(level)) {
            if (SanctuaryData.get(level.server).at(event.pos) != null && !player.isCreative) {
                event.isCanceled = true
                (player as? ServerPlayer)?.let { com.rickygamer28.sanctuary.ui.say(it, "This Core leads back home and can't be broken.", net.minecraft.ChatFormatting.RED) }
            }
            return
        }
        val s = SanctuaryData.get(level.server).atPortal(level.dimension().location().toString(), event.pos) ?: return
        if (player.uuid != s.owner && !player.hasPermissions(2)) {
            event.isCanceled = true
            (player as? ServerPlayer)?.let { com.rickygamer28.sanctuary.ui.say(it, "Only ${s.ownerName} can remove this Core.", net.minecraft.ChatFormatting.RED) }
        }
    }

    /** When part of a valley that was skipped (unloaded) loads, the builder comes back to it soon. */
    private fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        if (!SanctuaryDimension.isSanctuary(level)) return
        val pos = event.chunk.pos.getMiddleBlockPosition(SanctuaryDimension.GROUND_Y)
        level.server.execute {
            val s = SanctuaryData.get(level.server).near(pos, Sanctuaries.RIM + 16) ?: return@execute
            com.rickygamer28.sanctuary.world.SanctuaryGenerator.nudge(s, level.gameTime)
        }
    }

    private fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        Residents.shutdownAll()
    }
}
