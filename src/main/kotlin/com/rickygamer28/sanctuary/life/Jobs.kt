package com.rickygamer28.sanctuary.life

import com.rickygamer28.sanctuary.SanctuaryConfig
import com.rickygamer28.sanctuary.data.Sanctuary
import com.rickygamer28.sanctuary.world.Props
import com.rickygamer28.sanctuary.world.TypeLogic
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * Jobs: when a resident visits a spot that suits its type, it does a little chore there. Some change the valley
 * (relighting campfires, making berry trees grow, flowers bloom), and most can turn up something small for the
 * Gathering Basket. Finds are small and slow on purpose: a bonus from a living valley, not a farm.
 */
object Jobs {
    private class Find(val chance: Double, val items: List<Pair<String, Int>>, val tag: String? = null)

    private class Job(
        val type: String,
        /** Which spots (by kind id, or by action) it works at. */
        val kinds: Set<String> = emptySet(),
        val actions: Set<Props.Action> = emptySet(),
        /** What it did, for the Journal: "{name} tended the berry tree". */
        val done: String,
        val find: Find? = null,
        val effect: ((ServerLevel, Sanctuary, LiveResident, BlockPos) -> Unit)? = null,
    ) {
        fun fits(kind: Props.Kind) = kind.id in kinds || kind.action in actions
    }

    private val JOBS = listOf(
        Job("grass", kinds = setOf("berry_tree"), done = "tended the berry tree", find = Find(0.35, emptyList(), tag = "berry"), effect = ::growTree),
        Job("water", kinds = setOf("berry_tree", "flower_bed", "flower_ring"), done = "watered the plants", find = Find(0.12, emptyList(), tag = "berry"), effect = ::water),
        Job("bug", kinds = setOf("flower_bed", "flower_ring", "berry_tree"), done = "gathered nectar from the flowers", find = Find(0.3, listOf("minecraft:honeycomb" to 1, "minecraft:honey_bottle" to 1))),
        Job("fire", kinds = setOf("campfire", "warm_rocks"), done = "kept the fire going", find = Find(0.15, listOf("minecraft:charcoal" to 1)), effect = ::relight),
        Job("electric", kinds = setOf("lightning_rod", "copper_rocks", "scrap_pile"), done = "charged up the lightning rod", find = Find(0.25, listOf("minecraft:redstone" to 2, "minecraft:copper_ingot" to 1))),
        Job("ground", kinds = setOf("sand_mound", "old_bones", "boulder", "muddy_puddle"), done = "dug around", find = Find(0.3, listOf("minecraft:clay_ball" to 2, "minecraft:bone" to 1, "minecraft:flint" to 1), tag = "fossil")),
        Job("rock", kinds = setOf("boulder", "stone_circle", "crystal", "lifting_rock"), done = "chipped at the rocks", find = Find(0.25, listOf("cobblemon:tumblestone" to 1, "minecraft:flint" to 1, "cobblemon:sky_tumblestone" to 1))),
        Job("steel", kinds = setOf("scrap_pile", "iron_rock"), done = "sorted through the scrap", find = Find(0.25, listOf("minecraft:iron_nugget" to 3, "minecraft:iron_ingot" to 1))),
        Job("psychic", kinds = setOf("crystal", "meditation_stone", "stone_circle"), done = "focused at the crystal", find = Find(0.2, listOf("minecraft:amethyst_shard" to 2)), effect = ::bloom),
        Job("fairy", actions = setOf(Props.Action.SNIFF, Props.Action.SHIMMER, Props.Action.NAP), done = "made the flowers bloom", find = Find(0.12, listOf("minecraft:pink_petals" to 2, "minecraft:allium" to 1)), effect = ::bloom),
        Job("ghost", kinds = setOf("soul_lantern", "old_bones", "hollow_log", "ancient_pillar"), done = "found something lost", find = Find(0.2, listOf("minecraft:gold_nugget" to 2, "cobblemon:relic_coin" to 1, "minecraft:bone" to 1))),
        Job("dark", kinds = setOf("hollow_log", "old_bones", "mushroom_ring"), done = "snooped around", find = Find(0.2, listOf("minecraft:gold_nugget" to 1, "minecraft:spider_eye" to 1))),
        Job("ice", kinds = setOf("snow_pile", "frozen_pond"), done = "packed some snow", find = Find(0.3, listOf("minecraft:snowball" to 3, "minecraft:ice" to 1))),
        Job("poison", kinds = setOf("muddy_puddle", "mushroom_ring", "rotten_log"), done = "foraged mushrooms", find = Find(0.25, listOf("minecraft:brown_mushroom" to 1, "minecraft:red_mushroom" to 1))),
        Job("dragon", kinds = setOf("hoard", "ancient_pillar"), done = "guarded its hoard", find = Find(0.15, listOf("minecraft:gold_nugget" to 3, "cobblemon:dragon_scale" to 1))),
        Job("normal", kinds = setOf("picnic", "stump", "berry_tree"), done = "foraged for snacks", find = Find(0.2, listOf("minecraft:apple" to 1, "minecraft:wheat" to 2))),
        Job("fighting", kinds = setOf("training_dummy", "lifting_rock", "meditation_stone"), done = "trained hard", effect = ::train),
        Job("flying", actions = setOf(Props.Action.SIT), done = "preened on its perch", find = Find(0.2, listOf("minecraft:feather" to 2))),
    )

