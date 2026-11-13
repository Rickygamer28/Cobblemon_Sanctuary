package com.rickygamer28.sanctuary

import net.neoforged.neoforge.common.ModConfigSpec

/** Server-side settings (config/cobblemon_sanctuary-server.toml inside the world's serverconfig folder). */
object SanctuaryConfig {
    private val builder = ModConfigSpec.Builder()

    val spawnCapSmall: ModConfigSpec.IntValue = builder
        .comment("How many residents can be out at once in a Small sanctuary (the rest rest in their homes and swap in as you walk around).")
        .defineInRange("residentsOutSmall", 100, 4, 2000)
    val spawnCapMedium: ModConfigSpec.IntValue = builder
        .comment("How many residents can be out at once in a Medium sanctuary.")
        .defineInRange("residentsOutMedium", 180, 4, 2000)
    val spawnCapLarge: ModConfigSpec.IntValue = builder
        .comment("How many residents can be out at once in a Large sanctuary.")
        .defineInRange("residentsOutLarge", 260, 4, 2000)
    val spawnCapHuge: ModConfigSpec.IntValue = builder
        .comment("How many residents can be out at once in a Huge sanctuary.")
        .defineInRange("residentsOutHuge", 320, 4, 2000)

    val spawnRange: ModConfigSpec.IntValue = builder
        .comment("Residents come out in the parts of the valley within this many blocks of a player.")
        .defineInRange("residentRange", 128, 32, 512)

    val allowSmall: ModConfigSpec.BooleanValue = builder.comment("Allow the Small size (radius 128).").define("allowSmall", true)
    val allowMedium: ModConfigSpec.BooleanValue = builder.comment("Allow the Medium size (radius 224).").define("allowMedium", true)
    val allowLarge: ModConfigSpec.BooleanValue = builder.comment("Allow the Large size (radius 320).").define("allowLarge", true)
    val allowHuge: ModConfigSpec.BooleanValue = builder.comment("Allow the Huge size (radius 448).").define("allowHuge", true)

    val mediumResidents: ModConfigSpec.IntValue = builder
        .comment("Residents needed to unlock the Medium size.")
        .defineInRange("mediumResidents", 100, 0, 10000)

    val largeResidents: ModConfigSpec.IntValue = builder
        .comment("Residents needed to unlock the Large size.")
        .defineInRange("largeResidents", 300, 0, 10000)

    val hugeResidents: ModConfigSpec.IntValue = builder
        .comment("Residents needed to unlock the Huge size.")
        .defineInRange("hugeResidents", 600, 0, 100000)

    val generationSpeed: ModConfigSpec.IntValue = builder
        .comment("Most terrain columns dressed per tick while a sanctuary is growing.")
        .defineInRange("maxColumnsPerTick", 4096, 8, 65536)

    val generationMillis: ModConfigSpec.IntValue = builder
        .comment("Milliseconds per tick the sanctuary may spend building biomes (higher = faster building, more server load).")
        .defineInRange("generationMillis", 12, 1, 40)

    val hubPercent: ModConfigSpec.IntValue = builder
        .comment("Size of the Normal hub, as a percentage of the sanctuary's radius. Use Rebuild biomes after changing it.")
        .defineInRange("hubPercent", 33, 15, 60)

    val blockWildSpawns: ModConfigSpec.BooleanValue = builder
        .comment("Stop wild Pokémon from spawning inside sanctuaries, so only residents live there.")
        .define("blockWildSpawns", true)

    val fillRivers: ModConfigSpec.BooleanValue = builder
        .comment("Fill rivers and ponds so biomes continue across them.")
        .define("fillRivers", true)

    val fillWaterDepth: ModConfigSpec.IntValue = builder
        .comment("Deepest water (rivers, lakes, ocean) that gets filled in so the biomes continue as land. The Water biome keeps its water.")
        .defineInRange("maxFillDepth", 64, 1, 128)

    val buildWall: ModConfigSpec.BooleanValue = builder
        .comment("Build a wall with gates around the sanctuary's edge.")
        .define("buildWall", true)

