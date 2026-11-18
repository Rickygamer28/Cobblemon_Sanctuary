package com.rickygamer28.sanctuary.world

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Types other mods add (Gravelmon's, through Gravel's Extended Battles: Blood, Cosmic, Crystal, Digital, Eldritch,
 * Light, Nuclear, Plastic, ???, Shadow, Slime, Sound and Wind; Lost Lore's Shadow and Crystal…).
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
        "blood" to "poison", "eldritch" to "ghost",
    )

    /** Guesses from a type's name, for types this list doesn't know. */
    private val GUESSES = listOf(
        // (Gravelmon's "???" type, whatever its id: in the town, where anyone fits)
        listOf("question", "qmark", "unknown", "mystery", "typeless", "curse", "?") to ZoneLayout.HUB,
        listOf("shadow", "dark", "void", "abyss", "night", "eldritch", "horror", "eerie") to "ghost",
        listOf("blood", "flesh", "vampire", "crimson") to "poison",
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
        "sound" to "Echo canyon", "wind" to "Windswept heights", "blood" to "Crimson marsh", "eldritch" to "Eldritch depths",
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
            listOf(Blocks.BLUE_ORCHID, Blocks.FERN), tree = "swamp_oak", glow = Blocks.OCHRE_FROGLIGHT),
        "sound" to Palette(Blocks.GRASS_BLOCK, Blocks.TUFF, Blocks.DIRT, Blocks.TUFF, Blocks.AMETHYST_BLOCK,
            listOf(Blocks.SHORT_GRASS, Blocks.AZURE_BLUET), glow = Blocks.AMETHYST_CLUSTER),
        "wind" to Palette(Blocks.GRASS_BLOCK, Blocks.CALCITE, Blocks.DIRT, Blocks.CALCITE, Blocks.WHITE_TERRACOTTA,
            listOf(Blocks.FERN, Blocks.SHORT_GRASS, Blocks.CORNFLOWER), tree = "birch"),
        "blood" to Palette(Blocks.CRIMSON_NYLIUM, Blocks.RED_TERRACOTTA, Blocks.NETHERRACK, Blocks.RED_NETHER_BRICKS, Blocks.NETHER_WART_BLOCK,
            listOf(Blocks.CRIMSON_ROOTS, Blocks.CRIMSON_FUNGUS, Blocks.RED_MUSHROOM), tree = "dark_oak", glow = Blocks.SHROOMLIGHT),
        "eldritch" to Palette(Blocks.SCULK, Blocks.DEEPSLATE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.CRYING_OBSIDIAN,
            emptyList(), tree = "dark_oak", glow = Blocks.SCULK_CATALYST),
    )

    /** Palettes by colour, for types this list doesn't know (picked by the type's own colour). */
    private val BY_HUE = listOf(
        0x7B3FA0 to "cosmic", 0x2E2A3A to "shadow", 0xE6E6F0 to "crystal", 0xF6E89A to "light", 0x4FC3D6 to "digital",
        0x8BE04A to "nuclear", 0xF2A2C6 to "plastic", 0x6FBF5A to "slime", 0xB48CD6 to "sound", 0xBFE3F0 to "wind",
        0x9A1B1B to "blood", 0x0F3D44 to "eldritch",
    )

    /** The shape of a custom habitat's ground (and of its pocket, smaller). */
    enum class Terrain { HILLS, PLATEAUS, TERRACES, HOLLOW, CRATER, SPIRES, MARSH, DUNES, CANYON, FLAT }

    /**
     * One of a custom type's three looks: the shape of its ground, and what grows and stands on it. Anything not
     * given comes from the type's palette.
     */
    class Look(
        val name: String, val desc: String, val terrain: Terrain,
        val top: Block? = null, val patch: Block? = null,
        /** Trees: 0 none, 1 a few, 2 a wood (of [tree], or the palette's). */
        val trees: Int = 1, val tree: String? = null,
        /** Tall spires of this block. */
        val spires: Block? = null,
        /** Low outcrops of the palette's crystal. */
        val outcrops: Boolean = true,
        /** Boulders of this block. */
        val boulders: Block? = null,
        /** Lines of the patch block in a grid, with the glow block where they cross. */
        val grid: Boolean = false,
        /** Flat pools of this block in the low middle. */
        val pools: Block? = null,
        /** How many plants (0..1). */
        val plants: Double = 0.3,
        /** How many glowing blocks (1 = the usual). */
        val glow: Double = 1.0,
    )

    /** Each known type's own three looks. */
    private val TYPE_LOOKS: Map<String, List<Look>> = mapOf(
        "shadow" to listOf(
            Look("Shadow hollow", "A dark hollow of blackstone and soul soil, lit by soul lanterns", Terrain.HOLLOW),
            Look("Withered woods", "Rolling hills under a dark oak wood, with wither roses", Terrain.HILLS, patch = Blocks.PODZOL, trees = 2, outcrops = false, plants = 0.45),
            Look("Obsidian spires", "Flat blackstone with tall spires of obsidian rising out of it", Terrain.SPIRES, spires = Blocks.OBSIDIAN, trees = 0, glow = 1.6),
        ),
        "crystal" to listOf(
            Look("Crystal fields", "Calcite hills dotted with amethyst", Terrain.HILLS),
            Look("Geode basin", "A wide basin lined with amethyst, ringed by smooth basalt", Terrain.HOLLOW, patch = Blocks.AMETHYST_BLOCK, pools = Blocks.AMETHYST_BLOCK, trees = 0, glow = 1.8),
            Look("Crystal spires", "Tall amethyst spires over a calcite plain", Terrain.SPIRES, spires = Blocks.AMETHYST_BLOCK, trees = 0),
        ),
        "cosmic" to listOf(
            Look("Starfall crater", "A crater with a raised rim, glowing with end rods", Terrain.CRATER, trees = 0, glow = 1.6),
            Look("Chorus plateaus", "Flat-topped heights of end stone covered in chorus flowers", Terrain.PLATEAUS, trees = 0, plants = 0.5),
            Look("Meteor field", "End stone hills strewn with obsidian meteorites", Terrain.HILLS, boulders = Blocks.OBSIDIAN, trees = 0, outcrops = false),
        ),
        "light" to listOf(
            Look("Radiant meadow", "Bright hills of white flowers and birches", Terrain.HILLS, plants = 0.5),
            Look("Sunlit terraces", "Steps of quartz and grass climbing to the light", Terrain.TERRACES, patch = Blocks.QUARTZ_BLOCK, trees = 1),
            Look("Prism pillars", "A bright plain with tall quartz pillars", Terrain.SPIRES, spires = Blocks.QUARTZ_PILLAR, trees = 0, glow = 1.4),
        ),
        "digital" to listOf(
            Look("Digital grid", "Flat ground ruled into a glowing grid", Terrain.FLAT, grid = true, trees = 0, outcrops = false, glow = 0.0),
            Look("Circuit plateaus", "Flat-topped prismarine heights with circuit lines", Terrain.PLATEAUS, grid = true, trees = 0),
            Look("Data towers", "A grid of tall prismarine towers lit at the top", Terrain.SPIRES, grid = true, spires = Blocks.PRISMARINE_BRICKS, trees = 0, outcrops = false, glow = 0.0),
        ),
        "nuclear" to listOf(
            Look("Glowing wastes", "Mossy hills where green froglights glow", Terrain.HILLS, trees = 0),
            Look("Fallout crater", "A crater of green terracotta glowing at its heart", Terrain.CRATER, top = Blocks.LIME_TERRACOTTA, patch = Blocks.GREEN_TERRACOTTA, trees = 0, glow = 1.6),
            Look("Mutant jungle", "A thick wood of azaleas and moss, with big dripleaf", Terrain.HILLS, trees = 2, tree = "azalea", outcrops = false, plants = 0.5, glow = 0.6),
        ),
        "plastic" to listOf(
            Look("Plastic park", "Pastel hills with tulips", Terrain.HILLS, trees = 0),
            Look("Toy blocks", "Pastel steps scattered with big toy blocks", Terrain.TERRACES, boulders = Blocks.RED_CONCRETE, trees = 0, outcrops = false),
            Look("Candy dunes", "Rolling dunes in pink and white stripes", Terrain.DUNES, trees = 0, plants = 0.15),
        ),
        "slime" to listOf(
            Look("Slime bog", "A low, wet bog of mud and moss under swamp oaks", Terrain.MARSH),
            Look("Ooze pools", "A hollow with pools of slime in its middle", Terrain.HOLLOW, pools = Blocks.SLIME_BLOCK, trees = 1),
            Look("Moss mounds", "Rounded mossy mounds with slime boulders", Terrain.HILLS, top = Blocks.MOSS_BLOCK, patch = Blocks.MUD, boulders = Blocks.SLIME_BLOCK, outcrops = false, plants = 0.45),
        ),
        "sound" to listOf(
            Look("Echo canyon", "Tuff heights split by a winding canyon", Terrain.CANYON, trees = 0),
            Look("Amethyst chimes", "A plain of tall amethyst spires that ring in the wind", Terrain.SPIRES, spires = Blocks.AMETHYST_BLOCK, trees = 0),
            Look("Resonant hills", "Grassy hills with tuff stones and amethyst", Terrain.HILLS, boulders = Blocks.TUFF, plants = 0.4),
        ),
        "wind" to listOf(
            Look("Windswept heights", "High, flat-topped heights of tall grass", Terrain.PLATEAUS, plants = 0.55),
            Look("Rolling downs", "Big smooth hills of long grass, with a lone birch here and there", Terrain.DUNES, outcrops = false, plants = 0.6),
            Look("Cloud peaks", "White peaks of calcite like clouds", Terrain.SPIRES, spires = Blocks.CALCITE, trees = 0),
        ),
        "blood" to listOf(
            Look("Crimson marsh", "A low marsh of crimson nylium, roots and shroomlights", Terrain.MARSH, trees = 0, plants = 0.45),
            Look("Crimson woods", "Hills of red earth under dark oaks", Terrain.HILLS, trees = 2, outcrops = false, plants = 0.35),
            Look("Bloodstone crags", "Red nether brick crags over red terracotta", Terrain.SPIRES, top = Blocks.RED_TERRACOTTA, patch = Blocks.CRIMSON_NYLIUM, spires = Blocks.RED_NETHER_BRICKS, trees = 0),
        ),
        "eldritch" to listOf(
            Look("Eldritch depths", "A hollow of sculk and deepslate, with sculk catalysts", Terrain.HOLLOW, trees = 0),
            Look("Sculk fields", "Rolling sculk with dark oaks and soul lanterns", Terrain.HILLS, patch = Blocks.SOUL_SOIL, trees = 1),
            Look("Void spires", "Deepslate ground with tall spires of crying obsidian", Terrain.SPIRES, spires = Blocks.CRYING_OBSIDIAN, trees = 0),
        ),
    )

    /** The looks of a type this list doesn't know. */
    private val GENERIC_LOOKS = listOf(
        Look("Rolling hills", "Gentle hills of its own ground, with crystals and plants", Terrain.HILLS),
        Look("Plateaus", "Flat-topped heights with steep sides", Terrain.PLATEAUS),
        Look("Crystal hollow", "A wide hollow ringed with tall crystal spires", Terrain.HOLLOW),
    )

    /** The three looks [type] can have. */
    fun looksOf(type: String): List<Look> = TYPE_LOOKS[type.lowercase()] ?: GENERIC_LOOKS

    /** The look [type] has in [s] (its habitat's or its pocket's). */
    fun look(s: Sanctuary, type: String): Look = looksOf(type).let { it[BiomeDresser.variantOf(s, type).coerceIn(0, it.size - 1)] }

    /** Blocks the looks stand up on the ground (spires, boulders, outcrops): decorations, not ground. */
    val DECOR: Set<Block> by lazy {
        (TYPE_LOOKS.values.flatten().flatMap { listOfNotNull(it.spires, it.boulders) } + PALETTES.values.map { it.crystal } +
            listOf(Blocks.OBSIDIAN, Blocks.QUARTZ_PILLAR, Blocks.RED_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIME_CONCRETE, Blocks.SEA_LANTERN)).toSet()
    }

    /**
     * Gravelmon's "???" type: it has no home of its own (no pocket, no habitat). Its Pokémon live in the town,
     * where anyone fits.
     */
    fun townOnly(type: String): Boolean {
        val t = type.lowercase()
        return t.contains('?') || listOf("question", "qmark", "unknown", "mystery", "typeless").any { t.contains(it) }
    }

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
        !townOnly(type) && runCatching { SanctuaryConfig.customTypeBiomes.get() }.getOrDefault(true) && (choice(s, type) ?: OWN) == OWN

    /** How many Pokémon of a custom type it takes for it to get a habitat of its own. */
    fun minResidents(): Int = runCatching { SanctuaryConfig.customHabitatMin.get() }.getOrDefault(15)

    /** How many it takes for a pocket of its own, inside the habitat it lives in (more than [minResidents]: no pocket). */
    fun pocketMin(): Int = runCatching { SanctuaryConfig.customPocketMin.get() }.getOrDefault(5)

    /** Where Pokémon of [type] live in [s]: its own habitat if it has one, or the habitat chosen or guessed. */
    fun zoneFor(s: Sanctuary?, type: String): String {
        val t = type.lowercase()
        if (townOnly(t)) return ZoneLayout.HUB
        if (s != null && t in s.habitats && wantsOwn(s, t)) return t
        val c = choice(s, t)
        if (c != null && c != OWN && (c == ZoneLayout.HUB || c in ZoneLayout.HABITATS)) return c
        return defaultHome(t)
    }

    /** What [type]'s own habitat is called. */
    fun habitatName(type: String): String = NAMES[type.lowercase()] ?: "${typeName(type)} lands"

    /** What [type]'s pocket is called. */
    fun pocketName(type: String): String = "${typeName(type)} pocket"

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
