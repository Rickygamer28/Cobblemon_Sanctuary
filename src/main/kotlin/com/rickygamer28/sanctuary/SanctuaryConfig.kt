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

    val generationMillisMax: ModConfigSpec.IntValue = builder
        .comment("When the server has time to spare, building may use up to this many milliseconds per tick (it drops back to generationMillis when the server gets busy). Set it to generationMillis to turn this off.")
        .defineInRange("generationMillisMax", 30, 1, 45)

    val townPercent: ModConfigSpec.IntValue = builder
        .comment("Size of the town in the middle of the valley, as a percentage of its radius (the rest is habitats, meadows and routes). Reshape after changing it.")
        .defineInRange("townPercent", 14, 8, 40)

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
        .comment("Chance that a resident with a Mega Evolution Mega Evolves during a play-fight (needs Mega Showdown).")
        .defineInRange("megaChance", 0.35, 0.0, 1.0)

    val megaMinSeconds: ModConfigSpec.IntValue = builder.defineInRange("megaMinSeconds", 30, 1, 3600)
    val megaMaxSeconds: ModConfigSpec.IntValue = builder.defineInRange("megaMaxSeconds", 300, 1, 3600)

    val basketEnabled: ModConfigSpec.BooleanValue = builder
        .comment("Residents bring their Cobblemon drops, berries and apricorns to a Gathering Basket placed in the valley.")
        .define("basketEnabled", true)
    val basketMinutes: ModConfigSpec.IntValue = builder
        .comment("Minutes between gathering trips (a few residents bring something each time).")
        .defineInRange("basketMinutes", 5, 1, 1440)
    val basketItemCap: ModConfigSpec.IntValue = builder
        .comment("Residents stop bringing an item once the basket holds this many of it.")
        .defineInRange("basketItemCap", 32, 1, 3456)
    val jobRate: ModConfigSpec.DoubleValue = builder
        .comment("How often residents' jobs turn up something for the basket (1.0 = default, 0 = jobs only change the valley).")
        .defineInRange("jobRate", 1.0, 0.0, 10.0)
    val greetings: ModConfigSpec.BooleanValue = builder
        .comment("Residents that are fond of their trainer come over to say hello.")
        .define("greetings", true)
    val greetFriendship: ModConfigSpec.IntValue = builder
        .comment("Friendship a resident needs to come and greet its trainer.")
        .defineInRange("greetFriendship", 120, 0, 255)

    val caves: ModConfigSpec.BooleanValue = builder
        .comment("Dig big caves (a hall and wide tunnels) into the mountains of the Cave & mountain habitat.")
        .define("caves", true)
    val caveShare: ModConfigSpec.DoubleValue = builder
        .comment("Share of the Cave & mountain residents (that can't fly or swim) that live down in the caves.")
        .defineInRange("caveShare", 0.4, 0.0, 1.0)

    val pocketShare: ModConfigSpec.DoubleValue = builder
        .comment("Share of residents that live in a little pocket of their habitat out in another area (a pond in the forest, an outcrop in the meadow…).")
        .defineInRange("pocketShare", 0.3, 0.0, 1.0)
    val wanderShare: ModConfigSpec.DoubleValue = builder
        .comment("Roughly how many residents are out wandering a neighbouring habitat or the meadows at any time (they go home after a while).")
        .defineInRange("wanderShare", 0.25, 0.0, 0.9)
    val borderReach: ModConfigSpec.IntValue = builder
        .comment("How many blocks residents may roam across their habitat's border, so Pokémon from both sides mix there (0 = stay inside).")
        .defineInRange("borderReach", 14, 0, 64)

    val awayLife: ModConfigSpec.BooleanValue = builder
        .comment("Residents keep meeting, making friends and rivals and laying eggs while nobody is in the sanctuary.")
        .define("awayLife", true)
    val awayLifeRate: ModConfigSpec.DoubleValue = builder
        .comment("How busy life is while you're away: about (residents / 12 × this) meetings per minute.")
        .defineInRange("awayLifeRate", 1.0, 0.0, 10.0)

    val megaEnabled: ModConfigSpec.BooleanValue = builder
        .comment("Let residents that have a Mega Evolution Mega Evolve during play-fights (needs Mega Showdown).")
        .define("megaEnabled", true)
    val megaNeedsStone: ModConfigSpec.BooleanValue = builder
        .comment("Only residents holding their Mega Stone can Mega Evolve in the sanctuary.")
        .define("megaNeedsStone", false)

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

    // ---- growing up in the sanctuary
    val friendshipGrowthMinutes: ModConfigSpec.IntValue = builder
        .comment("Every this many minutes, every Pokémon living in the sanctuary grows a little fonder of its trainer (0 = off).")
        .defineInRange("friendshipGrowthMinutes", 5, 0, 1440)
    val friendshipGrowthPoints: ModConfigSpec.IntValue = builder
        .comment("Friendship points gained each time (Pokémon friendship goes up to 255; friendship evolutions need 160).")
        .defineInRange("friendshipGrowthPoints", 2, 0, 255)
    val playFightXp: ModConfigSpec.BooleanValue = builder
        .comment("Residents gain a little experience from play-fights.")
        .define("playFightXp", true)
    val playFightXpRate: ModConfigSpec.DoubleValue = builder
        .comment("Experience per play-fight is about (5 + level) × this.")
        .defineInRange("playFightXpRate", 1.0, 0.0, 100.0)
    val playFightXpMaxLevel: ModConfigSpec.IntValue = builder
        .comment("Residents stop gaining experience from play-fights at this level.")
        .defineInRange("playFightXpMaxLevel", 100, 1, 100)
    val loadChunksToBuild: ModConfigSpec.BooleanValue = builder
        .comment("The valley builder loads the chunks it's building in (so the whole valley is built in one go, whatever players' render distance).")
        .define("loadChunksToBuild", true)
    val customTypeBiomes: ModConfigSpec.BooleanValue = builder
        .comment("Give types added by other mods a biome of their own in the ring (off: their Pokémon live in the biome of their other type, or the Normal hub).")
        .define("customTypeBiomes", false)
    val dens: ModConfigSpec.BooleanValue = builder
        .comment("Build dens in every biome that residents walk into to rest and come out of.")
        .define("dens", true)
    val rotationMinutes: ModConfigSpec.IntValue = builder
        .comment("Besides the shift change at dusk and dawn, which residents are out changes every this many minutes (0 = only at dusk and dawn).")
        .defineInRange("rotationMinutes", 40, 0, 1440)
    val allowBigCrowds: ModConfigSpec.BooleanValue = builder
        .comment("Let sanctuary owners choose 'More' or 'Everyone' for how many Pokémon are out at once (heavier on performance).")
        .define("allowBigCrowds", true)
    val crowdMoreMultiplier: ModConfigSpec.DoubleValue = builder
        .comment("'More': this many times the normal number of Pokémon out at once.")
        .defineInRange("crowdMoreMultiplier", 1.75, 1.0, 10.0)
    val crowdEveryoneMax: ModConfigSpec.IntValue = builder
        .comment("'Everyone': every resident comes out, up to this many at once.")
        .defineInRange("crowdEveryoneMax", 800, 1, 5000)
    val despawnHiddenDistance: ModConfigSpec.IntValue = builder
        .comment("Residents are only sent back to rest (to make room for others) when no player is within this many blocks.")
        .defineInRange("despawnHiddenDistance", 48, 0, 256)
    val personalities: ModConfigSpec.BooleanValue = builder
        .comment("Residents behave according to their nature (playful, bold, gentle, laid-back, shy or curious).")
        .define("personalities", true)

    val maxSanctuariesPerPlayer: ModConfigSpec.IntValue = builder
        .comment("How many sanctuaries one player can own or belong to.")
        .defineInRange("maxSanctuariesPerPlayer", 1, 1, 16)

    val spec: ModConfigSpec = builder.build()
}
