package com.rickygamer28.sanctuary.world

import com.rickygamer28.sanctuary.SanctuaryDimension
import com.rickygamer28.sanctuary.data.Sanctuary
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.features.TreeFeatures
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One special landmark in the middle of every biome, built once the biome is awake:
 * a Great Tree, a desert pyramid, an amethyst spire, a haunted tower, a dojo arena…
 * Routes lead to them from the hub (see BiomeDresser).
 */
object Landmarks {
    private const val FLAGS = Block.UPDATE_CLIENTS
    private const val G = SanctuaryDimension.GROUND_Y
    const val RADIUS = 8

    private fun b(block: Block): BlockState = block.defaultBlockState()
    private fun leaves(block: Block): BlockState = block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)

    /** Names shown in the Journal. */
    private val NAMES = mapOf(
        "water" to "a palm island", "grass" to "the Great Tree", "bug" to "a giant hollow stump", "fairy" to "a fairy fountain",
        "fire" to "the Eternal Flame", "ground" to "an ancient pyramid", "rock" to "a stone arch", "fighting" to "a dojo arena",
        "dragon" to "the Dragon's Spire", "dark" to "a shadow obelisk", "ghost" to "a haunted tower", "poison" to "a giant mushroom grove",
        "psychic" to "an amethyst spire", "steel" to "an old radio tower", "electric" to "a power line", "ice" to "a frozen spire",
    )

    /** Stored for a biome whose landmark is part of the terrain itself (the volcano): nothing to protect. */
    const val NONE = Long.MIN_VALUE

    /** Is (x, z) on a landmark? The terrain builder leaves those columns alone. */
    fun near(s: Sanctuary, x: Int, z: Int): Boolean = (s.landmarks.values + s.staleLandmarks.values).any {
        if (it == NONE) return@any false
        val p = BlockPos.of(it)
        val dx = p.x - x
        val dz = p.z - z
        dx * dx + dz * dz <= RADIUS * RADIUS
    }

    /** Builds at most one missing landmark whose area is loaded. */
    fun tick(level: ServerLevel, s: Sanctuary) {
        fixVolcano(s)
        val ring = ZoneLayout.ring()
        if (relocate(level, s, ring)) return
        for (zone in ring) {
            if (zone !in s.unlocked || s.landmarks.containsKey(zone) || s.staleLandmarks.containsKey(zone)) continue
            val (ax, az) = ZoneLayout.anchor(s, zone, ring)
            if (!loadedAround(level, ax, az, RADIUS + 2)) continue
            val variant = BiomeDresser.variantOf(s, zone)
            val base = BlockPos(ax, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ax, az) - 1, az)
            val random = RandomSource.create(s.seed xor zone.hashCode().toLong())
            if (zone == "fire" && variant == 0) { s.landmarks[zone] = NONE; continue } // the volcano is the landmark
            runCatching { build(level, s, zone, variant, base, random) }
            s.landmarks[zone] = base.asLong()
            NAMES[zone]?.let { s.logEvent("${it.replaceFirstChar { c -> c.uppercase() }} rose in the ${zone.replaceFirstChar { c -> c.uppercase() }} biome.") }
            return
        }
    }

    /**
     * Older versions saved the volcano's crater as the Fire landmark, so after the valley grew, the old crater
     * was protected and stood there as a pillar. Forget it and let the builder reshape that spot.
     */
    private fun fixVolcano(s: Sanctuary) {
        val pos = s.landmarks["fire"] ?: return
        if (pos == NONE || BiomeDresser.variantOf(s, "fire") != 0) return
        s.landmarks["fire"] = NONE
        val p = BlockPos.of(pos)
        for (dx in -RADIUS - 2..RADIUS + 2) for (dz in -RADIUS - 2..RADIUS + 2) {
            val idx = ColumnOrder.indexOf(p.x + dx - s.center.x, p.z + dz - s.center.z)
            if (idx >= 0) s.dressed.clear(idx)
        }
        s.generationDirty = true
    }

    private fun loadedAround(level: ServerLevel, x: Int, z: Int, r: Int) =
        listOf(-r, r).all { dx -> listOf(-r, r).all { dz -> level.hasChunk((x + dx) shr 4, (z + dz) shr 4) } }

    /**
     * When the valley grows, the middle of every biome moves outwards. A landmark left at the old spot would keep
     * its old ground (it's protected from reshaping), so it ends up on a pillar or in a pit: take it down,
     * let the builder reshape the spot, and build it again in the new middle of its biome.
     */
    private fun relocate(level: ServerLevel, s: Sanctuary, ring: List<String>): Boolean {
        val candidates = s.staleLandmarks.entries.map { Triple(it.key, it.value, true) } + s.landmarks.entries.map { Triple(it.key, it.value, false) }
        for ((zone, packed, stale) in candidates) {
            if (packed == NONE) { if (stale) s.staleLandmarks.remove(zone); continue }
            val p = BlockPos.of(packed)
            val (ax, az) = ZoneLayout.anchor(s, zone, ring)
            val dx = (p.x - ax).toDouble()
            val dz = (p.z - az).toDouble()
            if (!stale && zone in ring && dx * dx + dz * dz <= 12.0 * 12.0) continue
            val r = RADIUS + 2
            if (!loadedAround(level, p.x, p.z, r)) continue
            // take down everything built above the landmark's floor, and turn the floor back into plain ground
            for (ox in -r..r) for (oz in -r..r) {
                if (ox * ox + oz * oz > r * r) continue
                for (y in p.y + 1..p.y + 28) {
                    val pos = BlockPos(p.x + ox, y, p.z + oz)
                    val st = level.getBlockState(pos)
                    if (!st.isAir && !st.`is`(com.rickygamer28.sanctuary.registry.ModContent.EGG_NEST.get()) &&
                        !st.`is`(com.rickygamer28.sanctuary.registry.ModContent.SANCTUARY_CORE.get())) level.setBlock(pos, b(Blocks.AIR), FLAGS)
                }
                val floor = BlockPos(p.x + ox, p.y, p.z + oz)
                if (!BiomeDresser.isNatural(level.getBlockState(floor))) level.setBlock(floor, b(Blocks.STONE), FLAGS)
            }
            if (stale) s.staleLandmarks.remove(zone) else s.landmarks.remove(zone)
            for (ox in -r - 2..r + 2) for (oz in -r - 2..r + 2) {
                val idx = ColumnOrder.indexOf(p.x + ox - s.center.x, p.z + oz - s.center.z)
                if (idx >= 0) { s.dressed.clear(idx); s.meadow.clear(idx) }
            }
            s.generationDirty = true
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ helpers

    private class B(val level: ServerLevel, val base: BlockPos, val random: RandomSource) {
        fun set(dx: Int, dy: Int, dz: Int, st: BlockState) { level.setBlock(base.offset(dx, dy, dz), st, FLAGS) }
        fun soft(dx: Int, dy: Int, dz: Int, st: BlockState) {
            val p = base.offset(dx, dy, dz)
            val cur = level.getBlockState(p)
            if (cur.isAir || cur.canBeReplaced()) level.setBlock(p, st, FLAGS)
        }
        fun clear(r: Int, h: Int) {
            for (dx in -r..r) for (dz in -r..r) for (dy in 1..h) {
                if (dx * dx + dz * dz > r * r) continue
                val p = base.offset(dx, dy, dz)
                val st = level.getBlockState(p)
                if (!st.isAir && (st.canBeReplaced() || st.`is`(net.minecraft.tags.BlockTags.LEAVES) || st.`is`(net.minecraft.tags.BlockTags.LOGS) || st.`is`(net.minecraft.tags.BlockTags.FLOWERS)))
                    level.setBlock(p, b(Blocks.AIR), FLAGS)
            }
        }
        /** A flat floor of radius [r] at the base height, filled down so it never floats. */
        fun floor(r: Int, top: (Int, Int) -> BlockState, fill: BlockState) {
            for (dx in -r..r) for (dz in -r..r) {
                if (dx * dx + dz * dz > r * r + r) continue
                set(dx, 0, dz, top(dx, dz))
                for (dy in -1 downTo -6) {
                    val p = base.offset(dx, dy, dz)
                    if (!level.getBlockState(p).isAir && level.getBlockState(p).fluidState.isEmpty) break
                    level.setBlock(p, fill, FLAGS)
                }
            }
        }
        fun column(dx: Int, dz: Int, from: Int, h: Int, st: BlockState) { for (dy in from until from + h) set(dx, dy, dz, st) }
        fun blob(cx: Int, cy: Int, cz: Int, r: Double, st: BlockState) {
            val ri = r.toInt() + 1
            for (dx in -ri..ri) for (dy in -ri..ri) for (dz in -ri..ri) {
                if (dx * dx + dy * dy + dz * dz <= r * r) soft(cx + dx, cy + dy, cz + dz, st)
            }
        }
        fun feature(key: ResourceKey<ConfiguredFeature<*, *>>, dx: Int, dz: Int) {
            runCatching {
                val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.x + dx, base.z + dz)
                level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key).ifPresent {
                    it.value().place(level, level.chunkSource.generator, random, BlockPos(base.x + dx, y, base.z + dz))
                }
            }
        }
        /** A tapered spike, wide at the bottom. */
        fun spike(r0: Double, h: Int, st: (Int) -> BlockState) {
            for (dy in 1..h) {
                val r = r0 * (1 - (dy - 1).toDouble() / h) + 0.4
                val ri = r.toInt() + 1
                for (dx in -ri..ri) for (dz in -ri..ri) if (dx * dx + dz * dz <= r * r) set(dx, dy, dz, st(dy))
            }
        }
    }

    // ------------------------------------------------------------------ the landmarks

    private fun build(level: ServerLevel, @Suppress("UNUSED_PARAMETER") s: Sanctuary, zone: String, variant: Int, base: BlockPos, random: RandomSource) {
        val c = B(level, base, random)
        when (zone) {
            "water" -> palmIsland(c)
            "grass" -> greatTree(c)
            "bug" -> hollowStump(c)
            "fairy" -> fairyFountain(c)
            "fire" -> if (variant != 0) eternalFlame(c) // the volcano already is the landmark
            "ground" -> pyramid(c)
            "rock" -> stoneArch(c)
            "fighting" -> dojoArena(c)
            "dragon" -> dragonSpire(c)
            "dark" -> obelisk(c)
            "ghost" -> hauntedTower(c)
            "poison" -> mushroomGrove(c)
            "psychic" -> amethystSpire(c)
            "steel" -> radioTower(c)
            "electric" -> powerLine(c)
            "ice" -> frozenSpire(c)
            else -> c.spike(2.5, 9) { b(Blocks.AMETHYST_BLOCK) }
        }
    }

    private fun palmIsland(c: B) {
        // raise a sandy island out of the lake (or a little dune on land)
        val r = 6
        for (dx in -r..r) for (dz in -r..r) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d > r) continue
            val top = G + if (d < 3.0) 1 else 0
            val x = c.base.x + dx
            val z = c.base.z + dz
            for (y in G - 6..top) {
                val p = BlockPos(x, y, z)
                val st = c.level.getBlockState(p)
                if (st.isAir || !st.fluidState.isEmpty || st.canBeReplaced() || y == top) c.level.setBlock(p, if (y == top && d < 2.5) b(Blocks.GRASS_BLOCK) else b(Blocks.SAND), FLAGS)
            }
            for (y in top + 1..top + 3) { val p = BlockPos(x, y, z); if (!c.level.getBlockState(p).fluidState.isEmpty) c.level.setBlock(p, b(Blocks.AIR), FLAGS) }
        }
        // a curved palm tree
        val log = b(Blocks.JUNGLE_LOG)
        var tx = 0
        var tz = 0
        val y0 = G + 1 - c.base.y
        for (i in 1..7) {
            if (i == 4 || i == 6) tx++
            c.set(tx, y0 + i, tz, log)
        }
        val top = y0 + 8
        val leaf = leaves(Blocks.JUNGLE_LEAVES)
        c.set(tx, top, tz, leaf)
        for (d in Direction.Plane.HORIZONTAL) {
            for (k in 1..3) c.soft(tx + d.stepX * k, top - (if (k == 3) 1 else 0), tz + d.stepZ * k, leaf)
            c.soft(tx + d.stepX * 3, top - 2, tz + d.stepZ * 3, leaf)
        }
        for ((dx, dz) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) { c.soft(tx + dx, top, tz + dz, leaf); c.soft(tx + dx * 2, top - 1, tz + dz * 2, leaf) }
        c.soft(tx + 1, top - 1, tz, b(Blocks.COCOA).setValue(net.minecraft.world.level.block.CocoaBlock.FACING, Direction.WEST))
    }

    private fun greatTree(c: B) {
        c.clear(7, 22)
        val log = b(Blocks.OAK_LOG)
        val h = 13
        for (dx in 0..1) for (dz in 0..1) c.column(dx, dz, 1, h, log)
        // roots
        for ((dx, dz) in listOf(-1 to 0, 2 to 1, 0 to -1, 1 to 2, -1 to 1, 2 to 0)) c.column(dx, dz, 1, 1 + c.random.nextInt(2), log)
        // branches
        val dirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
        for ((i, d) in dirs.withIndex()) {
            val y = 8 + i % 2 * 2
            for (k in 1..4) c.set((if (d.stepX > 0) 1 else 0) + d.stepX * k, y + k / 2, (if (d.stepZ > 0) 1 else 0) + d.stepZ * k,
                log.setValue(RotatedPillarBlock.AXIS, d.axis))
        }
        val leaf = leaves(Blocks.OAK_LEAVES)
        c.blob(0, h + 1, 0, 5.5, leaf)
        c.blob(4, 11, 0, 3.2, leaf); c.blob(-4, 11, 1, 3.2, leaf); c.blob(1, 12, 4, 3.2, leaf); c.blob(0, 12, -4, 3.2, leaf)
        c.soft(0, h + 7, 0, leaf)
        // a few beehives and flowers around
        c.soft(2, 6, 0, b(Blocks.BEE_NEST))
        for (i in 0 until 14) {
            val a = c.random.nextDouble() * Math.PI * 2
            val r = 4 + c.random.nextInt(4)
            c.soft((Math.sin(a) * r).toInt(), 1, (Math.cos(a) * r).toInt(), listOf(b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.AZURE_BLUET))[c.random.nextInt(3)])
        }
    }

    private fun hollowStump(c: B) {
        c.clear(5, 8)
        val log = b(Blocks.OAK_LOG)
        for (dx in -3..3) for (dz in -3..3) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d > 3.4 || d < 2.2) continue
            val h = 3 + c.random.nextInt(3)
            c.column(dx, dz, 1, h, log)
            c.soft(dx, 1 + h, dz, b(Blocks.MOSS_CARPET))
        }
        // a doorway
        c.set(0, 1, 3, b(Blocks.AIR)); c.set(0, 2, 3, b(Blocks.AIR))
        for (dx in -2..2) for (dz in -2..2) if (dx * dx + dz * dz <= 4) c.set(dx, 0, dz, b(Blocks.MOSS_BLOCK))
        c.soft(1, 1, -1, b(Blocks.BROWN_MUSHROOM)); c.soft(-1, 1, 1, b(Blocks.RED_MUSHROOM)); c.soft(0, 1, 0, b(Blocks.MOSS_CARPET))
        c.set(3, 3, 0, b(Blocks.BEE_NEST)); c.set(-3, 2, -1, b(Blocks.BEE_NEST))
    }

    private fun fairyFountain(c: B) {
        c.clear(7, 6)
        c.floor(5, { dx, dz -> if (dx * dx + dz * dz <= 4) b(Blocks.CALCITE) else b(Blocks.MOSS_BLOCK) }, b(Blocks.DIRT))
        // pool with a little spout
        for (dx in -2..2) for (dz in -2..2) {
            val d = dx * dx + dz * dz
            if (d <= 2) c.set(dx, 0, dz, b(Blocks.WATER))
            else if (d <= 5) c.set(dx, 1, dz, b(Blocks.POLISHED_DIORITE_SLAB))
        }
        c.column(0, 0, 1, 2, b(Blocks.CALCITE)); c.set(0, 3, 0, b(Blocks.WATER))
        for (dx in -5..5) for (dz in -5..5) {
            val d = dx * dx + dz * dz
            if (d in 12..30 && c.random.nextInt(2) == 0) c.soft(dx, 1, dz, listOf(b(Blocks.PINK_TULIP), b(Blocks.ALLIUM), b(Blocks.LILY_OF_THE_VALLEY), b(Blocks.PINK_PETALS))[c.random.nextInt(4)])
        }
        for ((dx, dz) in listOf(6 to 0, -6 to 0, 0 to 6, 0 to -6)) c.feature(TreeFeatures.CHERRY, dx, dz)
    }

    private fun eternalFlame(c: B) {
        c.clear(5, 8)
        c.floor(4, { dx, dz -> if ((dx + dz) % 2 == 0) b(Blocks.POLISHED_BLACKSTONE_BRICKS) else b(Blocks.BLACKSTONE) }, b(Blocks.BLACKSTONE))
        for ((dx, dz) in listOf(3 to 3, -3 to 3, 3 to -3, -3 to -3)) {
            c.column(dx, dz, 1, 4, b(Blocks.POLISHED_BLACKSTONE))
            c.set(dx, 5, dz, b(Blocks.CAMPFIRE))
        }
        c.set(0, 0, 0, b(Blocks.MAGMA_BLOCK))
        for (d in Direction.Plane.HORIZONTAL) c.set(d.stepX, 0, d.stepZ, b(Blocks.MAGMA_BLOCK))
        c.set(0, 1, 0, b(Blocks.CAMPFIRE))
    }

    private fun pyramid(c: B) {
        c.clear(6, 8)
        for (layer in 0..3) {
            val r = 4 - layer
            for (dx in -r..r) for (dz in -r..r) {
                val edge = abs(dx) == r || abs(dz) == r
                c.set(dx, layer + 1, dz, if (edge && c.random.nextInt(5) == 0) b(Blocks.SMOOTH_SANDSTONE) else if (edge) b(Blocks.CUT_SANDSTONE) else b(Blocks.SANDSTONE))
            }
        }
        c.set(0, 5, 0, b(Blocks.CHISELED_SANDSTONE))
        // entrance
        c.set(0, 1, 4, b(Blocks.AIR)); c.set(0, 2, 4, b(Blocks.AIR)); c.set(0, 1, 3, b(Blocks.AIR)); c.set(0, 2, 3, b(Blocks.AIR))
        c.set(0, 3, 4, b(Blocks.CHISELED_SANDSTONE))
        c.floor(5, { _, _ -> b(Blocks.SAND) }, b(Blocks.SANDSTONE))
        for ((dx, dz) in listOf(-2 to 6, 2 to 6)) { c.column(dx, dz, 1, 3, b(Blocks.CUT_SANDSTONE)); c.set(dx, 4, dz, b(Blocks.CHISELED_SANDSTONE)) }
    }

    private fun stoneArch(c: B) {
        c.clear(6, 12)
        for (side in listOf(-4, 4)) for (dx in -1..1) for (dz in -1..1) c.column(side + dx, dz, 1, 7, if (c.random.nextInt(4) == 0) b(Blocks.ANDESITE) else b(Blocks.STONE))
        for (dx in -5..5) for (dz in -1..1) {
            val y = 8 + if (abs(dx) <= 2) 1 else 0
            c.set(dx, y, dz, b(Blocks.STONE)); if (abs(dx) <= 3) c.set(dx, y - 1, dz, b(Blocks.STONE))
            if (c.random.nextInt(3) == 0) c.soft(dx, y + 1, dz, b(Blocks.MOSS_CARPET))
        }
        for (dz in -1..1) for (dx in -2..2) c.set(dx, 7, dz, b(Blocks.AIR))
    }

    private fun dojoArena(c: B) {
        c.clear(8, 6)
        c.floor(7, { dx, dz ->
            val d = dx * dx + dz * dz
            when { d <= 2 -> b(Blocks.CHISELED_STONE_BRICKS); d >= 36 -> b(Blocks.STONE_BRICKS); (dx + dz) % 2 == 0 -> b(Blocks.POLISHED_ANDESITE); else -> b(Blocks.SMOOTH_STONE) }
        }, b(Blocks.STONE))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val dx = (Math.sin(a) * 7).toInt()
            val dz = (Math.cos(a) * 7).toInt()
            c.column(dx, dz, 1, 3, b(Blocks.STRIPPED_SPRUCE_LOG))
            c.set(dx, 4, dz, b(Blocks.LANTERN))
        }
        // a torii-style gate on the side facing the hub
        for (dx in listOf(-2, 2)) c.column(dx, 8, 1, 4, b(Blocks.STRIPPED_MANGROVE_LOG))
        for (dx in -3..3) c.set(dx, 5, 8, b(Blocks.MANGROVE_PLANKS))
        for (dx in -2..2) c.set(dx, 4, 8, b(Blocks.STRIPPED_MANGROVE_LOG).setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))
    }

    private fun dragonSpire(c: B) {
        c.clear(5, 22)
        c.floor(4, { dx, dz -> if (dx * dx + dz * dz <= 4) b(Blocks.POLISHED_DEEPSLATE) else b(Blocks.DEEPSLATE_TILES) }, b(Blocks.DEEPSLATE))
        c.spike(3.2, 18) { dy -> if (dy % 5 == 0) b(Blocks.CRYING_OBSIDIAN) else if (dy % 3 == 0) b(Blocks.DEEPSLATE_TILES) else b(Blocks.DEEPSLATE_BRICKS) }
        c.set(0, 19, 0, b(Blocks.END_ROD))
        for (d in Direction.Plane.HORIZONTAL) c.soft(d.stepX * 3, 1, d.stepZ * 3, b(Blocks.BONE_BLOCK))
    }

    private fun obelisk(c: B) {
        c.clear(4, 13)
        c.floor(3, { _, _ -> b(Blocks.POLISHED_BLACKSTONE_BRICKS) }, b(Blocks.BLACKSTONE))
        for (dx in -1..1) for (dz in -1..1) c.set(dx, 1, dz, b(Blocks.POLISHED_BLACKSTONE))
        c.column(0, 0, 2, 9, b(Blocks.POLISHED_BLACKSTONE))
        c.set(0, 6, 0, b(Blocks.CRYING_OBSIDIAN)); c.set(0, 11, 0, b(Blocks.CRYING_OBSIDIAN))
        for ((dx, dz) in listOf(3 to 3, -3 to 3, 3 to -3, -3 to -3)) { c.set(dx, 1, dz, b(Blocks.POLISHED_BLACKSTONE_WALL)); c.set(dx, 2, dz, b(Blocks.SOUL_LANTERN)) }
    }

    private fun hauntedTower(c: B) {
        c.clear(5, 14)
        c.floor(4, { _, _ -> b(Blocks.COBBLED_DEEPSLATE) }, b(Blocks.STONE))
        for (dx in -3..3) for (dz in -3..3) {
            val d = sqrt((dx * dx + dz * dz).toDouble())
            if (d > 3.4 || d < 2.4) continue
            val h = 5 + c.random.nextInt(7)
            for (dy in 1..h) {
                if (c.random.nextInt(9) == 0) continue
                c.set(dx, dy, dz, listOf(b(Blocks.STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS), b(Blocks.CRACKED_STONE_BRICKS))[c.random.nextInt(3)])
            }
        }
        c.set(0, 1, 3, b(Blocks.AIR)); c.set(0, 2, 3, b(Blocks.AIR))
        c.set(0, 1, 0, b(Blocks.SOUL_LANTERN))
        repeat(6) { c.soft(c.random.nextInt(5) - 2, 3 + c.random.nextInt(6), c.random.nextInt(5) - 2, b(Blocks.COBWEB)) }
    }

    private fun mushroomGrove(c: B) {
        c.clear(6, 4)
        c.floor(4, { _, _ -> b(Blocks.MYCELIUM) }, b(Blocks.DIRT))
        c.feature(TreeFeatures.HUGE_RED_MUSHROOM, 0, 0)
        c.feature(TreeFeatures.HUGE_BROWN_MUSHROOM, 5, 3)
        c.feature(TreeFeatures.HUGE_RED_MUSHROOM, -4, -4)
        repeat(10) { c.soft(c.random.nextInt(11) - 5, 1, c.random.nextInt(11) - 5, if (c.random.nextBoolean()) b(Blocks.RED_MUSHROOM) else b(Blocks.BROWN_MUSHROOM)) }
    }

    private fun amethystSpire(c: B) {
        c.clear(5, 18)
        c.floor(4, { dx, dz -> if ((dx * dx + dz * dz) % 3 == 0) b(Blocks.CALCITE) else b(Blocks.SMOOTH_BASALT) }, b(Blocks.CALCITE))
        c.spike(2.6, 14) { dy -> if (dy % 4 == 0) b(Blocks.CALCITE) else b(Blocks.AMETHYST_BLOCK) }
        c.set(0, 15, 0, b(Blocks.AMETHYST_CLUSTER))
        for (d in Direction.Plane.HORIZONTAL) {
            c.soft(d.stepX * 3, 1, d.stepZ * 3, b(Blocks.AMETHYST_CLUSTER))
            c.column(d.stepX * 4 + d.stepZ, d.stepZ * 4 - d.stepX, 1, 2, b(Blocks.AMETHYST_BLOCK))
            c.soft(d.stepX * 4 + d.stepZ, 3, d.stepZ * 4 - d.stepX, b(Blocks.END_ROD))
        }
    }

    private fun radioTower(c: B) {
        c.clear(4, 18)
        c.floor(3, { _, _ -> b(Blocks.POLISHED_ANDESITE) }, b(Blocks.STONE))
        val h = 15
        for ((dx, dz) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) c.column(dx, dz, 1, h, b(Blocks.IRON_BARS))
        for (y in listOf(4, 8, 12)) for (dx in -1..1) for (dz in -1..1) if (abs(dx) == 1 || abs(dz) == 1) c.set(dx, y, dz, b(Blocks.WEATHERED_COPPER))
        c.column(0, 0, h - 1, 3, b(Blocks.IRON_BARS))
        c.set(0, h + 2, 0, b(Blocks.LIGHTNING_ROD))
        c.set(0, 1, 0, b(Blocks.REDSTONE_LAMP))
    }

    private fun powerLine(c: B) {
        c.clear(7, 12)
        for (side in listOf(-5, 5)) {
            for (dx in 0..1) for (dz in 0..1) c.column(side + dx, dz, 1, 8, b(Blocks.EXPOSED_COPPER))
            c.set(side, 9, 0, b(Blocks.LIGHTNING_ROD)); c.set(side + 1, 9, 1, b(Blocks.LIGHTNING_ROD))
        }
        for (dx in -3..4) c.set(dx, 7 - (if (abs(dx) <= 1) 1 else 0), 0, b(Blocks.CHAIN).setValue(ChainBlock.AXIS, Direction.Axis.X))
        c.set(0, 1, 2, b(Blocks.REDSTONE_LAMP)); c.set(0, 1, -2, b(Blocks.TARGET))
    }

    private fun frozenSpire(c: B) {
        c.clear(5, 20)
        c.floor(4, { _, _ -> b(Blocks.SNOW_BLOCK) }, b(Blocks.PACKED_ICE))
        c.spike(3.0, 17) { dy -> if (dy % 4 == 0) b(Blocks.BLUE_ICE) else b(Blocks.PACKED_ICE) }
        for ((dx, dz) in listOf(4 to 1, -3 to 3, 1 to -4)) for (dy in 1..3 + c.random.nextInt(3)) c.set(dx, dy, dz, b(Blocks.PACKED_ICE))
        for (dx in -4..4) for (dz in -4..4) if (dx * dx + dz * dz in 10..20) c.soft(dx, 1, dz, b(Blocks.SNOW))
    }
}
