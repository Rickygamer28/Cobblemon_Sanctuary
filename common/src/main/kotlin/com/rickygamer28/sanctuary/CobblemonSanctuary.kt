package com.rickygamer28.sanctuary

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.mojang.brigadier.CommandDispatcher
import com.mojang.logging.LogUtils
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.life.Residents
import com.rickygamer28.sanctuary.life.SanctuaryTethering
import com.rickygamer28.sanctuary.registry.ModContent
import com.rickygamer28.sanctuary.ui.Interactions
import com.rickygamer28.sanctuary.ui.Text
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.Logger
import java.util.UUID

/**
 * The mod itself, the same on every loader: the loader's entry point sets [Platform.current], registers
 * [ModContent], and calls these hooks from its own events.
 */
object CobblemonSanctuary {
    const val MOD_ID = "cobblemon_sanctuary"
    val LOGGER: Logger = LogUtils.getLogger()

    /** The running server (null when none is running). */
    @Volatile
    var server: MinecraftServer? = null

    fun serverStarting(s: MinecraftServer) { server = s }
    // types can change when the game loads its data (Gravelmon retypes some Pokémon) or on /reload: read them again
    fun serverStarted() = com.rickygamer28.sanctuary.world.TypeLogic.clearCache()
    fun dataReloaded() = com.rickygamer28.sanctuary.world.TypeLogic.clearCache()
    /** Before Cobblemon saves the PCs, so no Pokémon is saved still linked to the sanctuary. */
    fun serverStopping() = Residents.shutdownAll()
    fun serverStopped() { server = null }

    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) = SanctuaryCommand.register(dispatcher)

    fun serverTick(server: MinecraftServer) {
        if (SelfTest.active) SelfTest.tick(server)
        val level = SanctuaryDimension.level(server) ?: return
        val data = SanctuaryData.get(server)
        if (data.sanctuaries.isEmpty()) return
        for (s in data.sanctuaries.values.toList()) {
            if (SelfTest.isTest(s)) continue
            try {
                Residents.tick(level, data, s)
            } catch (e: Exception) {
                LOGGER.error("Sanctuary tick failed for ${s.id}", e)
            }
        }
    }

    fun playerTick(player: Player) = com.rickygamer28.sanctuary.life.Families.onPlayerTick(player)
    fun playerLoggedOut(id: UUID) = com.rickygamer28.sanctuary.life.Families.forgetPlayer(id)

    /**
     * Right-clicking a resident while holding the Journal opens its menu; a visitor can be fed or petted.
     * Returns the result when it was handled (the normal interaction is then stopped), or null.
     */
    fun entityInteract(player: Player, target: Entity, stack: ItemStack): InteractionResult? {
        if (target !is PokemonEntity) return null
        if (com.rickygamer28.sanctuary.life.Visitors.isVisitor(target)) {
            val sp = player as? ServerPlayer ?: return null
            return if (com.rickygamer28.sanctuary.life.Visitors.interact(sp, target, stack.`is`(ModContent.JOURNAL.get()))) InteractionResult.SUCCESS else null
        }
        if (!stack.`is`(ModContent.JOURNAL.get())) return null
        val tethering = target.tethering as? SanctuaryTethering ?: return null
        val sp = player as? ServerPlayer ?: return InteractionResult.SUCCESS
        val found = Sanctuaries.find(sp.server, tethering.sanctuaryId)
        if (found == null || !found.second.isMember(sp.uuid)) {
            sp.sendSystemMessage(Text.info("${target.pokemon.getDisplayName(false).string} lives in someone else's sanctuary."))
            return InteractionResult.SUCCESS
        }
        Interactions.act(sp, target.pokemon.uuid, "menu", null)
        return InteractionResult.SUCCESS
    }

    /** Nothing hurts Pokémon in the Sanctuary: no cactus, magma, falls, drowning or attacks. Only /kill still works. */
    fun allowDamage(entity: LivingEntity, source: DamageSource): Boolean {
        if (entity !is PokemonEntity) return true
        if (entity.level().isClientSide || !SanctuaryDimension.isSanctuary(entity.level())) return true
        if (source.`is`(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD) || source.`is`(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) return true
        entity.clearFire()
        return false
    }

    /**
     * May [entity] join [level]? Residents saved with the world come back with a plain tethering: they're dropped and
     * the sanctuary respawns them. Also keeps wild Pokémon out of the sanctuaries.
     */
    fun allowJoin(entity: Entity, level: Level): Boolean {
        if (level !is ServerLevel) return true
        if (entity !is PokemonEntity) return !(isOutsideTrainer(entity) && SanctuaryDimension.isSanctuary(level) && SanctuaryConfig.blockTrainerSpawns.get())
        val tethering = entity.tethering
        if (tethering == null) {
            return !(SanctuaryDimension.isSanctuary(level) && SanctuaryConfig.blockWildSpawns.get() &&
                runCatching { entity.pokemon.isWild() && !entity.isBattleClone() && !entity.tags.contains(com.rickygamer28.sanctuary.life.Visitors.TAG) }.getOrDefault(false))
        }
        if (tethering is SanctuaryTethering) return true
        // outside the sanctuaries, tethered Pokémon belong to pastures (Cobblemon's or other mods'): never touched
        if (!SanctuaryDimension.isSanctuary(level)) return true
        // in a sanctuary nobody can build a pasture: a plain tethering here is a resident saved with the world
        if (entity.pokemon.tetheringId == tethering.tetheringId) entity.pokemon.tetheringId = null
        return false
    }

    /** Mods whose NPC trainers wander in on their own (their entities are kept out when the setting is on). */
    private val TRAINER_MODS = setOf("rctmod")

    private fun isOutsideTrainer(entity: Entity): Boolean {
        val id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        return id.namespace in TRAINER_MODS
    }

    /** A player breaking a block: crystal nodes are mined, the valley is kept, and Cores have their own rules. False stops it. */
    fun allowBreak(level: Level, player: Player, pos: BlockPos, state: BlockState): Boolean {
        if (com.rickygamer28.sanctuary.life.Activities.onBreak(level, player, pos)) return false
        val ok = NoBuilding.allowBreak(level, player, state) && coreBreak(level, player, pos, state)
        if (!ok) NoBuilding.resync(level, player, pos)
        return ok
    }

    /** The return Core in a sanctuary's hub can't be broken; portal Cores only by their owner. */
    private fun coreBreak(lvl: Level, player: Player, pos: BlockPos, state: BlockState): Boolean {
        val level = lvl as? ServerLevel ?: return true
        if (!state.`is`(ModContent.SANCTUARY_CORE.get())) return true
        if (SanctuaryDimension.isSanctuary(level)) {
            if (SanctuaryData.get(level.server).at(pos) != null && !player.isCreative) {
                (player as? ServerPlayer)?.let { com.rickygamer28.sanctuary.ui.say(it, "This Core leads back home and can't be broken.", net.minecraft.ChatFormatting.RED) }
                return false
            }
            return true
        }
        val s = SanctuaryData.get(level.server).atPortal(level.dimension().location().toString(), pos) ?: return true
        if (player.uuid != s.owner && !player.hasPermissions(2)) {
            (player as? ServerPlayer)?.let { com.rickygamer28.sanctuary.ui.say(it, "Only ${s.ownerName} can remove this Core.", net.minecraft.ChatFormatting.RED) }
            return false
        }
        return true
    }

    fun fished(player: Player, hook: Entity) = com.rickygamer28.sanctuary.life.Activities.onFished(player, hook)

    /** When part of a valley that was skipped (unloaded) loads, the builder comes back to it soon. */
    fun chunkLoaded(lvl: Level, chunk: ChunkPos) {
        val level = lvl as? ServerLevel ?: return
        if (!SanctuaryDimension.isSanctuary(level)) return
        val pos = chunk.getMiddleBlockPosition(SanctuaryDimension.GROUND_Y)
        level.server.execute {
            val s = SanctuaryData.get(level.server).near(pos, Sanctuaries.RIM + 16) ?: return@execute
            com.rickygamer28.sanctuary.world.SanctuaryGenerator.nudge(s, level.gameTime)
        }
    }
}
