package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.evolution.variants.LevelUpEvolution
import com.rickygamer28.sanctuary.Sanctuaries
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * Residents grow up: one that's fond of its trainer and meets its evolution's conditions (a level, friendship,
 * the time of day…) evolves on its own in the sanctuary, with a little celebration. Only evolutions that happen
 * by levelling up; stones, trades and held items are left to the trainer. An Everstone, or its trainer's
 * "Stay as it is", stops it.
 */
object Evolving {
    private val cursor = HashMap<UUID, Int>()

    /** Called every second for every sanctuary. Looks over a few residents every half minute; at most one evolves. */
    fun tick(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        if (!SanctuaryConfig.evolveInSanctuary.get()) return
        if ((now / 20L + (s.id.hashCode() and 31)) % 30L != 0L) return
        val roster = rt.roster
        if (roster.isEmpty()) return
        var i = cursor[s.id] ?: 0
        repeat(minOf(60, roster.size)) {
            if (i >= roster.size) i = 0
            val pk = roster[i++].pokemon
            val evo = ready(s, pk)
            if (evo != null && evolve(level, s, rt, pk, evo)) { cursor[s.id] = i; return }
        }
        cursor[s.id] = i
    }

    private fun everstone(pk: Pokemon) = runCatching { pk.heldItem().item.toString().endsWith("everstone") }.getOrDefault(false)

    /** The level-up evolutions this Pokémon has. */
    private fun levelUps(pk: Pokemon): List<Evolution> = runCatching { pk.evolutions.filterIsInstance<LevelUpEvolution>() }.getOrDefault(emptyList())

    /** The evolution that would happen now, if any. */
    fun ready(s: Sanctuary, pk: Pokemon): Evolution? {
        if (s.residents[pk.uuid]?.noEvolve == true || everstone(pk) || Residents.hidden(pk)) return null
        if (pk.friendship < SanctuaryConfig.evolveFriendship.get()) return null
        return levelUps(pk).firstOrNull { e -> runCatching { e.test(pk) }.getOrDefault(false) }
    }

    /** The level an evolution needs (read from its level requirement, wherever this Cobblemon keeps that class). */
    private fun minLevel(e: Evolution): Int? = runCatching {
        e.requirements.firstOrNull { it.javaClass.simpleName == "LevelRequirement" }?.let { req ->
            val f = req.javaClass.declaredFields.firstOrNull { it.name == "minLevel" } ?: return@let null
            f.isAccessible = true
            (f.get(req) as? Number)?.toInt()
        }
    }.getOrNull()

    private fun resultName(e: Evolution): String =
        runCatching { e.result.species }.getOrNull()?.substringAfter(':')?.replaceFirstChar { it.uppercase() } ?: "its next form"

    /** For the resident's page: what it will grow into, and when. */
    fun status(s: Sanctuary, pk: Pokemon): String? {
        val evos = levelUps(pk)
        if (evos.isEmpty()) return if (runCatching { pk.evolutions.any() }.getOrDefault(false)) "Evolves another way (a stone, a trade…)" else null
        if (s.residents[pk.uuid]?.noEvolve == true) return "Stays as it is (its trainer's choice)"
        if (everstone(pk)) return "Holding an Everstone: it won't evolve"
        if (!SanctuaryConfig.evolveInSanctuary.get()) return "Doesn't evolve in the sanctuary on this server"
        val e = evos.first()
        val name = resultName(e)
        val lv: Int? = minLevel(e)
        val need = SanctuaryConfig.evolveFriendship.get()
        return when {
            evos.any { runCatching { it.test(pk) }.getOrDefault(false) } && pk.friendship >= need -> "About to evolve into $name!"
            lv != null && pk.level < lv -> "Evolves into $name at Lv.$lv"
            pk.friendship < need -> "Will evolve into $name once fonder of its trainer"
            else -> "Evolves into $name when the time is right"
        }
    }

    private fun evolve(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, pk: Pokemon, evo: Evolution): Boolean {
        val before = pk.getDisplayName(false).string
        val beforeSpecies = pk.species.name
        val live = rt.live[pk.uuid]
        live?.entity?.let { e ->
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, e.x, e.y + e.bbHeight / 2, e.z, 40, 0.4, 0.6, 0.4, 0.4)
            level.playSound(null, e.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8f, 1.2f)
        }
        val ok = runCatching { evo.forceEvolve(pk); true }.getOrElse {
            com.rickygamer28.sanctuary.CobblemonSanctuary.LOGGER.warn("Couldn't evolve $beforeSpecies in the sanctuary", it); false
        }
        if (!ok || pk.species.name == beforeSpecies) return false
        val after = pk.species.translatedName.string
        val zone = runCatching { TypeLogic.homeZone(pk, s.residents[pk.uuid], ZoneLayout.ring(s), s) }.getOrNull()
        s.logEvent("$before evolved into $after in the ${Residents.zoneName(zone)}!")
        s.stat("evolutions")
        live?.entity?.let { e -> Effects.happy(e); Effects.hearts(e) }
        for (p in level.players()) if (s.isMember(p.uuid)) (p as? ServerPlayer)?.displayClientMessage(
            Component.literal("✦ $before evolved into $after in your sanctuary!").withStyle(ChatFormatting.AQUA), false)
        rt.rosterDirty = true
        Sanctuaries.data(level).setDirty()
        return true
    }
}
