package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.data.SanctuaryData
import com.rickygamer28.sanctuary.data.SanctuaryState
import com.rickygamer28.sanctuary.world.SanctuaryGenerator
import com.rickygamer28.sanctuary.world.TypeLogic
import com.rickygamer28.sanctuary.world.ZoneLayout
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** A Pokémon in a member's PC that lives in the sanctuary. */
class RosterEntry(val pokemon: Pokemon, val owner: UUID, val ownerName: String, val pcId: UUID)

/** A resident that is currently spawned as an entity. */
class LiveResident(
    val pokemonId: UUID,
    val owner: UUID,
    val entity: PokemonEntity,
    val tethering: SanctuaryTethering,
) {
    var home: String? = null
    var second: String? = null
    var flyer = false
    var busyUntil = 0L
    var nextMeetAt = 0L
    var visitZone: String? = null
    var visitUntil = 0L
    val waypoints = ArrayDeque<BlockPos>()
    var migrating = false
    var migrateUntil = 0L
    var lastLeash = 0L
    /** A newborn follows its parents around for a while after hatching. */
    var followParents: Pair<UUID, UUID>? = null
    var followUntil = 0L
    val pokemon: Pokemon get() = entity.pokemon
    val name get() = entity.pokemon.getDisplayName(false).string
}

/** Runtime state of one sanctuary (not saved; rebuilt from the PCs). */
class SanctuaryRuntime(val id: UUID) {
    val live = HashMap<UUID, LiveResident>()
    var roster: List<RosterEntry> = emptyList()
    var rosterAt = -100000L
    var rosterDirty = true
    var shuffleSeed = 0L
    var shuffleAt = 0L
    var gatherUntil = 0L
    val scenes = ArrayList<Scene>()
    var anchors: Map<String, Pair<Int, Int>> = emptyMap()
    var outlineUntil = 0L
}

object Residents {
    private val runtimes = HashMap<UUID, SanctuaryRuntime>()

    fun runtime(s: Sanctuary): SanctuaryRuntime = runtimes.getOrPut(s.id) { SanctuaryRuntime(s.id) }
    fun runtimeOrNull(id: UUID): SanctuaryRuntime? = runtimes[id]

    // ------------------------------------------------------------------ main tick

    fun tick(level: ServerLevel, data: SanctuaryData, s: Sanctuary) {
        val now = level.gameTime
        val rt = runtime(s)
        val players = playersNear(level, s)

        if (s.state == SanctuaryState.PREVIEW) {
            if (now % 100L == 0L) refreshRoster(level, s, rt, now)
            return
        }

        if (players.isNotEmpty()) {
            SanctuaryGenerator.tick(level, data, s)
            if (now % 100L == 0L) com.rickygamer28.sanctuary.Sanctuaries.ensureCenterCore(level, s)
        }

        // scenes run every tick so animations line up
        Scenes.tick(level, s, rt, now)

        if ((now + (s.id.hashCode() and 15)) % 20L != 0L) return

        if (rt.rosterDirty || now - rt.rosterAt > 200) refreshRoster(level, s, rt, now)
        cleanUp(rt)
        if (players.isEmpty()) {
            despawnAll(rt)
            return
        }
        updateSpawns(level, s, rt, players, now)
        Behaviour.tickSecond(level, s, rt, now)
        MegaCompat.tick(now)
        Families.tickSecond(level, s, rt, now)
    }

    private fun playersNear(level: ServerLevel, s: Sanctuary): List<ServerPlayer> {
        val r = (s.size.radius + com.rickygamer28.sanctuary.Sanctuaries.RIM + 32).toDouble()
        return level.players().filter {
            val dx = it.x - s.center.x
            val dz = it.z - s.center.z
            dx * dx + dz * dz < r * r
        }
    }

    // ------------------------------------------------------------------ roster

    fun refreshRoster(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, now: Long) {
        val server = level.server
        val list = ArrayList<RosterEntry>()
        for (member in s.allMembers()) {
            val pc = runCatching { Cobblemon.storage.getPC(member, server.registryAccess()) }.getOrNull() ?: continue
            val name = if (member == s.owner) s.ownerName else s.memberNames[member] ?: "?"
            for (pokemon in pc) {
                val ours = rt.live[pokemon.uuid]
                val tether = pokemon.tetheringId
                if (tether != null && (ours == null || ours.tethering.tetheringId != tether)) continue // in a real pasture
                list += RosterEntry(pokemon, member, name, pc.uuid)
            }
        }
        rt.roster = list
        rt.rosterAt = now
        rt.rosterDirty = false

        val ring = ZoneLayout.ring()
        val counts = HashMap<String, Int>()
        for (e in list) {
            val types = runCatching { TypeLogic.zoneTypes(e.pokemon) }.getOrDefault(emptyList())
            for (t in types.toSet()) counts[t] = (counts[t] ?: 0) + 1
        }
        val unlocked = linkedSetOf(ZoneLayout.HUB) + counts.keys.filter { it in ring }
        val newTypes = unlocked - s.unlocked
        val grew = counts.any { (t, c) -> c > (s.typeCounts[t] ?: 0) }
        if (s.state == SanctuaryState.ACTIVE) {
            newTypes.filter { it != ZoneLayout.HUB && s.unlocked.size > 1 }.forEach { s.logEvent("The ${zoneName(it)} woke up!") }
        }
        s.typeCounts = counts
        s.residentCount = list.size
        s.unlocked = unlocked
        if (newTypes.isNotEmpty()) s.unlockedChanged = true
        if (newTypes.isNotEmpty() || grew) s.generationDirty = true
        rt.anchors = unlocked.associateWith { zoneAnchor(s, it, ring) }
    }

