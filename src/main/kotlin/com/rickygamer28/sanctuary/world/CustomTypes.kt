package com.rickygamer28.sanctuary.world

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Types other mods add (Gravelmon's Cosmic, Crystal, Digital, Light, Nuclear, Plastic, ???, Shadow, Slime, Sound
 * and Wind; Lost Lore's Shadow and Crystal…).
 *
 * Their Pokémon live in a habitat that suits them (Shadow in the Haunted tower, Slime in the Swamp…), or the one the
 * owner picks on the Region tab. Once a custom type has enough Pokémon, it gets a habitat of its own in the valley,
 * built from a palette that fits it, next to the habitat it's closest to.
 */
object CustomTypes {
    /** The owner's choice "its own habitat (once it has enough Pokémon)". */
    const val OWN = "own"

    /** Where each known custom type lives until it has a habitat of its own. */
    private val DEFAULT_HOMES = mapOf(
        "shadow" to "ghost", "crystal" to "rock", "cosmic" to "fairy", "light" to "fairy", "digital" to "electric",
        "nuclear" to "electric", "plastic" to "electric", "slime" to "poison", "sound" to ZoneLayout.HUB, "wind" to ZoneLayout.HUB,
    )

    /** Guesses from a type's name, for types this list doesn't know. */
    private val GUESSES = listOf(
        listOf("shadow", "dark", "void", "abyss", "night") to "ghost",
        listOf("light", "holy", "star", "moon", "sun", "cosmic", "space", "astral", "fairy", "magic") to "fairy",
        listOf("crystal", "gem", "stone", "metal", "earth") to "rock",
        listOf("digital", "cyber", "tech", "nuclear", "atom", "radio", "plastic", "machine", "laser") to "electric",
        listOf("slime", "toxic", "acid", "goo", "ooze") to "poison",
        listOf("frost", "snow", "cold") to "ice",
        listOf("lava", "heat", "magma", "flame") to "fire",
        listOf("sea", "ocean", "tide") to "water",
        listOf("wood", "nature", "plant", "leaf") to "grass",
        listOf("sand", "dust") to "ground",
        listOf("myth", "legend", "ancient") to "dragon",
    )

    /** What a custom type's own habitat is called. */
    private val NAMES = mapOf(
        "shadow" to "Shadow hollow", "crystal" to "Crystal fields", "cosmic" to "Starfall crater", "light" to "Radiant meadow",
        "digital" to "Digital grid", "nuclear" to "Glowing wastes", "plastic" to "Plastic park", "slime" to "Slime bog",
        "sound" to "Echo canyon", "wind" to "Windswept heights",
    )

    /** The blocks a custom habitat is built from: its ground, patches in it, its rock, crystals, plants and trees. */
    class Palette(
        val top: Block, val patch: Block, val filler: Block, val rock: Block, val crystal: Block,
        val plants: List<Block> = emptyList(), val tree: String? = null, val glow: Block? = null,
    )

    private val PALETTES = mapOf(
        "shadow" to Palette(Blocks.BLACKSTONE, Blocks.SOUL_SOIL, Blocks.BLACKSTONE, Blocks.DEEPSLATE, Blocks.CRYING_OBSIDIAN,
            listOf(Blocks.WITHER_ROSE, Blocks.DEAD_BUSH), tree = "dark_oak", glow = Blocks.SOUL_LANTERN),
        "crystal" to Palette(Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.CALCITE, Blocks.CALCITE, Blocks.AMETHYST_BLOCK,
            listOf(Blocks.AZURE_BLUET, Blocks.LILY_OF_THE_VALLEY), glow = Blocks.AMETHYST_CLUSTER),
        "cosmic" to Palette(Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.END_STONE, Blocks.PURPUR_BLOCK, Blocks.CRYING_OBSIDIAN,
            listOf(Blocks.CHORUS_FLOWER), glow = Blocks.END_ROD),
        "light" to Palette(Blocks.GRASS_BLOCK, Blocks.CALCITE, Blocks.DIRT, Blocks.QUARTZ_BLOCK, Blocks.QUARTZ_BLOCK,
            listOf(Blocks.OXEYE_DAISY, Blocks.LILY_OF_THE_VALLEY, Blocks.WHITE_TULIP, Blocks.TORCHFLOWER), tree = "birch", glow = Blocks.PEARLESCENT_FROGLIGHT),
        "digital" to Palette(Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
            emptyList(), glow = Blocks.SEA_LANTERN),
        "nuclear" to Palette(Blocks.MOSS_BLOCK, Blocks.LIME_TERRACOTTA, Blocks.DIRT, Blocks.GREEN_TERRACOTTA, Blocks.VERDANT_FROGLIGHT,
            listOf(Blocks.DEAD_BUSH), glow = Blocks.VERDANT_FROGLIGHT),
        "plastic" to Palette(Blocks.WHITE_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
            listOf(Blocks.PINK_TULIP, Blocks.ALLIUM)),
        "slime" to Palette(Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.PACKED_MUD, Blocks.SLIME_BLOCK,
            listOf(Blocks.SMALL_DRIPLEAF, Blocks.FERN), tree = "swamp_oak", glow = Blocks.OCHRE_FROGLIGHT),
        "sound" to Palette(Blocks.GRASS_BLOCK, Blocks.TUFF, Blocks.DIRT, Blocks.TUFF, Blocks.AMETHYST_BLOCK,
            listOf(Blocks.SHORT_GRASS, Blocks.AZURE_BLUET), glow = Blocks.AMETHYST_CLUSTER),
        "wind" to Palette(Blocks.GRASS_BLOCK, Blocks.CALCITE, Blocks.DIRT, Blocks.CALCITE, Blocks.WHITE_TERRACOTTA,
            listOf(Blocks.TALL_GRASS, Blocks.SHORT_GRASS, Blocks.CORNFLOWER), tree = "birch"),
    )

    /** Palettes by colour, for types this list doesn't know (picked by the type's own colour). */
    private val BY_HUE = listOf(
        0x7B3FA0 to "cosmic", 0x2E2A3A to "shadow", 0xE6E6F0 to "crystal", 0xF6E89A to "light", 0x4FC3D6 to "digital",
        0x8BE04A to "nuclear", 0xF2A2C6 to "plastic", 0x6FBF5A to "slime", 0xB48CD6 to "sound", 0xBFE3F0 to "wind",
    )

    /** The three looks a custom habitat can have. */
    val LOOKS = listOf(
        "Rolling hills" to "Gentle hills of its own ground, with crystals and plants",
        "Plateaus" to "Flat-topped heights with steep sides",
        "Crystal hollow" to "A wide hollow ringed with tall crystal spires",
    )

    /** The types other mods added (not one of the 18). */
    fun all(): List<String> = ZoneLayout.customTypes()

    /** Is [zone] a custom type's (not one of the habitats, not the town)? */
    fun isCustom(zone: String): Boolean = zone != ZoneLayout.HUB && zone !in ZoneLayout.HABITATS && zone != TypeLogic.FLYING

    /** From the config: "type=habitat" entries. */
    private fun configHomes(): Map<String, String> = runCatching {
        SanctuaryConfig.customTypeHomes.get().mapNotNull { e ->
            val (t, h) = e.split('=').map { it.trim().lowercase() }.takeIf { it.size == 2 } ?: return@mapNotNull null
            t to h
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Where Pokémon of [type] live when it has no habitat of its own (the config, this list, or a guess). */
    fun defaultHome(type: String): String {
        val t = type.lowercase()
        configHomes()[t]?.let { if (it == ZoneLayout.HUB || it in ZoneLayout.HABITATS) return it }
        DEFAULT_HOMES[t]?.let { return it }
        for ((words, zone) in GUESSES) if (words.any { t.contains(it) }) return zone
        return ZoneLayout.HUB
    }

    /** The owner's choice for [type] (a habitat, the town, or [OWN]), or null if not chosen (= [OWN]). */
    fun choice(s: Sanctuary?, type: String): String? = s?.customHomes?.get(type.lowercase())

    /** Does [type] get a habitat of its own in [s] once it has enough Pokémon? */
    fun wantsOwn(s: Sanctuary?, type: String): Boolean =
        runCatching { SanctuaryConfig.customTypeBiomes.get() }.getOrDefault(true) && (choice(s, type) ?: OWN) == OWN

    /** How many Pokémon of a custom type it takes for it to get a habitat of its own. */
    fun minResidents(): Int = runCatching { SanctuaryConfig.customHabitatMin.get() }.getOrDefault(10)

    /** Where Pokémon of [type] live in [s]: its own habitat if it has one, or the habitat chosen or guessed. */
    fun zoneFor(s: Sanctuary?, type: String): String {
        val t = type.lowercase()
        if (s != null && t in s.habitats && wantsOwn(s, t)) return t
        val c = choice(s, t)
        if (c != null && c != OWN && (c == ZoneLayout.HUB || c in ZoneLayout.HABITATS)) return c
        return defaultHome(t)
    }

    /** What [type]'s own habitat is called. */
    fun habitatName(type: String): String = NAMES[type.lowercase()] ?: "${typeName(type)} lands"

    /** The type's own name, as its mod shows it. */
    fun typeName(type: String): String =
        runCatching { ElementalTypes.get(type)?.displayName?.string }.getOrNull() ?: type.replaceFirstChar { it.uppercase() }

    /** The type's colour (its mod's). */
    fun color(type: String): Int = runCatching { ElementalTypes.get(type)?.hue }.getOrNull() ?: 0xA8A878

    /** The palette [type]'s habitat is built from: its own, or the one closest to its colour. */
    fun palette(type: String): Palette {
        val t = type.lowercase()
        PALETTES[t]?.let { return it }
        val c = color(t)
        fun dist(a: Int, b: Int): Int {
            val dr = (a shr 16 and 255) - (b shr 16 and 255)
            val dg = (a shr 8 and 255) - (b shr 8 and 255)
            val db = (a and 255) - (b and 255)
            return dr * dr + dg * dg + db * db
        }
        return PALETTES[BY_HUE.minBy { dist(it.first, c) }.second]!!
    }
}
