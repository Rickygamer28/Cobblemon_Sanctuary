package com.rickygamer28.sanctuary

import net.neoforged.neoforge.common.ModConfigSpec

/** Server-side settings (config/cobblemon_sanctuary-server.toml inside the world's serverconfig folder). */
object SanctuaryConfig {
    private val builder = ModConfigSpec.Builder()

    val spawnCap: ModConfigSpec.IntValue = builder
        .comment("How many resident Pokémon can be spawned at once in one sanctuary (the rest live there virtually).")
        .defineInRange("spawnCap", 60, 4, 400)

    val spawnRange: ModConfigSpec.IntValue = builder
        .comment("Residents are spawned only in biomes within this many blocks of a player.")
        .defineInRange("spawnRange", 96, 32, 256)

    val allowSmall: ModConfigSpec.BooleanValue = builder.comment("Allow the Small size (radius 96).").define("allowSmall", true)
    val allowMedium: ModConfigSpec.BooleanValue = builder.comment("Allow the Medium size (radius 160).").define("allowMedium", true)
    val allowLarge: ModConfigSpec.BooleanValue = builder.comment("Allow the Large size (radius 224).").define("allowLarge", true)

    val mediumResidents: ModConfigSpec.IntValue = builder
        .comment("Residents needed to unlock the Medium size.")
        .defineInRange("mediumResidents", 100, 0, 10000)

    val largeResidents: ModConfigSpec.IntValue = builder
        .comment("Residents needed to unlock the Large size.")
        .defineInRange("largeResidents", 300, 0, 10000)

    val generationSpeed: ModConfigSpec.IntValue = builder
        .comment("Most terrain columns dressed per tick while a sanctuary is growing.")
        .defineInRange("generationSpeed", 4096, 8, 65536)

    val generationMillis: ModConfigSpec.IntValue = builder
        .comment("Milliseconds per tick the sanctuary may spend building biomes (higher = faster building, more server load).")
        .defineInRange("generationMillis", 12, 1, 40)

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

    val maxSanctuariesPerPlayer: ModConfigSpec.IntValue = builder
        .comment("How many sanctuaries one player can own or belong to.")
        .defineInRange("maxSanctuariesPerPlayer", 1, 1, 16)

    val spec: ModConfigSpec = builder.build()
}