    private fun zoneAnchor(s: Sanctuary, zone: String, ring: List<String>): Pair<Int, Int> {
        if (zone == ZoneLayout.HUB) return s.center.x to s.center.z
        val i = ring.indexOf(zone)
        val w = 2 * Math.PI / ring.size
        val a = (i + 0.5) * w
        val d = (s.size.hubRadius + ZoneLayout.extent(s, zone)) * 0.55
        return (s.center.x + sin(a) * d).toInt() to (s.center.z - cos(a) * d).toInt()
    }

    // ------------------------------------------------------------------ spawning

    private fun cleanUp(rt: SanctuaryRuntime) {
        val it = rt.live.values.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val gone = r.entity.isRemoved || r.entity.pokemon.tetheringId != r.tethering.tetheringId
            if (gone) it.remove()
        }
    }

    /** How many residents can be out at once in a sanctuary of [size]. */
    fun spawnCap(size: com.rickygamer28.sanctuary.data.SanctuarySize): Int = when (size) {
        com.rickygamer28.sanctuary.data.SanctuarySize.SMALL -> SanctuaryConfig.spawnCapSmall.get()
        com.rickygamer28.sanctuary.data.SanctuarySize.MEDIUM -> SanctuaryConfig.spawnCapMedium.get()
        com.rickygamer28.sanctuary.data.SanctuarySize.LARGE -> SanctuaryConfig.spawnCapLarge.get()
        com.rickygamer28.sanctuary.data.SanctuarySize.HUGE -> SanctuaryConfig.spawnCapHuge.get()
    }

    private fun updateSpawns(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, players: List<ServerPlayer>, now: Long) {
        val cap = spawnCap(s.size)
        val range = SanctuaryConfig.spawnRange.get().toDouble()
        if (now - rt.shuffleAt > 20L * 60 * 10) { rt.shuffleSeed = level.random.nextLong(); rt.shuffleAt = now }
        val ring = ZoneLayout.ring()

        fun distToPlayers(zone: String?): Double {
            val (ax, az) = rt.anchors[zone ?: ZoneLayout.HUB] ?: (s.center.x to s.center.z)
            return players.minOf { val dx = it.x - ax; val dz = it.z - az; Math.sqrt(dx * dx + dz * dz) }
        }

        // group by biome, then take one from each nearby biome in turn so every biome gets residents out
        class Cand(val e: RosterEntry, val rank: Int)
        val byZone = HashMap<String, MutableList<Cand>>()
        val zoneDist = HashMap<String, Double>()
        for (e in rt.roster) {
            if (e.pokemon.currentHealth <= 0) continue
            val home = TypeLogic.homeZone(e.pokemon, s.residents[e.pokemon.uuid], ring)
            if (home != null && home !in s.unlocked) continue
            val key = home ?: "~travelers"
            val d = zoneDist.getOrPut(key) { distToPlayers(home) }
            if (d > range + s.size.radius / 3.0) continue
            val rank = (e.pokemon.uuid.hashCode().toLong() xor rt.shuffleSeed).hashCode()
            byZone.getOrPut(key) { ArrayList() } += Cand(e, rank)
        }
        val queues = byZone.entries.sortedBy { zoneDist[it.key] ?: 0.0 }.map { ArrayDeque(it.value.sortedBy { c -> c.rank }) }
        val wanted = ArrayList<RosterEntry>()
        while (wanted.size < cap && queues.any { it.isNotEmpty() }) {
            for (q in queues) {
                if (wanted.size >= cap) break
                q.removeFirstOrNull()?.let { wanted += it.e }
            }
        }
        val wantedIds = wanted.map { it.pokemon.uuid }.toHashSet()

        // despawn the ones no longer wanted (not while in a scene)
        var removed = 0
        for (r in rt.live.values.toList()) {
            if (removed >= 6) break
            if (r.pokemonId !in wantedIds && r.busyUntil < now) {
                despawn(rt, r)
                removed++
            }
        }
        // spawn a few new ones each second to avoid lag spikes
        var spawned = 0
        for (e in wanted) {
            if (spawned >= 8 || rt.live.size >= cap) break
            if (rt.live.containsKey(e.pokemon.uuid)) continue
            if (spawn(level, s, rt, e, null, players) != null) spawned++
        }
    }

    fun spawn(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, e: RosterEntry, at: BlockPos?, players: List<ServerPlayer> = emptyList()): LiveResident? {
        val pokemon = e.pokemon
        if (pokemon.tetheringId != null || pokemon.entity != null) return null
        val ring = ZoneLayout.ring()
        val home = TypeLogic.homeZone(pokemon, s.residents[pokemon.uuid], ring)
        val spawnZone = home ?: s.unlocked.random()
        val pos = at ?: run {
            // a few random spots in its biome; take the one closest to a player so residents are seen
            // a random spot anywhere in its biome (in loaded chunks), preferably within sight of a player
            val range = SanctuaryConfig.spawnRange.get().toDouble()
            val candidates = (0 until 8).mapNotNull { ZoneLayout.randomPoint(s, spawnZone, level.random, ring) }
                .filter { level.hasChunk(it.first shr 4, it.second shr 4) }
            val seen = candidates.filter { p -> players.isEmpty() || players.any { val dx = it.x - p.first; val dz = it.z - p.second; dx * dx + dz * dz < range * range } }
            val pt = (seen.ifEmpty { candidates }).randomOrNull() ?: return null
            BlockPos(pt.first, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pt.first, pt.second), pt.second)
        }
        MegaCompat.cleanStale(pokemon)
        // the sanctuary is a place to rest: residents come out healthy
        if (pokemon.currentHealth < pokemon.maxHealth) runCatching { pokemon.heal() }
        val entity = PokemonEntity(level, pokemon)
        entity.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        val r = s.size.radius + 32
        val tethering = SanctuaryTethering(
            BlockPos(s.center.x - r, level.minBuildHeight, s.center.z - r),
            BlockPos(s.center.x + r, level.maxBuildHeight, s.center.z + r),
            e.owner, e.ownerName, UUID.randomUUID(), pokemon.uuid, e.pcId, entity.id, s.center, s.id,
        )
        pokemon.tetheringId = tethering.tetheringId
        entity.tethering = tethering
        if (!level.addFreshEntity(entity)) {
            pokemon.tetheringId = null
            return null
        }
        val live = LiveResident(pokemon.uuid, e.owner, entity, tethering)
        live.home = home
        live.second = TypeLogic.secondZone(pokemon, home)
        live.flyer = TypeLogic.canFlyInFlocks(pokemon)
        live.nextMeetAt = level.gameTime + 200 + level.random.nextInt(400)
        tethering.roamCheck = { p -> Behaviour.canRoam(s, rt, live, p) }
        rt.live[pokemon.uuid] = live
        runCatching {
            entity.beamMode = 2
            entity.after(1.5f) { entity.beamMode = 0 }
        }
        return live
    }

    fun despawn(rt: SanctuaryRuntime, r: LiveResident) {
        rt.live.remove(r.pokemonId)
        MegaCompat.onDespawn(r.entity)
        if (!r.entity.isRemoved) {
            Effects.poof(r.entity)
            r.entity.discard()
        }
        if (r.entity.pokemon.tetheringId == r.tethering.tetheringId) r.entity.pokemon.tetheringId = null
    }

    fun despawnAll(rt: SanctuaryRuntime) {
        rt.scenes.clear()
        rt.live.values.toList().forEach { despawn(rt, it) }
    }

    /** Called when a sanctuary is removed or the server stops. */
    fun shutdown(id: UUID) {
        runtimes.remove(id)?.let { despawnAll(it) }
        SanctuaryGenerator.forget(id)
    }

    fun shutdownAll() {
        MegaCompat.revertAll()
        runtimes.keys.toList().forEach { shutdown(it) }
    }

    // ------------------------------------------------------------------ helpers

    fun walkTo(entity: PokemonEntity, pos: BlockPos, speed: Float = 0.4f, closeEnough: Int = 1) {
        entity.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(pos, speed, closeEnough))
    }

    fun stop(entity: PokemonEntity) {
        entity.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        entity.navigation.stop()
    }

    fun isSleeping(entity: PokemonEntity): Boolean =
        runCatching { entity.brain.hasMemoryValue(CobblemonMemories.POKEMON_SLEEPING) && entity.brain.getMemory(CobblemonMemories.POKEMON_SLEEPING).orElse(false) }
            .getOrDefault(false)

    fun surface(level: ServerLevel, x: Int, z: Int): BlockPos =
        BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)

    fun zoneName(zone: String?): String = when (zone) {
        null -> "skies"
        ZoneLayout.HUB -> "Normal hub"
        else -> zone.replaceFirstChar { it.uppercase() } + " biome"
    }

    fun findRoster(rt: SanctuaryRuntime, pokemonId: UUID): RosterEntry? = rt.roster.firstOrNull { it.pokemon.uuid == pokemonId }
}
