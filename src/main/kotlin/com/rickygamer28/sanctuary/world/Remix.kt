package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature

/**
 * The fourth look of each habitat (unlocked at Valley Heart level 7). It keeps one of the habitat's other looks'
 * land (so the ground, the routes and the caves are shaped just as well) but builds it from other blocks, with
 * other trees, and sometimes a tree of its own scattered around.
 */
class Remix(
    val name: String,
    val desc: String,
    /** The look whose land it keeps. */
    val base: Int,
    val swap: Map<Block, BlockState>,
    val trees: Map<ResourceKey<ConfiguredFeature<*, *>>, ResourceKey<ConfiguredFeature<*, *>>> = emptyMap(),
    /** A tree it adds here and there, and how far apart (in blocks). */
    val extra: ResourceKey<ConfiguredFeature<*, *>>? = null,
    val extraEvery: Int = 14,
) {
    fun swap(st: BlockState): BlockState = swap[st.block] ?: st
    fun tree(key: ResourceKey<ConfiguredFeature<*, *>>) = trees[key] ?: key

    companion object {
        /** Which look number the fourth look is. */
        const val INDEX = 3

        private fun b(block: Block): BlockState = block.defaultBlockState()

        val ALL: Map<String, Remix> = mapOf(
            "water" to Remix("Chalk coast", "White chalk cliffs and pale beaches, with birches", 2,
                mapOf(Blocks.STONE to b(Blocks.CALCITE), Blocks.ANDESITE to b(Blocks.DIORITE), Blocks.GRAVEL to b(Blocks.SAND), Blocks.MOSSY_COBBLESTONE to b(Blocks.CALCITE)),
                mapOf(TreeFeatures.OAK to TreeFeatures.BIRCH, TreeFeatures.SPRUCE to TreeFeatures.BIRCH)),
            "ice" to Remix("Blue glacier", "A glacier of packed and blue ice, deep in snow", 0,
                mapOf(Blocks.STONE to b(Blocks.PACKED_ICE), Blocks.GRAVEL to b(Blocks.SNOW_BLOCK), Blocks.DIRT to b(Blocks.SNOW_BLOCK), Blocks.ICE to b(Blocks.BLUE_ICE))),
            "rock" to Remix("Amethyst ridge", "White calcite peaks streaked with amethyst", 0,
                mapOf(Blocks.STONE to b(Blocks.CALCITE), Blocks.ANDESITE to b(Blocks.AMETHYST_BLOCK), Blocks.COBBLESTONE to b(Blocks.TUFF),
                    Blocks.GRAVEL to b(Blocks.TUFF), Blocks.MOSSY_COBBLESTONE to b(Blocks.CALCITE))),
            "dragon" to Remix("Deepslate crags", "Dark deepslate crags under snow, with dark oaks", 1,
                mapOf(Blocks.STONE to b(Blocks.DEEPSLATE), Blocks.MOSSY_COBBLESTONE to b(Blocks.COBBLED_DEEPSLATE), Blocks.CALCITE to b(Blocks.TUFF)),
                mapOf(TreeFeatures.SPRUCE to TreeFeatures.DARK_OAK)),
            "fire" to Remix("Crimson volcano", "A netherrack cone with crimson moss and lava", 0,
                mapOf(Blocks.BLACKSTONE to b(Blocks.NETHERRACK), Blocks.BASALT to b(Blocks.NETHERRACK), Blocks.SMOOTH_BASALT to b(Blocks.CRIMSON_NYLIUM),
                    Blocks.TUFF to b(Blocks.BLACKSTONE), Blocks.GRAVEL to b(Blocks.NETHERRACK))),
            "ground" to Remix("Dry savanna", "Rolling golden grassland with acacias", 0,
                mapOf(Blocks.SAND to b(Blocks.GRASS_BLOCK), Blocks.SANDSTONE to b(Blocks.DIRT), Blocks.SMOOTH_SANDSTONE to b(Blocks.COARSE_DIRT),
                    Blocks.CACTUS to b(Blocks.AIR), Blocks.DEAD_BUSH to b(Blocks.SHORT_GRASS)),
                extra = TreeFeatures.ACACIA, extraEvery = 15),
            "electric" to Remix("Copper works", "A works yard paved with weathered copper", 1,
                mapOf(Blocks.SMOOTH_STONE to b(Blocks.WAXED_WEATHERED_CUT_COPPER), Blocks.POLISHED_ANDESITE to b(Blocks.WAXED_OXIDIZED_CUT_COPPER))),
            "ghost" to Remix("Soul vale", "Soul soil under dark oaks, lit by soul lanterns", 0,
                mapOf(Blocks.GRASS_BLOCK to b(Blocks.SOUL_SOIL), Blocks.PODZOL to b(Blocks.SOUL_SOIL), Blocks.COARSE_DIRT to b(Blocks.SOUL_SOIL),
                    Blocks.SHORT_GRASS to b(Blocks.AIR), Blocks.BROWN_MUSHROOM to b(Blocks.SOUL_LANTERN))),
            "poison" to Remix("Mangrove marsh", "Mangroves rooted in mud and murky pools", 0,
                mapOf(Blocks.GRASS_BLOCK to b(Blocks.MUD)),
                mapOf(TreeFeatures.SWAMP_OAK to TreeFeatures.MANGROVE)),
            "grass" to Remix("Birch forest", "A bright forest of tall birches", 1,
                mapOf(Blocks.PODZOL to b(Blocks.GRASS_BLOCK)),
                mapOf(TreeFeatures.OAK to TreeFeatures.SUPER_BIRCH_BEES_0002, TreeFeatures.FANCY_OAK to TreeFeatures.BIRCH)),
            "fairy" to Remix("Azalea meadow", "Moss and flowering azaleas among tall flowers", 2,
                mapOf(Blocks.GRASS_BLOCK to b(Blocks.MOSS_BLOCK)),
                mapOf(TreeFeatures.CHERRY to TreeFeatures.AZALEA_TREE),
                extra = TreeFeatures.AZALEA_TREE, extraEvery = 13),
        )

        /** The fourth look [zone] is wearing, if it is. */
        fun of(s: Sanctuary, zone: String): Remix? = if (s.variants[zone] == INDEX) ALL[zone] else null

        /** Every block the fourth looks use (so they count as the valley's own). */
        val BLOCKS: Set<Block> = ALL.values.flatMap { r -> r.swap.values.map { it.block } }.toSet()

        /** The ones that are ground. */
        val GROUND: Set<Block> = BLOCKS - setOf(Blocks.AIR, Blocks.SHORT_GRASS, Blocks.SOUL_LANTERN)
    }
}