    private val FOSSILS: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("cobblemon", "fossils"))

    /** Called when a resident finishes a visit: does its job there if it has one. */
    fun afterVisit(level: ServerLevel, s: Sanctuary, rt: SanctuaryRuntime, r: LiveResident, kind: Props.Kind, at: BlockPos, now: Long) {
        val types = runCatching { TypeLogic.currentTypes(r.pokemon) }.getOrDefault(emptyList())
        val job = JOBS.firstOrNull { it.type in types && it.fits(kind) } ?: return
        s.stat("jobs")
        job.effect?.invoke(level, s, r, at)
        var found: ItemStack? = null
        val find = job.find
        if (find != null && "jobs" !in s.basketOff && level.random.nextDouble() < find.chance * SanctuaryConfig.jobRate.get()) {
            found = when (find.tag) {
                "berry" -> berryOf(level, at) ?: if (find.items.isEmpty()) ItemStack(Items.SWEET_BERRIES) else null
                "fossil" -> if (level.random.nextInt(25) == 0) fromTag(level, FOSSILS) else null
                else -> null
            } ?: find.items.mapNotNull { (id, n) -> item(id)?.let { ItemStack(it, 1 + level.random.nextInt(n)) } }.randomOrNull()
            if (found != null && !Gathering.add(s, found)) found = null
        }
        if (level.random.nextInt(if (found != null) 4 else 12) == 0) {
            s.logEvent("${r.name} ${job.done}" + (found?.let { " and brought ${it.hoverName.string} to the basket" } ?: "") + ".")
        }
    }

    private fun item(id: String): Item? = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)).takeIf { it != Items.AIR }

    private fun fromTag(level: ServerLevel, tag: TagKey<Item>): ItemStack? =
        BuiltInRegistries.ITEM.getTag(tag).map { it.toList() }.orElse(emptyList()).randomOrNull()?.let { ItemStack(it.value()) }

    /** The berry a Cobblemon berry tree at (or right by) [at] gives. */
    private fun berryOf(level: ServerLevel, at: BlockPos): ItemStack? {
        for (p in listOf(at, at.above(), at.below())) {
            val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).block)
            if (id.namespace == "cobblemon" && id.path.endsWith("_berry")) item("cobblemon:${id.path}")?.let { return ItemStack(it) }
            if (level.getBlockState(p).`is`(Blocks.SWEET_BERRY_BUSH)) return ItemStack(Items.SWEET_BERRIES, 2)
        }
        return null
    }

    // ------------------------------------------------------------------ what jobs change

    /** A berry tree grows a stage (so it fruits again sooner). */
    private fun growTree(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos) {
        for (p in listOf(at, at.above(), at.below())) {
            val st = level.getBlockState(p)
            val age = st.block.stateDefinition.getProperty("age") as? IntegerProperty ?: continue
            val now = st.getValue(age)
            if (now < age.possibleValues.max()) level.setBlock(p, st.setValue(age, now + 1), Block.UPDATE_ALL)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x + 0.5, p.y + 0.8, p.z + 0.5, 10, 0.5, 0.5, 0.5, 0.0)
            return
        }
    }

    private fun water(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos) {
        level.sendParticles(ParticleTypes.FALLING_WATER, at.x + 0.5, at.y + 1.6, at.z + 0.5, 20, 0.6, 0.2, 0.6, 0.0)
        if (level.random.nextInt(3) == 0) growTree(level, s, r, at)
    }

    /** Campfires burn out over time; Fire types light them again. */
    private fun relight(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos) {
        val st = level.getBlockState(at)
        if (st.block is CampfireBlock && !st.getValue(CampfireBlock.LIT)) {
            level.setBlock(at, st.setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL)
            level.sendParticles(ParticleTypes.FLAME, at.x + 0.5, at.y + 0.5, at.z + 0.5, 16, 0.3, 0.3, 0.3, 0.02)
            s.logEvent("${r.name} lit the campfire again.")
        }
    }

    /** A flower or two springs up on the grass nearby (never more than a few around one spot). */
    private fun bloom(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos) {
        var flowers = 0
        for (dx in -3..3) for (dz in -3..3) for (dy in -1..1) if (level.getBlockState(at.offset(dx, dy, dz)).`is`(BlockTags.FLOWERS)) flowers++
        if (flowers >= 8) return
        val choices = listOf(Blocks.POPPY, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.PINK_TULIP, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY)
        repeat(2) {
            val x = at.x + level.random.nextInt(7) - 3
            val z = at.z + level.random.nextInt(7) - 3
            val p = Residents.surface(level, x, z)
            if (level.getBlockState(p).isAir && level.getBlockState(p.below()).`is`(BlockTags.DIRT)) {
                level.setBlock(p, choices[level.random.nextInt(choices.size)].defaultBlockState(), Block.UPDATE_ALL)
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x + 0.5, p.y + 0.3, p.z + 0.5, 5, 0.3, 0.2, 0.3, 0.0)
            }
        }
    }

    /** Training: a little fonder of its trainer and a bit of experience. */
    private fun train(level: ServerLevel, s: Sanctuary, r: LiveResident, at: BlockPos) {
        Growth.playFightXp(s, r)
    }

    /** At dawn some campfires have burned out overnight, for the Fire types to relight. */
    fun dawn(level: ServerLevel, s: Sanctuary) {
        for ((p, kind) in s.props) {
            if (kind != "campfire") continue
            val pos = BlockPos.of(p)
            if (!level.isLoaded(pos) || level.random.nextInt(2) != 0) continue
            val st = level.getBlockState(pos)
            if (st.block is CampfireBlock && st.getValue(CampfireBlock.LIT)) level.setBlock(pos, st.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL)
        }
    }
}
