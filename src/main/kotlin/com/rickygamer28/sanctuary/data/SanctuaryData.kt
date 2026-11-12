package com.rickygamer28.sanctuary.data

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** All sanctuaries in one dimension. */
class SanctuaryData : SavedData() {
    val sanctuaries: MutableMap<UUID, Sanctuary> = linkedMapOf()

    fun at(pos: BlockPos): Sanctuary? = sanctuaries.values.firstOrNull { it.center == pos }

    /** The sanctuary whose current area contains [pos]. */
    fun containing(pos: BlockPos): Sanctuary? = sanctuaries.values.firstOrNull {
        val dx = (pos.x - it.center.x).toDouble()
        val dz = (pos.z - it.center.z).toDouble()
        dx * dx + dz * dz <= it.size.radius.toDouble() * it.size.radius
    }

    /** Sanctuaries whose maximum outline overlaps a circle of [radius] around [pos]. */
    fun overlapping(pos: BlockPos, radius: Int): List<Sanctuary> = sanctuaries.values.filter {
        val dx = (pos.x - it.center.x).toDouble()
        val dz = (pos.z - it.center.z).toDouble()
        val r = (radius + SanctuarySize.MAX_RADIUS).toDouble()
        dx * dx + dz * dz < r * r
    }

    fun forMember(player: UUID): List<Sanctuary> = sanctuaries.values.filter { it.isMember(player) }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        sanctuaries.values.forEach { list.add(it.save()) }
        tag.put("Sanctuaries", list)
        return tag
    }

    companion object {
        private const val NAME = "cobblemon_sanctuary"

        private fun load(tag: CompoundTag, @Suppress("UNUSED_PARAMETER") registries: HolderLookup.Provider): SanctuaryData {
            val data = SanctuaryData()
            tag.getList("Sanctuaries", Tag.TAG_COMPOUND.toInt()).forEach {
                runCatching { Sanctuary.load(it as CompoundTag) }.onSuccess { s -> data.sanctuaries[s.id] = s }
            }
            return data
        }

        fun get(level: ServerLevel): SanctuaryData =
            level.dataStorage.computeIfAbsent(SavedData.Factory({ SanctuaryData() }, ::load, null), NAME)
    }
}
