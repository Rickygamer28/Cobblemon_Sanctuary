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

    /**
     * The same link, with its "pasture" where the resident stands now. Pasture mods (TP-Pastures) pull a Pokémon back
     * when it's far from its pasture; a resident's home is the whole valley, so its link follows it around.
     */
    fun movedTo(pos: BlockPos): SanctuaryTethering =
        SanctuaryTethering(minRoamPos, maxRoamPos, playerId, playerName, tetheringId, pokemonId, pcId, entityId, pos, sanctuaryId)
            .also { it.roamCheck = roamCheck }
}
