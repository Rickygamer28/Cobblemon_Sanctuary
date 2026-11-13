package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.neoforged.fml.ModList
import java.util.UUID

/**
 * Mega Evolution during play-fights, with Cobblemon: Mega Showdown installed.
 *
 * Mega Showdown describes every Mega Stone in a data file (`data/<ns>/mega_showdown/mega/<stone>.json`) with the
 * species it works for and the aspects to apply ("mega_evolution=mega") and to revert ("mega_evolution=none").
 * Those files are read here, so the sanctuary works with every Mega Showdown version and with custom megas.
 * The Pokémon keeps its original typing for its home: see TypeLogic.
 */
object MegaCompat {
    val megaShowdownLoaded: Boolean by lazy { ModList.get().isLoaded("mega_showdown") }

    private class Stone(val items: Set<String>, val species: Set<String>, val apply: List<String>, val revert: List<String>,
                        val required: List<List<String>>, val blacklist: List<List<String>>)

    private class Active(val revertAt: Long, val revert: List<String>, val entity: PokemonEntity)

    private var stones: List<Stone>? = null
    private var loadedFor: MinecraftServer? = null
    private val active = HashMap<UUID, Active>()
    private val lastRoll = HashMap<UUID, Long>()
    private val MEGA_ASPECTS = setOf("mega", "mega_x", "mega_y", "mega-x", "mega-y", "primal")

    private fun strings(e: JsonElement?): List<String> = when {
        e == null || e.isJsonNull -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
        e.isJsonPrimitive -> listOf(e.asString)
        else -> emptyList()
    }

    private fun nested(e: JsonElement?): List<List<String>> = when {
        e == null || !e.isJsonArray -> emptyList()
        else -> (e as JsonArray).map { strings(it) }
    }

    private fun load(server: MinecraftServer): List<Stone> {
        if (loadedFor === server) stones?.let { return it }
        val list = ArrayList<Stone>()
        runCatching {
            val found = server.resourceManager.listResources("mega_showdown/mega") { it.path.endsWith(".json") }
            for ((id, res) in found) {
                runCatching {
                    val json = res.openAsReader().use { JsonParser.parseReader(it) }.asJsonObject
                    val file = id.path.substringAfterLast('/').removeSuffix(".json")
                    val conditions = json.get("aspect_conditions")
                    val cond: JsonObject? = when {
                        conditions == null -> null
                        conditions.isJsonArray -> conditions.asJsonArray.firstOrNull()?.asJsonObject
                        else -> conditions.asJsonObject
                    }
                    val apply = cond?.getAsJsonObject("apply")
                    val revert = cond?.getAsJsonObject("revert")
                    val applyAspects = strings(apply?.get("aspects")).ifEmpty { listOf("mega_evolution=mega") }
                    val revertAspects = strings(revert?.get("aspects")).ifEmpty { listOf("mega_evolution=none") }
                    val aspectCond = apply?.getAsJsonObject("aspect")
                    val items = linkedSetOf("${id.namespace}:$file", "mega_showdown:$file")
                    for (key in listOf("item", "item_id", "items")) items += strings(json.get(key))
                    val species = strings(json.get("pokemons")).map { it.lowercase().replace(" ", "").replace("-", "") }.toSet()
                    if (species.isNotEmpty()) list += Stone(items, species, applyAspects, revertAspects,
                        nested(aspectCond?.get("required_aspects")), nested(aspectCond?.get("blacklist_aspects")))
                }.onFailure { CobblemonSanctuary.LOGGER.debug("Skipping Mega Showdown file {}", id, it) }
            }
        }.onFailure { CobblemonSanctuary.LOGGER.warn("Couldn't read Mega Showdown's mega data", it) }
        CobblemonSanctuary.LOGGER.info("Sanctuary: found {} Mega Stones from Mega Showdown", list.size)
        stones = list
        loadedFor = server
        return list
    }

    fun isMega(pokemon: Pokemon): Boolean =
        pokemon.aspects.any { it.lowercase() in MEGA_ASPECTS } || pokemon.form.name.lowercase().startsWith("mega")

