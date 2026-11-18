package com.rickygamer28.sanctuary.world

/**
 * The looks each habitat can have (in the order the builder numbers them), with a line about each, for the owner
 * to pick from on the Region tab.
 */
object Looks {
    val ALL: Map<String, List<Pair<String, String>>> = mapOf(
        ZoneLayout.HUB to listOf(
            "Town square" to "Paths, lamp posts, flower beds and big shade trees",
            "Old Kanto village" to "Gravel paths, stone lanterns, koi ponds, bamboo and red gates",
            "Park & gardens" to "Pale walkways, hedges, benches, tulips and fountains",
        ),
        "water" to listOf(
            "Clear lake" to "A clear lake with sandy beaches and islands",
            "Turquoise lagoon" to "Warm shallows with coral and palm-lined shores",
            "Rocky coast" to "A deep bay with beaches, cliffs and rock stacks",
        ),
        "ice" to listOf(
            "Ice spikes" to "A snowy mountain with ice spikes on its slopes",
            "Spruce slopes" to "Thick spruce forest climbing the snowy mountain",
            "Frozen tarns" to "Icy lakes at the foot of the snowy mountain",
        ),
        "rock" to listOf(
            "Rugged peaks" to "Grey peaks over grassy valleys",
            "Mossy heights" to "Green, mossy mountains with boulders",
            "Tuff and dripstone" to "Dark tuff heights with dripstone",
        ),
        "dragon" to listOf(
            "Banded canyons" to "Striped canyon highlands with giant bone arches",
            "Grey crags" to "Steep grey crags with snowy tops and spruces",
            "Ruined plateaus" to "Green plateaus with old ruined walls",
        ),
        "fire" to listOf(
            "Black volcano" to "A blackstone cone with lava running down",
            "Red volcano" to "A basalt cone on red sand and terracotta",
            "Sleeping volcano" to "An old grey cone, quiet and overgrown",
        ),
        "ground" to listOf(
            "Golden dunes" to "Rolling dunes of golden sand",
            "Red badlands" to "Red mesas and buttes",
            "Oasis" to "Dunes around a palm-lined oasis",
        ),
        "electric" to listOf(
            "Lightning plains" to "Open grassy plains with lightning rods",
            "Works yard" to "A paved yard with pylons and machinery",
            "Copper hills" to "Rocky hills with veins of copper",
        ),
        "ghost" to listOf(
            "Dark woods" to "Gloomy dark oak woods and an old graveyard",
            "Pumpkin hollow" to "Dead trees and carved pumpkins everywhere",
            "Withered grounds" to "A graveyard among wither roses",
        ),
        "poison" to listOf(
            "Swamp" to "Murky pools among mud, grass and swamp oaks",
            "Toxic bog" to "A purple, poisonous bog",
            "Giant mushrooms" to "Huge red and brown mushrooms on mycelium",
        ),
        "grass" to listOf(
            "Jungle hills" to "Jungle trees on mossy hills",
            "Mixed wood" to "Oak and birch woods on podzol",
            "Open woodland" to "Big, spread-out oaks in open grass",
        ),
        "fairy" to listOf(
            "Cherry groves" to "Pink cherry trees and petals",
            "Mossy glades" to "Mossy clearings with glowing toadstools",
            "Tall flowers" to "Meadows of tall, bright flowers",
        ),
    )

    /** The looks [zone] can have (custom types' habitats share three), or null. */
    fun of(zone: String): List<Pair<String, String>>? = ALL[zone] ?: if (CustomTypes.isCustom(zone)) CustomTypes.LOOKS else null
}
