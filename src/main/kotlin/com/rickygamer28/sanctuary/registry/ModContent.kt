package com.rickygamer28.sanctuary.registry

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.block.EggNestBlock
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
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister

object ModContent {
    private val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks(CobblemonSanctuary.MOD_ID)
    private val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(CobblemonSanctuary.MOD_ID)
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CobblemonSanctuary.MOD_ID)
    private val TABS: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CobblemonSanctuary.MOD_ID)

    val SANCTUARY_CORE: DeferredBlock<SanctuaryCoreBlock> = BLOCKS.register("sanctuary_core") { ->
        SanctuaryCoreBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(3.5f, 1200f)
                .sound(SoundType.AMETHYST).lightLevel { 10 },
        )
    }
    val HUB_BELL: DeferredBlock<HubBellBlock> = BLOCKS.register("hub_bell") { ->
        HubBellBlock(BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(2.5f).sound(SoundType.ANVIL))
    }

    val EGG_NEST: DeferredBlock<EggNestBlock> = BLOCKS.register("egg_nest") { ->
        EggNestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.4f).sound(SoundType.GRASS).noOcclusion())
    }
    val INCUBATOR: DeferredBlock<IncubatorBlock> = BLOCKS.register("incubator") { ->
        IncubatorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(2.0f).sound(SoundType.COPPER).noOcclusion()
            .lightLevel { if (it.getValue(IncubatorBlock.HAS_EGG)) 9 else 3 })
    }
    val GATHERING_BASKET: DeferredBlock<GatheringBasketBlock> = BLOCKS.register("gathering_basket") { ->
        GatheringBasketBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.8f).sound(SoundType.BAMBOO_WOOD).noOcclusion())
    }
    val INCUBATOR_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<IncubatorBlockEntity>> = BLOCK_ENTITIES.register("incubator") { ->
        BlockEntityType.Builder.of({ pos, state -> IncubatorBlockEntity(pos, state) }, INCUBATOR.get()).build(null)
    }

    val SANCTUARY_CORE_ITEM: DeferredItem<Item> = ITEMS.register("sanctuary_core") { ->
        net.minecraft.world.item.BlockItem(SANCTUARY_CORE.get(), Item.Properties().rarity(Rarity.RARE))
    }
    val HUB_BELL_ITEM: DeferredItem<Item> = ITEMS.register("hub_bell") { ->
        net.minecraft.world.item.BlockItem(HUB_BELL.get(), Item.Properties())
    }
    val JOURNAL: DeferredItem<JournalItem> = ITEMS.register("sanctuary_journal") { ->
        JournalItem(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    }
    val INCUBATOR_ITEM: DeferredItem<Item> = ITEMS.register("incubator") { ->
        net.minecraft.world.item.BlockItem(INCUBATOR.get(), Item.Properties())
    }
    val GATHERING_BASKET_ITEM: DeferredItem<Item> = ITEMS.register("gathering_basket") { ->
        net.minecraft.world.item.BlockItem(GATHERING_BASKET.get(), Item.Properties())
    }
    val POKEMON_EGG: DeferredItem<PokemonEggItem> = ITEMS.register("pokemon_egg") { ->
        PokemonEggItem(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    }
    val WHISTLE: DeferredItem<WhistleItem> = ITEMS.register("whistle") { ->
        WhistleItem(Item.Properties().stacksTo(1))
    }

    @Suppress("unused")
    val TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> = TABS.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cobblemon_sanctuary"))
            .icon { ItemStack(SANCTUARY_CORE_ITEM.get()) }
            .displayItems { _, output ->
                output.accept(SANCTUARY_CORE_ITEM.get())
                output.accept(JOURNAL.get())
                output.accept(WHISTLE.get())
                output.accept(HUB_BELL_ITEM.get())
                output.accept(INCUBATOR_ITEM.get())
                output.accept(GATHERING_BASKET_ITEM.get())
            }
            .build()
    }

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        BLOCK_ENTITIES.register(bus)
        TABS.register(bus)
    }
}
