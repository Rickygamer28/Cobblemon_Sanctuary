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

    /** The form used for typing: a Mega (or Primal) keeps the typing of its normal form. */
    private fun baseForm(pokemon: Pokemon): FormData {
        val name = pokemon.form.name.lowercase()
        return if (name.startsWith("mega") || name == "primal") pokemon.species.standardForm else pokemon.form
    }

    /** Current typing, lowercase, in order (a Mega keeps its original typing). */
    fun currentTypes(pokemon: Pokemon): List<String> = baseForm(pokemon).types.map { it.name.lowercase() }

    /** Typing of the Pokémon's final evolution; branching lines (Eevee, Tyrogue…) keep their own typing. */
    fun finalTypes(pokemon: Pokemon): List<String> {
        val form = baseForm(pokemon)
        val key = "${pokemon.species.resourceIdentifier}/${form.name}"
        return finalTypeCache.getOrPut(key) {
            runCatching { resolve(form, 0) }.getOrElse { typesOf(form) }
        }
    }

    /** Types that have a biome (everything except Flying). Empty = a pure Flying "traveler". */
    /**
     * The habitats a Pokémon belongs to, from its types (a Bug type lives in the forest, a Steel type in the cave
     * and mountain…). Types without a habitat (ones other mods add) are skipped; a Pokémon with only such types,
     * or a Normal type, lives in the town and meadows.
     */
    fun zoneTypes(pokemon: Pokemon, s: com.rickygamer28.sanctuary.data.Sanctuary? = null): List<String> {
        val types = finalTypes(pokemon).filter { it != FLYING }
        // a custom type (another mod's): its own habitat if it has one here, or the habitat that suits it
        val homes = types.map { ZoneLayout.habitatOf(it) ?: CustomTypes.zoneFor(s, it) }.distinct()
        return if (homes.isEmpty() && types.isNotEmpty()) listOf(ZoneLayout.HUB) else homes
    }

    fun isTraveler(pokemon: Pokemon) = zoneTypes(pokemon).isEmpty()

    /** The custom types (other mods') a Pokémon has. */
    fun customTypes(pokemon: Pokemon): List<String> = finalTypes(pokemon).filter { it != FLYING && ZoneLayout.habitatOf(it) == null }

    fun canFlyInFlocks(pokemon: Pokemon) = FLYING in currentTypes(pokemon)

    /** Pokémon that can't go on land (Sharpedo, Wailord, Magikarp…), from Cobblemon's own movement data. */
    fun waterOnly(pokemon: Pokemon): Boolean = runCatching {
        val moving = pokemon.form.behaviour.moving
        val landless = !moving.walk.canWalk || moving.walk.avoidsLand
        // a Water type that can't get around on land: fish and whales, but also sea-floor dwellers like Shellos or
        // Corsola. Pokémon that fly or float (Pelipper, Jellicent) can cross land, and non-Water floaters like
        // Doublade aren't sea creatures at all.
        landless && !moving.fly.canFly && pokemon.form.types.any { it.name.equals("water", ignoreCase = true) }
    }.getOrDefault(false)

    /** The zone this Pokémon calls home, or null for a traveler that hasn't settled anywhere. */
    /** The habitat a Pokémon lives in; [ring] is the habitats on the map (a habitat that isn't there yet → meadows). */
    fun homeZone(pokemon: Pokemon, data: ResidentData?, ring: List<String>, s: com.rickygamer28.sanctuary.data.Sanctuary? = null): String? {
        if ("water" in ring && waterOnly(pokemon)) return "water"
        val all = zoneTypes(pokemon, s)
        val options = all.filter { it == ZoneLayout.HUB || it in ring }.ifEmpty { if (all.isEmpty()) all else listOf(ZoneLayout.HUB) }
        val choice = data?.homeChoice
        if (choice != null) {
            if (choice in options) return choice
            if (options.isEmpty() && (choice == ZoneLayout.HUB || choice in ring)) return choice
        }
        return options.firstOrNull()
    }

    /** The other biome a dual-type Pokémon belongs to, if any. */
    fun secondZone(pokemon: Pokemon, home: String?, ring: List<String> = ZoneLayout.HABITATS, s: com.rickygamer28.sanctuary.data.Sanctuary? = null): String? =
        if (waterOnly(pokemon)) null else zoneTypes(pokemon, s).firstOrNull { it != home && (it == ZoneLayout.HUB || it in ring) }

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
