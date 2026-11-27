package com.rickygamer28.sanctuary.registry

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.block.BedPartBlock
import com.rickygamer28.sanctuary.block.BouncyBallBlock
import com.rickygamer28.sanctuary.block.HugePokemonBedBlock
import com.rickygamer28.sanctuary.block.LargePokemonBedBlock
import com.rickygamer28.sanctuary.block.EggNestBlock
import com.rickygamer28.sanctuary.block.FeedingTroughBlock
import com.rickygamer28.sanctuary.block.PokemonBedBlock
import com.rickygamer28.sanctuary.block.GatheringBasketBlock
import com.rickygamer28.sanctuary.block.HubBellBlock
import com.rickygamer28.sanctuary.block.IncubatorBlock
import com.rickygamer28.sanctuary.block.IncubatorBlockEntity
import com.rickygamer28.sanctuary.item.PokemonEggItem
import net.minecraft.world.level.block.entity.BlockEntityType
import com.rickygamer28.sanctuary.block.SanctuaryCoreBlock
import com.rickygamer28.sanctuary.item.JournalItem
import com.rickygamer28.sanctuary.item.WhistleItem
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import com.rickygamer28.sanctuary.Platform
import com.rickygamer28.sanctuary.Reg
import net.minecraft.world.level.block.Block

object ModContent {
    /** Everything to register, by kind (the loader registers them in this order: blocks, items, block entities, menus, tabs). */
    val BLOCKS = ArrayList<Reg<out Block>>()
    val ITEMS = ArrayList<Reg<out Item>>()
    val BLOCK_ENTITIES = ArrayList<Reg<out BlockEntityType<*>>>()
    val MENUS = ArrayList<Reg<out net.minecraft.world.inventory.MenuType<*>>>()
    val TABS = ArrayList<Reg<out CreativeModeTab>>()
    private fun <T : Block> block(id: String, f: () -> T) = Reg(id, f).also { BLOCKS += it }
    private fun <T : Item> item(id: String, f: () -> T) = Reg(id, f).also { ITEMS += it }

    /** The Gathering Basket's menu (its size comes along when it opens). */
    val BASKET_MENU: Reg<net.minecraft.world.inventory.MenuType<com.rickygamer28.sanctuary.ui.BasketMenu>> =
        Reg("basket") { Platform.current.basketMenuType() }.also { MENUS += it }