    /**
     * The Mega Evolution this Pokémon can do. In the sanctuary it doesn't need to hold its Mega Stone (unless the
     * config says so); if it holds one, that one is used (Charizard X or Y), otherwise one of its megas at random.
     */
    private fun stoneFor(server: MinecraftServer, pokemon: Pokemon, random: net.minecraft.util.RandomSource): Stone? {
        val name = pokemon.species.name.lowercase().replace(" ", "").replace("-", "")
        val aspects = pokemon.aspects.map { it.lowercase() }.toSet()
        val options = load(server).filter { st ->
            name in st.species &&
                (st.required.isEmpty() || st.required.any { group -> group.all { it.lowercase() in aspects } }) &&
                st.blacklist.none { group -> group.isNotEmpty() && group.all { it.lowercase() in aspects } }
        }
        if (options.isEmpty()) return null
        val held = pokemon.heldItem()
        if (!held.isEmpty) {
            val itemId = BuiltInRegistries.ITEM.getKey(held.item).toString()
            val path = itemId.substringAfter(':')
            options.firstOrNull { st -> itemId in st.items || st.items.any { it.substringAfter(':') == path } }?.let { return it }
        }
        if (SanctuaryConfig.megaNeedsStone.get()) return null
        return options[random.nextInt(options.size)]
    }

    private fun applyAll(entity: PokemonEntity, props: List<String>) {
        for (p in props) runCatching { PokemonProperties.parse(p).apply(entity) }
            .onFailure { CobblemonSanctuary.LOGGER.debug("Couldn't apply {}", p, it) }
    }

    /** Called when a resident attacks during a play-fight. */
    fun maybeMega(entity: PokemonEntity, s: Sanctuary? = null) {
        if (!megaShowdownLoaded || !SanctuaryConfig.megaEnabled.get()) return
        val level = entity.level() as? ServerLevel ?: return
        val pokemon = entity.pokemon
        val now = level.gameTime
        if (pokemon.uuid in active || isMega(pokemon)) return
        // one roll per play-fight
        val last = lastRoll[pokemon.uuid]
        if (last != null && now - last < 20L * 60) return
        lastRoll[pokemon.uuid] = now
        if (level.random.nextDouble() >= SanctuaryConfig.megaChance.get()) return
        val stone = stoneFor(level.server, pokemon, level.random) ?: return
        val min = SanctuaryConfig.megaMinSeconds.get()
        val max = SanctuaryConfig.megaMaxSeconds.get().coerceAtLeast(min)
        val seconds = min + level.random.nextInt(max - min + 1)
        applyAll(entity, stone.apply)
        active[pokemon.uuid] = Active(now + seconds * 20L, stone.revert, entity)
        level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1f, 1.4f)
        level.sendParticles(ParticleTypes.END_ROD, entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 40, 0.6, 0.8, 0.6, 0.08)
        level.sendParticles(ParticleTypes.FLASH, entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 1, 0.0, 0.0, 0.0, 0.0)
        Effects.play(entity, Effects.CRY)
        s?.logEvent("${pokemon.getDisplayName(false).string} Mega Evolved during a play-fight!")
    }

    /** Ends Mega Evolutions whose time is up. Called every second. */
    fun tick(now: Long) {
        if (active.isEmpty()) return
        val done = active.filter { (_, a) -> now >= a.revertAt || a.entity.isRemoved }
        for ((id, a) in done) {
            active.remove(id)
            if (a.entity.isRemoved) { revertPokemon(a.entity.pokemon, a.revert); continue }
            applyAll(a.entity, a.revert)
            (a.entity.level() as? ServerLevel)?.sendParticles(ParticleTypes.END_ROD, a.entity.x, a.entity.y + a.entity.bbHeight * 0.6, a.entity.z, 20, 0.5, 0.6, 0.5, 0.05)
        }
        if (lastRoll.size > 5000) lastRoll.clear()
    }

    private fun revertPokemon(pokemon: Pokemon, revert: List<String>) {
        for (p in revert) runCatching { PokemonProperties.parse(p).apply(pokemon) }
    }

    /** Before a resident goes back to its PC (despawn, server stop): it shouldn't stay Mega. */
    fun onDespawn(entity: PokemonEntity) {
        val a = active.remove(entity.pokemon.uuid) ?: return
        revertPokemon(entity.pokemon, a.revert)
    }

    /** A resident that is still Mega from before a crash goes back to normal when it comes out. */
    fun cleanStale(pokemon: Pokemon) {
        if (!megaShowdownLoaded || pokemon.uuid in active || !isMega(pokemon)) return
        revertPokemon(pokemon, listOf("mega_evolution=none"))
    }

    fun revertAll() {
        for ((_, a) in active) revertPokemon(a.entity.pokemon, a.revert)
        active.clear()
    }
}
