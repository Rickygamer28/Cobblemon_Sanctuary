package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * Re-uses Cobblemon's pasture tethering: the Pokémon stays in its owner's PC while its entity roams.
 * Cobblemon's wander AI asks [canRoamTo] before picking a spot, which keeps residents in their biomes.
 */
class SanctuaryTethering(
    minRoamPos: BlockPos,
    maxRoamPos: BlockPos,
    playerId: UUID,
    playerName: String,
    tetheringId: UUID,
    pokemonId: UUID,
    pcId: UUID,
    entityId: Int,
    corePos: BlockPos,
    val sanctuaryId: UUID,
) : PokemonPastureBlockEntity.Tethering(
    minRoamPos, maxRoamPos, playerId, playerName, tetheringId, pokemonId, pcId, entityId, corePos,
) {
    @Volatile
    var roamCheck: (BlockPos) -> Boolean = { true }

    override fun canRoamTo(pos: BlockPos): Boolean = roamCheck(pos)
}
