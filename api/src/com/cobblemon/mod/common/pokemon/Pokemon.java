package com.cobblemon.mod.common.pokemon

import com.bedrockk.molang.runtime.struct.QueryStruct
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonMovesetBuilders
import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.CobblemonSounds
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.abilities.Ability
import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.abilities.PotentialAbility
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.data.ShowdownIdentifiable
import com.cobblemon.mod.common.api.entity.PokemonSender
import com.cobblemon.mod.common.api.events.Cancelable
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.FriendshipUpdatedEvent
import com.cobblemon.mod.common.api.events.pokemon.FullnessUpdatedEvent
import com.cobblemon.mod.common.api.events.pokemon.LevelUpEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonAspectsChangedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonFaintedEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonGainedEvent
import com.cobblemon.mod.common.api.events.pokemon.HyperTrainedIvEvent.Post
import com.cobblemon.mod.common.api.events.pokemon.HyperTrainedIvEvent.Pre
import com.cobblemon.mod.common.api.events.pokemon.healing.PokemonHealedEvent
import com.cobblemon.mod.common.api.mark.Mark
import com.cobblemon.mod.common.api.mark.Marks
import com.cobblemon.mod.common.api.moves.BenchedMove
import com.cobblemon.mod.common.api.moves.BenchedMoves
import com.cobblemon.mod.common.api.moves.Move
import com.cobblemon.mod.common.api.moves.MoveSet
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.moves.MovesetBuilder
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonPropertyExtractor
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.aspect.AspectProvider
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.api.pokemon.evolution.EvolutionDisplay
import com.cobblemon.mod.common.api.pokemon.evolution.EvolutionProxy
import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution
import com.cobblemon.mod.common.api.pokemon.evolution.progress.EvolutionProgress
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroup
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceSource
import com.cobblemon.mod.common.api.pokemon.feature.IntSpeciesFeature
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeature
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatureProvider
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatures
import com.cobblemon.mod.common.api.pokemon.feature.SynchronizedSpeciesFeature
import com.cobblemon.mod.common.api.pokemon.feature.SynchronizedSpeciesFeatureProvider
import com.cobblemon.mod.common.api.pokemon.friendship.FriendshipMutationCalculator
import com.cobblemon.mod.common.api.pokemon.moves.LearnsetQuery
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.properties.CustomPokemonProperty
import com.cobblemon.mod.common.api.reactive.CancelableObservable
import com.cobblemon.mod.common.api.reactive.EventObservable
import com.cobblemon.mod.common.api.reactive.SettableObservable
import com.cobblemon.mod.common.api.riding.RidingProperties
import com.cobblemon.mod.common.api.riding.RidingStyle
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings
import com.cobblemon.mod.common.api.riding.stats.RidingStat
import com.cobblemon.mod.common.api.scheduling.SchedulingFunctionsKt
import com.cobblemon.mod.common.api.storage.PokemonStore
import com.cobblemon.mod.common.api.storage.StoreCoordinates
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.api.storage.pc.PCStore
import com.cobblemon.mod.common.api.tms.TMMoveManager
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.api.types.tera.TeraType
import com.cobblemon.mod.common.battles.ActiveBattlePokemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.datafixer.CobblemonSchemas
import com.cobblemon.mod.common.datafixer.CobblemonTypeReferences
import com.cobblemon.mod.common.entity.PlatformType
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.entity.pokemon.effects.IllusionEffect
import com.cobblemon.mod.common.net.messages.client.PokemonUpdatePacket
import com.cobblemon.mod.common.net.messages.client.effect.SpawnSnowstormEntityParticlePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.AbilityUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.ActiveMarkUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.AlphaUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.AspectsUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.BenchedMovesUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.CaughtBallUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.CosmeticItemUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.DmaxLevelUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.EVsUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.ExperienceUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.FormUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.FriendshipUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.FullnessUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.GenderUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.GmaxFactorUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.HealthUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.HeldItemUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.IVsUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarkAddUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarkPotentialAddUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarkRemoveUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarkingsUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarksPotentialUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MarksUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.MoveSetUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.NatureUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.NicknameUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.OriginalTrainerUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.PokemonStateUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.RideBoostsUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.RideStaminaUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.ScaleModifierUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.ShinyUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.SpeciesFeatureUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.SpeciesUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.StatusUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.TeraTypeUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.TetheringUpdatePacket
import com.cobblemon.mod.common.net.messages.client.pokemon.update.TradeableUpdatePacket
import com.cobblemon.mod.common.net.messages.client.ui.ExpGainedDataPacket
import com.cobblemon.mod.common.pokeball.PokeBall
import com.cobblemon.mod.common.pokemon.Pokemon.Companion
import com.cobblemon.mod.common.pokemon.Pokemon.WhenMappings
import com.cobblemon.mod.common.pokemon.Pokemon.special..inlined.sortedBy.1
import com.cobblemon.mod.common.pokemon.activestate.ActivePokemonState
import com.cobblemon.mod.common.pokemon.activestate.InactivePokemonState
import com.cobblemon.mod.common.pokemon.activestate.PokemonState
import com.cobblemon.mod.common.pokemon.activestate.SentOutState
import com.cobblemon.mod.common.pokemon.activestate.ShoulderedState
import com.cobblemon.mod.common.pokemon.evolution.CobblemonEvolutionProxy
import com.cobblemon.mod.common.pokemon.evolution.controller.ClientEvolutionController.Intermediate
import com.cobblemon.mod.common.pokemon.evolution.progress.DamageTakenEvolutionProgress
import com.cobblemon.mod.common.pokemon.evolution.progress.RecoilEvolutionProgress
import com.cobblemon.mod.common.pokemon.feature.SeasonFeatureHandler
import com.cobblemon.mod.common.pokemon.feature.StashHandler
import com.cobblemon.mod.common.pokemon.properties.BattleCloneProperty
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty
import com.cobblemon.mod.common.pokemon.requirements.BlocksTraveledRequirement
import com.cobblemon.mod.common.pokemon.status.PersistentStatus
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer
import com.cobblemon.mod.common.util.DistributionUtilsKt
import com.cobblemon.mod.common.util.EntityExtensionsKt
import com.cobblemon.mod.common.util.MiscUtilsKt
import com.cobblemon.mod.common.util.ResourceLocationExtensionsKt
import com.cobblemon.mod.common.util.Vec3ExtensionsKt
import com.cobblemon.mod.common.util.WorldExtensionsKt
import com.cobblemon.mod.common.util.codec.internal.ClientPokemonP1
import com.cobblemon.mod.common.util.codec.internal.ClientPokemonP2
import com.cobblemon.mod.common.util.codec.internal.ClientPokemonP3
import com.cobblemon.mod.common.util.codec.internal.ClientPokemonP4
import com.cobblemon.mod.common.util.codec.internal.PokemonP1
import com.cobblemon.mod.common.util.codec.internal.PokemonP2
import com.cobblemon.mod.common.util.codec.internal.PokemonP3
import com.cobblemon.mod.common.util.codec.internal.PokemonP4
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import com.mojang.datafixers.kinds.App
import com.mojang.datafixers.kinds.Applicative
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import com.mojang.serialization.codecs.RecordCodecBuilder
import com.mojang.serialization.codecs.RecordCodecBuilder.Instance
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.NoSuchElementException
import java.util.Optional
import java.util.UUID
import java.util.Map.Entry
import java.util.concurrent.CompletableFuture
import kotlin.jvm.functions.Function0
import kotlin.jvm.functions.Function1
import kotlin.jvm.internal.SourceDebugExtension
import kotlin.math.MathKt
import kotlin.random.Random
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.core.RegistryAccess.Frozen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.GameProfileCache
import net.minecraft.server.players.PlayerList
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.WitherRoseBlock
import net.minecraft.world.phys.Vec3

@SourceDebugExtension(["SMAP\nPokemon.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Pokemon.kt\ncom/cobblemon/mod/common/pokemon/Pokemon\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n+ 4 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/CancelableObservable\n+ 5 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/EventObservable\n+ 6 _Arrays.kt\nkotlin/collections/ArraysKt___ArraysKt\n+ 7 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/CancelableObservable$postThen$1\n+ 8 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/EventObservable$post$1\n+ 9 Maps.kt\nkotlin/collections/MapsKt__MapsKt\n+ 10 ArraysJVM.kt\nkotlin/collections/ArraysKt__ArraysJVMKt\n+ 11 _Maps.kt\nkotlin/collections/MapsKt___MapsKt\n*L\n1#1,2435:1\n1#2:2436\n1#2:2447\n1#2:2502\n1617#3,9:2437\n1869#3:2446\n1870#3:2448\n1626#3:2449\n774#3:2477\n865#3,2:2478\n1869#3,2:2480\n1617#3,9:2492\n1869#3:2501\n1870#3:2503\n1626#3:2504\n1869#3,2:2505\n1626#3:2507\n1056#3:2508\n774#3:2509\n865#3,2:2510\n1761#3,3:2562\n1761#3,3:2566\n827#3:2602\n855#3,2:2603\n1491#3:2605\n1516#3,3:2606\n1519#3,3:2616\n1563#3:2619\n1634#3,3:2620\n774#3:2623\n865#3:2624\n2746#3,3:2625\n866#3:2628\n1374#3:2629\n1460#3,5:2630\n1761#3,3:2644\n295#3,2:2647\n2746#3,3:2649\n1252#3,4:2654\n1374#3:2658\n1460#3,5:2659\n808#3,11:2664\n774#3:2697\n865#3:2698\n2746#3,3:2699\n866#3:2702\n1869#3,2:2703\n1869#3:2710\n2746#3,3:2711\n295#3,2:2714\n1870#3:2716\n1869#3:2717\n1870#3:2727\n295#3,2:2728\n230#3,2:2730\n1869#3,2:2750\n1869#3,2:2754\n40#4,2:2450\n42#4,2:2455\n45#4:2458\n47#4:2468\n48#4:2471\n40#4,2:2512\n42#4,2:2517\n45#4,3:2520\n48#4:2525\n40#4,2:2526\n42#4,2:2531\n45#4:2534\n47#4:2544\n48#4:2547\n40#4,2:2548\n42#4,2:2553\n45#4,3:2556\n48#4:2561\n41#4:2679\n42#4,6:2683\n48#4:2691\n18#5,2:2452\n15#5,5:2459\n20#5:2467\n20#5:2470\n18#5,2:2472\n20#5:2476\n18#5,2:2482\n20#5:2486\n18#5,2:2487\n20#5:2491\n18#5,2:2514\n20#5:2524\n18#5,2:2528\n15#5,5:2535\n20#5:2543\n20#5:2546\n18#5,2:2550\n20#5:2560\n15#5,5:2570\n20#5:2578\n18#5,2:2579\n20#5:2583\n15#5,5:2584\n20#5:2592\n15#5,5:2593\n20#5:2601\n15#5,5:2635\n20#5:2643\n18#5,2:2680\n20#5:2690\n18#5,2:2692\n20#5:2696\n18#5,2:2705\n20#5:2709\n15#5,5:2718\n20#5:2726\n15#5,5:2732\n20#5:2740\n15#5,5:2741\n20#5:2749\n13805#6:2454\n13805#6:2464\n13806#6:2466\n13806#6:2469\n13805#6,2:2474\n13805#6,2:2484\n13805#6,2:2489\n13805#6:2516\n13806#6:2523\n13805#6:2530\n13805#6:2540\n13806#6:2542\n13806#6:2545\n13805#6:2552\n13806#6:2559\n12767#6:2565\n12768#6:2569\n13805#6:2575\n13806#6:2577\n13805#6,2:2581\n13805#6:2589\n13806#6:2591\n13805#6:2598\n13806#6:2600\n13805#6:2640\n13806#6:2642\n13805#6,2:2675\n13805#6:2682\n13806#6:2689\n13805#6,2:2694\n13805#6,2:2707\n13805#6:2723\n13806#6:2725\n13805#6:2737\n13806#6:2739\n13805#6:2746\n13806#6:2748\n40#7:2457\n40#7:2519\n40#7:2533\n40#7:2555\n15#8:2465\n15#8:2541\n15#8:2576\n15#8:2590\n15#8:2599\n15#8:2641\n15#8:2724\n15#8:2738\n15#8:2747\n382#9,7:2609\n463#9:2652\n413#9:2653\n37#10,2:2677\n216#11,2:2752\n*S KotlinDebug\n*F\n+ 1 Pokemon.kt\ncom/cobblemon/mod/common/pokemon/Pokemon\n*L\n192#1:2447\n474#1:2502\n192#1:2437,9\n192#1:2446\n192#1:2448\n192#1:2449\n358#1:2477\n358#1:2478,2\n359#1:2480,2\n474#1:2492,9\n474#1:2501\n474#1:2503\n474#1:2504\n474#1:2505,2\n474#1:2507\n727#1:2508\n729#1:2509\n729#1:2510,2\n1026#1:2562,3\n1244#1:2566,3\n1391#1:2602\n1391#1:2603,2\n1395#1:2605\n1395#1:2606,3\n1395#1:2616,3\n1688#1:2619\n1688#1:2620,3\n1691#1:2623\n1691#1:2624\n1691#1:2625,3\n1691#1:2628\n1700#1:2629\n1700#1:2630,5\n1742#1:2644,3\n1850#1:2647,2\n1874#1:2649,3\n2067#1:2654,4\n2083#1:2658\n2083#1:2659,5\n2084#1:2664,11\n2175#1:2697\n2175#1:2698\n2175#1:2699,3\n2175#1:2702\n2176#1:2703,2\n2349#1:2710\n2350#1:2711,3\n2351#1:2714,2\n2349#1:2716\n778#1:2717\n778#1:2727\n848#1:2728,2\n853#1:2730,2\n1482#1:2750,2\n1490#1:2754,2\n286#1:2450,2\n286#1:2455,2\n286#1:2458\n286#1:2468\n286#1:2471\n800#1:2512,2\n800#1:2517,2\n800#1:2520,3\n800#1:2525\n978#1:2526,2\n978#1:2531,2\n978#1:2534\n978#1:2544\n978#1:2547\n995#1:2548,2\n995#1:2553,2\n995#1:2556,3\n995#1:2561\n2155#1:2679\n2155#1:2683,6\n2155#1:2691\n286#1:2452,2\n293#1:2459,5\n293#1:2467\n286#1:2470\n353#1:2472,2\n353#1:2476\n401#1:2482,2\n401#1:2486\n414#1:2487,2\n414#1:2491\n800#1:2514,2\n800#1:2524\n978#1:2528,2\n982#1:2535,5\n982#1:2543\n978#1:2546\n995#1:2550,2\n995#1:2560\n1294#1:2570,5\n1294#1:2578\n1304#1:2579,2\n1304#1:2583\n1336#1:2584,5\n1336#1:2592\n1344#1:2593,5\n1344#1:2601\n1703#1:2635,5\n1703#1:2643\n2155#1:2680,2\n2155#1:2690\n2167#1:2692,2\n2167#1:2696\n2182#1:2705,2\n2182#1:2709\n779#1:2718,5\n779#1:2726\n909#1:2732,5\n909#1:2740\n966#1:2741,5\n966#1:2749\n286#1:2454\n293#1:2464\n293#1:2466\n286#1:2469\n353#1:2474,2\n401#1:2484,2\n414#1:2489,2\n800#1:2516\n800#1:2523\n978#1:2530\n982#1:2540\n982#1:2542\n978#1:2545\n995#1:2552\n995#1:2559\n1244#1:2565\n1244#1:2569\n1294#1:2575\n1294#1:2577\n1304#1:2581,2\n1336#1:2589\n1336#1:2591\n1344#1:2598\n1344#1:2600\n1703#1:2640\n1703#1:2642\n2131#1:2675,2\n2155#1:2682\n2155#1:2689\n2167#1:2694,2\n2182#1:2707,2\n779#1:2723\n779#1:2725\n909#1:2737\n909#1:2739\n966#1:2746\n966#1:2748\n286#1:2457\n800#1:2519\n978#1:2533\n995#1:2555\n293#1:2465\n982#1:2541\n1294#1:2576\n1336#1:2590\n1344#1:2599\n1703#1:2641\n779#1:2724\n909#1:2738\n966#1:2747\n1395#1:2609,7\n2067#1:2652\n2067#1:2653\n2141#1:2677,2\n1485#1:2752,2\n*E\n"])
open class Pokemon : ShowdownIdentifiable {
   @JvmStatic
   public Companion Companion = new Companion(null);
   @JvmStatic
   private FriendshipMutationCalculator LEVEL_UP_FRIENDSHIP_CALCULATOR = FriendshipMutationCalculator.Companion.getSWORD_AND_SHIELD_LEVEL_UP();
   @JvmStatic
   private ResourceLocation SHEDINJA = MiscUtilsKt.cobblemonResource("shedinja");
   @JvmStatic
   private Codec<Pokemon> ROOT_CODEC;
   @JvmStatic
   private Codec<Pokemon> CODEC;
   @JvmStatic
   private Codec<Pokemon> CLIENT_CODEC;
   @JvmStatic
   private StreamCodec<RegistryFriendlyByteBuf, Pokemon> S2C_CODEC;