    val reshapeTerrain: ModConfigSpec.BooleanValue = builder
        .comment("Reshape the terrain to fit each biome (volcano, lakes, dunes, mesas, peaks, flat hub).")
        .define("reshapeTerrain", true)

    val clearTrees: ModConfigSpec.BooleanValue = builder
        .comment("Clear natural trees inside the sanctuary so the biomes can grow their own plants. Player builds are never touched.")
        .define("clearTrees", true)

    val growthFullAt: ModConfigSpec.IntValue = builder
        .comment("Pokémon of one type needed for its biome to reach full size.")
        .defineInRange("growthFullAt", 20, 1, 1000)

    val friendshipRate: ModConfigSpec.DoubleValue = builder
        .comment("Multiplier for how fast friendships form (1.0 = default).")
        .defineInRange("friendshipRate", 1.0, 0.0, 10.0)

    val rivalryRate: ModConfigSpec.DoubleValue = builder
        .comment("Multiplier for how fast rivalries form (1.0 = default).")
        .defineInRange("rivalryRate", 1.0, 0.0, 10.0)

    val personalDislikeChance: ModConfigSpec.DoubleValue = builder
        .comment("Chance that two Pokémon simply don't get along when they first meet.")
        .defineInRange("personalDislikeChance", 0.08, 0.0, 1.0)

    val meetingChance: ModConfigSpec.DoubleValue = builder
        .comment("Chance per second that two nearby idle residents start interacting.")
        .defineInRange("meetingChance", 0.15, 0.0, 1.0)

    val megaChance: ModConfigSpec.DoubleValue = builder
        .comment("Chance that a resident holding its Mega Stone Mega Evolves during a play-fight (needs Mega Showdown).")
        .defineInRange("megaChance", 0.35, 0.0, 1.0)

    val megaMinSeconds: ModConfigSpec.IntValue = builder.defineInRange("megaMinSeconds", 30, 1, 3600)
    val megaMaxSeconds: ModConfigSpec.IntValue = builder.defineInRange("megaMaxSeconds", 300, 1, 3600)

    val megaEnabled: ModConfigSpec.BooleanValue = builder
        .comment("Let residents Mega Evolve during play-fights (needs Mega Showdown and the Pokémon holding its Mega Stone).")
        .define("megaEnabled", true)

    // ---- families and eggs
    val eggsEnabled: ModConfigSpec.BooleanValue = builder
        .comment("Close friends can start a family and lay eggs in the sanctuary. Eggs never hatch on their own.")
        .define("eggsEnabled", true)
    val familyFriendship: ModConfigSpec.IntValue = builder
        .comment("How close two residents must be (relationship, 0..100) before they can have an egg.")
        .defineInRange("familyFriendship", 70, 0, 100)
    val eggChance: ModConfigSpec.DoubleValue = builder
        .comment("Chance that a nuzzle between two close friends ends with an egg.")
        .defineInRange("eggChance", 0.2, 0.0, 1.0)
    val eggCooldownMinutes: ModConfigSpec.IntValue = builder
        .comment("Minutes before the same pair can have another egg.")
        .defineInRange("eggCooldownMinutes", 30, 0, 100000)
    val maxEggs: ModConfigSpec.IntValue = builder
        .comment("Most eggs that can wait in the nests of one sanctuary.")
        .defineInRange("maxEggs", 40, 1, 500)
    val eggStepsPerCycle: ModConfigSpec.IntValue = builder
        .comment("Blocks to walk per egg cycle when carrying an egg (most Pokémon have 20 cycles).")
        .defineInRange("eggStepsPerCycle", 32, 1, 1000)
    val incubatorSecondsPerCycle: ModConfigSpec.IntValue = builder
        .comment("Seconds per egg cycle in an Incubator.")
        .defineInRange("incubatorSecondsPerCycle", 12, 1, 3600)

    val maxSanctuariesPerPlayer: ModConfigSpec.IntValue = builder
        .comment("How many sanctuaries one player can own or belong to.")
        .defineInRange("maxSanctuariesPerPlayer", 1, 1, 16)

    val spec: ModConfigSpec = builder.build()
}
