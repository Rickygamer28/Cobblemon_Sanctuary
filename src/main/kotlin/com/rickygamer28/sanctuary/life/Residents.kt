package com.rickygamer28.sanctuary.life

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.Sanctuaries
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
    /** Asleep for the night, and where it's heading to sleep next to a friend. */
    var napping = false
    var napSpot: BlockPos? = null
    var napGoalUntil = 0L
    /** Heading to a berry tree for a meal. */
    var snackSpot: BlockPos? = null
    var snackUntil = 0L
    /** Can't go on land: stays in the water. */
    var swimmer = false
    /** When a swimmer was first seen out of the water (0 = in the water). */
    var strandedSince = 0L
    /** How it behaves, from its nature. */
    var personality = Personality.NEUTRAL
    /** Heading to a spot (a log, a pool…), and what it's doing there once it arrives. */
    var propSpot: BlockPos? = null
    var propStand: BlockPos? = null
    var propKind: String? = null
    var propUntil = 0L
    var activityKind: String? = null
    var activityAt: BlockPos? = null
    var activityUntil = 0L
    /** A short daytime doze (laid-back Pokémon especially). */
    var dozeUntil = 0L
    /** Last time a visit made it fonder of its trainer. */
    var lastFondAt = -100000L
    /** Going back to rest: walking into a den (or out of sight, or diving), then gone. */
    var leaving = false
    var leaveTarget: BlockPos? = null
    var leaveSince = 0L
    val pokemon: Pokemon get() = entity.pokemon
    val name get() = entity.pokemon.getDisplayName(false).string
}