    val SANCTUARY_CORE: Reg<SanctuaryCoreBlock> = block("sanctuary_core") {
        SanctuaryCoreBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(3.5f, 1200f)
                .sound(SoundType.AMETHYST).lightLevel { 10 }.noOcclusion(),
        )
    }
    val HUB_BELL: Reg<HubBellBlock> = block("hub_bell") {
        HubBellBlock(BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.5f).sound(SoundType.ANVIL).noOcclusion())
    }

    val EGG_NEST: Reg<EggNestBlock> = block("egg_nest") {
        EggNestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.4f).sound(SoundType.GRASS).noOcclusion())
    }
    val INCUBATOR: Reg<IncubatorBlock> = block("incubator") {
        IncubatorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(2.0f).sound(SoundType.COPPER).noOcclusion()
            .lightLevel { if (it.getValue(IncubatorBlock.HAS_EGG)) 9 else 3 })
    }
    val GATHERING_BASKET: Reg<GatheringBasketBlock> = block("gathering_basket") {
        GatheringBasketBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.8f).sound(SoundType.BAMBOO_WOOD).noOcclusion())
    }
    val BASKET_LINK: Reg<com.rickygamer28.sanctuary.block.BasketLinkBlock> = block("basket_link") {
        com.rickygamer28.sanctuary.block.BasketLinkBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(0.8f).sound(SoundType.BAMBOO_WOOD).noOcclusion().lightLevel { 4 })
    }
    val POKEMON_BED: Reg<PokemonBedBlock> = block("pokemon_bed") {
        PokemonBedBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.6f).sound(SoundType.WOOL).noOcclusion(), 1)
    }
    val LARGE_POKEMON_BED: Reg<LargePokemonBedBlock> = block("large_pokemon_bed") {
        LargePokemonBedBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.6f).sound(SoundType.WOOL).noOcclusion())
    }
    val HUGE_POKEMON_BED: Reg<HugePokemonBedBlock> = block("huge_pokemon_bed") {
        HugePokemonBedBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.6f).sound(SoundType.WOOL).noOcclusion())
    }
    val BED_PART: Reg<BedPartBlock> = block("bed_part") {
        BedPartBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.6f).sound(SoundType.WOOL).noOcclusion().noLootTable())
    }
    val BOUNCY_BALL: Reg<BouncyBallBlock> = block("bouncy_ball") {
        BouncyBallBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(0.3f).sound(SoundType.WOOL).noOcclusion())
    }
    val FEEDING_TROUGH: Reg<FeedingTroughBlock> = block("feeding_trough") {
        FeedingTroughBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0f).sound(SoundType.WOOD).noOcclusion())
    }
    val BASKET_LINK_ENTITY: Reg<BlockEntityType<com.rickygamer28.sanctuary.block.BasketLinkBlockEntity>> = Reg("basket_link") {
        BlockEntityType.Builder.of({ pos, state -> com.rickygamer28.sanctuary.block.BasketLinkBlockEntity(pos, state) }, BASKET_LINK.get()).build(null)
    }
    val INCUBATOR_ENTITY: Reg<BlockEntityType<IncubatorBlockEntity>> = Reg("incubator") {
        BlockEntityType.Builder.of({ pos, state -> IncubatorBlockEntity(pos, state) }, INCUBATOR.get()).build(null)
    }

    val SANCTUARY_CORE_ITEM: Reg<Item> = item("sanctuary_core") {
        net.minecraft.world.item.BlockItem(SANCTUARY_CORE.get(), Item.Properties().rarity(Rarity.RARE))
    }
    val HUB_BELL_ITEM: Reg<Item> = item("hub_bell") {
        net.minecraft.world.item.BlockItem(HUB_BELL.get(), Item.Properties())
    }
    val JOURNAL: Reg<JournalItem> = item("sanctuary_journal") {
        JournalItem(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    }
    val INCUBATOR_ITEM: Reg<Item> = item("incubator") {
        net.minecraft.world.item.BlockItem(INCUBATOR.get(), Item.Properties())
    }
    val CAMERA: Reg<Item> = item("camera") { com.rickygamer28.sanctuary.item.CameraItem(Item.Properties().stacksTo(1)) }
    val BASKET_LINK_ITEM: Reg<Item> = item("basket_link") {
        net.minecraft.world.item.BlockItem(BASKET_LINK.get(), Item.Properties())
    }
    val GATHERING_BASKET_ITEM: Reg<Item> = item("gathering_basket") {
        net.minecraft.world.item.BlockItem(GATHERING_BASKET.get(), Item.Properties())
    }
    val POKEMON_BED_ITEM: Reg<Item> = item("pokemon_bed") { net.minecraft.world.item.BlockItem(POKEMON_BED.get(), Item.Properties()) }
    val LARGE_POKEMON_BED_ITEM: Reg<Item> = item("large_pokemon_bed") { net.minecraft.world.item.BlockItem(LARGE_POKEMON_BED.get(), Item.Properties()) }
    val HUGE_POKEMON_BED_ITEM: Reg<Item> = item("huge_pokemon_bed") { net.minecraft.world.item.BlockItem(HUGE_POKEMON_BED.get(), Item.Properties()) }
    val BOUNCY_BALL_ITEM: Reg<Item> = item("bouncy_ball") { net.minecraft.world.item.BlockItem(BOUNCY_BALL.get(), Item.Properties()) }
    val FEEDING_TROUGH_ITEM: Reg<Item> = item("feeding_trough") { net.minecraft.world.item.BlockItem(FEEDING_TROUGH.get(), Item.Properties()) }
    val POKEMON_EGG: Reg<PokemonEggItem> = item("pokemon_egg") {
        PokemonEggItem(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    }
    val WHISTLE: Reg<WhistleItem> = item("whistle") {
        WhistleItem(Item.Properties().stacksTo(1))
    }

    @Suppress("unused")
    val TAB: Reg<CreativeModeTab> = Reg("main") {
        Platform.current.tabBuilder()
            .title(Component.translatable("itemGroup.cobblemon_sanctuary"))
            .icon { ItemStack(SANCTUARY_CORE_ITEM.get()) }
            .displayItems { _, output ->
                output.accept(SANCTUARY_CORE_ITEM.get())
                output.accept(JOURNAL.get())
                output.accept(WHISTLE.get())
                output.accept(HUB_BELL_ITEM.get())
                output.accept(INCUBATOR_ITEM.get())
                output.accept(GATHERING_BASKET_ITEM.get())
                output.accept(BASKET_LINK_ITEM.get())
                output.accept(CAMERA.get())
                output.accept(POKEMON_BED_ITEM.get())
                output.accept(LARGE_POKEMON_BED_ITEM.get())
                output.accept(HUGE_POKEMON_BED_ITEM.get())
                output.accept(BOUNCY_BALL_ITEM.get())
                output.accept(FEEDING_TROUGH_ITEM.get())
            }
            .build()
    }

    init {
        BLOCK_ENTITIES += BASKET_LINK_ENTITY
        BLOCK_ENTITIES += INCUBATOR_ENTITY
        TABS += TAB
    }
}
