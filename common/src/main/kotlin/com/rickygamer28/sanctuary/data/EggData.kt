package com.rickygamer28.sanctuary.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import java.util.UUID

/** One parent of an egg, as it was when the egg was laid. */
class EggParent(val id: UUID, val name: String, val species: String, val aspects: List<String>) {
    fun save() = CompoundTag().also { t ->
        t.putUUID("Id", id)
        t.putString("Name", name)
        t.putString("Species", species)
        t.putString("Aspects", aspects.joinToString(","))
    }

    companion object {
        fun load(t: CompoundTag) = EggParent(t.getUUID("Id"), t.getString("Name"), t.getString("Species"),
            t.getString("Aspects").split(',').filter { it.isNotBlank() })
    }
}

/** An egg waiting in a nest of the sanctuary. The baby is already decided (stats and all) and kept as Cobblemon data. */
class EggData(
    val id: UUID,
    val owner: UUID,
    val ownerName: String,
    val pokemon: CompoundTag,
    val mother: EggParent,
    val father: EggParent,
    val zone: String,
    var nest: BlockPos?,
    val laidAt: Long,
    val cycles: Int,
) {
    fun save() = CompoundTag().also { t ->
        t.putUUID("Id", id)
        t.putUUID("Owner", owner)
        t.putString("OwnerName", ownerName)
        t.put("Pokemon", pokemon)
        t.put("Mother", mother.save())
        t.put("Father", father.save())
        t.putString("Zone", zone)
        nest?.let { t.putLong("Nest", it.asLong()) }
        t.putLong("LaidAt", laidAt)
        t.putInt("Cycles", cycles)
    }

    companion object {
        fun load(t: CompoundTag) = EggData(
            t.getUUID("Id"), t.getUUID("Owner"), t.getString("OwnerName"), t.getCompound("Pokemon"),
            EggParent.load(t.getCompound("Mother")), EggParent.load(t.getCompound("Father")), t.getString("Zone"),
            if (t.contains("Nest")) BlockPos.of(t.getLong("Nest")) else null, t.getLong("LaidAt"), t.getInt("Cycles").coerceAtLeast(1),
        )
    }
}
