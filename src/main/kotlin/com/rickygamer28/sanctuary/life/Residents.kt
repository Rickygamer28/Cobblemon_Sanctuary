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
import kotlin.math.sqrt

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
    /** Came over to greet its trainer, and follows them until then. */
    var greetPlayer: UUID? = null
    var greetUntil = 0L
    var greeted = false
    /** Going back to rest: walking into a den (or out of sight, or diving), then gone. */
    var leaving = false
    /** Lives in a little pocket of its habitat out in another area instead of in the habitat itself. */
    var pocket: ZoneLayout.Pocket? = null
    /** Lives down in the caves of the Cave & mountain habitat. */
    var cave = false
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
        LoadGuard.building = SanctuaryGenerator.isGrowing(s)
        LoadGuard.tick(level.server, now)

        if (rt.rosterDirty || now - rt.rosterAt > 200) refreshRoster(level, s, rt, now)
        cleanUp(rt)
        Gathering.tick(level, s, rt, now)
        Growth.tick(level, s, rt, now)
        Milestones.tick(level, s, rt, now)
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
        Greeting.tick(level, s, rt, players, now)
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

        val ring = ZoneLayout.zonesOf(s)
        val counts = HashMap<String, Int>()
        // custom types (other mods'): how many Pokémon each has, for giving it a habitat of its own
        val customCounts = HashMap<String, Int>()
        for (e in list) {
            val types = runCatching { TypeLogic.zoneTypes(e.pokemon, s) }.getOrDefault(emptyList())
            for (t in types.toSet()) counts[t] = (counts[t] ?: 0) + 1
            for (t in runCatching { TypeLogic.customTypes(e.pokemon) }.getOrDefault(emptyList()).toSet()) customCounts[t] = (customCounts[t] ?: 0) + 1
        }
        s.customCounts = customCounts
        val unlocked = linkedSetOf(ZoneLayout.HUB) + counts.keys.filter { it in ring } + s.customPockets
        val newTypes = unlocked - s.unlocked
        val cavesChange = s.state == SanctuaryState.ACTIVE && s.planned && s.caveStyle >= 2 &&
            com.rickygamer28.sanctuary.world.Caves.MOUNTAINS.any { (it in unlocked) != (it in s.unlocked) }
        val grew = counts.any { (t, c) -> c > (s.typeCounts[t] ?: 0) }
        if (s.state == SanctuaryState.ACTIVE) {
            newTypes.filter { it != ZoneLayout.HUB && s.unlocked.size > 1 && it in s.habitats }.forEach { s.logEvent("The ${zoneName(it)} woke up!") }
        }
        s.typeCounts = counts
        s.residentCount = list.size
        s.unlocked = unlocked
        if (newTypes.isNotEmpty()) s.unlockedChanged = true
        // the pockets of a habitat that just woke up get built too
        if (s.planned && s.state == SanctuaryState.ACTIVE) s.rebuildZones += newTypes.filter { it != ZoneLayout.HUB && ZoneLayout.pockets(s).any { p -> p.zone == it } }
        // a habitat waking up in its place: it and the strip along its borders (which blends into it) are built again
        if (s.planned && s.state == SanctuaryState.ACTIVE) s.rebuildZones += newTypes.filter { it != ZoneLayout.HUB && it in s.habitats }
        if (newTypes.isNotEmpty() || grew) s.generationDirty = true
        if (cavesChange) com.rickygamer28.sanctuary.world.SanctuaryGenerator.redoCaves(s)
        planRegion(s, counts, customCounts)
        rt.anchors = unlocked.associateWith { ZoneLayout.anchor(s, it) }
    }

    /**
     * The region map: laid out the first time (and after a Reshape) from how many residents live in each habitat;
     * a habitat that wakes up later is fitted into a free meadow without moving the others.
     */
    private fun planRegion(s: Sanctuary, counts: Map<String, Int>, customCounts: Map<String, Int> = emptyMap()) {
        if (s.state != SanctuaryState.ACTIVE) return
        // custom types with enough Pokémon (and the owner hasn't sent them to live elsewhere) want a habitat of their own
        val ownWanted = customCounts.filter { (t, n) -> n >= com.rickygamer28.sanctuary.world.CustomTypes.minResidents() && com.rickygamer28.sanctuary.world.CustomTypes.wantsOwn(s, t) }
        if (!s.planned) {
            s.pocketStyle = 2
            s.caveStyle = 2
            s.habitats.clear()
            s.habitats.putAll(ZoneLayout.plan(s, counts + ownWanted))
            s.planned = true
            s.planRadius = s.size.radius
            s.generationDirty = true
            updatePockets(s, customCounts)
            return
        }
        for ((zone, n) in ZoneLayout.HABITATS.associateWith { counts[it] ?: 0 } + ownWanted) {
            if (n <= 0 || zone in s.habitats) continue
            if (zone in s.noRoomFor) continue
            val hb = ZoneLayout.fitIn(s, zone, n) ?: run {
                // no room for it until the next Reshape (its Pokémon live in the habitat that suits them meanwhile)
                if (com.rickygamer28.sanctuary.world.CustomTypes.isCustom(zone)) {
                    s.noRoomFor += zone
                    s.logEvent("The ${zoneName(zone)} is ready to appear, but there's no room left: it will on the next Reshape.")
                }
                null
            } ?: continue
            s.habitats[zone] = hb
            s.rebuildZones += zone
            s.generationDirty = true
            s.logEvent("A new habitat appeared in the valley: the ${zoneName(zone)}!")
        }
        updatePockets(s, customCounts)
    }

    /**
     * Custom types with a few Pokémon (not yet enough for a habitat) get a pocket of their own, inside the habitat
     * they live in: a little patch built from their palette and look.
     */
    private fun updatePockets(s: Sanctuary, customCounts: Map<String, Int>) {
        val min = com.rickygamer28.sanctuary.world.CustomTypes.pocketMin()
        val wanted = customCounts.filter { (t, n) -> n >= min && t !in s.habitats && com.rickygamer28.sanctuary.world.CustomTypes.wantsOwn(s, t) }.keys
        if (wanted == s.customPockets.toSet()) return
        val before = ZoneLayout.pockets(s).associate { (it.x to it.z) to it.zone }
        val added = wanted - s.customPockets
        val gone = s.customPockets - wanted
        s.customPockets.clear()
        s.customPockets += wanted
        s.unlocked = s.unlocked - gone + wanted
        val after = ZoneLayout.pockets(s)
        // only the pockets that changed are built again
        for (p in after) if (before[p.x to p.z] != p.zone) s.rebuildZones += "pocket:${p.x},${p.z}"
        s.generationDirty = true
        for (t in added) {
            val p = after.firstOrNull { it.zone == t } ?: continue
            s.logEvent("A little ${com.rickygamer28.sanctuary.world.CustomTypes.pocketName(t)} appeared in the ${zoneName(p.host)}!")
        }
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
            if (rt.shiftNight == true && !night) Jobs.dawn(level, s)
            if (rt.shiftNight != null && rt.shiftNight != night) {
                s.logEvent(if (night) "Night fell: the night owls came out and the others went home to their dens." else "Morning came: the day crowd came out of their dens.")
            }
            rt.shiftNight = night
            rt.shuffleSeed = level.random.nextLong()
            rt.shuffleAt = now
        }
        val ring = ZoneLayout.ring(s)

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
            val home = TypeLogic.homeZone(e.pokemon, s.residents[e.pokemon.uuid], ring, s)
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
        val ring = ZoneLayout.ring(s)
        val home = TypeLogic.homeZone(pokemon, s.residents[pokemon.uuid], ring, s)
        val swimmer = TypeLogic.waterOnly(pokemon)
        val spawnZone = if (swimmer) "water" else home ?: s.unlocked.random()
        if (swimmer && "water" !in s.unlocked) return null
        var fromDen: com.rickygamer28.sanctuary.world.Dens.Den? = null
        var inPocket: ZoneLayout.Pocket? = null
        var inCave = false
        val pos = at ?: run {
            val range = SanctuaryConfig.spawnRange.get().toDouble()
            fun nearPlayer(x: Int, z: Int) = players.isEmpty() || players.any { val dx = it.x - x; val dz = it.z - z; dx * dx + dz * dz < range * range }
            // some of the mountain habitats' residents live down in their part of the caves
            if (home != null && com.rickygamer28.sanctuary.world.Caves.hasSection(s, home) && !swimmer && !pokemon.form.behaviour.moving.fly.canFly &&
                level.random.nextDouble() < SanctuaryConfig.caveShare.get()) {
                repeat(4) {
                    val p = com.rickygamer28.sanctuary.world.Caves.randomFloor(s, level.random, home) ?: return@repeat
                    if (!level.hasChunk(p.x shr 4, p.z shr 4) || !level.getBlockState(p).isAir || !level.getBlockState(p.above()).isAir) return@repeat
                    if (!unseen(players, p.x, p.z)) return@repeat
                    inCave = true
                    return@run p
                }
            }
            // a custom type's Pokémon mostly live in its pocket, while it has one
            val ownPocket = if (swimmer) null else runCatching { TypeLogic.customTypes(pokemon) }.getOrDefault(emptyList())
                .firstOrNull { t -> ZoneLayout.customPocket(s, t)?.live(s) == true }
            if (ownPocket != null && level.random.nextDouble() < 0.8) {
                repeat(4) {
                    val (p, pt) = ZoneLayout.pocketPoint(s, ownPocket, level.random) ?: return@repeat
                    if (!level.hasChunk(pt.first shr 4, pt.second shr 4)) return@repeat
                    if (!unseen(players, pt.first, pt.second)) return@repeat
                    inPocket = p
                    return@run BlockPos(pt.first, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pt.first, pt.second), pt.second)
                }
            }
            // some live out in a pocket of their habitat (a pond in the forest, an outcrop in the meadow…)
            if (home != null && home != ZoneLayout.HUB && level.random.nextDouble() < SanctuaryConfig.pocketShare.get()) {
                val near = players.randomOrNull()?.let { it.blockX to it.blockZ }
                repeat(4) {
                    val (p, pt) = ZoneLayout.pocketPoint(s, spawnZone, level.random, near) ?: return@repeat
                    if (!level.hasChunk(pt.first shr 4, pt.second shr 4)) return@repeat
                    if (swimmer && !Behaviour.isWater(level, pt.first, pt.second)) return@repeat
                    if (!unseen(players, pt.first, pt.second)) return@repeat
                    inPocket = p
                    return@run BlockPos(pt.first, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pt.first, pt.second), pt.second)
                }
            }
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
        live.second = TypeLogic.secondZone(pokemon, home, ring, s)
        live.flyer = TypeLogic.canFlyInFlocks(pokemon)
        live.swimmer = swimmer
        live.pocket = inPocket
        live.cave = inCave
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
        longWalks.remove(r.entity.uuid)
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

    /** Where residents on a long walk are ultimately headed (they get there in legs). */
    private class LongWalk(val pos: BlockPos, val speed: Float, val closeEnough: Int)
    private val longWalks = HashMap<UUID, LongWalk>()

    /** Longer than this, a walk is split into legs. */
    private const val LEG = 30

    /**
     * Sends a resident walking to [pos]. Pathfinding can't plan very far ahead: a far target makes it plan, give up,
     * stand still and try again, which looks like stuttering (and costs the server time). So far walks over open
     * ground go in legs of about [LEG] blocks; [continueLongWalk] starts the next leg when one is done.
     */
    fun walkTo(entity: PokemonEntity, pos: BlockPos, speed: Float = 0.4f, closeEnough: Int = 1) {
        val level = entity.level() as? ServerLevel
        val dx = pos.x - entity.x
        val dz = pos.z - entity.z
        val dist = sqrt(dx * dx + dz * dz)
        val surfaceTarget = level != null &&
            pos.y >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z) - 2 && !entity.isInWater
        if (level != null && surfaceTarget && dist > LEG + 10) {
            val lx = (entity.x + dx / dist * LEG).toInt()
            val lz = (entity.z + dz / dist * LEG).toInt()
            if (level.hasChunk(lx shr 4, lz shr 4)) {
                longWalks[entity.uuid] = LongWalk(pos, speed, closeEnough)
                entity.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(surface(level, lx, lz), speed, 3))
                return
            }
        }
        longWalks.remove(entity.uuid)
        entity.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(pos, speed, closeEnough))
    }

    /** A leg of a long walk is done: on to the next one. True while the resident is on a long walk. */
    fun continueLongWalk(entity: PokemonEntity): Boolean {
        val walk = longWalks[entity.uuid] ?: return false
        if (entity.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) return true
        walkTo(entity, walk.pos, walk.speed, walk.closeEnough)
        return true
    }

    fun forgetWalk(entity: PokemonEntity) { longWalks.remove(entity.uuid) }

    fun stop(entity: PokemonEntity) {
        longWalks.remove(entity.uuid)
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
        else -> ZoneLayout.NAMES[zone] ?: com.rickygamer28.sanctuary.world.CustomTypes.habitatName(zone)
    }

    fun findRoster(rt: SanctuaryRuntime, pokemonId: UUID): RosterEntry? = rt.roster.firstOrNull { it.pokemon.uuid == pokemonId }
}
