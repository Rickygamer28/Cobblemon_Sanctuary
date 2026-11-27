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
import java.util.UUID

/**
 * Mega Evolution, Primal Reversion and other form changes during play-fights, with Cobblemon: Mega Showdown installed.
 *
 * Mega Showdown describes every Mega Stone in a data file (`data/<ns>/mega_showdown/mega/<stone>.json`) with the
 * species it works for and the aspects to apply ("mega_evolution=mega") and to revert ("mega_evolution=none"), and
 * its other form changes the same way: items held (the Red and Blue Orbs for Primal Groudon and Kyogre), items used
 * on a Pokémon (the Gracidea for Shaymin's Sky Forme) and forms taken in battle (Aegislash's Blade Forme, Darmanitan's
 * Zen Mode…). Those files are read here, so the sanctuary works with every Mega Showdown version and with custom ones.
 * A changed Pokémon keeps its original typing for its home (see TypeLogic), and goes back after a while.
 *
 * Addons that give a species Mega or Primal forms of their own without Mega Showdown files (Gravelmon's fakemon
 * megas, say) work too: those forms are found in the species' data and taken through their aspects.
 */
object MegaCompat {
    val megaShowdownLoaded: Boolean by lazy { com.rickygamer28.sanctuary.Platform.current.isModLoaded("mega_showdown") }

    /** What a change is: a Mega Evolution, a Primal Reversion, or another form change (and from which kind of file). */
    enum class Kind { MEGA, PRIMAL, FORM }

    private class Stone(val items: Set<String>, val species: Set<String>, val apply: List<String>, val revert: List<String>,
                        val required: List<List<String>>, val blacklist: List<List<String>>, val kind: Kind, val held: Boolean = false)

    /** Mega Showdown's folders of form changes, and whether the item has to be held for the form (then it's Mega Showdown's to keep). */
    private val FOLDERS = listOf("mega" to false, "held_form_change" to true, "form_change_interact" to false, "battle_form" to false)
    private const val SAVED_REVERT = "sanctuary_form_revert"
    /** "Add this aspect" / "take this aspect away": how a species' own Mega or Primal form is taken and left. */
    private const val ADD = "sanctuary+aspect:"
    private const val REMOVE = "sanctuary-aspect:"