   public final var uuid: UUID = UUID.randomUUID()
   public final var species: Species = PokemonSpecies.random()
      public final set(value) {
         if (PokemonSpecies.getByIdentifier(value.getResourceIdentifier()) == null) {
            throw new IllegalArgumentException("Cannot set a species that isn't registered");
         } else {
            var quotient: Float = Mth.clamp((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
            var oldValue: Species = this.species;
            this.species = value;
            if (!this.isClient) {
               var `$this$mapNotNull$iv`: java.lang.Iterable = SpeciesFeatures.getFeaturesFor(this.species);
               var `destination$iv$iv`: java.util.Collection = new ArrayList();

               for (Object element$iv$iv$iv : $this$mapNotNull$iv) {
                  var var10000: SpeciesFeature = (`element$iv$iv$iv` as SpeciesFeatureProvider).invoke(this);
                  if (var10000 != null) {
                     `destination$iv$iv`.add(var10000);
                  }
               }

               var newFeatures: java.util.List = `destination$iv$iv` as java.util.List;
               this.features.clear();
               this.features.addAll(newFeatures);
               if (!(oldValue == value)) {
                  this.evolutionProxy.current().clear();
               }
            }

            this.updateAspects();
            this.updateForm();
            this.checkGender();
            this.updateHP(quotient);
            this.attemptAbilityUpdate();
            this.onChange((new SpeciesUpdatePacket(Pokemon::_set_species_$lambda$1, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final var form: FormData = this.species.getStandardForm()
      public final set(value) {
         if (!(this.form == value)) {
            var quotient: Float = Mth.clamp((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
            var oldForm: FormData = this.form;
            this.form = value;
            this.updateMovesOnFormChange(oldForm);
            this.evolutionProxy.current().clear();
            this.checkGender();
            this.updateHP(quotient);
            this.attemptAbilityUpdate();
            this.onChange((new FormUpdatePacket(Pokemon::_set_form_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final var ivs: IVs
      public final set(value) {
         var oldChangeFunction: Function1 = this.ivs.getChangeFunction();
         this.ivs.setChangeFunction(Pokemon::_set_ivs_$lambda$0);
         this.ivs = value;
         value.setChangeFunction(oldChangeFunction);
         var var10001: com.cobblemon.mod.common.pokemon.Characteristic.Companion = Characteristic.Companion;
         var var10003: UUID = this.uuid;
         this.characteristic = var10001.calculate(value, var10003);
      }

   public final var evs: EVs
      public final set(value) {
         var oldChangeFunction: Function1 = this.evs.getChangeFunction();
         this.evs.setChangeFunction(Pokemon::_set_evs_$lambda$0);
         this.evs = value;
         value.setChangeFunction(oldChangeFunction);
      }

   public final var characteristic: Characteristic
      private set
   public final var isAlpha: Boolean
      public final set(value) {
         if (this.isAlpha != value) {
            this.isAlpha = value;
            var var10000: Mark = Marks.getByIdentifier(MiscUtilsKt.cobblemonResource("mark_alpha"));
            if (value) {
               this.setActiveMark(var10000);
            } else {
               this.setScaleModifier(1.0F);
            }

            this.exchangeMark(var10000, value);
            this.updateAspects();
            this.onChange((new AlphaUpdatePacket(Pokemon::_set_isAlpha_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final var nickname: MutableComponent?
      public final set(value) {
         if (!(this.nickname == value)) {
            this.nickname = value;
            this.onChange((new NicknameUpdatePacket(Pokemon::_set_nickname_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final var level: Int
      public final set(value) {
         var boundedValue: Int = Mth.clamp(value, 1, Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel());
         var hpRatio: Float = RangesKt.coerceIn((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
         this.level = boundedValue;
         if (this.getExperienceGroup().getLevel(this.experience) != boundedValue || value == Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel()) {
            this.setExperience$common(this.getExperienceGroup().getExperience(boundedValue));
         }

         this.setCurrentHealth(RangesKt.coerceIn(Mth.ceil(hpRatio * (float)this.getMaxHealth()), (new IntRange(0, this.getMaxHealth())) as ClosedRange));
      }

   public final var currentHealth: Int
      public final set(value) {
         if (value != this.currentHealth) {
            if (value <= 0) {
               var var10000: PokemonEntity = this.getEntity();
               if (var10000 != null) {
                  var10000.setHealth(0.0F);
               }

               this.setStatus(null);
            }

            this.currentHealth = Math.max(Math.min(this.getMaxHealth(), value), 0);
            this.onChange((new HealthUpdatePacket(Pokemon::_set_currentHealth_$lambda$0, this.currentHealth)) as PokemonUpdatePacket<?>);
            if (this.isFainted()) {
               decrementFriendship$default(this, 1, false, 2, null);
               var faintTime: Int = Cobblemon.INSTANCE.getConfig().getDefaultFaintTimer();
               var `$this$forEach$iv`: EventObservable = CobblemonEvents.POKEMON_FAINTED;
               var `$i$f$forEach`: Array<PokemonFaintedEvent> = new PokemonFaintedEvent[]{new PokemonFaintedEvent(this, faintTime)};
               `$this$forEach$iv`.emit(Arrays.copyOf(`$i$f$forEach`, `$i$f$forEach`.length));

               for (Object element$iv$iv : $i$f$forEach) {
                  this.setFaintedTimer(it.getFaintedTimer());
               }

               var var13: java.lang.Iterable = this.evolutionProxy.current().progress();
               var var18: java.util.Collection = new ArrayList();

               for (Object element$iv$iv : var13) {
                  if (var24 as EvolutionProgress is RecoilEvolutionProgress || var24 as EvolutionProgress is DamageTakenEvolutionProgress) {
                     var18.add(var24);
                  }
               }

               for (Object element$iv : var14) {
                  (var19 as EvolutionProgress).reset();
               }
            }

            this.setHealTimer(Cobblemon.INSTANCE.getConfig().getHealTimer());
         }
      }

   public final var gender: Gender
      public final set(value) {
         if (this.isClient || this.species.getPossibleGenders().contains(value)) {
            this.gender = value;
            this.updateAspects();
            this.onChange((new GenderUpdatePacket(Pokemon::_set_gender_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final var status: PersistentStatusContainer?
      public final set(value) {
         this.status = value;
         this.onChange((new StatusUpdatePacket(Pokemon::_set_status_$lambda$0, if (value != null) value.getStatus() else null)) as PokemonUpdatePacket<?>);
      }

   public final var experience: Int
      public final set(value) {
         this.experience = value;
         if (this.level == Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel()) {
            this.experience = this.getExperienceGroup().getExperience(this.level);
         }

         this.onChange((new ExperienceUpdatePacket(Pokemon::_set_experience_$lambda$0, this.experience)) as PokemonUpdatePacket<?>);
      }

   public final var friendship: Int
      public final set(value) {
         var earned: Boolean = this.friendshipEarned;
         this.friendshipEarned = false;
         if (this.isPossibleFriendship(value)) {
            var `this_$iv`: EventObservable = CobblemonEvents.FRIENDSHIP_UPDATED;
            var `events$iv`: Array<FriendshipUpdatedEvent> = new FriendshipUpdatedEvent[]{new FriendshipUpdatedEvent(this, value, earned)};
            `this_$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

            for (Object element$iv$iv : events$iv) {
               this.friendship = `element$iv$iv`.getNewFriendship();
               this.onChange((new FriendshipUpdatePacket(Pokemon::_set_friendship_$lambda$0$0, `element$iv$iv`.getNewFriendship())) as PokemonUpdatePacket<?>);
            }
         }
      }

   private final var friendshipEarned: Boolean
   public final var currentFullness: Int
      public final set(value) {
         var clamped: Int = RangesKt.coerceIn(value, 0, this.getMaxFullness());
         if (this.currentFullness != clamped) {
            var `this_$iv`: EventObservable = CobblemonEvents.FULLNESS_UPDATED;
            var `events$iv`: Array<FullnessUpdatedEvent> = new FullnessUpdatedEvent[]{new FullnessUpdatedEvent(this, clamped)};
            `this_$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

            for (Object element$iv$iv : events$iv) {
               this.currentFullness = `element$iv$iv`.getNewFullness();
               this.onChange((new FullnessUpdatePacket(Pokemon::_set_currentFullness_$lambda$0$0, `element$iv$iv`.getNewFullness())) as PokemonUpdatePacket<?>);
            }
         }
      }

   public final var rideStamina: Float
      public final set(value) {
         var newValue: Float = RangesKt.coerceIn(value, 0.0F, 1.0F);
         if (newValue != this.rideStamina) {
            this.rideStamina = newValue;
            this.onChange((new RideStaminaUpdatePacket(Pokemon::_set_rideStamina_$lambda$0, newValue)) as PokemonUpdatePacket<?>);
         }
      }

   public final var interactionCooldowns: MutableMap<ResourceLocation, Int>
   public final var state: PokemonState
      public final set(value) {
         if (!(this.state == value)) {
            this.state = value;
            this.onChange((new PokemonStateUpdatePacket(Pokemon::_set_state_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final val entity: PokemonEntity?
      public final get() {
         return if (this.state is ActivePokemonState) (this.state as ActivePokemonState).getEntity() else null;
      }

   public final val primaryType: ElementalType
      public final get() {
         return this.form.getPrimaryType();
      }

   public final val secondaryType: ElementalType?
      public final get() {
         return this.form.getSecondaryType();
      }

   public final val types: Iterable<ElementalType>
      public final get() {
         return this.form.getTypes();
      }

   public final var teraType: TeraType
      public final set(value) {
         this.teraType = value;
         this.onChange((new TeraTypeUpdatePacket(Pokemon::_set_teraType_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var dmaxLevel: Int
      public final set(value) {
         this.dmaxLevel = RangesKt.coerceIn(value, 0, Cobblemon.INSTANCE.getConfig().getMaxDynamaxLevel());
         this.onChange((new DmaxLevelUpdatePacket(Pokemon::_set_dmaxLevel_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var gmaxFactor: Boolean
      public final set(value) {
         var `$this$mapNotNull$iv`: java.lang.Iterable = this.species.getStandardForm().getEvolutions();
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv$iv : $this$mapNotNull$iv) {
            var var10000: java.lang.String = (`element$iv$iv$iv` as Evolution).getResult().getSpecies();
            if (var10000 != null) {
               `destination$iv$iv`.add(var10000);
            }
         }

         `$this$mapNotNull$iv` = `destination$iv$iv` as java.util.List;
         `destination$iv$iv` = new ArrayList();

         for (Object element$iv$iv$ivx : $this$mapNotNull$iv) {
            var var33: Species = PokemonSpecies.getByIdentifier(
               ResourceLocationExtensionsKt.asIdentifierDefaultingNamespace$default(`element$iv$iv$ivx` as java.lang.String, null, 1, null)
            );
            if (var33 != null) {
               `destination$iv$iv`.add(var33);
            }
         }

         var evolutions: java.util.List = `destination$iv$iv` as java.util.List;
         if (!this.species.canGmax()) {
            var `$this$mapNotNullTo$iv$iv`: java.util.Iterator = evolutions.iterator();

            var var34: Any;
            while (true) {
               if (!`$this$mapNotNullTo$iv$iv`.hasNext()) {
                  var34 = null;
                  break;
               }

               var var22: Any = `$this$mapNotNullTo$iv$iv`.next();
               if ((var22 as Species).canGmax()) {
                  var34 = var22;
                  break;
               }
            }

            if (var34 == null) {
               return;
            }
         }

         this.gmaxFactor = value;
         this.onChange((new GmaxFactorUpdatePacket(Pokemon::_set_gmaxFactor_$lambda$3, value)) as PokemonUpdatePacket<?>);
      }

   public final var shiny: Boolean
      public final set(value) {
         this.shiny = value;
         this.updateAspects();
         this.onChange((new ShinyUpdatePacket(Pokemon::_set_shiny_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var tradeable: Boolean
      public final set(value) {
         this.tradeable = value;
         this.onChange((new TradeableUpdatePacket(Pokemon::_set_tradeable_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var nature: Nature
      public final set(value) {
         this.nature = value;
         this.onChange((new NatureUpdatePacket(Pokemon::_set_nature_$lambda$0, value, false)) as PokemonUpdatePacket<?>);
      }

   public final var mintedNature: Nature?
      public final set(value) {
         this.mintedNature = value;
         this.onChange((new NatureUpdatePacket(Pokemon::_set_mintedNature_$lambda$0, value, true)) as PokemonUpdatePacket<?>);
      }

   public final val effectiveNature: Nature
      public final get() {
         var var10000: Nature = this.mintedNature;
         if (this.mintedNature == null) {
            var10000 = this.nature;
         }

         return var10000;
      }

   public final val moveSet: MoveSet
   public final val experienceGroup: ExperienceGroup
      public final get() {
         return this.form.getExperienceGroup();
      }

   public final var faintedTimer: Int
      public final set(value) {
         this.faintedTimer = value;
         onChange$default(this, null, 1, null);
      }

   public final var healTimer: Int
      public final set(value) {
         this.healTimer = value;
         onChange$default(this, null, 1, null);
      }

   public final var tetheringId: UUID?
      public final set(value) {
         this.tetheringId = value;
         this.onChange((new TetheringUpdatePacket(Pokemon::_set_tetheringId_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var originalTrainerType: OriginalTrainerType
      public final set(value) {
         this.originalTrainerType = `<set-?>`;
      }

   public final var originalTrainer: String?
      public final set(value) {
         this.originalTrainer = `<set-?>`;
      }

   public final var originalTrainerName: String?
      public final set(value) {
         this.originalTrainerName = value;
         this.onChange((new OriginalTrainerUpdatePacket(Pokemon::_set_originalTrainerName_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var originalTrainerFriendship: Int?
      public final set(value) {
         this.originalTrainerFriendship = `<set-?>`;
      }

   public final var benchedMoves: BenchedMoves
      public final set(value) {
         var oldChangeFunction: Function1 = this.benchedMoves.getChangeFunction();
         this.benchedMoves.setChangeFunction(Pokemon::_set_benchedMoves_$lambda$0);
         this.benchedMoves = value;
         value.setChangeFunction(oldChangeFunction);
      }

   public final var ability: Ability
      public final set(value) {
         if (!(this.ability == value)) {
            this.onChange((new AbilityUpdatePacket(Pokemon::_set_ability_$lambda$0, value.getTemplate())) as PokemonUpdatePacket<?>);
         }

         this.ability = value;
      }

   public final val maxHealth: Int
      public final get() {
         return this.getStat(Stats.HP as Stat);
      }

   public final val hp: Int
      public final get() {
         return this.getMaxHealth();
      }

   public final val attack: Int
      public final get() {
         return this.getStat(Stats.ATTACK as Stat);
      }

   public final val defence: Int
      public final get() {
         return this.getStat(Stats.DEFENCE as Stat);
      }

   public final val specialAttack: Int
      public final get() {
         return this.getStat(Stats.SPECIAL_ATTACK as Stat);
      }

   public final val specialDefence: Int
      public final get() {
         return this.getStat(Stats.SPECIAL_DEFENCE as Stat);
      }

   public final val speed: Int
      public final get() {
         return this.getStat(Stats.SPEED as Stat);
      }

   public final var scaleModifier: Float
      public final set(value) {
         if (this.scaleModifier != value) {
            this.scaleModifier = RangesKt.coerceAtLeast(value, 0.05F);
            this.onChange((new ScaleModifierUpdatePacket(Pokemon::_set_scaleModifier_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   public final val effectiveScale: Float
      public final get() {
         var var10000: Float;
         if (this.level <= 1) {
            var10000 = Cobblemon.INSTANCE.getConfig().getBabyPokemonSizeMultiplier();
         } else if (Cobblemon.INSTANCE.getConfig().getBabyPokemonLevelDuration() > 1
            && this.level < Cobblemon.INSTANCE.getConfig().getBabyPokemonLevelDuration()) {
            var minMultiplier: Float = Cobblemon.INSTANCE.getConfig().getBabyPokemonSizeMultiplier();
            var10000 = minMultiplier
               + (1.0F - minMultiplier) * ((float)(this.level - 1) / (float)(Cobblemon.INSTANCE.getConfig().getBabyPokemonLevelDuration() - 1));
         } else {
            var10000 = 1.0F;
         }

         return if (this.isAlpha) this.getAlphaScaleMultiplier() else var10000 * this.scaleModifier;
      }

   public final var caughtBall: PokeBall
      public final set(value) {
         this.caughtBall = value;
         this.onChange((new CaughtBallUpdatePacket(Pokemon::_set_caughtBall_$lambda$0, this.caughtBall)) as PokemonUpdatePacket<?>);
      }

   public final var features: MutableList<SpeciesFeature>
   public final var cosmeticItem: ItemStack
      public final set(value) {
         this.cosmeticItem = value;
         this.updateAspects();
         var var10003: Function0 = Pokemon::_set_cosmeticItem_$lambda$0;
         this.onChange((new CosmeticItemUpdatePacket(var10003, value)) as PokemonUpdatePacket<?>);
      }

   public final var activeMark: Mark?
      public final set(value) {
         this.activeMark = value;
         this.updateAspects();
         this.onChange((new ActiveMarkUpdatePacket(Pokemon::_set_activeMark_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var marks: MutableSet<Mark>
      public final set(value) {
         this.marks = value;
         this.onChange((new MarksUpdatePacket(Pokemon::_set_marks_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var potentialMarks: MutableSet<Mark>
      public final set(value) {
         this.potentialMarks = value;
         this.onChange((new MarksPotentialUpdatePacket(Pokemon::_set_potentialMarks_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var markings: List<Int>
      public final set(value) {
         this.markings = value;
         this.onChange((new MarkingsUpdatePacket(Pokemon::_set_markings_$lambda$0, value)) as PokemonUpdatePacket<?>);
      }

   public final var lastFlowerFed: ItemStack
   public final var forcedAspects: Set<String>
      public final set(value) {
         this.forcedAspects = value;
         this.updateAspects();
      }

   public final var aspects: Set<String>
      public final set(value) {
         if (!(this.aspects == value)) {
            this.aspects = value;
            if (!this.isClient) {
               this.updateForm();
            }

            this.onChange((new AspectsUpdatePacket(Pokemon::_set_aspects_$lambda$0, value)) as PokemonUpdatePacket<?>);
         }
      }

   internal final var isClient: Boolean
   public final val storeCoordinates: SettableObservable<StoreCoordinates<*>?>
   public final val evolutions: Iterable<Evolution>
      public final get() {
         return CollectionsKt.sortedWith(this.form.getEvolutions(), (new 1()) as Comparator);
      }

   public final val lockedEvolutions: Iterable<Evolution>
      public final get() {
         var `$this$filter$iv`: java.lang.Iterable = this.getEvolutions();
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filter$iv) {
            if (!CollectionsKt.contains(this.evolutionProxy.current() as java.lang.Iterable, `element$iv$iv` as Evolution)) {
               `destination$iv$iv`.add(`element$iv$iv`);
            }
         }

         return `destination$iv$iv`;
      }

   public final val preEvolution: PreEvolution?
      public final get() {
         return this.form.getPreEvolution();
      }

   private final val rideBoosts: MutableMap<RidingStat, Float>
   public final var evolutionProxy: EvolutionProxy<
      EvolutionDisplay,
      Evolution,
      Intermediate,
      com.cobblemon.mod.common.pokemon.evolution.controller.ServerEvolutionController.Intermediate
   >
      private set
   public final val customProperties: MutableList<CustomPokemonProperty>
   public final var persistentData: CompoundTag
      public final set(value) {
         this.persistentData = `<set-?>`;
      }

   internal final var heldItem: ItemStack
   public final var heldItemVisible: Boolean
      public final set(value) {
         this.heldItemVisible = value;
         onChange$default(this, null, 1, null);
      }

   internal final var canDropHeldItem: Boolean
      internal final get() {
         return this.canDropHeldItem || this.heldItem.isEmpty();
      }

   public final val riding: RidingProperties
      public final get() {
         return this.form.getRiding();
      }

   public final var metabolismCycle: Int
   public final val allAccessibleMoves: Set<MoveTemplate>
      public final get() {
         var var10000: java.util.Set = this.form.getMoves().getLevelUpMovesUpTo(this.level);
         var `$this$map$iv`: java.lang.Iterable = this.benchedMoves as java.lang.Iterable;
         var `destination$iv$iv`: java.util.Collection = new ArrayList(CollectionsKt.collectionSizeOrDefault(this.benchedMoves as java.lang.Iterable, 10));

         for (Object item$iv$iv : $this$map$iv) {
            `destination$iv$iv`.add((`item$iv$iv` as BenchedMove).getMoveTemplate());
         }

         return SetsKt.plus(SetsKt.plus(var10000, `destination$iv$iv` as java.util.List), this.form.getMoves().getEvolutionMoves());
      }

   public final val relearnableMoves: Iterable<MoveTemplate>
      public final get() {
         var `$this$filter$iv`: java.lang.Iterable = this.getAllAccessibleMoves();
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filter$iv) {
            var accessibleMove: MoveTemplate = `element$iv$iv` as MoveTemplate;
            var `$this$none$iv`: java.lang.Iterable = this.moveSet as java.lang.Iterable;
            var var10000: Boolean;
            if (this.moveSet as java.lang.Iterable is java.util.Collection && ((this.moveSet as java.lang.Iterable) as java.util.Collection).isEmpty()) {
               var10000 = true;
            } else {
               var var12: java.util.Iterator = `$this$none$iv`.iterator();

               while (true) {
                  if (!var12.hasNext()) {
                     var10000 = true;
                     break;
                  }

                  if ((var12.next() as Move).getTemplate() == accessibleMove) {
                     var10000 = false;
                     break;
                  }
               }
            }

            if (var10000) {
               `destination$iv$iv`.add(`element$iv$iv`);
            }
         }

         return `destination$iv$iv`;
      }

   public final val struct: QueryStruct


   public fun setIV(stat: Stat, value: Int) {
      var quotient: Float = Mth.clamp((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
      this.ivs.set(stat, value);
      if (stat === Stats.HP) {
         this.updateHP(quotient);
      }
   }

   public fun initializeScale() {
      if (this.isAlpha) {
         this.setScaleModifier(1.0F);
      } else {
         var config: CobblemonConfig = Cobblemon.INSTANCE.getConfig();
         this.setIntrinsicScale(MiscUtilsKt.nextBetween(Random.Default as Random, config.getPokemonIntrinsicSizeMin(), config.getPokemonIntrinsicSizeMax()));
      }
   }

   public fun hyperTrainIV(stat: Stat, value: Int) {
      var quotient: Float = Mth.clamp((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
      var `$this$iv`: CancelableObservable = CobblemonEvents.HYPER_TRAINED_IV_PRE;
      var `event$iv`: Cancelable = (new Pre(this, stat, value)) as Cancelable;
      var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
      var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
      `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

      for (Object element$iv$iv$iv : events$iv$iv) {
         if (!`element$iv$iv$iv`.isCanceled()) {
            this.ivs.setHyperTrainedIV(stat, value);
            if (stat === Stats.HP) {
               this.updateHP(quotient);
            }

            var `$this$ivx`: EventObservable = CobblemonEvents.HYPER_TRAINED_IV_POST;
            var `events$iv`: Array<Post> = new Post[]{new Post(this, stat, value)};
            `$this$ivx`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

            for (Object element$iv$iv : events$iv) {
               ;
            }
         }
      }
   }

   public fun setEV(stat: Stat, value: Int) {
      var quotient: Float = Mth.clamp((float)this.currentHealth / (float)this.getMaxHealth(), 0.0F, 1.0F);
      this.evs.set(stat, value);
      if (stat === Stats.HP) {
         this.updateHP(quotient);
      }
   }

   public fun getDisplayName(showTitle: Boolean): MutableComponent {
      var var9: MutableComponent;
      label30: {
         if (this.nickname != null) {
            var9 = this.nickname.copy();
            if (var9 != null) {
               var9 = if (!(var9.getContents() == PlainTextContents.EMPTY)) var9 else null;
               if (var9 != null) {
                  break label30;
               }
            }
         }

         var9 = this.species.getTranslatedName().copy();
      }

      var var7: MutableComponent = var9;
      if (showTitle) {
         if (this.activeMark != null) {
            var it: Mark = this.activeMark;
            var7 = it.getTitle(var9);
         }
      }

      return var7;
   }

   private fun getAlphaScaleMultiplier(): Float {
      var hitbox: EntityDimensions = this.form.getHitbox();
      return (float)(1.1 + 0.8 * Math.pow(0.5, Mth.clamp((double)(Math.max(hitbox.width(), hitbox.height()) * this.form.getBaseScale()), 0.25, 5.0)));
   }

   private fun setIntrinsicScale(value: Float) {
      this.setScaleModifier(1.0F + (float)MathKt.roundToInt((value - 1.0F) * 100.0F * 10.0F) / 10.0F / 100.0F);
   }

   public fun asRenderablePokemon(): RenderablePokemon {
      var var10002: Species = this.species;
      var var10003: java.util.Set = this.aspects;
      var var1: ItemStack = if (this.heldItemVisible) this.heldItem else ItemStack.EMPTY;
      return new RenderablePokemon(var10002, var10003, var1);
   }

   public open fun getStat(stat: Stat): Int {
      return Cobblemon.INSTANCE.getStatProvider().getStatForPokemon(this, stat);
   }

   public open fun showdownId(): String {
      return if (this.form == this.species.getStandardForm()) this.species.showdownId() else this.form.showdownId();
   }

   public fun sendOut(level: ServerLevel, position: Vec3, illusion: IllusionEffect?, mutation: (PokemonEntity) -> Unit): PokemonEntity? {
      var `$this$iv`: CancelableObservable = CobblemonEvents.POKEMON_SENT_PRE;
      var `event$iv`: Cancelable = (new com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Pre(this, level, position)) as Cancelable;
      var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
      var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
      `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

      for (Object element$iv$iv$iv : events$iv$iv) {
         if (!`element$iv$iv$iv`.isCanceled()) {
            var it: com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Pre = `element$iv$iv$iv` as com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Pre;
            SeasonFeatureHandler.INSTANCE.updateSeason(this, level as LevelAccessor, Vec3ExtensionsKt.toBlockPos(position));
            var entity: PokemonEntity = new PokemonEntity(level as Level, this, null, 4, null);
            if (illusion != null) {
               illusion.start(entity);
            }

            var adjustedPosition: Vec3 = entity.getAdjustedSendoutPosition(position);
            EntityExtensionsKt.setPositionSafely(entity as Entity, adjustedPosition);
            mutation.invoke(entity);
            entity.finalizeSpawn(
               level as ServerLevelAccessor, level.getCurrentDifficultyAt(Vec3ExtensionsKt.toBlockPos(adjustedPosition)), MobSpawnType.EVENT, null
            );
            level.addFreshEntity(entity as Entity);
            this.setState((new SentOutState(entity)) as PokemonState);
            return entity;
         }
      }

      return null;
   }

   public fun sendOutWithAnimation(
      source: LivingEntity,
      level: ServerLevel,
      position: Vec3,
      battleId: UUID?,
      doCry: Boolean,
      illusion: IllusionEffect?,
      mutation: (PokemonEntity) -> Unit
   ): CompletableFuture<PokemonEntity> {
      if (this.state is ShoulderedState) {
         return this.sendOutFromShoulder(source as ServerPlayer, level, position, battleId, doCry, illusion, mutation);
      } else {
         var future: CompletableFuture = new CompletableFuture();
         (if (source is PokemonSender) (source as PokemonSender).sendingOut(this) else CompletableFuture.completedFuture(Unit.INSTANCE))
            .thenApply(Pokemon::sendOutWithAnimation$lambda$2);
         return future;
      }
   }

   public fun sendOutFromShoulder(
      player: ServerPlayer,
      level: ServerLevel,
      targetPosition: Vec3,
      battleId: UUID?,
      doCry: Boolean,
      illusion: IllusionEffect?,
      mutation: (PokemonEntity) -> Unit
   ): CompletableFuture<PokemonEntity> {
      var future: CompletableFuture = new CompletableFuture();
      var var10000: PokemonState = this.state;
      var isLeftShoulder: Boolean = (var10000 as ShoulderedState).isLeftShoulder();
      var arbitraryXOffset: Double = (double)player.getBbWidth() * 0.3 + (double)this.form.getHitbox().width() * 0.3;
      var currentPosition: Vec3 = player.position()
         .add(
            new Vec3(
                  if (isLeftShoulder) arbitraryXOffset else -arbitraryXOffset, (double)player.getBbHeight() - (double)this.form.getHitbox().height() * 0.4, 0.0
               )
               .yRot(-player.getYRot() * (float) (Math.PI / 180.0))
         );
      this.recall();
      this.sendOut(level, currentPosition, illusion, Pokemon::sendOutFromShoulder$lambda$1);
      return future;
   }

   public fun recall() {
      var `$this$iv`: CancelableObservable = CobblemonEvents.POKEMON_RECALL_PRE;
      var `event$iv`: Cancelable = (new com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Pre(this, this.getEntity())) as Cancelable;
      var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
      var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
      `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

      for (Object element$iv$iv$iv : events$iv$iv) {
         if (!`element$iv$iv$iv`.isCanceled()) {
            var it: com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Pre = `element$iv$iv$iv` as com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Pre;
            var state: ActivePokemonState = this.state as? ActivePokemonState;
            this.setState((new InactivePokemonState()) as PokemonState);
            if (state != null) {
               state.recall();
            }

            var var28: EventObservable = CobblemonEvents.POKEMON_RECALL_POST;
            var `events$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Post[]{
               new com.cobblemon.mod.common.api.events.pokemon.PokemonRecallEvent.Post(this, this.getEntity())
            };
            var28.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

            for (Object element$iv$iv : events$iv) {
               ;
            }
         }
      }
   }

   public fun tryRecallWithAnimation() {
      if (this.getEntity() != null) {
         var var10000: PokemonEntity = this.getEntity();
         if (var10000 != null) {
            var10000.recallWithAnimation();
         }
      } else {
         this.recall();
      }
   }

   public fun heal() {
      var `$this$iv`: CancelableObservable = CobblemonEvents.POKEMON_HEALED;
      var `event$iv`: Cancelable = (new PokemonHealedEvent(this, 0, null, 6, null)) as Cancelable;
      var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
      var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
      `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

      for (Object element$iv$iv$iv : events$iv$iv) {
         if (!`element$iv$iv$iv`.isCanceled()) {
            var event: PokemonHealedEvent = `element$iv$iv$iv` as PokemonHealedEvent;
            this.setCurrentHealth(this.getMaxHealth());
            this.moveSet.heal();
            this.setStatus(null);
            this.setFaintedTimer(-1);
            this.setHealTimer(-1);
            var entity: PokemonEntity = this.getEntity();
            if (entity != null) {
               entity.heal(entity.getMaxHealth() - entity.getHealth());
            }
         }
      }
   }

   public fun isFullHealth(): Boolean {
      return this.currentHealth == this.getMaxHealth();
   }

   public fun didSleep() {
      this.setCurrentHealth(Math.min(this.currentHealth + this.getMaxHealth() / 2, this.getMaxHealth()));
      this.setStatus(null);
      this.setFaintedTimer(-1);
      this.setHealTimer(-1);
      var entity: PokemonEntity = this.getEntity();
      if (entity != null) {
         entity.heal(entity.getMaxHealth() - entity.getHealth());
      }

      this.moveSet.partialHeal();
   }

   public fun canBeHealed(): Boolean {
      if (this.getMaxHealth() == this.currentHealth && this.status == null) {
         var `$this$any$iv`: java.lang.Iterable = this.moveSet as java.lang.Iterable;
         var var10000: Boolean;
         if (this.moveSet as java.lang.Iterable is java.util.Collection && ((this.moveSet as java.lang.Iterable) as java.util.Collection).isEmpty()) {
            var10000 = false;
         } else {
            label44: {
               for (Object element$iv : $this$any$iv) {
                  if ((`element$iv` as Move).getCurrentPp() != (`element$iv` as Move).getMaxPp()) {
                     var10000 = true;
                     break label44;
                  }
               }

               var10000 = false;
            }
         }

         if (!var10000) {
            return false;
         }
      }

      return true;
   }

   public fun isFainted(): Boolean {
      return this.currentHealth <= 0;
   }

   private fun updateHP(quotient: Float) {
      this.setCurrentHealth(MathKt.roundToInt((float)this.getMaxHealth() * quotient));
   }

   public fun applyStatus(status: PersistentStatus) {
      this.setStatus(new PersistentStatusContainer(status, RangesKt.random(status.statusPeriod(), Random.Default as Random)));
      if (this.status != null) {
         this.onChange((new StatusUpdatePacket(Pokemon::applyStatus$lambda$0, status)) as PokemonUpdatePacket<?>);
      }
   }

   public fun isFireImmune(): Boolean {
      return CollectionsKt.contains(this.getTypes(), ElementalTypes.FIRE)
         || this.form.getBehaviour().getMoving().getSwim().getCanSwimInLava()
         || this.form.getBehaviour().getFireImmune();
   }

   public fun isFreezeImmune(): Boolean {
      return CollectionsKt.contains(this.getTypes(), ElementalTypes.ICE) || this.form.getBehaviour().getFreezeImmune();
   }

   public fun dampensVibrations(): Boolean {
      return this.form.getBehaviour().getDampensVibrations();
   }

   public fun getMaxFullness(): Int {
      return this.getGrassKnotPower((double)this.species.getWeight()) / 10 / 2 + 1;
   }

   public fun getGrassKnotPower(weight: Double): Int {
      return if (0.1 <= weight && weight <= 21.8)
         20
         else
         (
            if (21.9 <= weight && weight <= 54.9)
               40
               else
               (
                  if (55.0 <= weight && weight <= 110.1)
                     60
                     else
                     (if (110.2 <= weight && weight <= 220.3) 80 else (if (220.4 <= weight && weight <= 440.8) 100 else (if (weight >= 440.9) 120 else 0)))
               )
         );
   }

   public fun feedPokemon(feedCount: Int, playSound: Boolean) {
      if (this.getEntity() != null && playSound) {
         var fullnessPercent: Float = (float)this.currentFullness / (float)this.getMaxFullness() * 0.5F;
         if (this.currentFullness >= this.getMaxFullness()) {
            var var10000: PokemonEntity = this.getEntity();
            if (var10000 != null) {
               var10000.playSound(CobblemonSounds.BERRY_EAT_FULL, 1.0F, 1.0F);
            }
         } else {
            var var4: PokemonEntity = this.getEntity();
            if (var4 != null) {
               var4.playSound(CobblemonSounds.BERRY_EAT, 1.0F, 1.0F + fullnessPercent);
            }
         }
      }

      if (!this.isFull()) {
         this.setCurrentFullness(RangesKt.coerceIn(this.currentFullness + feedCount, 0, this.getMaxFullness()));
         if (this.currentFullness == 1) {
            this.resetMetabolismCycle();
         }
      }
   }

   public fun loseFullness(value: Int) {
      this.setCurrentFullness(Math.max(0, this.currentFullness - value));
   }

   public fun getMetabolismRate(): Int {
      var var10000: Any = this.species.getBaseStats().getOrDefault(Stats.SPEED, 0);
      var speed: Int = (var10000 as java.lang.Number).intValue();
      var10000 = this.species.getBaseStats().values();
      var BST: Int = CollectionsKt.sumOfInt(var10000 as java.lang.Iterable);
      var metabolismRateInSeconds: Int = (int)(((double)20 - (double)speed / (double)BST * (double)20 * (double)4) * 60.0);
      if ((int)(((double)20 - (double)speed / (double)BST * (double)20 * (double)4) * 60.0) <= 0) {
         metabolismRateInSeconds = 60;
      }

      return metabolismRateInSeconds * 20;
   }

   public fun isFull(): Boolean {
      return this.currentFullness >= this.getMaxFullness();
   }

   public fun resetMetabolismCycle() {
      this.metabolismCycle = 0;
   }

   public fun resetInteractionCooldown(group: ResourceLocation) {
      this.interactionCooldowns.remove(group);
   }

   public open fun tickInteractionCooldown(ticksPassed: Int) {
      var iterator: java.util.Iterator = this.interactionCooldowns.entrySet().iterator();

      while (iterator.hasNext()) {
         var entry: Entry = iterator.next() as Entry;
         var newValue: Int = (entry.getValue() as java.lang.Number).intValue() - RangesKt.coerceAtLeast(ticksPassed, 1);
         if (newValue <= 0) {
            iterator.remove();
         } else {
            entry.setValue(newValue);
         }
      }
   }

   public fun isOnInteractionCooldown(group: ResourceLocation): Boolean {
      return this.interactionCooldowns.getOrDefault(group, 0).intValue() > 0;
   }

   public open fun tickMetabolism(ticksPassed: Int) {
      this.metabolismCycle = this.metabolismCycle + RangesKt.coerceAtLeast(ticksPassed, 1);
      if (this.metabolismCycle >= this.getMetabolismRate()) {
         if (this.currentFullness > 0) {
            this.loseFullness(1);
         }

         this.resetMetabolismCycle();
      }
   }

   public fun isPositionSafe(world: Level, pos: Vec3): Boolean {
      return this.isPositionSafe(world, Vec3ExtensionsKt.toBlockPos(pos));
   }

   public fun isPositionSafe(world: Level, pos1: BlockPos): Boolean {
      var var10000: BlockPos;
      if (world.getBlockState(pos1).isSolid()) {
         var positions: BlockPos = pos1.above();
         var10000 = positions;
      } else {
         var var10: BlockPos = pos1.below();
         var10000 = var10;
      }

      var isSafe: Array<BlockPos> = new BlockPos[]{pos1, var10000};
      var var12: Boolean = true;

      for (BlockPos pos : isSafe) {
         if (var12) {
            var block: Block = world.getBlockState(pos).getBlock();
            if (block is SweetBerryBushBlock || block is CactusBlock || block is WitherRoseBlock) {
               var12 = false;
            }

            if (!this.isFireImmune()
               && (block is FireBlock || block is MagmaBlock || block is CampfireBlock || world.getBlockState(pos).getFluidState().is(FluidTags.LAVA))) {
               var12 = false;
            }
         }
      }

      return var12;
   }

   public fun isLegendary(): Boolean {
      return this.hasLabels("legendary");
   }

   public fun isMythical(): Boolean {
      return this.hasLabels("mythical");
   }

   public fun isUltraBeast(): Boolean {
      return this.hasLabels("ultra_beast");
   }

   public fun hasLabels(vararg labels: String): Boolean {
      var `$this$all$iv`: Array<Any> = labels;
      var var4: Int = 0;
      var var5: Int = labels.length;

      var var15: Boolean;
      while (true) {
         if (var4 >= var5) {
            var15 = true;
            break;
         }

         var label: java.lang.String = (java.lang.String)`$this$all$iv`[var4];
         var `$this$any$iv`: java.lang.Iterable = this.form.getLabels();
         if (`$this$any$iv` is java.util.Collection && (`$this$any$iv` as java.util.Collection).isEmpty()) {
            var15 = false;
         } else {
            var var11: java.util.Iterator = `$this$any$iv`.iterator();

            while (true) {
               if (!var11.hasNext()) {
                  var15 = false;
                  break;
               }

               if (StringsKt.equals(var11.next() as java.lang.String, label, true)) {
                  var15 = true;
                  break;
               }
            }
         }

         if (!var15) {
            var15 = false;
            break;
         }

         var4++;
      }

      return var15;
   }

   public fun isUncatchable(): Boolean {
      return UncatchableProperty.INSTANCE.uncatchable().matches(this);
   }

   public fun isBattleClone(): Boolean {
      return BattleCloneProperty.INSTANCE.isBattleClone().matches(this);
   }

   public fun heldItem(): ItemStack {
      var var10000: ItemStack = this.heldItem.copy();
      return var10000;
   }

   internal fun heldItemNoCopy(): ItemStack {
      return this.heldItem;
   }

   public fun swapHeldItem(stack: ItemStack, decrement: Boolean, aiCanDrop: Boolean): ItemStack {
      var event: com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre(
         this, stack, this.heldItem(), decrement
      );
      if (!this.isClient) {
         var giving: EventObservable = CobblemonEvents.HELD_ITEM_PRE as EventObservable;
         var `this_$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre> = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre[]{
            event
         };
         giving.emit(Arrays.copyOf(`this_$iv`, `this_$iv`.length));

         for (Object element$iv$iv : this_$iv) {
            ;
         }
      }

      if (!event.isCanceled()) {
         var var17: ItemStack = event.getReceiving().copy();
         var17.setCount(1);
         if (event.getDecrement()) {
            event.getReceiving().shrink(1);
         }

         this.heldItem = var17;
         this.canDropHeldItem = var17.isEmpty() || aiCanDrop;
         this.onChange((new HeldItemUpdatePacket(Pokemon::swapHeldItem$lambda$1, var17)) as PokemonUpdatePacket<?>);
         var var18: EventObservable = CobblemonEvents.HELD_ITEM_POST;
         var `events$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post[1];
         var var10005: ItemStack = this.heldItem();
         var var10006: ItemStack = event.getReturning().copy();
         `events$iv`[0] = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post(this, var10005, var10006, event.getDecrement());
         var18.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

         for (Object element$iv$iv : events$iv) {
            StashHandler.INSTANCE.giveHeldItem((com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post)var25);
         }

         return event.getReturning();
      } else {
         return stack;
      }
   }

   public fun cosmeticItem(): ItemStack {
      var var10000: ItemStack = this.cosmeticItem.copy();
      return var10000;
   }

   public fun swapCosmeticItem(stack: ItemStack, decrement: Boolean): ItemStack {
      var existing: ItemStack = this.cosmeticItem.copy();
      var event: com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre(
         this, stack, existing, decrement
      );
      if (!this.isClient) {
         var giving: EventObservable = CobblemonEvents.COSMETIC_ITEM_PRE as EventObservable;
         var `$this$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre> = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Pre[]{
            event
         };
         giving.emit(Arrays.copyOf(`$this$iv`, `$this$iv`.length));

         for (Object element$iv$iv : $this$iv) {
            ;
         }
      }

      if (event.isCanceled()) {
         return stack;
      } else {
         var var17: ItemStack = event.getReceiving().copy();
         var17.setCount(1);
         if (event.getDecrement()) {
            event.getReceiving().shrink(1);
         }

         this.setCosmeticItem(var17);
         var var18: EventObservable = CobblemonEvents.COSMETIC_ITEM_POST;
         var `events$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post[1];
         var var10005: ItemStack = this.cosmeticItem.copy();
         var var10006: ItemStack = event.getReturning().copy();
         `events$iv`[0] = new com.cobblemon.mod.common.api.events.pokemon.HeldItemEvent.Post(this, var10005, var10006, event.getDecrement());
         var18.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

         for (Object element$iv$iv : events$iv) {
            ;
         }

         return event.getReturning();
      }
   }

   public fun removeHeldItem(): ItemStack {
      var var10001: ItemStack = ItemStack.EMPTY;
      return swapHeldItem$default(this, var10001, false, false, 6, null);
   }

   public fun removeCosmeticItem(): ItemStack {
      var var10001: ItemStack = ItemStack.EMPTY;
      return swapCosmeticItem$default(this, var10001, false, 2, null);
   }

   public fun addPotentialMark(mark: Mark) {
      this.potentialMarks.add(mark);
      if (!this.isClient) {
         this.onChange((new MarkPotentialAddUpdatePacket(Pokemon::addPotentialMark$lambda$0, mark)) as PokemonUpdatePacket<?>);
      }
   }

   public fun exchangeMark(mark: Mark, give: Boolean) {
      if (give) {
         var var10000: java.util.List = mark.getReplace();
         if (var10000 != null && (if (!var10000.isEmpty()) var10000 else null) != null) {
            var replacements: java.util.List;
            for (ResourceLocation markx : replacements) {
               var var12: Mark = Marks.getByIdentifier(markx);
               if (var12 != null) {
                  this.exchangeMark(var12, false);
               }
            }
         }

         this.marks.add(mark);
         if (!this.isClient) {
            this.onChange((new MarkAddUpdatePacket(Pokemon::exchangeMark$lambda$2, mark)) as PokemonUpdatePacket<?>);
         }
      } else {
         this.marks.remove(mark);
         if (this.activeMark == mark) {
            this.setActiveMark(null);
         }

         if (!this.isClient) {
            this.onChange((new MarkRemoveUpdatePacket(Pokemon::exchangeMark$lambda$3, mark)) as PokemonUpdatePacket<?>);
         }
      }
   }

   public fun applyPotentialMarks(chance: Double): Boolean {
      var selectedMark: java.lang.Iterable = this.potentialMarks;
      var `$i$f$groupBy`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$filterNot$iv) {
         if (!this.marks.contains(probability as Mark)) {
            `$i$f$groupBy`.add(probability);
         }
      }

      var potentials: java.util.Set = CollectionsKt.toMutableSet(`$i$f$groupBy` as java.util.List);
      if (!potentials.isEmpty()) {
         var var21: Mark = null;
         var `$this$groupBy$iv`: java.lang.Iterable = potentials;
         var `destination$iv$ivx`: java.util.Map = new LinkedHashMap();

         for (Object element$iv$ivx : $this$groupBy$iv) {
            var `key$iv$iv`: Any = (`element$iv$ivx` as Mark).getChanceGroup();
            var `value$iv$iv$iv`: Any = `destination$iv$ivx`.get(`key$iv$iv`);
            var var10000: Any;
            if (`value$iv$iv$iv` == null) {
               var var36: Any = new ArrayList();
               `destination$iv$ivx`.put(`key$iv$iv`, var36);
               var10000 = var36;
            } else {
               var10000 = `value$iv$iv$iv`;
            }

            (var10000 as java.util.List).add(`element$iv$ivx`);
         }

         for (Entry var25 : MapsKt.toSortedMap(
               destination$iv$ivx, (Comparator)(new com.cobblemon.mod.common.pokemon.Pokemon.applyPotentialMarks..inlined.compareBy.1())
            )
            .entrySet()) {
            var var26: Pair = var25.getKey() as Pair;
            var var28: java.util.List = var25.getValue() as java.util.List;
            if (Random.Default.nextDouble(0.0, 100.0) / chance
               < (double)(RangesKt.coerceIn((var26.getSecond() as java.lang.Number).floatValue(), 0.0F, 1.0F) * (float)100)) {
               var21 = CollectionsKt.random(var28, Random.Default as Random) as Mark;
               break;
            }
         }

         this.setPotentialMarks(new LinkedHashSet<>());
         if (var21 != null) {
            this.exchangeMark(var21, true);
            return true;
         }
      }

      return false;
   }

   public fun saveToNBT(registryAccess: RegistryAccess, nbt: CompoundTag): CompoundTag {
      var var10001: RegistryOps = registryAccess.createSerializationContext(NbtOps.INSTANCE as DynamicOps);
      var var10000: Any = this.saveTo(var10001 as DynamicOps<CompoundTag>, nbt).getOrThrow();
      return var10000 as CompoundTag;
   }

   public fun loadFromNBT(registryAccess: RegistryAccess, nbt: CompoundTag): Pokemon {
      var var10001: RegistryOps = registryAccess.createSerializationContext(NbtOps.INSTANCE as DynamicOps);
      this.loadFrom(var10001 as DynamicOps<CompoundTag>, nbt);
      return this;
   }

   public fun saveToJSON(registryAccess: RegistryAccess, json: JsonObject): JsonObject {
      var ops: RegistryOps = registryAccess.createSerializationContext(JsonOps.INSTANCE as DynamicOps);
      var var10000: Any = this.saveTo(ops as DynamicOps<JsonObject>, json).getOrThrow();
      return var10000 as JsonObject;
   }

   public fun loadFromJSON(registryAccess: RegistryAccess, json: JsonObject): Pokemon {
      var ops: RegistryOps = registryAccess.createSerializationContext(JsonOps.INSTANCE as DynamicOps);
      this.loadFrom(ops as DynamicOps<JsonObject>, json);
      return this;
   }

   public open fun <T> saveTo(ops: DynamicOps<Any>, prefix: Any): DataResult<Any> {
      var var10000: DataResult = CODEC.encode(this, ops, prefix);
      return var10000;
   }

   public open fun <T> loadFrom(ops: DynamicOps<Any>, data: Any): DataResult<com.mojang.datafixers.util.Pair<Pokemon, Any>> {
      var var10000: DataResult = CODEC.decode(ops, data).ifSuccess(Pokemon::loadFrom$lambda$1);
      return var10000;
   }

   public fun clone(newUUID: Boolean, registryAccess: RegistryAccess?): Pokemon {
      var var10000: RegistryAccess = registryAccess;
      if (registryAccess == null) {
         var var6: MinecraftServer = DistributionUtilsKt.server();
         var var7: Frozen = if (var6 != null) var6.registryAccess() else null;
         if (var7 == null) {
            throw new IllegalStateException("No registry access for cloning available");
         }

         var10000 = var7 as RegistryAccess;
      }

      var ops: RegistryOps = var10000.createSerializationContext(NbtOps.INSTANCE as DynamicOps);
      var result: Pokemon = (CODEC.decode(ops as DynamicOps, CODEC.encodeStart(ops as DynamicOps, this).getOrThrow() as Tag).getOrThrow() as com.mojang.datafixers.util.Pair)
         .getFirst() as Pokemon;
      result.isClient = this.isClient;
      if (newUUID) {
         result.uuid = UUID.randomUUID();
         result.setTetheringId(null);
      }

      return result;
   }

   public fun recalculateCharacteristic() {
      var var10001: com.cobblemon.mod.common.pokemon.Characteristic.Companion = Characteristic.Companion;
      var var10002: IVs = this.ivs;
      var var10003: UUID = this.uuid;
      this.characteristic = var10001.calculate(var10002, var10003);
   }

   public open fun copyFrom(other: Pokemon): Pokemon {
      this.isClient = other.isClient;
      this.uuid = other.uuid;
      this.setAbility$common(other.ability);
      this.setSpecies(other.species);
      this.setForm(other.form);
      this.setNickname(other.nickname);
      this.setLevel(other.level);
      this.setExperience$common(other.experience);
      setFriendship$default(this, other.friendship, false, 2, null);
      this.ivs.doWithoutEmitting(Pokemon::copyFrom$lambda$0);
      this.evs.doWithoutEmitting(Pokemon::copyFrom$lambda$1);
      this.setCurrentHealth(other.currentHealth);
      this.setGender(other.gender);
      this.moveSet.copyFrom(other.moveSet);
      this.benchedMoves.copyFrom(other.benchedMoves);
      this.setScaleModifier(other.scaleModifier);
      this.setShiny(other.shiny);
      this.setState(other.state);
      this.setStatus(other.status);
      this.setCaughtBall(other.caughtBall);
      this.setFaintedTimer(other.faintedTimer);
      this.setHealTimer(other.healTimer);
      var var2: EvolutionProxy = this.evolutionProxy;
      var var10000: CobblemonEvolutionProxy = this.evolutionProxy as? CobblemonEvolutionProxy;
      if ((this.evolutionProxy as? CobblemonEvolutionProxy) != null) {
         var10000.overrideController$common(other.evolutionProxy.current().asIntermediate().create(this));
      }

      this.customProperties.clear();
      CollectionsKt.addAll(this.customProperties, other.customProperties);
      this.setNature(other.nature);
      this.characteristic = other.characteristic;
      this.setMintedNature(other.mintedNature);
      this.heldItem = other.heldItem;
      this.canDropHeldItem = other.getCanDropHeldItem$common();
      this.persistentData = other.persistentData;
      this.setTetheringId(other.tetheringId);
      this.setTeraType(other.teraType);
      this.setDmaxLevel(other.dmaxLevel);
      this.setGmaxFactor(other.gmaxFactor);
      this.setTradeable(other.tradeable);
      this.originalTrainerType = other.originalTrainerType;
      this.originalTrainer = other.originalTrainer;
      this.setForcedAspects(other.forcedAspects);
      this.features = other.features;
      this.setCosmeticItem(other.cosmeticItem);
      this.setActiveMark(other.activeMark);
      this.marks.clear();
      CollectionsKt.addAll(this.marks, other.marks);
      this.potentialMarks.clear();
      CollectionsKt.addAll(this.potentialMarks, other.marks);
      this.setMarkings(other.markings);
      this.recalculateCharacteristic();
      this.updateAspects();
      this.refreshOriginalTrainer();
      this.initialize();
      return this;
   }

   public fun getOwnerEntity(): LivingEntity? {
      if (this.isClient) {
         var var9: PokemonEntity = this.getEntity();
         if (var9 != null) {
            var var10: UUID = var9.getOwnerUUID();
            if (var10 != null) {
               var9 = this.getEntity();
               if (var9 != null) {
                  var var12: Level = var9.level();
                  if (var12 != null) {
                     return var12.getPlayerByUUID(var10) as LivingEntity;
                  }
               }

               return null as LivingEntity;
            }
         }

         return null;
      } else {
         var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
         var var7: LivingEntity;
         if (var10000 != null) {
            if (!this.isPlayerOwned()) {
               if (this.isNPCOwned()) {
                  var var8: PokemonStore = var10000.getStore();
                  var7 = (var8 as NPCPartyStore).getNpc() as LivingEntity;
               } else {
                  var7 = null;
               }
            } else {
               label41: {
                  var var4: MinecraftServer = DistributionUtilsKt.server();
                  if (var4 != null) {
                     var var5: PlayerList = var4.getPlayerList();
                     if (var5 != null) {
                        var6 = var5.getPlayer(var10000.getStore().getUuid());
                        break label41;
                     }
                  }

                  var6 = null;
               }

               var7 = var6 as LivingEntity;
            }
         } else {
            var7 = null;
         }

         return var7;
      }
   }

   public fun getOwnerPlayer(): ServerPlayer? {
      var var1: LivingEntity = this.getOwnerEntity();
      return var1 as? ServerPlayer;
   }

   public fun getOwnerNPC(): NPCEntity? {
      var var1: LivingEntity = this.getOwnerEntity();
      return var1 as? NPCEntity;
   }

   public fun getOwnerUUID(): UUID? {
      var var1: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      if (var1 != null) {
         var var4: PokemonStore = var1.getStore();
         return if (var4 is PlayerPartyStore)
            (var1.getStore() as PlayerPartyStore).getPlayerUUID()
            else
            (
               if (var4 is NPCPartyStore)
                  (var1.getStore() as NPCPartyStore).getNpc().getUUID()
                  else
                  (if (var4 is PCStore) (var1.getStore() as PCStore).getUuid() else null)
            );
      } else {
         return null;
      }
   }

   public fun belongsTo(player: Player): Boolean {
      var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      return var10000 != null && var10000.getStore().getUuid() == player.getUUID();
   }

   public fun isPlayerOwned(): Boolean {
      var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      return var10000 != null && (var10000.getStore() is PlayerPartyStore || var10000.getStore() is PCStore);
   }

   public fun isNPCOwned(): Boolean {
      var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      return var10000 != null && var10000.getStore() is NPCPartyStore;
   }

   public fun isWild(): Boolean {
      return this.storeCoordinates.get() == null;
   }

   public fun setFriendship(value: Int, coerceSafe: Boolean): Boolean {
      var sanitizedAmount: Int = if (coerceSafe)
         RangesKt.coerceAtMost(Math.abs(value), Cobblemon.INSTANCE.getConfig().getMaxPokemonFriendship())
         else
         Math.abs(value);
      if (!this.isPossibleFriendship(sanitizedAmount)) {
         return false;
      } else {
         this.setFriendship(sanitizedAmount);
         return true;
      }
   }

   public fun incrementFriendship(amount: Int, coerceSafe: Boolean): Boolean {
      var newValue: Int = this.friendship
         + (
            if (coerceSafe)
               RangesKt.coerceAtMost(Math.abs(amount), Cobblemon.INSTANCE.getConfig().getMaxPokemonFriendship() - this.friendship)
               else
               Math.abs(amount)
         );
      if (this.isPossibleFriendship(newValue)) {
         this.friendshipEarned = true;
         this.setFriendship(newValue);
      }

      return this.friendship == newValue;
   }

   public fun decrementFriendship(amount: Int, coerceSafe: Boolean): Boolean {
      var newValue: Int = this.friendship - (if (coerceSafe) RangesKt.coerceAtMost(Math.abs(amount), this.friendship) else Math.abs(amount));
      if (this.isPossibleFriendship(newValue)) {
         this.setFriendship(newValue);
      }

      return this.friendship == newValue;
   }

   public fun isPossibleFriendship(value: Int): Boolean {
      return value >= 0 && value <= Cobblemon.INSTANCE.getConfig().getMaxPokemonFriendship();
   }

   public fun setOriginalTrainer(playerUUID: UUID) {
      this.originalTrainerType = OriginalTrainerType.PLAYER;
      this.originalTrainer = playerUUID.toString();
   }

   public fun setOriginalTrainer(fakeTrainerName: String) {
      this.originalTrainerType = OriginalTrainerType.NPC;
      this.originalTrainer = fakeTrainerName;
   }

   public fun refreshOriginalTrainer() {
      switch (WhenMappings.$EnumSwitchMapping$0[this.originalTrainerType.ordinal()]) {
         case 1:
            var var10000: UUID = UUID.fromString(this.originalTrainer);
            if (var10000 != null) {
               var var5: MinecraftServer = DistributionUtilsKt.server();
               if (var5 != null) {
                  var var6: GameProfileCache = var5.getProfileCache();
                  if (var6 != null) {
                     var var7: Optional = var6.get(var10000);
                     if (var7 != null) {
                        var var8: GameProfile = var7.orElse(null) as GameProfile;
                        if (var8 != null) {
                           var var9: java.lang.String = var8.getName();
                           if (var9 != null) {
                              this.setOriginalTrainerName(var9);
                           }
                        }
                     }
                  }
               }
            }
            break;
         case 2:
            this.setOriginalTrainerName(this.originalTrainer);
            break;
         case 3:
            this.setOriginalTrainerName(null);
            break;
         default:
            throw new NoWhenBranchMatchedException();
      }
   }

   public fun removeOriginalTrainer() {
      this.originalTrainer = null;
      this.originalTrainerType = OriginalTrainerType.NONE;
      this.setOriginalTrainerName(null);
   }

   public fun updateAspects() {
      if (!this.isClient) {
         var oldAspects: java.util.Set = this.aspects;
         var `$this$iv`: java.lang.Iterable = AspectProvider.Companion.getProviders();
         var `$i$f$post`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$iv) {
            CollectionsKt.addAll(`$i$f$post`, (`element$iv$iv` as AspectProvider).provide(this));
         }

         this.setAspects(SetsKt.plus(CollectionsKt.toSet(`$i$f$post` as java.util.List), this.forcedAspects));
         if (!(oldAspects == this.aspects) && !this.isWild()) {
            var var14: EventObservable = CobblemonEvents.POKEMON_ASPECTS_CHANGED;
            var var15: Array<PokemonAspectsChangedEvent> = new PokemonAspectsChangedEvent[]{new PokemonAspectsChangedEvent(this.getOwnerUUID(), this)};
            var14.emit(Arrays.copyOf(var15, var15.length));

            for (Object element$iv$iv : var15) {
               ;
            }
         }
      } else {
         this.setAspects(this.forcedAspects);
      }
   }

   public fun updateForm() {
      var newForm: FormData = this.species.getForm(this.aspects);
      if (!(this.form == newForm)) {
         this.setForm(newForm);
      }
   }

   public fun initialize(): Pokemon {
      this.setSpecies(this.species);
      this.checkGender();
      if (this.moveSet.getMoves().isEmpty()) {
         this.initializeMovesetFromDefault();
      }

      return this;
   }

   public fun checkGender() {
      var reassess: Boolean = false;
      var allForms: Float = this.form.getMaleRatio();
      if ((!(0.0F <= allForms) || !(allForms <= 1.0F)) && this.gender != Gender.GENDERLESS) {
         reassess = true;
      } else if (this.form.getMaleRatio() == 0.0F && this.gender != Gender.FEMALE) {
         reassess = true;
      } else if (this.form.getMaleRatio() == 1.0F && this.gender != Gender.MALE) {
         reassess = true;
      } else {
         allForms = this.form.getMaleRatio();
         if (0.0F <= allForms && allForms <= 1.0F && this.gender === Gender.GENDERLESS) {
            reassess = true;
         }
      }

      if (reassess) {
         var var11: java.util.List = this.form.getSpecies().getForms();
         var `$this$any$iv`: java.lang.Iterable = var11;
         var var10000: Boolean;
         if (var11 is java.util.Collection && (var11 as java.util.Collection).isEmpty()) {
            var10000 = false;
         } else {
            label179: {
               for (Object element$iv : $this$any$iv) {
                  if ((`element$iv` as FormData).getMaleRatio() == 1.0F) {
                     var10000 = true;
                     break label179;
                  }
               }

               var10000 = false;
            }
         }

         label121: {
            if (var10000) {
               `$this$any$iv` = var11;
               if (var11 is java.util.Collection && (var11 as java.util.Collection).isEmpty()) {
                  var10000 = false;
               } else {
                  label180: {
                     for (Object element$ivx : $this$any$iv) {
                        if ((`element$ivx` as FormData).getMaleRatio() == 0.0F) {
                           var10000 = true;
                           break label180;
                        }
                     }

                     var10000 = false;
                  }
               }

               if (var10000) {
                  var10000 = true;
                  break label121;
               }
            }

            var10000 = false;
         }

         var var10001: Gender;
         if (var10000) {
            var10001 = if (Random.Default.nextBoolean()) Gender.FEMALE else Gender.MALE;
         } else {
            var var13: Float = this.form.getMaleRatio();
            var10001 = if (!(0.0F <= var13) || !(var13 <= 1.0F))
               Gender.GENDERLESS
               else
               (if (this.form.getMaleRatio() != 1.0F && !(Random.Default.nextFloat() <= this.form.getMaleRatio())) Gender.FEMALE else Gender.MALE);
         }

         this.setGender(var10001);
      }
   }

   public open fun rollAbility(): Ability {
      return if (this.isClient)
         this.ability
         else
         this.updateAbility(
            AbilityTemplate.create$default(
               (this.form.getAbilities().select(this.species, this.aspects).component1() as Ability).getTemplate(), false, null, 2, null
            )
         );
   }

   public open fun updateAbility(ability: Ability): Ability {
      if (this.isClient) {
         return this.ability;
      } else {
         this.setAbility$common(if (ability.getForced()) ability else this.attachAbilityCoordinate(ability));
         return this.ability;
      }
   }

   protected open fun attemptAbilityUpdate() {
      if (!this.isClient && !this.ability.getForced()) {
         if (this.ability.getTemplate() == Abilities.INSTANCE.getDUMMY()) {
            this.rollAbility();
         } else {
            var potentials: java.util.List = this.form.getAbilities().getMapping().get(this.ability.getPriority()) as java.util.List;
            var potential: PotentialAbility = if (potentials != null)
               CollectionsKt.getOrNull(potentials, this.ability.getIndex()) as PotentialAbility
               else
               null;
            if (potential == null && potentials != null) {
               for (int i = RangesKt.coerceAtLeast(this.ability.getIndex(), 0); -1 < i; i--) {
                  var `$this$attemptAbilityUpdate_u24lambda_u240`: PotentialAbility = CollectionsKt.getOrNull(potentials, i) as PotentialAbility;
                  if (`$this$attemptAbilityUpdate_u24lambda_u240` != null) {
                     potential = `$this$attemptAbilityUpdate_u24lambda_u240`;
                     break;
                  }
               }
            }

            if (potential != null) {
               var var7: Ability = AbilityTemplate.create$default(potential.getTemplate(), false, null, 2, null);
               var7.setIndex$common(this.ability.getIndex());
               var7.setPriority$common(this.ability.getPriority());
               this.setAbility$common(var7);
            } else {
               this.rollAbility();
            }
         }
      }
   }

   protected open fun attachAbilityCoordinate(ability: Ability): Ability {
      if (!this.isClient && !ability.getForced() && !(ability.getTemplate() == Abilities.INSTANCE.getDUMMY())) {
         var var6: java.util.Iterator = (this.form.getAbilities() as java.lang.Iterable).iterator();

         var var10000: Any;
         while (true) {
            if (!var6.hasNext()) {
               var10000 = null;
               break;
            }

            var `element$iv`: Any = var6.next();
            if ((`element$iv` as PotentialAbility).getTemplate() == ability.getTemplate()
               && (`element$iv` as PotentialAbility).getPriority() === ability.getPriority()) {
               var10000 = `element$iv`;
               break;
            }
         }

         var10000 = var10000 as PotentialAbility;
         if (var10000 as PotentialAbility == null) {
            ability.setForced$common(true);
            return ability;
         } else {
            var var10: java.util.List = this.form.getAbilities().getMapping().get(var10000.getPriority()) as java.util.List;
            if (var10 != null) {
               var var13: Int = var10.indexOf(var10000);
               var var11: Int = if (var13.intValue() != -1) var13 else null;
               if (var11 != null) {
                  var index: Int = var11;
                  ability.setPriority$common(var10000.getPriority());
                  ability.setIndex$common(index);
                  return ability;
               }
            }

            ability.setForced$common(true);
            return ability;
         }
      } else {
         return ability;
      }
   }

   public fun teachLearnableMoves(includeLegacy: Boolean) {
      var possibleMoves: java.util.Set = this.form.getMoves().getAllLegalMoves();
      var possibleMovesSet: HashSet = new HashSet();
      var query: LearnsetQuery = if (includeLegacy) LearnsetQuery.Companion.getANY() else LearnsetQuery.Companion.getLEGAL();

      for (MoveTemplate move : possibleMoves) {
         if (query.canLearn(move, this.form.getMoves())) {
            var `$this$none$iv`: java.lang.Iterable = this.moveSet as java.lang.Iterable;
            var var10000: Boolean;
            if (this.moveSet as java.lang.Iterable is java.util.Collection && ((this.moveSet as java.lang.Iterable) as java.util.Collection).isEmpty()) {
               var10000 = true;
            } else {
               var var9: java.util.Iterator = `$this$none$iv`.iterator();

               while (true) {
                  if (!var9.hasNext()) {
                     var10000 = true;
                     break;
                  }

                  if ((var9.next() as Move).getTemplate() == move) {
                     var10000 = false;
                     break;
                  }
               }
            }

            if (var10000) {
               possibleMovesSet.add(new BenchedMove(move, 0));
            }
         }
      }

      this.benchedMoves.addAll(possibleMovesSet);
      this.benchedMoves.update();
      this.moveSet.update();
   }

   public fun validateMoveset(includeLegacy: Boolean) {
      this.moveSet.doWithoutEmitting(Pokemon::validateMoveset$lambda$0);
      this.moveSet.update();
   }

   public fun initializeMovesetFromDefault() {
      if (this.isClient) {
         var var10000: MoveSet = this.moveSet;
         var var5: MoveSet = new MoveSet();
         var5.add(Moves.getExceptional().create());
         var10000.copyFrom(var5);
      } else {
         this.initializeMovesetFrom(
            CobblemonMovesetBuilders.INSTANCE
               .getOrThrow(if (this.isAlpha) MiscUtilsKt.cobblemonResource("alpha") else this.form.getDefaultWildMovesetBuilder())
         );
      }
   }

   public fun initializeMovesetFrom(movesetBuilder: MovesetBuilder) {
      this.moveSet.copyFrom(movesetBuilder.build(this.form, this.level));
   }

   @Deprecated(message = "Will be removed with a title update, maybe as early as 1.9", replaceWith = @ReplaceWith(expression = "initializeMovesetFromDefault() or initializeMovesetFrom(movesetBuilder)", imports = []), level = DeprecationLevel.WARNING)
   public fun initializeMoveset(preferLatest: Boolean) {
      this.moveSet.doWithoutEmitting(Pokemon::initializeMoveset$lambda$0);
      this.moveSet.update();
   }

   public fun unlearnMove(move: MoveTemplate) {
      this.moveSet.doWithoutEmitting(Pokemon::unlearnMove$lambda$0);
      this.moveSet.update();
   }

   public fun getBaseRideStat(stat: RidingStat): Float {
      var var10000: java.util.Map = this.form.getRiding().getBehaviours();
      if (var10000 == null) {
         return 0.0F;
      } else {
         var var3: java.util.Iterator = var10000.values().iterator();
         if (!var3.hasNext()) {
            throw new NoSuchElementException();
         } else {
            var var10: IntRange = (var3.next() as RidingBehaviourSettings).getStats().get(stat) as IntRange;
            var var7: Float = if (var10 != null) (float)var10.getFirst() else 0.0F;

            while (var3.hasNext()) {
               var var11: IntRange = (var3.next() as RidingBehaviourSettings).getStats().get(stat) as IntRange;
               var7 = Math.max(var7, if (var11 != null) (float)var11.getFirst() else 0.0F);
            }

            return var7;
         }
      }
   }

   public fun getMaxRideBoost(stat: RidingStat): Float {
      var var10000: java.util.Map = this.form.getRiding().getBehaviours();
      if (var10000 == null) {
         return 0.0F;
      } else {
         var var3: java.util.Iterator = var10000.values().iterator();
         var var14: java.lang.Float;
         if (!var3.hasNext()) {
            var14 = null;
         } else {
            var var15: IntRange = (var3.next() as RidingBehaviourSettings).getStats().get(stat) as IntRange;
            var var9: Float = if (var15 != null) (float)(var15.getLast() - var15.getFirst()) else 0.0F;

            while (var3.hasNext()) {
               var var17: IntRange = (var3.next() as RidingBehaviourSettings).getStats().get(stat) as IntRange;
               var9 = Math.max(var9, if (var17 != null) (float)(var17.getLast() - var17.getFirst()) else 0.0F);
            }

            var14 = var9;
         }

         return var14 ?: 0.0F;
      }
   }

   public fun getRideBoost(stat: RidingStat): Float {
      var var10000: java.lang.Float = this.rideBoosts.get(stat);
      return var10000 ?: 0.0F;
   }

   public fun getRideBoosts(): Map<RidingStat, Float> {
      return MapsKt.toMap(this.rideBoosts);
   }

   public fun getRideStat(style: RidingStyle, stat: RidingStat): Float {
      var var10000: java.util.Map = this.form.getRiding().getBehaviours();
      if (var10000 != null) {
         var var5: RidingBehaviourSettings = var10000.get(style) as RidingBehaviourSettings;
         return if (var5 != null) var5.calculate(stat, this.getRideBoost(stat)) else 0.0F;
      } else {
         return 0.0F;
      }
   }

   public fun canApplyRideBoost(stat: RidingStat, decreases: Boolean): Boolean {
      return if (!decreases) this.canIncreaseRideBoost(stat) else this.canDecreaseRideBoost(stat);
   }

   private fun canIncreaseRideBoost(stat: RidingStat): Boolean {
      var var10000: java.lang.Float = this.rideBoosts.get(stat);
      return (var10000 ?: 0.0F) < this.getMaxRideBoost(stat);
   }

   private fun canDecreaseRideBoost(stat: RidingStat): Boolean {
      var var10000: java.lang.Float = this.rideBoosts.get(stat);
      return (var10000 ?: 0.0F) > 0.0F;
   }

   public fun addRideBoost(stat: RidingStat, boostAmount: Float): Boolean {
      if (!this.canApplyRideBoost(stat, boostAmount < 0.0F)) {
         return false;
      } else {
         this.rideBoosts.put(stat, RangesKt.coerceIn(this.getRideBoost(stat) + boostAmount, 0.0F, this.getMaxRideBoost(stat)));
         this.onChange((new RideBoostsUpdatePacket(Pokemon::addRideBoost$lambda$0, this.getRideBoosts())) as PokemonUpdatePacket<?>);
         return true;
      }
   }

   public fun addRideBoosts(boosts: Map<RidingStat, Float>) {
      var changed: Boolean = false;

      for (Entry boost : boosts.entrySet()) {
         var stat: RidingStat = boost.getKey() as RidingStat;
         var boostAmount: Float = (boost.getValue() as java.lang.Number).floatValue();
         if (this.canApplyRideBoost(stat, boostAmount < 0.0F)) {
            this.rideBoosts.put(stat, RangesKt.coerceIn(this.getRideBoost(stat) + boostAmount, 0.0F, this.getMaxRideBoost(stat)));
            changed = true;
         }
      }

      if (changed) {
         this.onChange((new RideBoostsUpdatePacket(Pokemon::addRideBoosts$lambda$0, this.getRideBoosts())) as PokemonUpdatePacket<?>);
      }
   }

   public fun setRideBoost(stat: RidingStat, boost: Float) {
      this.rideBoosts.put(stat, RangesKt.coerceIn(boost, 0.0F, this.getMaxRideBoost(stat)));
      this.onChange((new RideBoostsUpdatePacket(Pokemon::setRideBoost$lambda$0, this.getRideBoosts())) as PokemonUpdatePacket<?>);
   }

   public fun setRideBoosts(boosts: Map<RidingStat, Float>) {
      this.rideBoosts.clear();
      var var17: java.util.Map = this.rideBoosts;
      var `destination$iv$iv`: java.util.Map = new LinkedHashMap(MapsKt.mapCapacity(boosts.size()));
      var `$this$associateByTo$iv$iv$iv`: java.lang.Iterable = boosts.entrySet();
      var `destination$iv$iv$iv`: java.util.Map = `destination$iv$iv`;

      for (Object element$iv$iv$iv : $this$associateByTo$iv$iv$iv) {
         `destination$iv$iv$iv`.put(
            (`element$iv$iv$iv` as Entry).getKey(),
            RangesKt.coerceIn(
               ((`element$iv$iv$iv` as Entry).getValue() as java.lang.Number).floatValue(),
               0.0F,
               this.getMaxRideBoost((`element$iv$iv$iv` as Entry).getKey() as RidingStat)
            )
         );
      }

      var17.putAll(`destination$iv$iv$iv`);
      this.onChange((new RideBoostsUpdatePacket(Pokemon::setRideBoosts$lambda$1, this.getRideBoosts())) as PokemonUpdatePacket<?>);
   }

   public fun getBlocksTraveled(): Int {
      var var10000: IntSpeciesFeature = this.getFeature("blocks_traveled");
      return if (var10000 != null) var10000.getValue() else 0;
   }

   public fun addBlocksTraveled(value: Int) {
      var var10000: IntSpeciesFeature = this.getFeature("blocks_traveled");
      if (var10000 != null) {
         var10000.setValue(var10000.getValue() + value);
         this.markFeatureDirty(var10000 as SpeciesFeature);
      }
   }

   public fun hasBlocksTraveledRequirement(): Boolean {
      var `$this$filterIsInstance$iv`: java.lang.Iterable = this.getEvolutions();
      var `destination$iv$iv`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$filterIsInstance$iv) {
         CollectionsKt.addAll(`destination$iv$iv`, (`element$iv$iv` as Evolution).getRequirements());
      }

      `$this$filterIsInstance$iv` = `destination$iv$iv` as java.util.List;
      `destination$iv$iv` = new ArrayList();

      for (Object element$iv$iv : $this$filterIsInstance$iv) {
         if (var15 is BlocksTraveledRequirement) {
            `destination$iv$iv`.add(var15);
         }
      }

      return !(`destination$iv$iv` as java.util.List).isEmpty();
   }

   public fun getExperienceToNextLevel(): Int {
      return this.getExperienceToLevel(this.level + 1);
   }

   public fun getExperienceToLevel(level: Int): Int {
      return if (level <= this.level) 0 else this.getExperienceGroup().getExperience(level) - this.experience;
   }

   public fun setExperienceAndUpdateLevel(xp: Int) {
      this.setExperience$common(xp);
      var newLevel: Int = this.getExperienceGroup().getLevel(xp);
      if (newLevel != this.level && newLevel <= Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel()) {
         this.setLevel(newLevel);
      }
   }

   public fun addExperienceWithPlayer(player: ServerPlayer, source: ExperienceSource, xp: Int): AddExperienceResult {
      var result: AddExperienceResult = this.addExperience(source, xp);
      if (result.getExperienceAdded() <= 0) {
         return result;
      } else {
         if (result.getOldLevel() != result.getNewLevel()) {
            var repeats: Int = result.getNewLevel() - result.getOldLevel();
            if (repeats >= 1) {
               for (int var6 = 0; var6 < repeats; var6++) {
                  incrementFriendship$default(this, LEVEL_UP_FRIENDSHIP_CALCULATOR.calculate(this), false, 2, null);
               }
            }
         }

         var var10000: CobblemonNetwork = CobblemonNetwork.INSTANCE;
         var var10004: UUID = this.uuid;
         var10000.sendPacket(
            player,
            (
               new ExpGainedDataPacket(
                  var10004,
                  if (result.getOldLevel() != result.getNewLevel()) result.getOldLevel() else null,
                  result.getExperienceAdded(),
                  result.getNewMoves().size()
               )
            ) as NetworkPacket
         );
         return result;
      }
   }

   public fun <T : SpeciesFeature> getFeature(name: String): Any? {
      var var4: java.util.Iterator = this.features.iterator();

      var var10000: Any;
      while (true) {
         if (var4.hasNext()) {
            var var5: Any = var4.next();
            if (!((var5 as SpeciesFeature).getName() == name)) {
               continue;
            }

            var10000 = var5;
            break;
         }

         var10000 = null;
         break;
      }

      return (T)(var10000 as? SpeciesFeature);
   }

   public fun createPokemonProperties(vararg extractors: PokemonPropertyExtractor): PokemonProperties {
      var properties: PokemonProperties = new PokemonProperties();

      for (Object element$iv : extractors) {
         `element$iv`.invoke(this, properties);
      }

      return properties;
   }

   public fun createPokemonProperties(extractors: MutableList<PokemonPropertyExtractor>): PokemonProperties {
      var var2: Array<PokemonPropertyExtractor> = extractors.toArray(new PokemonPropertyExtractor[0]);
      return this.createPokemonProperties(Arrays.copyOf(var2, var2.length));
   }

   public fun getSizeCategory(): PokemonSizeCategory {
      return PokemonSizeCategory.Companion.fromScale(this.scaleModifier);
   }

   public fun addExperience(source: ExperienceSource, xp: Int): AddExperienceResult {
      label77:
      if (xp >= 0 && this.canLevelUpFurther()) {
         var oldLevel: Int = this.level;
         var previousLevelUpMoves: java.util.Set = this.form.getMoves().getLevelUpMovesUpTo(oldLevel);
         var var24: Int = xp;
         var newLevel: CancelableObservable = CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE;
         var `this_$iv`: Cancelable = (new com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre(this, source, xp)) as Cancelable;
         var `$i$f$post`: EventObservable = newLevel as EventObservable;
         var `$this$forEach$iv$iv`: Array<Cancelable> = new Cancelable[]{`this_$iv`};
         `$i$f$post`.emit(Arrays.copyOf(`$this$forEach$iv$iv`, `$this$forEach$iv$iv`.length));

         for (Object element$iv$iv$iv : $this$forEach$iv$iv) {
            if (var18.isCanceled()) {
               var var61: com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre = var18 as com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre;
               return new AddExperienceResult(this.level, this.level, SetsKt.emptySet(), 0);
            }

            var24 = (var18 as com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Pre).getExperience();
         }

         this.setExperience$common(this.experience + var24);
         var var26: Int = RangesKt.coerceAtMost(this.getExperienceGroup().getLevel(this.experience), Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel());
         if (var26 != oldLevel) {
            var var27: EventObservable = CobblemonEvents.LEVEL_UP_EVENT;
            var differences: Array<LevelUpEvent> = new LevelUpEvent[]{new LevelUpEvent(this, oldLevel, var26)};
            var27.emit(Arrays.copyOf(differences, differences.length));

            for (Object element$iv$iv : differences) {
               var26 = var48.getNewLevel();
            }

            this.setLevel(var26);
         }

         var var31: java.lang.Iterable = SetsKt.minus(this.form.getMoves().getLevelUpMovesUpTo(var26), previousLevelUpMoves);
         var var42: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filter$iv) {
            var var55: MoveTemplate = var52 as MoveTemplate;
            var var58: java.lang.Iterable = this.moveSet as java.lang.Iterable;
            var var10000: Boolean;
            if (this.moveSet as java.lang.Iterable is java.util.Collection && ((this.moveSet as java.lang.Iterable) as java.util.Collection).isEmpty()) {
               var10000 = true;
            } else {
               var var60: java.util.Iterator = var58.iterator();

               while (true) {
                  if (!var60.hasNext()) {
                     var10000 = true;
                     break;
                  }

                  if ((var60.next() as Move).getTemplate() == var55) {
                     var10000 = false;
                     break;
                  }
               }
            }

            if (var10000) {
               var42.add(var52);
            }
         }

         var var29: java.util.Set = CollectionsKt.toMutableSet(var42 as java.util.List);

         for (Object element$iv : var32) {
            var itx: MoveTemplate = var43 as MoveTemplate;
            if (this.moveSet.hasSpace()) {
               this.moveSet.add(itx.create());
            }
         }

         var var33: EventObservable = CobblemonEvents.EXPERIENCE_GAINED_EVENT_POST;
         var var37: Array<com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Post[]{
            new com.cobblemon.mod.common.api.events.pokemon.ExperienceGainedEvent.Post(this, source, var24, oldLevel, var26, var29)
         };
         var33.emit(Arrays.copyOf(var37, var37.length));
         return if (0 < var37.length)
            new AddExperienceResult(oldLevel, var26, var37[0].getLearnedMoves(), var24)
            else
            new AddExperienceResult(oldLevel, var26, var29, var24);
      } else {
         return new AddExperienceResult(this.level, this.level, SetsKt.emptySet(), 0);
      }
   }

   public fun canLevelUpFurther(): Boolean {
      return this.level < Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel();
   }

   public fun levelUp(source: ExperienceSource): AddExperienceResult {
      return this.addExperience(source, this.getExperienceToNextLevel());
   }

   public fun exchangeMove(oldMove: MoveTemplate?, newMove: MoveTemplate?): Boolean {
      if (oldMove == null && newMove == null) {
         return false;
      } else {
         if (newMove == null) {
            if (this.moveSet.getMoves().size() <= 1) {
               return false;
            }

            var it: java.util.Iterator = (this.moveSet as java.lang.Iterable).iterator();

            var var10000: Any;
            while (true) {
               if (!it.hasNext()) {
                  var10000 = null;
                  break;
               }

               var var8: Any = it.next();
               if ((var8 as Move).getTemplate() == oldMove) {
                  var10000 = var8;
                  break;
               }
            }

            var10000 = var10000 as Move;
            if (var10000 as Move == null) {
               return false;
            }

            this.benchedMoves.add(new BenchedMove(var10000.getTemplate(), var10000.getRaisedPpStages()));
            var currentMove: Int = this.moveSet.getMovesWithNulls().indexOf(var10000);
            this.moveSet.setMove(currentMove, null);

            while (index < 3 && this.moveSet.get(index + 1) != null) {
               this.moveSet.swapMove(currentMove, currentMove + 1);
               currentMove++;
            }
         } else {
            var var17: java.util.Iterator = (this.benchedMoves as java.lang.Iterable).iterator();

            var var27: Any;
            while (true) {
               if (!var17.hasNext()) {
                  var27 = null;
                  break;
               }

               var var19: Any = var17.next();
               if ((var19 as BenchedMove).getMoveTemplate() == newMove) {
                  var27 = (Move)var19;
                  break;
               }
            }

            var27 = var27 as BenchedMove;
            if (var27 as BenchedMove == null) {
               var27 = new BenchedMove(newMove, 0);
            }

            if (oldMove == null) {
               if (this.moveSet.hasSpace()) {
                  var var13: Move = var27.getMoveTemplate().create();
                  var13.setRaisedPpStages(var27.getPpRaisedStages());
                  var13.setCurrentPp(0);
                  this.moveSet.add(var13);
                  this.benchedMoves.remove(newMove);
                  return true;
               }
            } else {
               var var20: java.util.Iterator = (this.moveSet as java.lang.Iterable).iterator();

               while (true) {
                  if (!var20.hasNext()) {
                     var27 = null;
                     break;
                  }

                  var var23: Any = var20.next();
                  if ((var23 as Move).getTemplate() == oldMove) {
                     var27 = var23;
                     break;
                  }
               }

               var27 = var27 as Move;
               if (var27 as Move == null) {
                  return false;
               }

               var currentPPRatio: Float = (float)var27.getCurrentPp() / (float)var27.getMaxPp();
               this.benchedMoves.doThenEmit(Pokemon::exchangeMove$lambda$4);
               var var16: Move = newMove.create();
               var16.setRaisedPpStages(var27.getPpRaisedStages());
               var16.setCurrentPp((int)(currentPPRatio * (float)var16.getMaxPp()));
               this.moveSet.setMove(CollectionsKt.indexOf(this.moveSet as java.lang.Iterable, var27), var16);
            }
         }

         return true;
      }
   }

   public fun notify(packet: PokemonUpdatePacket<*>) {
      var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      if (var10000 != null) {
         CobblemonNetwork.sendPacketToPlayers(var10000.getStore().getObservingPlayers(), packet as NetworkPacket);
      }
   }

   public fun markFeatureDirty(feature: SpeciesFeature) {
      var featureProvider: SpeciesFeatureProvider = SpeciesFeatures.getFeature(feature.getName());
      this.onChange(
         (
            if (feature is SynchronizedSpeciesFeature
                  && featureProvider is SynchronizedSpeciesFeatureProvider
                  && (featureProvider as SynchronizedSpeciesFeatureProvider).getVisible())
               new SpeciesFeatureUpdatePacket(Pokemon::markFeatureDirty$lambda$0, this.species.getResourceIdentifier(), feature as SynchronizedSpeciesFeature)
               else
               null
         ) as PokemonUpdatePacket<?>
      );
   }

   public fun cacheFriendship(playerID: UUID) {
      if (OriginalTrainerType.PLAYER === this.originalTrainerType && UUID.fromString(this.originalTrainer) == playerID) {
         this.originalTrainerFriendship = this.friendship;
      }
   }

   public fun restoreFriendship(playerID: UUID): Boolean {
      if (OriginalTrainerType.PLAYER === this.originalTrainerType && UUID.fromString(this.originalTrainer) == playerID) {
         if (this.originalTrainerFriendship != null) {
            this.setFriendship(this.originalTrainerFriendship.intValue());
         }

         this.originalTrainerFriendship = null;
         return true;
      } else {
         return false;
      }
   }

   public fun onChange(packet: PokemonUpdatePacket<*>?) {
      var var10000: StoreCoordinates = this.storeCoordinates.get() as StoreCoordinates;
      if (var10000 != null) {
         if (packet != null) {
            this.notify(packet);
         }

         var10000.getStore().onPokemonChanged(this);
      }
   }

   public open fun self(): Pokemon {
      return this;
   }

   private fun updateMovesOnFormChange(oldForm: FormData) {
      if (!this.isClient) {
         var newForm: FormData = this.form;
         var oldFormChangeMoves: java.util.Set = CollectionsKt.toSet(oldForm.getMoves().getFormChangeMoves());

         for (int i = 0; i < 4; i++) {
            var benchedMove: Move = this.moveSet.get(benchedIterator);
            if (benchedMove != null
               && oldFormChangeMoves.contains(benchedMove.getTemplate())
               && !LearnsetQuery.Companion.getANY().canLearn(benchedMove.getTemplate(), newForm.getMoves())) {
               this.moveSet.setMove(benchedIterator, null);
            }
         }

         var var6: java.util.Iterator = this.benchedMoves.iterator();

         while (benchedIterator.hasNext()) {
            if (!LearnsetQuery.Companion.getANY().canLearn((var6.next() as BenchedMove).getMoveTemplate(), newForm.getMoves())) {
               var6.remove();
            }
         }

         this.grantEligibleFormChangeMoves();
         if (CollectionsKt.filterNotNull(this.moveSet as java.lang.Iterable).isEmpty()) {
            var var8: BenchedMove = CollectionsKt.firstOrNull(this.benchedMoves as java.lang.Iterable) as BenchedMove;
            if (var8 != null) {
               this.moveSet.setMove(0, new Move(var8.getMoveTemplate(), var8.getPpRaisedStages(), 0, 4, null));
            }
         }
      }
   }

   private fun grantEligibleFormChangeMoves() {
      if (!this.isClient) {
         var `$this$forEach$iv`: java.lang.Iterable;
         for (Object element$iv : $this$forEach$iv) {
            var move: MoveTemplate = `element$iv` as MoveTemplate;
            var requiredLevel: java.lang.Iterable = CollectionsKt.filterNotNull(this.moveSet as java.lang.Iterable);
            var var10000: Boolean;
            if (requiredLevel is java.util.Collection && (requiredLevel as java.util.Collection).isEmpty()) {
               var10000 = true;
            } else {
               var `$this$firstOrNull$iv`: java.util.Iterator = requiredLevel.iterator();

               while (true) {
                  if (!`$this$firstOrNull$iv`.hasNext()) {
                     var10000 = true;
                     break;
                  }

                  if ((`$this$firstOrNull$iv`.next() as Move).getTemplate() == move) {
                     var10000 = false;
                     break;
                  }
               }
            }

            if (var10000) {
               requiredLevel = this.benchedMoves as java.lang.Iterable;
               if (this.benchedMoves as java.lang.Iterable is java.util.Collection
                  && ((this.benchedMoves as java.lang.Iterable) as java.util.Collection).isEmpty()) {
                  var10000 = true;
               } else {
                  var var18: java.util.Iterator = requiredLevel.iterator();

                  while (true) {
                     if (!var18.hasNext()) {
                        var10000 = true;
                        break;
                     }

                     if ((var18.next() as BenchedMove).getMoveTemplate() == move) {
                        var10000 = false;
                        break;
                     }
                  }
               }

               if (var10000) {
                  var var23: java.util.Iterator = this.form.getMoves().getLevelUpMoves().entrySet().iterator();

                  while (true) {
                     if (!var23.hasNext()) {
                        var27 = null;
                        break;
                     }

                     var `element$ivx`: Any = var23.next();
                     if (((`element$ivx` as Entry).getValue() as java.util.List).contains(move)) {
                        var27 = `element$ivx`;
                        break;
                     }
                  }

                  var var16: Int = if (var27 as Entry != null) (var27 as Entry).getKey() as Integer else null;
                  if (var16 == null || this.level >= var16) {
                     if (this.moveSet.hasSpace()) {
                        this.moveSet.add(move.create());
                     } else {
                        this.benchedMoves.add(new BenchedMove(move, 0));
                     }
                  }
               }
            }
         }
      }
   }

   @JvmStatic
   fun `_set_species_$lambda$1`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_form_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `ivs$lambda$0$0`(`this$0`: Pokemon, it: PokemonStats): Unit {
      `this$0`.onChange((new IVsUpdatePacket(Pokemon::ivs$lambda$0$0$0, it as IVs)) as PokemonUpdatePacket<?>);
      var var10001: com.cobblemon.mod.common.pokemon.Characteristic.Companion = Characteristic.Companion;
      var var10002: IVs = it as IVs;
      var var10003: UUID = `this$0`.uuid;
      `this$0`.characteristic = var10001.calculate(var10002, var10003);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `ivs$lambda$0$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_ivs_$lambda$0`(it: PokemonStats): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `evs$lambda$0$0`(`this$0`: Pokemon, it: PokemonStats): Unit {
      `this$0`.onChange((new EVsUpdatePacket(Pokemon::evs$lambda$0$0$0, it as EVs)) as PokemonUpdatePacket<?>);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `evs$lambda$0$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_evs_$lambda$0`(it: PokemonStats): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `_set_isAlpha_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_nickname_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_currentHealth_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_gender_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_status_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_experience_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_friendship_$lambda$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_currentFullness_$lambda$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_rideStamina_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_state_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_teraType_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_dmaxLevel_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_gmaxFactor_$lambda$3`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_shiny_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_tradeable_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_nature_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_mintedNature_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `moveSet$lambda$0$0`(`this$0`: Pokemon, it: MoveSet): Unit {
      `this$0`.onChange((new MoveSetUpdatePacket(Pokemon::moveSet$lambda$0$0$0, it)) as PokemonUpdatePacket<?>);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `moveSet$lambda$0$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_tetheringId_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_originalTrainerName_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `benchedMoves$lambda$0$0`(`this$0`: Pokemon, it: BenchedMoves): Unit {
      `this$0`.onChange((new BenchedMovesUpdatePacket(Pokemon::benchedMoves$lambda$0$0$0, it)) as PokemonUpdatePacket<?>);
      var var10000: UUID = `this$0`.getOwnerUUID();
      if (var10000 != null) {
         var var4: TMMoveManager = Cobblemon.INSTANCE.getPlayerDataManager().getTMData(var10000);
         if (var4 != null) {
            var4.syncTMsFromPokemon(`this$0`);
         }
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `benchedMoves$lambda$0$0$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_benchedMoves_$lambda$0`(it: BenchedMoves): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `_set_ability_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_scaleModifier_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_caughtBall_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_cosmeticItem_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_activeMark_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_marks_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_potentialMarks_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_markings_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_set_aspects_$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `_init_$lambda$0`(`this$0`: Pokemon, it: StoreCoordinates): Unit {
      if (it != null && it.getStore() !is PCStore && `this$0`.tetheringId != null) {
         SchedulingFunctionsKt.afterOnServer(0.05F, Pokemon::lambda$0$0);
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `lambda$0$0`(`this$0`: Pokemon): Unit {
      `this$0`.setTetheringId(null);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `_init_$lambda$1`(`this$0`: Pokemon, it: StoreCoordinates): Unit {
      if (it != null) {
         var var10000: PokemonStore = it.getStore();
         if (var10000 != null && var10000.getObservingPlayers() != null) {
            var `$this$forEach$iv`: java.lang.Iterable;
            for (Object element$iv : $this$forEach$iv) {
               var itx: ServerPlayer = `element$iv` as ServerPlayer;
               var `$this$iv`: EventObservable = CobblemonEvents.POKEMON_GAINED;
               var `events$iv`: Array<PokemonGainedEvent> = new PokemonGainedEvent[1];
               var var10004: UUID = itx.getUUID();
               `events$iv`[0] = new PokemonGainedEvent(var10004, `this$0`);
               `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

               for (Object element$iv$iv : events$iv) {
                  ;
               }
            }
         }
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOut$lambda$0`(it: PokemonEntity): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$0`(it: PokemonEntity): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$1`(
      `this$0`: Pokemon,
      `$level`: ServerLevel,
      `$position`: Vec3,
      `$illusion`: IllusionEffect,
      `$battleId`: UUID,
      `$source`: LivingEntity,
      `$mutation`: Function1,
      `$future`: CompletableFuture,
      `$doCry`: Boolean,
      it: Unit
   ): PokemonEntity {
      return `this$0`.sendOut(`$level`, `$position`, `$illusion`, Pokemon::sendOutWithAnimation$lambda$1$0);
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$1$0`(
      `this$0`: Pokemon,
      `$battleId`: UUID,
      `$position`: Vec3,
      `$level`: ServerLevel,
      `$source`: LivingEntity,
      `$mutation`: Function1,
      `$future`: CompletableFuture,
      `$doCry`: Boolean,
      `$illusion`: IllusionEffect,
      it: PokemonEntity
   ): Unit {
      var owner: LivingEntity = `this$0`.getOwnerEntity();
      if (owner != null) {
         owner.swing(InteractionHand.MAIN_HAND, true);
         var var29: Double;
         if (`$battleId` == null) {
            var var48: Vec3 = `$position`.subtract(owner.position());
            var29 = Math.atan2(var48.z, var48.x) * 180.0 / Math.PI + 102.5;
         } else {
            var battle: PokemonBattle;
            var var35: ActiveBattlePokemon;
            label128: {
               Cobblemon.INSTANCE.getBattleRegistry();
               battle = BattleRegistry.getBattle(`$battleId`);
               if (battle != null) {
                  var var10000: java.lang.Iterable = battle.getActivePokemon();
                  if (var10000 != null) {
                     var var19: java.util.Iterator = var10000.iterator();

                     while (true) {
                        if (!var19.hasNext()) {
                           var39 = null;
                           break;
                        }

                        var `element$iv`: Any;
                        label119: {
                           `element$iv` = var19.next();
                           var var36: BattlePokemon = (`element$iv` as ActiveBattlePokemon).getBattlePokemon();
                           if (var36 != null) {
                              var var37: Pokemon = var36.getOriginalPokemon();
                              if (var37 != null) {
                                 var38 = var37.uuid;
                                 break label119;
                              }
                           }

                           var38 = null;
                        }

                        if (var38 == it.getPokemon().uuid) {
                           var39 = `element$iv`;
                           break;
                        }
                     }

                     var35 = var39 as ActiveBattlePokemon;
                     break label128;
                  }
               }

               var35 = null;
            }

            label109: {
               var opposingActiveBattlePokemon: ActiveBattlePokemon = (if (var35 != null) var35.getOppositeOpponent() else null) as ActiveBattlePokemon;
               if (opposingActiveBattlePokemon != null) {
                  var var40: BattlePokemon = opposingActiveBattlePokemon.getBattlePokemon();
                  if (var40 != null) {
                     var var41: PokemonEntity = var40.getEntity();
                     if (var41 != null) {
                        var42 = var41.position();
                        break label109;
                     }
                  }
               }

               var42 = null;
            }

            var var30: Vec3 = var42;
            if (var42 == null) {
               label100:
               if (battle != null && battle.getActors() != null) {
                  var var33: java.lang.Iterable;
                  for (Object element$ivx : var33) {
                     label94: {
                        var battleActor: BattleActor = `element$ivx` as BattleActor;
                        if (`element$ivx` as BattleActor is EntityBackedBattleActor
                           && ((`element$ivx` as BattleActor) as EntityBackedBattleActor).getEntity() != null) {
                           var var45: LivingEntity = (battleActor as EntityBackedBattleActor).getEntity();
                           if ((if (var45 != null) (var45 as Entity).getUUID() else null) != owner.getUUID()) {
                              var46 = true;
                              break label94;
                           }
                        }

                        var46 = false;
                     }

                     if (var46) {
                        var44 = `element$ivx` as BattleActor;
                        break label100;
                     }
                  }

                  throw new NoSuchElementException("Collection contains no element matching the predicate.");
               } else {
                  var44 = null;
               }

               var var31: EntityBackedBattleActor = var44 as EntityBackedBattleActor;
               if (var35 != null) {
                  var30 = var35.getSendOutPosition();
               }

               if (var30 == null) {
                  var30 = var31.getInitialPos();
               }
            }

            label84: {
               if (var30 != null) {
                  var47 = var30.subtract(it.position());
                  if (var47 != null) {
                     break label84;
                  }
               }

               var47 = `$position`.subtract(owner.position());
            }

            var29 = Math.atan2(var47.z, var47.x) * 180.0 / Math.PI - 90.0;
         }

         if (!(Math.abs(var29) <= java.lang.Double.MAX_VALUE)) {
            var29 = 0.0;
         }

         it.getEntityData().set(PokemonEntity.Companion.getSPAWN_DIRECTION(), Mth.wrapDegrees((float)var29));
      }

      if (owner != null) {
         var var49: Level = `$level` as Level;
         var var10001: Vec3 = owner.position();
         WorldExtensionsKt.playSoundServer$default(var49, var10001, CobblemonSounds.POKE_BALL_THROW, null, 0.6F, 0.0F, 20, null);
      }

      it.setOwnerUUID(`this$0`.getOwnerUUID());
      it.setPhasingTargetId(`$source`.getId());
      it.setBeamMode(1);
      it.setBattleId(`$battleId`);
      if (it.getBattleId() == null) {
         it.setPlatform(PlatformType.NONE);
      }

      it.after(0.5F, Pokemon::sendOutWithAnimation$lambda$1$0$2);
      it.after(1.5F, Pokemon::sendOutWithAnimation$lambda$1$0$3);
      `$mutation`.invoke(it);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$1$0$2`(`$it`: PokemonEntity): Unit {
      `$it`.setPhasingTargetId(-1);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$1$0$3`(
      `$it`: PokemonEntity,
      `$future`: CompletableFuture,
      `this$0`: Pokemon,
      `$level`: ServerLevel,
      `$position`: Vec3,
      `$doCry`: Boolean,
      `$illusion`: IllusionEffect
   ): Unit {
      if (`$it`.getBeamMode() == 3) {
         `$future`.complete(`$it`);
         return Unit.INSTANCE;
      } else {
         `$it`.setPhasingTargetId(-1);
         `$it`.setBeamMode(0);
         `$future`.complete(`$it`);
         var `$this$iv`: EventObservable = CobblemonEvents.POKEMON_SENT_POST;
         var `events$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post[]{
            new com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post(`this$0`, `$level`, `$position`, `$it`)
         };
         `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

         for (Object element$iv$iv : events$iv) {
            ;
         }

         if (`$doCry`) {
            `$it`.cry();
         }

         if (`$illusion` != null) {
            if (`$illusion`.getMock().getShiny() == true) {
               var var10000: NetworkPacket = (
                  new SpawnSnowstormEntityParticlePacket(
                     MiscUtilsKt.cobblemonResource("shiny_ring"),
                     `$it`.getId(),
                     CollectionsKt.listOf(new java.lang.String[]{"shiny_particles", "middle"}),
                     null,
                     null,
                     24,
                     null
                  )
               ) as NetworkPacket;
               var var10001: Double = `$it`.getX();
               var var21: Double = `$it`.getY();
               var var24: Double = `$it`.getZ();
               var var10005: ResourceKey = `$it`.level().dimension();
               NetworkPacket.sendToPlayersAround$default(var10000, var10001, var21, var24, 64.0, var10005, null, 32, null);
            }
         } else if (`this$0`.shiny) {
            var var19: NetworkPacket = (
               new SpawnSnowstormEntityParticlePacket(
                  MiscUtilsKt.cobblemonResource("shiny_ring"),
                  `$it`.getId(),
                  CollectionsKt.listOf(new java.lang.String[]{"shiny_particles", "middle"}),
                  null,
                  null,
                  24,
                  null
               )
            ) as NetworkPacket;
            var var20: Double = `$it`.getX();
            var var23: Double = `$it`.getY();
            var var26: Double = `$it`.getZ();
            var var27: ResourceKey = `$it`.level().dimension();
            NetworkPacket.sendToPlayersAround$default(var19, var20, var23, var26, 64.0, var27, null, 32, null);
         }

         return Unit.INSTANCE;
      }
   }

   @JvmStatic
   fun `sendOutWithAnimation$lambda$2`(`$tmp0`: Function1, p0: Any): PokemonEntity {
      return `$tmp0`.invoke(p0) as PokemonEntity;
   }

   @JvmStatic
   fun `sendOutFromShoulder$lambda$0`(it: PokemonEntity): Unit {
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutFromShoulder$lambda$1`(
      `$level`: ServerLevel,
      `$currentPosition`: Vec3,
      `$targetPosition`: Vec3,
      `$battleId`: UUID,
      `$mutation`: Function1,
      `$future`: CompletableFuture,
      `this$0`: Pokemon,
      `$doCry`: Boolean,
      it: PokemonEntity
   ): Unit {
      var var10000: Level = `$level` as Level;
      WorldExtensionsKt.playSoundServer$default(var10000, `$currentPosition`, CobblemonSounds.PC_DROP, null, 0.6F, 0.0F, 20, null);
      it.getMoveControl().setWantedPosition(`$targetPosition`.x, `$targetPosition`.y, `$targetPosition`.z, 1.2);
      it.setBattleId(`$battleId`);
      SchedulingFunctionsKt.afterOnServer(1.5F, Pokemon::sendOutFromShoulder$lambda$1$0);
      `$mutation`.invoke(it);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `sendOutFromShoulder$lambda$1$0`(
      `$future`: CompletableFuture, `$it`: PokemonEntity, `this$0`: Pokemon, `$level`: ServerLevel, `$currentPosition`: Vec3, `$doCry`: Boolean
   ): Unit {
      `$future`.complete(`$it`);
      var `$this$iv`: EventObservable = CobblemonEvents.POKEMON_SENT_POST;
      var `events$iv`: Array<com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post> = new com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post[1];
      `events$iv`[0] = new com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent.Post(`this$0`, `$level`, `$currentPosition`, `$it`);
      `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

      for (Object element$iv$iv : events$iv) {
         ;
      }

      if (`$doCry`) {
         `$it`.cry();
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `applyStatus$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `swapHeldItem$lambda$1`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `addPotentialMark$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `exchangeMark$lambda$2`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `exchangeMark$lambda$3`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `loadFrom$lambda$0`(`this$0`: Pokemon, it: com.mojang.datafixers.util.Pair): Unit {
      var var10001: Any = it.getFirst();
      `this$0`.copyFrom(var10001 as Pokemon);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `loadFrom$lambda$1`(`$tmp0`: Function1, p0: Any) {
      `$tmp0`.invoke(p0);
   }

   @JvmStatic
   fun `copyFrom$lambda$0`(`$other`: Pokemon, `this$0`: Pokemon): Unit {
      var `$this$forEach$iv`: java.lang.Iterable;
      for (Object element$iv : $this$forEach$iv) {
         `this$0`.ivs.set((`element$iv` as Entry).getKey() as Stat, ((`element$iv` as Entry).getValue() as java.lang.Number).intValue());
      }

      for (Entry element$iv : $other.ivs.getHyperTrainedIVs().entrySet()) {
         `this$0`.ivs.setHyperTrainedIV(var13.getKey() as Stat, (var13.getValue() as java.lang.Number).intValue());
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `copyFrom$lambda$1`(`$other`: Pokemon, `this$0`: Pokemon): Unit {
      var `$this$forEach$iv`: java.lang.Iterable;
      for (Object element$iv : $this$forEach$iv) {
         `this$0`.evs.set((`element$iv` as Entry).getKey() as Stat, ((`element$iv` as Entry).getValue() as java.lang.Number).intValue());
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `validateMoveset$lambda$0`(`this$0`: Pokemon, `$query`: LearnsetQuery): Unit {
      `this$0`.benchedMoves.doWithoutEmitting(Pokemon::validateMoveset$lambda$0$0);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `validateMoveset$lambda$0$0`(`this$0`: Pokemon, `$query`: LearnsetQuery): Unit {
      for (int i = 0; i < 4; i++) {
         var benchedMove: Move = `this$0`.moveSet.get(benchedIterator);
         if (benchedMove != null && !`$query`.canLearn(benchedMove.getTemplate(), `this$0`.form.getMoves())) {
            `this$0`.moveSet.setMove(benchedIterator, null);
         }
      }

      var var4: java.util.Iterator = `this$0`.benchedMoves.iterator();

      while (benchedIterator.hasNext()) {
         if (!`$query`.canLearn((var4.next() as BenchedMove).getMoveTemplate(), `this$0`.form.getMoves())) {
            var4.remove();
         }
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `initializeMoveset$lambda$0`(`this$0`: Pokemon, `$possibleMoves`: java.util.List, `$preferLatest`: Boolean): Unit {
      `this$0`.moveSet.clear();
      if (`$possibleMoves`.isEmpty()) {
         `this$0`.moveSet.add(Moves.getExceptional().create());
         return Unit.INSTANCE;
      } else {
         var selector: Function0 = Pokemon::initializeMoveset$lambda$0$0;

         for (int i = 0; i < 4; i++) {
            var var10000: MoveTemplate = selector.invoke() as MoveTemplate;
            if (var10000 == null) {
               break;
            }

            `this$0`.moveSet.setMove(i, var10000.create());
         }

         return Unit.INSTANCE;
      }
   }

   @JvmStatic
   fun `initializeMoveset$lambda$0$0`(`$preferLatest`: Boolean, `$possibleMoves`: java.util.List): MoveTemplate {
      var var10000: MoveTemplate;
      if (`$preferLatest`) {
         var10000 = CollectionsKt.removeLastOrNull(`$possibleMoves`) as MoveTemplate;
      } else {
         var random: MoveTemplate = CollectionsKt.randomOrNull(`$possibleMoves`, Random.Default as Random) as MoveTemplate;
         if (random != null) {
            `$possibleMoves`.remove(random);
         }

         var10000 = random;
      }

      return var10000;
   }

   @JvmStatic
   fun `unlearnMove$lambda$0`(`this$0`: Pokemon, `$move`: MoveTemplate): Unit {
      for (int i = 0; i < 4; i++) {
         var currentMove: Move = `this$0`.moveSet.get(i);
         if (currentMove != null && currentMove.getTemplate() == `$move`) {
            `this$0`.moveSet.setMove(i, null);
         }
      }

      `this$0`.benchedMoves.doWithoutEmitting(Pokemon::unlearnMove$lambda$0$0);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `unlearnMove$lambda$0$0`(`this$0`: Pokemon, `$move`: MoveTemplate): Unit {
      var benchedIterator: java.util.Iterator = `this$0`.benchedMoves.iterator();

      while (benchedIterator.hasNext()) {
         if ((benchedIterator.next() as BenchedMove).getMoveTemplate() == `$move`) {
            benchedIterator.remove();
         }
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `addRideBoost$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `addRideBoosts$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `setRideBoost$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `setRideBoosts$lambda$1`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `exchangeMove$lambda$4`(`this$0`: Pokemon, `$newMove`: MoveTemplate, `$currentMove`: Move): Unit {
      `this$0`.benchedMoves.remove(`$newMove`);
      `this$0`.benchedMoves.add(new BenchedMove(`$currentMove`.getTemplate(), `$currentMove`.getRaisedPpStages()));
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `markFeatureDirty$lambda$0`(`this$0`: Pokemon): Pokemon {
      return `this$0`;
   }

   @JvmStatic
   fun `ROOT_CODEC$lambda$0`(instance: Instance): App {
      return instance.group(
            PokemonP1.Companion.getCODEC$common().forGetter(PokemonP1.Companion::from$common) as App,
            PokemonP2.Companion.getCODEC$common().forGetter(PokemonP2.Companion::from$common) as App,
            PokemonP3.Companion.getCODEC$common().forGetter(PokemonP3.Companion::from$common) as App,
            PokemonP4.Companion.getCODEC$common().forGetter(PokemonP4.Companion::from$common) as App
         )
         .apply(instance as Applicative, Pokemon::ROOT_CODEC$lambda$0$0);
   }

   @JvmStatic
   fun `ROOT_CODEC$lambda$0$0`(p1: PokemonP1, p2: PokemonP2, p3: PokemonP3, p4: PokemonP4): Pokemon {
      var pokemon: Pokemon = new Pokemon();
      pokemon.isClient = false;
      p1.into(pokemon);
      p2.into(pokemon);
      p3.into(pokemon);
      p4.into(pokemon);
      return pokemon.initialize();
   }

   @JvmStatic
   fun `CLIENT_CODEC$lambda$0`(instance: Instance): App {
      return instance.group(
            ClientPokemonP1.Companion.getCODEC$common().forGetter(ClientPokemonP1.Companion::from$common) as App,
            ClientPokemonP2.Companion.getCODEC$common().forGetter(ClientPokemonP2.Companion::from$common) as App,
            ClientPokemonP3.Companion.getCODEC$common().forGetter(ClientPokemonP3.Companion::from$common) as App,
            ClientPokemonP4.Companion.getCODEC$common().forGetter(ClientPokemonP4.Companion::from$common) as App
         )
         .apply(instance as Applicative, Pokemon::CLIENT_CODEC$lambda$0$0);
   }

   @JvmStatic
   fun `CLIENT_CODEC$lambda$0$0`(p1: ClientPokemonP1, p2: ClientPokemonP2, p3: ClientPokemonP3, p4: ClientPokemonP4): Pokemon {
      var pokemon: Pokemon = new Pokemon();
      pokemon.isClient = true;
      p1.into(pokemon);
      p2.into(pokemon);
      p3.into(pokemon);
      p4.into(pokemon);
      return pokemon.initialize();
   }

   @JvmStatic
   fun getCODEC(): Codec<Pokemon> {
      return Companion.getCODEC();
   }

   @JvmStatic
   fun getCLIENT_CODEC(): Codec<Pokemon> {
      return Companion.getCLIENT_CODEC();
   }

   @JvmStatic
   fun getS2C_CODEC(): StreamCodec<RegistryFriendlyByteBuf, Pokemon> {
      return Companion.getS2C_CODEC();
   }

   @JvmStatic
   fun {
      var var10000: Codec = RecordCodecBuilder.create(Pokemon::ROOT_CODEC$lambda$0);
      ROOT_CODEC = var10000;
      CODEC = CobblemonSchemas.INSTANCE.wrapCodec(ROOT_CODEC, CobblemonTypeReferences.getPOKEMON());
      var10000 = RecordCodecBuilder.create(Pokemon::CLIENT_CODEC$lambda$0);
      CLIENT_CODEC = var10000;
      var var1: StreamCodec = ByteBufCodecs.fromCodecWithRegistries(CLIENT_CODEC);
      S2C_CODEC = var1;
   }
}
