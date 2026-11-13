package com.rickygamer28.sanctuary.data

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Every sanctuary on the server (stored with the overworld's data). */
class SanctuaryData : SavedData() {
    val sanctuaries: MutableMap<UUID, Sanctuary> = linkedMapOf()
    var nextSlot = 1

    /** The sanctuary whose return Core (centre) is at [pos] in the Sanctuary dimension. */
    fun at(pos: BlockPos): Sanctuary? = sanctuaries.values.firstOrNull { it.center == pos }

    /** The sanctuary whose portal Core is at [pos] in dimension [dim]. */
    fun atPortal(dim: String, pos: BlockPos): Sanctuary? = sanctuaries.values.firstOrNull { it.portalDim == dim && it.portalPos == pos }

    /** The sanctuary (in the Sanctuary dimension) whose current area contains [pos]. */
    fun containing(pos: BlockPos): Sanctuary? = sanctuaries.values.firstOrNull {
        val dx = (pos.x - it.center.x).toDouble()
        val dz = (pos.z - it.center.z).toDouble()
        dx * dx + dz * dz <= it.size.radius.toDouble() * it.size.radius
    }

    /** The sanctuary whose valley (including the mountain rim) contains [pos]. */
    fun near(pos: BlockPos, extra: Int): Sanctuary? = sanctuaries.values.firstOrNull {
        val dx = (pos.x - it.center.x).toDouble()
        val dz = (pos.z - it.center.z).toDouble()
        val r = (it.size.radius + extra).toDouble()
        dx * dx + dz * dz <= r * r
    }

    fun forMember(player: UUID): List<Sanctuary> = sanctuaries.values.filter { it.isMember(player) }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        sanctuaries.values.forEach { list.add(it.save(registries)) }
        tag.put("Sanctuaries", list)
        tag.putInt("NextSlot", nextSlot)
        return tag
    }

    companion object {
        private const val NAME = "cobblemon_sanctuary_v2"

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): SanctuaryData {
            val data = SanctuaryData()
            tag.getList("Sanctuaries", Tag.TAG_COMPOUND.toInt()).forEach {
                runCatching { Sanctuary.load(it as CompoundTag, registries) }.onSuccess { s -> data.sanctuaries[s.id] = s }
            }
            data.nextSlot = tag.getInt("NextSlot").coerceAtLeast(1)
            return data
        }

        fun get(server: MinecraftServer): SanctuaryData =
            server.overworld().dataStorage.computeIfAbsent(SavedData.Factory({ SanctuaryData() }, ::load, null), NAME)
    }
}
