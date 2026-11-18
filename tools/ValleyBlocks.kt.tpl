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
@@BODY@@,
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
