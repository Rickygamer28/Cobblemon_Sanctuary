package com.rickygamer28.sanctuary.world

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.data.ResidentData
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/** Decides where a Pokémon lives, based on the typing of its final evolution. */
object TypeLogic {
    const val FLYING = "flying"
    private val finalTypeCache = ConcurrentHashMap<String, List<String>>()

    fun clearCache() = finalTypeCache.clear()

    /** Current typing, lowercase, in order. */
    fun currentTypes(pokemon: Pokemon): List<String> = pokemon.form.types.map { it.name.lowercase() }

    /** Typing of the Pokémon's final evolution; branching lines (Eevee, Tyrogue…) keep their own typing. */
    fun finalTypes(pokemon: Pokemon): List<String> {
        val form = pokemon.form
        val key = "${pokemon.species.resourceIdentifier}/${form.name}"
        return finalTypeCache.getOrPut(key) {
            runCatching { resolve(form, 0) }.getOrElse { typesOf(form) }
        }
    }

    /** Types that have a biome (everything except Flying). Empty = a pure Flying "traveler". */
    fun zoneTypes(pokemon: Pokemon): List<String> = finalTypes(pokemon).filter { it != FLYING }

    fun isTraveler(pokemon: Pokemon) = zoneTypes(pokemon).isEmpty()

    fun canFlyInFlocks(pokemon: Pokemon) = FLYING in currentTypes(pokemon)

    /** The zone this Pokémon calls home, or null for a traveler that hasn't settled anywhere. */
    fun homeZone(pokemon: Pokemon, data: ResidentData?, ring: List<String>): String? {
        val options = zoneTypes(pokemon)
        val choice = data?.homeChoice
        if (choice != null) {
            if (choice in options) return choice
            if (options.isEmpty() && (choice == ZoneLayout.HUB || choice in ring)) return choice
        }
        return options.firstOrNull()
    }

    /** The other biome a dual-type Pokémon belongs to, if any. */
    fun secondZone(pokemon: Pokemon, home: String?): String? = zoneTypes(pokemon).firstOrNull { it != home }

    private fun typesOf(form: FormData): List<String> = form.types.map { it.name.lowercase() }

    private fun resolve(form: FormData, depth: Int): List<String> {
        val evolutions = form.evolutions
        if (evolutions.isEmpty() || depth > 6) return typesOf(form)
        val results = evolutions.mapNotNull { targetForm(it.result, form) }
            .distinctBy { it.species.resourceIdentifier }
        if (results.size != 1) return typesOf(form)
        return resolve(results[0], depth + 1)
    }

    private fun targetForm(props: PokemonProperties, from: FormData): FormData? {
        val name = props.species ?: return null
        val species = if (':' in name) {
            PokemonSpecies.getByIdentifier(ResourceLocation.parse(name))
        } else {
            PokemonSpecies.getByName(name.lowercase())
        } ?: return null
        val formName = props.form
        val forms = species.forms
        return forms.firstOrNull { formName != null && it.name.equals(formName, ignoreCase = true) }
            ?: forms.firstOrNull { !from.name.equals("normal", ignoreCase = true) && it.name.equals(from.name, ignoreCase = true) }
            ?: species.standardForm
    }
}
