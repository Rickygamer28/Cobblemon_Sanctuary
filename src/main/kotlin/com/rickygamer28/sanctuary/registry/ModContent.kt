package com.rickygamer28.sanctuary.registry

import com.rickygamer28.sanctuary.CobblemonSanctuary
import com.rickygamer28.sanctuary.block.HubBellBlock
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

    val SANCTUARY_CORE_ITEM: DeferredItem<Item> = ITEMS.register("sanctuary_core") { ->
        net.minecraft.world.item.BlockItem(SANCTUARY_CORE.get(), Item.Properties().rarity(Rarity.RARE))
    }
    val HUB_BELL_ITEM: DeferredItem<Item> = ITEMS.register("hub_bell") { ->
        net.minecraft.world.item.BlockItem(HUB_BELL.get(), Item.Properties())
    }
    val JOURNAL: DeferredItem<JournalItem> = ITEMS.register("sanctuary_journal") { ->
        JournalItem(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
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
            }
            .build()
    }

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
        ITEMS.register(bus)
        TABS.register(bus)
    }
}