    /** The Mega and Primal forms a species has in its own data (an addon's), as changes; none for its normal form already changed. */
    private fun ownForms(pokemon: Pokemon): List<Stone> {
        if (pokemon.form != pokemon.species.standardForm) return emptyList()
        return pokemon.species.forms.mapNotNull { f ->
            val n = f.name.lowercase()
            val kind = when { n.startsWith("mega") -> Kind.MEGA; n == "primal" || n.startsWith("primal") -> Kind.PRIMAL; else -> return@mapNotNull null }
            val aspects = f.aspects.filter { it.isNotBlank() }.ifEmpty { return@mapNotNull null }
            Stone(emptySet(), emptySet(), aspects.map { ADD + it }, aspects.map { REMOVE + it }, emptyList(), emptyList(), kind)
        }
    }

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
        for ((folder, heldForm) in FOLDERS) runCatching {
            val found = server.resourceManager.listResources("mega_showdown/$folder") { it.path.endsWith(".json") }
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
                    val mega = folder == "mega"
                    val applyAspects = strings(apply?.get("aspects")).ifEmpty { if (mega) listOf("mega_evolution=mega") else emptyList() }
                    val revertAspects = strings(revert?.get("aspects")).ifEmpty { if (mega) listOf("mega_evolution=none") else emptyList() }
                    // (a form change needs both ways, or the Pokémon couldn't go back)
                    if (applyAspects.isEmpty() || revertAspects.isEmpty()) return@runCatching
                    val kind = when {
                        applyAspects.any { it.lowercase().endsWith("=primal") } -> Kind.PRIMAL
                        mega -> Kind.MEGA
                        else -> Kind.FORM
                    }
                    val aspectCond = apply?.getAsJsonObject("aspect")
                    val items = linkedSetOf("${id.namespace}:$file", "mega_showdown:$file")
                    for (key in listOf("item", "item_id", "items")) items += strings(json.get(key))
                    val species = (strings(json.get("pokemons")) + strings(json.get("pokemon"))).map { it.lowercase().replace(" ", "").replace("-", "") }.toSet()
                    if (species.isNotEmpty()) list += Stone(items, species, applyAspects, revertAspects,
                        nested(aspectCond?.get("required_aspects")), nested(aspectCond?.get("blacklist_aspects")), kind, heldForm)
                }.onFailure { CobblemonSanctuary.LOGGER.debug("Skipping Mega Showdown file {}", id, it) }
            }
        }.onFailure { CobblemonSanctuary.LOGGER.warn("Couldn't read Mega Showdown's {} data", folder, it) }
        CobblemonSanctuary.LOGGER.info("Sanctuary: found {} Mega Stones, {} Primal Reversions and {} other form changes from Mega Showdown",
            list.count { it.kind == Kind.MEGA }, list.count { it.kind == Kind.PRIMAL }, list.count { it.kind == Kind.FORM })
        stones = list
        loadedFor = server
        return list
    }

    fun isMega(pokemon: Pokemon): Boolean =
        pokemon.aspects.any { it.lowercase() in MEGA_ASPECTS } || pokemon.form.name.lowercase().startsWith("mega")

    /** Is the Pokémon in a form it took for a while in the sanctuary (a Mega, a Primal, Sky Forme…)? Its home stays its normal form's. */
    fun isChanged(pokemon: Pokemon): Boolean =
        pokemon.uuid in active || runCatching { pokemon.persistentData.contains(SAVED_REVERT) }.getOrDefault(false) || isMega(pokemon)

    /**
     * The Mega Evolution this Pokémon can do. In the sanctuary it doesn't need to hold its Mega Stone (unless the
     * config says so); if it holds one, that one is used (Charizard X or Y), otherwise one of its megas at random.
     */
    private fun stoneFor(server: MinecraftServer, pokemon: Pokemon, random: net.minecraft.util.RandomSource): Stone? {
        val name = pokemon.species.name.lowercase().replace(" ", "").replace("-", "")
        val aspects = pokemon.aspects.map { it.lowercase() }.toSet()
        val megas = SanctuaryConfig.megaEnabled.get()
        val forms = SanctuaryConfig.formsEnabled.get()
        val skip = runCatching { SanctuaryConfig.formSkip.get().map { it.lowercase() } }.getOrDefault(listOf("shadow"))
        val heldId = pokemon.heldItem().takeIf { !it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        fun holds(st: Stone) = heldId != null && (heldId in st.items || st.items.any { it.substringAfter(':') == heldId.substringAfter(':') })
        val options = load(server).filter { st ->
            name in st.species &&
                (if (st.kind == Kind.FORM) forms else megas) &&
                st.apply.none { a -> skip.any { w -> w.isNotBlank() && a.lowercase().contains(w) } } &&
                // a form its held item gives it is Mega Showdown's to keep: it isn't changed back and forth here
                !(st.held && holds(st)) &&
                (st.required.isEmpty() || st.required.any { group -> group.all { it.lowercase() in aspects } }) &&
                st.blacklist.none { group -> group.isNotEmpty() && group.all { it.lowercase() in aspects } }
        }
        if (options.isEmpty()) {
            if (!megas) return null
            val own = ownForms(pokemon)
            if (own.isEmpty() || SanctuaryConfig.megaNeedsStone.get()) return null
            return own[random.nextInt(own.size)]
        }
        options.firstOrNull { holds(it) }?.let { return it }
        if (SanctuaryConfig.megaNeedsStone.get()) return null
        return options[random.nextInt(options.size)]
    }

    /** An aspect added or taken away by hand (a species' own form), true if [p] was one. */
    private fun ownAspect(pokemon: Pokemon, p: String): Boolean {
        val add = p.startsWith(ADD)
        if (!add && !p.startsWith(REMOVE)) return false
        val aspect = p.substring(if (add) ADD.length else REMOVE.length)
        runCatching {
            pokemon.forcedAspects = if (add) pokemon.forcedAspects + aspect else pokemon.forcedAspects - aspect
            pokemon.updateAspects()
            pokemon.updateForm()
        }.onFailure { CobblemonSanctuary.LOGGER.debug("Couldn't change aspect {}", p, it) }
        return true
    }

    private fun applyAll(entity: PokemonEntity, props: List<String>) {
        for (p in props) {
            if (ownAspect(entity.pokemon, p)) continue
            runCatching { PokemonProperties.parse(p).apply(entity) }
                .onFailure { CobblemonSanctuary.LOGGER.debug("Couldn't apply {}", p, it) }
        }
    }

    /** Called when a resident attacks during a play-fight. */
    fun maybeMega(entity: PokemonEntity, s: Sanctuary? = null) {
        if (!(SanctuaryConfig.megaEnabled.get() || SanctuaryConfig.formsEnabled.get())) return
        // (without Mega Showdown, only species with Mega or Primal forms of their own can change)
        if (!megaShowdownLoaded && (!SanctuaryConfig.megaEnabled.get() || ownForms(entity.pokemon).isEmpty())) return
        val level = entity.level() as? ServerLevel ?: return
        val pokemon = entity.pokemon
        val now = level.gameTime
        if (isChanged(pokemon)) return
        // one roll per play-fight
        val last = lastRoll[pokemon.uuid]
        if (last != null && now - last < 20L * 60) return
        lastRoll[pokemon.uuid] = now
        if (level.random.nextDouble() >= SanctuaryConfig.megaChance.get()) return
        val stone = stoneFor(level.server, pokemon, level.random) ?: return
        val min = SanctuaryConfig.megaMinSeconds.get()
        val max = SanctuaryConfig.megaMaxSeconds.get().coerceAtLeast(min)
        val seconds = min + level.random.nextInt(max - min + 1)
        val before = pokemon.aspects.toSet()
        val formBefore = pokemon.form.name
        applyAll(entity, stone.apply)
        // nothing changed (it was in that form already): no show
        if (pokemon.aspects.toSet() == before && pokemon.form.name == formBefore) return
        active[pokemon.uuid] = Active(now + seconds * 20L, stone.revert, entity)
        // remembered on the Pokémon too, so it goes back even after a crash
        runCatching { pokemon.persistentData.putString(SAVED_REVERT, stone.revert.joinToString("\n")) }
        level.playSound(null, entity.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1f, 1.4f)
        level.sendParticles(ParticleTypes.END_ROD, entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 40, 0.6, 0.8, 0.6, 0.08)
        level.sendParticles(ParticleTypes.FLASH, entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 1, 0.0, 0.0, 0.0, 0.0)
        Effects.play(entity, Effects.CRY)
        val who = pokemon.getDisplayName(false).string
        val formName = pokemon.form.name.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        s?.logEvent(when (stone.kind) {
            Kind.MEGA -> "$who Mega Evolved during a play-fight!"
            Kind.PRIMAL -> "$who underwent Primal Reversion during a play-fight!"
            Kind.FORM -> if (formName.isNotBlank() && formName != formBefore && !formName.equals("normal", true)) "$who changed into its $formName form during a play-fight!"
                else "$who changed form during a play-fight!"
        })
        s?.stat(if (stone.kind == Kind.FORM) "forms" else "megas")
    }

    /** Ends Mega Evolutions whose time is up. Called every second. */
    fun tick(now: Long) {
        if (active.isEmpty()) return
        val done = active.filter { (_, a) -> now >= a.revertAt || a.entity.isRemoved }
        for ((id, a) in done) {
            active.remove(id)
            if (a.entity.isRemoved) { revertPokemon(a.entity.pokemon, a.revert); continue }
            applyAll(a.entity, a.revert)
            runCatching { a.entity.pokemon.persistentData.remove(SAVED_REVERT) }
            (a.entity.level() as? ServerLevel)?.sendParticles(ParticleTypes.END_ROD, a.entity.x, a.entity.y + a.entity.bbHeight * 0.6, a.entity.z, 20, 0.5, 0.6, 0.5, 0.05)
        }
        if (lastRoll.size > 5000) lastRoll.clear()
    }

    private fun revertPokemon(pokemon: Pokemon, revert: List<String>) {
        for (p in revert) if (!ownAspect(pokemon, p)) runCatching { PokemonProperties.parse(p).apply(pokemon) }
        runCatching { pokemon.persistentData.remove(SAVED_REVERT) }
    }

    /** Before a resident goes back to its PC (despawn, server stop): it shouldn't stay Mega. */
    fun onDespawn(entity: PokemonEntity) {
        val a = active.remove(entity.pokemon.uuid) ?: return
        revertPokemon(entity.pokemon, a.revert)
    }

    /** A resident that is still Mega from before a crash goes back to normal when it comes out. */
    fun cleanStale(pokemon: Pokemon) {
        if (pokemon.uuid in active) return
        val saved = runCatching { pokemon.persistentData.getString(SAVED_REVERT) }.getOrDefault("")
        when {
            saved.isNotEmpty() -> revertPokemon(pokemon, saved.split('\n'))
            megaShowdownLoaded && isMega(pokemon) -> revertPokemon(pokemon, listOf("mega_evolution=none"))
        }
    }

    fun revertAll() {
        for ((_, a) in active) revertPokemon(a.entity.pokemon, a.revert)
        active.clear()
    }
}
