package com.rickygamer28.sanctuary.data

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import java.util.BitSet
import java.util.UUID

enum class SanctuaryState { PREVIEW, ACTIVE }

enum class SanctuarySize(val radius: Int, val label: String) {
    SMALL(96, "Small"),
    MEDIUM(160, "Medium"),
    LARGE(224, "Large");

    val hubRadius: Int get() = radius / 4

    companion object {
        val MAX_RADIUS = LARGE.radius
        fun byOrdinal(i: Int) = entries.getOrElse(i) { SMALL }
    }
}

/** Per-Pokémon sanctuary data that survives the Pokémon being despawned. */
class ResidentData(
    var homeChoice: String? = null,
    var noPartners: Boolean = false,
    var lastPet: Long = 0L,
    var lastPlay: Long = 0L,
    var lastFeed: Long = 0L,
) {
    fun save(): CompoundTag = CompoundTag().also { t ->
        homeChoice?.let { t.putString("Home", it) }
        if (noPartners) t.putBoolean("NoPartners", true)
        t.putLong("Pet", lastPet)
        t.putLong("Play", lastPlay)
        t.putLong("Feed", lastFeed)
    }

    companion object {
        fun load(t: CompoundTag) = ResidentData(
            homeChoice = if (t.contains("Home")) t.getString("Home") else null,
            noPartners = t.getBoolean("NoPartners"),
            lastPet = t.getLong("Pet"),
            lastPlay = t.getLong("Play"),
            lastFeed = t.getLong("Feed"),
        )
    }
}

class Sanctuary(
    val id: UUID,
    var owner: UUID,
    var ownerName: String,
    val center: BlockPos,
    var size: SanctuarySize,
    var state: SanctuaryState,
    val seed: Long,
) {
    val members: MutableSet<UUID> = linkedSetOf()
    val memberNames: MutableMap<UUID, String> = mutableMapOf()
    val residents: MutableMap<UUID, ResidentData> = mutableMapOf()

    /** Relationship score between two Pokémon, -100 (rivals) .. 100 (best friends). Key = sorted "a|b". */
    val relationships: MutableMap<String, Int> = mutableMapOf()

    /** Columns already dressed by the generator (indexes into [com.rickygamer28.sanctuary.world.ColumnOrder]). */
    var dressed: BitSet = BitSet()

    /** Recent events shown in the Journal, newest last. */
    val events: ArrayDeque<String> = ArrayDeque()

    /** Biome variant chosen per type for this sanctuary. */
    val variants: MutableMap<String, Int> = mutableMapOf()

    // ---- runtime values (recomputed from the members' PCs, not saved) ----
    /** How many residents belong to each type (home or second type). */
    var typeCounts: Map<String, Int> = emptyMap()
    /** Number of residents (Pokémon in the members' PCs). */
    var residentCount: Int = 0
    /** Biomes that have woken up. The Normal hub is always awake. */
    var unlocked: Set<String> = setOf("normal")
    /** Set when the biome layout should be swept again (new type, growth, expansion). */
    var generationDirty: Boolean = true

    /** Go over the whole area again (e.g. after trees were left in the way). */
    fun rebuild() {
        dressed = BitSet()
        generationDirty = true
    }

    fun allMembers(): Set<UUID> = linkedSetOf(owner) + members

    fun isMember(uuid: UUID) = uuid == owner || uuid in members

    fun resident(pokemon: UUID): ResidentData = residents.getOrPut(pokemon) { ResidentData() }

    fun relation(a: UUID, b: UUID): Int = relationships[key(a, b)] ?: 0

    fun hasRelation(a: UUID, b: UUID) = relationships.containsKey(key(a, b))

    fun addRelation(a: UUID, b: UUID, delta: Int): Int {
        val k = key(a, b)
        val v = ((relationships[k] ?: 0) + delta).coerceIn(-100, 100)
        relationships[k] = v
        if (relationships.size > 20000) {
            // keep the file small: forget the weakest relationships
            relationships.entries.sortedBy { kotlin.math.abs(it.value) }.take(2000).forEach { relationships.remove(it.key) }
        }
        return v
    }

    fun setRelation(a: UUID, b: UUID, value: Int) {
        relationships[key(a, b)] = value.coerceIn(-100, 100)
    }

    fun logEvent(text: String) {
        events.addLast(text)
        while (events.size > 40) events.removeFirst()
    }

    fun save(): CompoundTag {
        val t = CompoundTag()
        t.putUUID("Id", id)
        t.putUUID("Owner", owner)
        t.putString("OwnerName", ownerName)
        t.putLong("Center", center.asLong())
        t.putInt("Size", size.ordinal)
        t.putString("State", state.name)
        t.putLong("Seed", seed)
        val mem = ListTag()
        members.forEach { m ->
            mem.add(CompoundTag().also { it.putUUID("Id", m); it.putString("Name", memberNames[m] ?: "") })
        }
        t.put("Members", mem)
        val res = CompoundTag()
        residents.forEach { (k, v) -> res.put(k.toString(), v.save()) }
        t.put("Residents", res)
        val rel = CompoundTag()
        relationships.forEach { (k, v) -> rel.putInt(k, v) }
        t.put("Relationships", rel)
        t.put("Dressed", LongArrayTag(dressed.toLongArray()))
        val ev = ListTag()
        events.forEach { ev.add(StringTag.valueOf(it)) }
        t.put("Events", ev)
        val vars = CompoundTag()
        variants.forEach { (k, v) -> vars.putInt(k, v) }
        t.put("Variants", vars)
        t.putInt("Version", DATA_VERSION)
        return t
    }

    companion object {
        /** Bump to make existing sanctuaries rebuild their biomes after a generator change. */
        const val DATA_VERSION = 2

        fun key(a: UUID, b: UUID): String = if (a < b) "$a|$b" else "$b|$a"

        fun load(t: CompoundTag): Sanctuary {
            val s = Sanctuary(
                id = t.getUUID("Id"),
                owner = t.getUUID("Owner"),
                ownerName = t.getString("OwnerName"),
                center = BlockPos.of(t.getLong("Center")),
                size = SanctuarySize.byOrdinal(t.getInt("Size")),
                state = runCatching { SanctuaryState.valueOf(t.getString("State")) }.getOrDefault(SanctuaryState.ACTIVE),
                seed = t.getLong("Seed"),
            )
            t.getList("Members", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val c = e as CompoundTag
                val m = c.getUUID("Id")
                s.members += m
                s.memberNames[m] = c.getString("Name")
            }
            val res = t.getCompound("Residents")
            res.allKeys.forEach { k -> runCatching { s.residents[UUID.fromString(k)] = ResidentData.load(res.getCompound(k)) } }
            val rel = t.getCompound("Relationships")
            rel.allKeys.forEach { k -> s.relationships[k] = rel.getInt(k) }
            s.dressed = if (t.getInt("Version") >= DATA_VERSION) BitSet.valueOf(t.getLongArray("Dressed")) else BitSet()
            t.getList("Events", Tag.TAG_STRING.toInt()).forEach { s.events.addLast(it.asString) }
            val vars = t.getCompound("Variants")
            vars.allKeys.forEach { k -> s.variants[k] = vars.getInt(k) }
            return s
        }
    }
}
