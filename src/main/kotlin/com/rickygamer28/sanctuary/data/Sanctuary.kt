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
    SMALL(128, "Small"),
    MEDIUM(224, "Medium"),
    LARGE(320, "Large"),
    HUGE(448, "Huge");

    /** The Normal hub in the middle (a third of the radius by default; see hubPercent in the config). */
    val hubRadius: Int get() = radius * runCatching { com.rickygamer28.sanctuary.SanctuaryConfig.hubPercent.get() }.getOrDefault(33) / 100

    companion object {
        val MAX_RADIUS = HUGE.radius

        /** Radius of each size before 0.7.0 (saves without a stored radius). */
        val OLD_RADIUS = intArrayOf(96, 160, 224)
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
    /** Centre of the valley inside the Sanctuary dimension (where the return Core stands). */
    val center: BlockPos,
    var size: SanctuarySize,
    var state: SanctuaryState,
    val seed: Long,
    val slot: Int = 0,
) {
    /** The portal Core in the player's own world (dimension id + position), if one is placed. */
    var portalDim: String? = null
    var portalPos: BlockPos? = null

    val members: MutableSet<UUID> = linkedSetOf()
    val memberNames: MutableMap<UUID, String> = mutableMapOf()
    val residents: MutableMap<UUID, ResidentData> = mutableMapOf()

    /** Relationship score between two Pokémon, -100 (rivals) .. 100 (best friends). Key = sorted "a|b". */
    val relationships: MutableMap<String, Int> = mutableMapOf()

    /** Columns already dressed by the generator (indexes into [com.rickygamer28.sanctuary.world.ColumnOrder]). */
    var dressed: BitSet = BitSet()

    /** Dressed columns that are only a meadow, because their biome was still asleep. */
    var meadow: BitSet = BitSet()

    /** Set when a new biome woke up, so the meadow columns get checked again. Not saved. */
    var unlockedChanged: Boolean = true

    /** Original ground height of each column before the sanctuary reshaped it (Short.MIN_VALUE = not recorded). */
    var originY: ShortArray = ShortArray(0)

    /** Radius of a wall that must be taken down after expanding (0 = none). */
    var oldWallRadius: Int = 0

    fun origin(idx: Int): Int? {
        if (idx >= originY.size) return null
        val v = originY[idx]
        return if (v == Short.MIN_VALUE) null else v.toInt()
    }

    fun setOrigin(idx: Int, y: Int, total: Int) {
        if (originY.size < total) {
            val grown = ShortArray(total) { Short.MIN_VALUE }
            originY.copyInto(grown)
            originY = grown
        }
        originY[idx] = y.toShort()
    }

    /** Recent events shown in the Journal, newest last. */
    val events: ArrayDeque<String> = ArrayDeque()

    /** Eggs waiting in the nests, by egg id. */
    val eggs: LinkedHashMap<UUID, EggData> = LinkedHashMap()

    /** Families: child Pokémon → its two parents. */
    val families: MutableMap<UUID, Pair<UUID, UUID>> = mutableMapOf()

    /** When each pair (sorted "a|b") last had an egg, in game time. */
    val mates: MutableMap<String, Long> = mutableMapOf()

    /** Where each biome's landmark stands (packed BlockPos), once built. */
    val landmarks: MutableMap<String, Long> = mutableMapOf()

    /** Landmarks built on terrain that has been rebuilt since: they get taken down and built again. */
    val staleLandmarks: MutableMap<String, Long> = mutableMapOf()

    /** What happened while nobody was in the sanctuary, for the next visitor's summary. */
    var awayMeetings = 0
    var awayFriends = 0
    var awayRivals = 0
    var awayEggs = 0

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
        meadow = BitSet()
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
        t.putInt("Radius", size.radius)
        t.putString("State", state.name)
        t.putLong("Seed", seed)
        t.putInt("Slot", slot)
        portalDim?.let { t.putString("PortalDim", it) }
        portalPos?.let { t.putLong("PortalPos", it.asLong()) }
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
        t.put("Meadow", LongArrayTag(meadow.toLongArray()))
        val ev = ListTag()
        events.forEach { ev.add(StringTag.valueOf(it)) }
        t.put("Events", ev)
        val vars = CompoundTag()
        variants.forEach { (k, v) -> vars.putInt(k, v) }
        t.put("Variants", vars)
        val eggList = ListTag()
        eggs.values.forEach { eggList.add(it.save()) }
        t.put("Eggs", eggList)
        val fam = ListTag()
        families.forEach { (child, parents) ->
            fam.add(CompoundTag().also { it.putUUID("Child", child); it.putUUID("A", parents.first); it.putUUID("B", parents.second) })
        }
        t.put("Families", fam)
        val mt = CompoundTag()
        mates.forEach { (k, v) -> mt.putLong(k, v) }
        t.put("Mates", mt)
        val lm = CompoundTag()
        landmarks.forEach { (k, v) -> lm.putLong(k, v) }
        t.put("Landmarks", lm)
        val st = CompoundTag()
        staleLandmarks.forEach { (k, v) -> st.putLong(k, v) }
        t.put("StaleLandmarks", st)
        t.putIntArray("Away", intArrayOf(awayMeetings, awayFriends, awayRivals, awayEggs))
        t.putInt("Version", DATA_VERSION)
        t.putInt("OldWall", oldWallRadius)
        val packed = IntArray((originY.size + 1) / 2) { i ->
            val a = originY[i * 2].toInt() and 0xFFFF
            val b = (if (i * 2 + 1 < originY.size) originY[i * 2 + 1].toInt() else Short.MIN_VALUE.toInt()) and 0xFFFF
            (a shl 16) or b
        }
        t.putIntArray("Origin", packed)
        return t
    }

    companion object {
        /** Bump to make existing sanctuaries rebuild their biomes after a generator change. */
        const val DATA_VERSION = 6

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
                slot = t.getInt("Slot"),
            )
            if (t.contains("PortalDim")) s.portalDim = t.getString("PortalDim")
            if (t.contains("PortalPos")) s.portalPos = BlockPos.of(t.getLong("PortalPos"))
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
            s.meadow = if (t.getInt("Version") >= DATA_VERSION) BitSet.valueOf(t.getLongArray("Meadow")) else BitSet()
            s.oldWallRadius = t.getInt("OldWall")
            // the sizes got bigger: grow the valley like an expansion (old wall down, new wall and mountains further out)
            val savedRadius = if (t.contains("Radius")) t.getInt("Radius") else SanctuarySize.OLD_RADIUS.getOrElse(s.size.ordinal) { s.size.radius }
            if (savedRadius != s.size.radius && s.state == SanctuaryState.ACTIVE) {
                if (savedRadius < s.size.radius) s.oldWallRadius = savedRadius
                s.dressed = BitSet()
                s.meadow = BitSet()
                s.generationDirty = true
            }
            val packed = t.getIntArray("Origin")
            if (packed.isNotEmpty()) {
                s.originY = ShortArray(packed.size * 2) { i -> ((packed[i / 2] shr (if (i % 2 == 0) 16 else 0)) and 0xFFFF).toShort() }
            }
            t.getList("Events", Tag.TAG_STRING.toInt()).forEach { s.events.addLast(it.asString) }
            t.getList("Eggs", Tag.TAG_COMPOUND.toInt()).forEach { e -> runCatching { EggData.load(e as CompoundTag) }.getOrNull()?.let { s.eggs[it.id] = it } }
            t.getList("Families", Tag.TAG_COMPOUND.toInt()).forEach { e ->
                val c = e as CompoundTag
                runCatching { s.families[c.getUUID("Child")] = c.getUUID("A") to c.getUUID("B") }
            }
            val mt = t.getCompound("Mates")
            mt.allKeys.forEach { k -> s.mates[k] = mt.getLong(k) }
            val lm = t.getCompound("Landmarks")
            lm.allKeys.forEach { k -> s.landmarks[k] = lm.getLong(k) }
            t.getIntArray("Away").let { a -> if (a.size == 4) { s.awayMeetings = a[0]; s.awayFriends = a[1]; s.awayRivals = a[2]; s.awayEggs = a[3] } }
            val st = t.getCompound("StaleLandmarks")
            st.allKeys.forEach { k -> s.staleLandmarks[k] = st.getLong(k) }
            if (t.getInt("Version") < DATA_VERSION && t.getInt("Version") > 0) {
                // the terrain is being rebuilt: landmarks get rebuilt on the new ground too
                s.landmarks.forEach { (k, v) -> if (v != Long.MIN_VALUE) s.staleLandmarks[k] = v }
                s.landmarks.clear()
            }
            val vars = t.getCompound("Variants")
            vars.allKeys.forEach { k -> s.variants[k] = vars.getInt(k) }
            return s
        }
    }
}
