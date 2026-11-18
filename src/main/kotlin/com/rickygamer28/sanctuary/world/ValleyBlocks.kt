package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.CobblemonSanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * What belongs in the valley: every block the builder, the landmarks, the dens, the caves and the residents' spots
 * use (the list is taken from their code), what trees and plants grow with, Cobblemon's own plants and crystals,
 * and the mod's own blocks (beds, balls, troughs, nests… that players place). Anything else in a valley was built
 * by a player, and the cleanup sweep takes it away (players can't build there).
 */
object ValleyBlocks {
    private val OURS: Set<Block> = setOf(
        Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, Blocks.AIR, Blocks.ALLIUM, Blocks.AMETHYST_BLOCK,
        Blocks.AMETHYST_CLUSTER, Blocks.ANDESITE, Blocks.AZALEA, Blocks.AZALEA_LEAVES, Blocks.AZURE_BLUET,
        Blocks.BAMBOO, Blocks.BASALT, Blocks.BEE_NEST, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
        Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, Blocks.BLACKSTONE, Blocks.BLUE_ICE, Blocks.BLUE_ORCHID,
        Blocks.BONE_BLOCK, Blocks.BRAIN_CORAL, Blocks.BRAIN_CORAL_BLOCK, Blocks.BRICKS, Blocks.BROWN_MUSHROOM,
        Blocks.BROWN_MUSHROOM_BLOCK, Blocks.BROWN_TERRACOTTA, Blocks.BUBBLE_COLUMN, Blocks.BUBBLE_CORAL_BLOCK, Blocks.CACTUS,
        Blocks.CALCITE, Blocks.CAMPFIRE, Blocks.CARVED_PUMPKIN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
        Blocks.CHAIN, Blocks.CHERRY_LEAVES, Blocks.CHERRY_LOG, Blocks.CHIPPED_ANVIL, Blocks.CHISELED_SANDSTONE,
        Blocks.CHISELED_STONE_BRICKS, Blocks.CHORUS_FLOWER, Blocks.CLAY, Blocks.COARSE_DIRT, Blocks.COBBLED_DEEPSLATE,
        Blocks.COBBLED_DEEPSLATE_WALL, Blocks.COBBLESTONE, Blocks.COBBLESTONE_WALL, Blocks.COBWEB, Blocks.COCOA,
        Blocks.CORNFLOWER, Blocks.CRACKED_STONE_BRICKS, Blocks.CRIMSON_FUNGUS, Blocks.CRIMSON_NYLIUM, Blocks.CRIMSON_ROOTS,
        Blocks.CRYING_OBSIDIAN, Blocks.CUT_SANDSTONE, Blocks.CYAN_TERRACOTTA, Blocks.DAMAGED_ANVIL, Blocks.DANDELION,
        Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_LEAVES, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, Blocks.DEAD_BUSH,
        Blocks.DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.DIRT, Blocks.DIRT_PATH,
        Blocks.DRIPSTONE_BLOCK, Blocks.END_ROD, Blocks.END_STONE, Blocks.EXPOSED_COPPER, Blocks.FERN,
        Blocks.FIRE, Blocks.FIRE_CORAL_BLOCK, Blocks.FIRE_CORAL_FAN, Blocks.FLOWERING_AZALEA, Blocks.FLOWERING_AZALEA_LEAVES,
        Blocks.GLOW_LICHEN, Blocks.GOLD_BLOCK, Blocks.GRANITE, Blocks.GRASS_BLOCK, Blocks.GRAVEL,
        Blocks.GREEN_TERRACOTTA, Blocks.HANGING_ROOTS, Blocks.HAY_BLOCK, Blocks.HORN_CORAL, Blocks.HORN_CORAL_BLOCK,
        Blocks.ICE, Blocks.IRON_BARS, Blocks.JACK_O_LANTERN, Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG,
        Blocks.KELP, Blocks.KELP_PLANT, Blocks.LANTERN, Blocks.LARGE_AMETHYST_BUD, Blocks.LARGE_FERN,
        Blocks.LAVA, Blocks.LIGHTNING_ROD, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.LILAC,
        Blocks.LILY_OF_THE_VALLEY, Blocks.LILY_PAD, Blocks.LIME_TERRACOTTA, Blocks.MAGMA_BLOCK, Blocks.MANGROVE_LEAVES,
        Blocks.MANGROVE_LOG, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_PROPAGULE, Blocks.MANGROVE_ROOTS, Blocks.MELON,
        Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_STONE_BRICK_WALL, Blocks.MOSS_BLOCK,
        Blocks.MOSS_CARPET, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.MUD_BRICKS, Blocks.MUSHROOM_STEM,
        Blocks.MYCELIUM, Blocks.NETHERRACK, Blocks.NETHER_WART_BLOCK, Blocks.OAK_FENCE, Blocks.OAK_LEAVES,
        Blocks.OAK_LOG, Blocks.OAK_STAIRS, Blocks.OBSIDIAN, Blocks.OCHRE_FROGLIGHT, Blocks.ORANGE_TERRACOTTA,
        Blocks.ORANGE_TULIP, Blocks.OXEYE_DAISY, Blocks.OXIDIZED_COPPER, Blocks.PACKED_ICE, Blocks.PACKED_MUD,
        Blocks.PEARLESCENT_FROGLIGHT, Blocks.PEONY, Blocks.PINK_PETALS, Blocks.PINK_TERRACOTTA, Blocks.PINK_TULIP,
        Blocks.PODZOL, Blocks.POINTED_DRIPSTONE, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
        Blocks.POLISHED_BLACKSTONE_WALL, Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_DIORITE_SLAB, Blocks.POPPY,
        Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.PUMPKIN, Blocks.PURPLE_TERRACOTTA, Blocks.PURPUR_BLOCK,
        Blocks.PURPUR_PILLAR, Blocks.QUARTZ_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.RAW_IRON_BLOCK, Blocks.REDSTONE_LAMP,
        Blocks.RED_CARPET, Blocks.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK, Blocks.RED_NETHER_BRICKS, Blocks.RED_SAND,
        Blocks.RED_SANDSTONE, Blocks.RED_TERRACOTTA, Blocks.RED_TULIP, Blocks.ROOTED_DIRT, Blocks.ROSE_BUSH,
        Blocks.SAND, Blocks.SANDSTONE, Blocks.SCULK, Blocks.SCULK_CATALYST, Blocks.SEAGRASS,
        Blocks.SEA_LANTERN, Blocks.SEA_PICKLE, Blocks.SHORT_GRASS, Blocks.SHROOMLIGHT, Blocks.SLIME_BLOCK,
        Blocks.SMALL_DRIPLEAF, Blocks.SMOOTH_BASALT, Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB,
        Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.SOUL_FIRE, Blocks.SOUL_LANTERN, Blocks.SOUL_SAND,
        Blocks.SOUL_SOIL, Blocks.SPORE_BLOSSOM, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE, Blocks.SPRUCE_LEAVES,
        Blocks.SPRUCE_LOG, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL,
        Blocks.STONE_BUTTON, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG,
        Blocks.SUGAR_CANE, Blocks.SUNFLOWER, Blocks.SWEET_BERRY_BUSH, Blocks.TALL_GRASS, Blocks.TALL_SEAGRASS,
        Blocks.TARGET, Blocks.TERRACOTTA, Blocks.TORCHFLOWER, Blocks.TUBE_CORAL, Blocks.TUBE_CORAL_BLOCK,
        Blocks.TUFF, Blocks.VERDANT_FROGLIGHT, Blocks.VINE, Blocks.WARPED_NYLIUM, Blocks.WARPED_ROOTS,
        Blocks.WATER, Blocks.WEATHERED_COPPER, Blocks.WHITE_CANDLE, Blocks.WHITE_CARPET, Blocks.WHITE_TERRACOTTA,
        Blocks.WHITE_TULIP, Blocks.WITHER_ROSE, Blocks.YELLOW_TERRACOTTA,
    )

    /** Is [st] something the valley itself has (or one of the mod's own blocks)? */
    fun belongs(st: BlockState): Boolean {
        if (st.isAir || st.block in OURS) return true
        if (st.`is`(BlockTags.LEAVES) || st.`is`(BlockTags.LOGS) || st.`is`(BlockTags.FLOWERS) || st.`is`(BlockTags.SAPLINGS) ||
            st.`is`(BlockTags.CORALS) || st.`is`(BlockTags.CORAL_BLOCKS) || st.`is`(BlockTags.WALL_CORALS)) return true
        if (BiomeDresser.isNatural(st) || BiomeDresser.isClutter(st) || BiomeDresser.isModSolid(st) || BiomeDresser.isCobblemonDecor(st)) return true
        // the lake's coral that died (out of the water)
        val id = BuiltInRegistries.BLOCK.getKey(st.block)
        if (id.namespace == "minecraft" && id.path.startsWith("dead_") && id.path.contains("coral")) return true
        return isMods(st)
    }

    /** Is [st] one of the mod's own blocks (the core, beds, balls, troughs, nests…)? */
    fun isMods(st: BlockState): Boolean = BuiltInRegistries.BLOCK.getKey(st.block).namespace == CobblemonSanctuary.MOD_ID

    /** Takes away a block a player built: anything it held is dropped where it stood. */
    fun remove(level: ServerLevel, p: BlockPos) {
        (level.getBlockEntity(p) as? net.minecraft.world.Container)?.let { net.minecraft.world.Containers.dropContents(level, p, it) }
        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
    }
}