/** Runtime state of one sanctuary (not saved; rebuilt from the PCs). */
class SanctuaryRuntime(val id: UUID) {
    /** Whether players were in the sanctuary last second (to greet the next visitor with a summary). */
    var hadPlayers = false
    val live = HashMap<UUID, LiveResident>()
    var roster: List<RosterEntry> = emptyList()
    var rosterAt = -100000L
    var rosterDirty = true
    var shuffleSeed = 0L
    var shuffleAt = 0L
    /** Whether it was night at the last shift change (null before the first one). */
    var shiftNight: Boolean? = null
    /** When a resident last came out of each den. */
    val denUsed = HashMap<Long, Long>()
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
        Gathering.tick(level, s, rt, now)
        Growth.tick(level, s, rt, now)
        if (players.isEmpty()) {
            despawnAll(rt)
            rt.hadPlayers = false
            AwayLife.tick(level, s, rt, now)
            return
        }
        if (!rt.hadPlayers) {
            rt.hadPlayers = true
            AwayLife.welcome(s, players)
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
        var freed = 0
        var pcsOk = true
        for (member in s.allMembers()) {
            val pc = runCatching { Cobblemon.storage.getPC(member, server.registryAccess()) }.getOrNull() ?: run { pcsOk = false; null } ?: continue
            val name = if (member == s.owner) s.ownerName else s.memberNames[member] ?: "?"
            for (pokemon in pc) {
                val ours = rt.live[pokemon.uuid]
                val tether = pokemon.tetheringId
                if (tether != null && (ours == null || ours.tethering.tetheringId != tether)) {
                    // still linked to the sanctuary from before the game closed (or crashed): bring it back.
                    // Older versions didn't remember their links, so once, every link without a Pokémon out is freed.
                    val left = pokemon.entity == null && (tether == s.residents[pokemon.uuid]?.tether || !s.legacyTethersFixed)
                    if (!left) continue // out in a real Cobblemon Pasture
                    pokemon.tetheringId = null
                    freed++
                }
                list += RosterEntry(pokemon, member, name, pc.uuid)
            }
        }
        rt.roster = list
        rt.rosterAt = now
        rt.rosterDirty = false
        if (pcsOk && !s.legacyTethersFixed) s.legacyTethersFixed = true
        if (freed > 0) {
            CobblemonSanctuary.LOGGER.info("Sanctuary: {} Pokémon were still linked from last time and came back", freed)
            Sanctuaries.data(level).setDirty()
        }

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

    /** How many of this sanctuary's residents are out at once, with its crowd setting. */
    fun spawnCap(s: Sanctuary): Int {
        val base = spawnCap(s.size)
        if (!SanctuaryConfig.allowBigCrowds.get()) return base
        return when (s.crowd) {
            1 -> (base * SanctuaryConfig.crowdMoreMultiplier.get()).toInt()
            2 -> maxOf(base, SanctuaryConfig.crowdEveryoneMax.get())
            else -> base
        }
    }

    private fun updateSpawns(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, players: List<ServerPlayer>, now: Long) {
        val cap = spawnCap(s)
        val everyone = s.crowd == 2 && SanctuaryConfig.allowBigCrowds.get()
        val range = SanctuaryConfig.spawnRange.get().toDouble()
        // shift change at dusk and dawn (and now and then in between): a different set of residents comes out
        val night = Routines.isNight(level)
        val rotate = SanctuaryConfig.rotationMinutes.get()
        if (rt.shiftNight != night || (rotate > 0 && now - rt.shuffleAt > 20L * 60 * rotate)) {
            if (rt.shiftNight != null && rt.shiftNight != night) {
                s.logEvent(if (night) "Night fell: the night owls came out and the others went home to their dens." else "Morning came: the day crowd came out of their dens.")
            }
            rt.shiftNight = night
            rt.shuffleSeed = level.random.nextLong()
            rt.shuffleAt = now
        }
        val ring = ZoneLayout.ring()

        fun distToPlayers(zone: String?): Double {
            val (ax, az) = rt.anchors[zone ?: ZoneLayout.HUB] ?: (s.center.x to s.center.z)
            return players.minOf { val dx = it.x - ax; val dz = it.z - az; Math.sqrt(dx * dx + dz * dz) }
        }

        // group by biome, then take one from each nearby biome in turn so every biome gets residents out
        class Cand(val e: RosterEntry, val rank: Int, val shift: Int)
        val byZone = HashMap<String, MutableList<Cand>>()
        val zoneDist = HashMap<String, Double>()
        for (e in rt.roster) {
            // fainted ones come out too: the sanctuary heals them when they appear
            val home = TypeLogic.homeZone(e.pokemon, s.residents[e.pokemon.uuid], ring)
            if (home != null && home !in s.unlocked) continue
            val key = home ?: "~travelers"
            val d = zoneDist.getOrPut(key) { distToPlayers(home) }
            if (!everyone && d > range + s.size.radius / 3.0) continue
            val rank = (e.pokemon.uuid.hashCode().toLong() xor rt.shuffleSeed).hashCode()
            // night owls come first at night and last by day
            val owl = runCatching { TypeLogic.currentTypes(e.pokemon).any { it == "ghost" || it == "dark" } }.getOrDefault(false)
            val shift = if (owl == night) 0 else 1
            byZone.getOrPut(key) { ArrayList() } += Cand(e, rank, shift)
        }
        val queues = byZone.entries.sortedBy { zoneDist[it.key] ?: 0.0 }.map { ArrayDeque(it.value.sortedWith(compareBy({ c -> c.shift }, { c -> c.rank }))) }
        val wanted = ArrayList<RosterEntry>()
        while (wanted.size < cap && queues.any { it.isNotEmpty() }) {
            for (q in queues) {
                if (wanted.size >= cap) break
                q.removeFirstOrNull()?.let { wanted += it.e }
            }
        }
        val wantedIds = wanted.map { it.pokemon.uuid }.toHashSet()

        // the ones no longer wanted go back to rest: out of sight they just go; near a player they walk into a den
        // (or out of sight, or dive), so nobody sees them vanish
        val hidden = SanctuaryConfig.despawnHiddenDistance.get().toDouble()
        fun nearestPlayer(r: LiveResident) = players.minOfOrNull { it.distanceToSqr(r.entity) } ?: Double.MAX_VALUE
        var removed = 0
        var sent = 0
        for (r in rt.live.values.toList()) {
            if (r.leaving && r.pokemonId in wantedIds) { r.leaving = false; r.leaveTarget = null; r.busyUntil = 0 } // wanted again
        }
        for (r in rt.live.values.filter { it.pokemonId !in wantedIds && !it.leaving && it.busyUntil < now }.sortedByDescending { nearestPlayer(it) }) {
            if (nearestPlayer(r) >= hidden * hidden) {
                if (removed >= 6) continue
                despawn(rt, r, quiet = true)
                removed++
            } else {
                if (sent >= 4) break
                Leaving.start(level, s, rt, r, now)
                sent++
            }
        }
        // spawn a few new ones each second to avoid lag spikes
        var spawned = 0
        val out = rt.live.values.count { !it.leaving }
        for (e in wanted) {
            if (spawned >= 8 || out + spawned >= cap) break
            if (rt.live.containsKey(e.pokemon.uuid)) continue
            if (spawn(level, s, rt, e, null, players) != null) spawned++
        }
    }

    fun spawn(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, e: RosterEntry, at: BlockPos?, players: List<ServerPlayer> = emptyList()): LiveResident? {
        val pokemon = e.pokemon
        if (pokemon.tetheringId != null || pokemon.entity != null) return null
        val ring = ZoneLayout.ring()
        val home = TypeLogic.homeZone(pokemon, s.residents[pokemon.uuid], ring)
        val swimmer = TypeLogic.waterOnly(pokemon)
        val spawnZone = if (swimmer) "water" else home ?: s.unlocked.random()
        if (swimmer && "water" !in s.unlocked) return null
        var fromDen: com.rickygamer28.sanctuary.world.Dens.Den? = null
        val pos = at ?: run {
            val range = SanctuaryConfig.spawnRange.get().toDouble()
            fun nearPlayer(x: Int, z: Int) = players.isEmpty() || players.any { val dx = it.x - x; val dz = it.z - z; dx * dx + dz * dz < range * range }
            // out of a den of its biome (it walks out), preferably one near a player
            if (!swimmer) {
                // a den nobody came out of in the last few seconds, so they don't pile up at one entrance
                val dens = com.rickygamer28.sanctuary.world.Dens.near(level, s, spawnZone)
                    .filter { level.gameTime - (rt.denUsed[it.center.asLong()] ?: -1000L) > 60 && com.rickygamer28.sanctuary.world.Dens.intact(level, it) }
                val den = dens.filter { nearPlayer(it.center.x, it.center.z) }.randomOrNull() ?: dens.randomOrNull()
                if (den != null && level.random.nextInt(4) != 0) {
                    fromDen = den
                    rt.denUsed[den.center.asLong()] = level.gameTime
                    return@run if (den.big) den.inside.offset(level.random.nextInt(3) - 1, 0, level.random.nextInt(3) - 1) else den.inside
                }
            }
            // otherwise a random spot of its biome where nobody is looking
            val candidates = (0 until if (swimmer) 24 else 12).mapNotNull { ZoneLayout.randomPoint(s, spawnZone, level.random, ring) }
                .filter { level.hasChunk(it.first shr 4, it.second shr 4) }
                .filter { !swimmer || Behaviour.isWater(level, it.first, it.second) }
                .filter { unseen(players, it.first, it.second) }
            val pt = (candidates.filter { nearPlayer(it.first, it.second) }.ifEmpty { candidates }).randomOrNull() ?: return null
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
        s.resident(pokemon.uuid).tether = tethering.tetheringId
        entity.tethering = tethering
        if (!level.addFreshEntity(entity)) {
            pokemon.tetheringId = null
            return null
        }
        val live = LiveResident(pokemon.uuid, e.owner, entity, tethering)
        live.home = home
        live.second = TypeLogic.secondZone(pokemon, home)
        live.flyer = TypeLogic.canFlyInFlocks(pokemon)
        live.swimmer = swimmer
        live.personality = Personality.of(pokemon)
        live.nextMeetAt = level.gameTime + 200 + level.random.nextInt(400)
        tethering.roamCheck = { p -> Behaviour.canRoam(s, rt, live, p) }
        rt.live[pokemon.uuid] = live
        val den = fromDen
        if (den != null) {
            // out of the den and off into the open, each in its own direction
            val out = den.center.relative(den.dir, 7 + level.random.nextInt(6)).relative(den.dir.clockWise, level.random.nextInt(11) - 5)
            walkTo(entity, Residents.surface(level, out.x, out.z), 0.45f, 2)
            live.busyUntil = level.gameTime + 60
        } else if (at != null) runCatching {
            // called out on purpose (a whistle, a hatching): the usual send-out beam
            entity.beamMode = 2
            entity.after(1.5f) { entity.beamMode = 0 }
        }
        return live
    }

    /**
     * True if (x, z) is out of every player's sight: far enough away, or behind them.
     */
    fun unseen(players: List<ServerPlayer>, x: Int, z: Int): Boolean {
        val hidden = SanctuaryConfig.despawnHiddenDistance.get().toDouble()
        return players.all { p ->
            val dx = x + 0.5 - p.x
            val dz = z + 0.5 - p.z
            val d2 = dx * dx + dz * dz
            if (d2 >= hidden * hidden) return@all true
            if (d2 < 12.0 * 12.0) return@all false
            val look = p.lookAngle
            val len = Math.sqrt(d2)
            (look.x * dx + look.z * dz) / (len * Math.max(1e-6, Math.sqrt(look.x * look.x + look.z * look.z))) < -0.35
        }
    }

    fun despawn(rt: SanctuaryRuntime, r: LiveResident, quiet: Boolean = false) {
        rt.live.remove(r.pokemonId)
        Routines.clearSleep(r)
        MegaCompat.onDespawn(r.entity)
        if (!r.entity.isRemoved) {
            if (!quiet) Effects.poof(r.entity)
            r.entity.discard()
        }
        if (r.entity.pokemon.tetheringId == r.tethering.tetheringId) r.entity.pokemon.tetheringId = null
    }

    fun despawnAll(rt: SanctuaryRuntime) {
        rt.scenes.clear()
        rt.live.values.toList().forEach { despawn(rt, it, quiet = true) }
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
