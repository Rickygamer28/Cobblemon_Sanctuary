package com.cobblemon.mod.common.entity.pokemon

import com.bedrockk.molang.Expression
import com.bedrockk.molang.runtime.MoLangRuntime
import com.bedrockk.molang.runtime.MoParams
import com.bedrockk.molang.runtime.struct.QueryStruct
import com.bedrockk.molang.runtime.struct.VariableStruct
import com.bedrockk.molang.runtime.value.DoubleValue
import com.bedrockk.molang.runtime.value.MoValue
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonBlocks
import com.cobblemon.mod.common.CobblemonCosmeticItems
import com.cobblemon.mod.common.CobblemonItems
import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.CobblemonSounds
import com.cobblemon.mod.common.OrientationControllable
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.conditional.RegistryLikeCondition
import com.cobblemon.mod.common.api.drop.DropTable
import com.cobblemon.mod.common.api.entity.Despawner
import com.cobblemon.mod.common.api.entity.PokemonSender
import com.cobblemon.mod.common.api.entity.PokemonSideDelegate
import com.cobblemon.mod.common.api.entity.pokemon.MocKEffect
import com.cobblemon.mod.common.api.events.Cancelable
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.entity.PokemonEntityLoadEvent
import com.cobblemon.mod.common.api.events.entity.PokemonEntitySaveEvent
import com.cobblemon.mod.common.api.events.entity.PokemonEntitySaveToWorldEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonSeenEvent
import com.cobblemon.mod.common.api.events.pokemon.ShoulderMountEvent
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent.ApplyStamina
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent.Post
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent.Pre
import com.cobblemon.mod.common.api.interaction.InteractionEffect
import com.cobblemon.mod.common.api.interaction.PokemonEntityInteraction
import com.cobblemon.mod.common.api.interaction.PokemonInteraction
import com.cobblemon.mod.common.api.interaction.PokemonInteractions
import com.cobblemon.mod.common.api.mark.Mark
import com.cobblemon.mod.common.api.mark.Marks
import com.cobblemon.mod.common.api.molang.MoLangFunctions
import com.cobblemon.mod.common.api.molang.ObjectValue
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.net.serializers.PlatformTypeDataSerializer
import com.cobblemon.mod.common.api.net.serializers.PoseTypeDataSerializer
import com.cobblemon.mod.common.api.net.serializers.RideBoostsDataSerializer
import com.cobblemon.mod.common.api.net.serializers.StringSetDataSerializer
import com.cobblemon.mod.common.api.npc.configuration.MoLangConfigVariable
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.effect.ShoulderEffect
import com.cobblemon.mod.common.api.pokemon.feature.ChoiceSpeciesFeatureProvider
import com.cobblemon.mod.common.api.pokemon.feature.FlagSpeciesFeature
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeature
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatureProvider
import com.cobblemon.mod.common.api.pokemon.feature.SpeciesFeatures
import com.cobblemon.mod.common.api.pokemon.feature.StringSpeciesFeature
import com.cobblemon.mod.common.api.reactive.CancelableObservable
import com.cobblemon.mod.common.api.reactive.EventObservable
import com.cobblemon.mod.common.api.reactive.ObservableSubscription
import com.cobblemon.mod.common.api.reactive.SimpleObservable
import com.cobblemon.mod.common.api.riding.Rideable
import com.cobblemon.mod.common.api.riding.RidingProperties
import com.cobblemon.mod.common.api.riding.RidingStyle
import com.cobblemon.mod.common.api.riding.Seat
import com.cobblemon.mod.common.api.riding.behaviour.ActiveRidingContext
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviour
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourState
import com.cobblemon.mod.common.api.riding.behaviour.RidingController
import com.cobblemon.mod.common.api.riding.behaviour.SidedRidingState
import com.cobblemon.mod.common.api.riding.events.SelectDriverEvent
import com.cobblemon.mod.common.api.riding.sound.RideSoundManager
import com.cobblemon.mod.common.api.riding.stats.RidingStat
import com.cobblemon.mod.common.api.riding.util.RidingAnimationData
import com.cobblemon.mod.common.api.scheduling.Schedulable
import com.cobblemon.mod.common.api.scheduling.SchedulingFunctionsKt
import com.cobblemon.mod.common.api.scheduling.SchedulingTracker
import com.cobblemon.mod.common.api.spawning.SpawnCause
import com.cobblemon.mod.common.api.storage.PokemonStoreManager
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.api.storage.pc.PCStore
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.battles.BagItems
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity.Tethering
import com.cobblemon.mod.common.client.MountedCameraTypeHandler
import com.cobblemon.mod.common.client.entity.PokemonClientDelegate
import com.cobblemon.mod.common.entity.BehaviourEditingTracker
import com.cobblemon.mod.common.entity.EntityCallbacks
import com.cobblemon.mod.common.entity.MoLangScriptingEntity
import com.cobblemon.mod.common.entity.OmniPathingEntity
import com.cobblemon.mod.common.entity.PlatformType
import com.cobblemon.mod.common.entity.PosableEntity
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.entity.ai.OmniPathNavigation
import com.cobblemon.mod.common.entity.generic.GenericBedrockEntity
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity.Companion
import com.cobblemon.mod.common.entity.pokemon.ai.PokemonMoveControl
import com.cobblemon.mod.common.entity.pokemon.effects.EffectTracker
import com.cobblemon.mod.common.entity.pokemon.effects.IllusionEffect
import com.cobblemon.mod.common.item.battle.BagItemLike
import com.cobblemon.mod.common.net.messages.client.OpenBehaviourEditorPacket
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket
import com.cobblemon.mod.common.net.messages.client.sound.UnvalidatedPlaySoundS2CPacket
import com.cobblemon.mod.common.net.messages.client.spawn.SpawnPokemonPacket
import com.cobblemon.mod.common.net.messages.client.ui.InteractPokemonUIPacket
import com.cobblemon.mod.common.net.messages.server.behaviour.DamageOnCollisionPacket
import com.cobblemon.mod.common.net.messages.server.riding.DismountPokemonPacket
import com.cobblemon.mod.common.pokeball.PokeBall
import com.cobblemon.mod.common.pokedex.scanner.PokedexEntityData
import com.cobblemon.mod.common.pokedex.scanner.ScannableEntity
import com.cobblemon.mod.common.pokedex.scanner.PokedexEntityData.DisguiseData
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.activestate.ActivePokemonState
import com.cobblemon.mod.common.pokemon.activestate.InactivePokemonState
import com.cobblemon.mod.common.pokemon.activestate.PokemonState
import com.cobblemon.mod.common.pokemon.activestate.SentOutState
import com.cobblemon.mod.common.pokemon.activestate.ShoulderedState
import com.cobblemon.mod.common.pokemon.ai.FormPokemonBehaviour
import com.cobblemon.mod.common.pokemon.ai.ObtainableItem
import com.cobblemon.mod.common.pokemon.ai.PokemonBrain
import com.cobblemon.mod.common.pokemon.ai.RestBehaviour
import com.cobblemon.mod.common.pokemon.ai.ToleratedHerdLeader
import com.cobblemon.mod.common.pokemon.ai.ThunderstruckBehaviour.RotateFeature
import com.cobblemon.mod.common.pokemon.cosmetic.CosmeticItemDefinition
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution.ItemInteractionContext
import com.cobblemon.mod.common.pokemon.feature.SlowpokeTailRegrowthSpeciesFeature
import com.cobblemon.mod.common.pokemon.feature.SlowpokeTailRegrowthSpeciesFeatureProvider
import com.cobblemon.mod.common.pokemon.feature.StashHandler
import com.cobblemon.mod.common.util.BitUtilitiesKt
import com.cobblemon.mod.common.util.BrainExtensionsKt
import com.cobblemon.mod.common.util.CollectionUtilsKt
import com.cobblemon.mod.common.util.CompoundTagExtensionsKt
import com.cobblemon.mod.common.util.EntityExtensionsKt
import com.cobblemon.mod.common.util.LocalizationUtilsKt
import com.cobblemon.mod.common.util.MiscUtilsKt
import com.cobblemon.mod.common.util.MoLangExtensionsKt
import com.cobblemon.mod.common.util.PlayerExtensionsKt
import com.cobblemon.mod.common.util.PositionType
import com.cobblemon.mod.common.util.WorldExtensionsKt
import com.cobblemon.mod.common.util.math.geometry.AngleExtensionsKt
import com.cobblemon.mod.common.world.gamerules.CobblemonGameRules
import com.google.common.collect.UnmodifiableIterator
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import com.mojang.serialization.Dynamic
import com.mojang.serialization.DynamicOps
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.Map.Entry
import java.util.concurrent.CompletableFuture
import kotlin.jvm.functions.Function1
import kotlin.jvm.functions.Function3
import kotlin.jvm.internal.SourceDebugExtension
import kotlin.jvm.internal.Ref.ObjectRef
import net.minecraft.core.BlockPos
import net.minecraft.core.DefaultedRegistry
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.Vec3i
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.HolderLookup.Provider
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.DebugPackets
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.network.syncher.SynchedEntityData.Builder
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.Shearable
import net.minecraft.world.entity.Entity.MoveFunction
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.entity.ai.Brain
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.sensing.SensorType
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.ShoulderRidingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.SuspiciousEffectHolder
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.EmptyFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

@SourceDebugExtension(["SMAP\nPokemonEntity.kt\nKotlin\n*S Kotlin\n*F\n+ 1 PokemonEntity.kt\ncom/cobblemon/mod/common/entity/pokemon/PokemonEntity\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n+ 4 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/EventObservable\n+ 5 _Arrays.kt\nkotlin/collections/ArraysKt___ArraysKt\n+ 6 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/EventObservable$post$1\n+ 7 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/CancelableObservable\n+ 8 EventObservables.kt\ncom/cobblemon/mod/common/api/reactive/CancelableObservable$postThen$1\n*L\n1#1,2748:1\n947#1:2958\n1#2:2749\n1#2:2929\n774#3:2750\n865#3,2:2751\n774#3:2753\n865#3,2:2754\n1869#3,2:2756\n295#3,2:2758\n808#3,11:2782\n774#3:2793\n865#3,2:2794\n1761#3,3:2796\n1761#3,3:2799\n1761#3,3:2802\n1761#3,3:2805\n1761#3,3:2808\n1761#3,3:2811\n865#3,2:2828\n808#3,11:2830\n1374#3:2841\n1460#3,5:2842\n1869#3,2:2849\n808#3,11:2851\n774#3:2862\n865#3,2:2863\n1869#3,2:2865\n774#3:2867\n865#3,2:2868\n1563#3:2870\n1634#3,3:2871\n1563#3:2906\n1634#3,3:2907\n1869#3,2:2910\n1869#3,2:2912\n295#3,2:2914\n1761#3,3:2916\n1617#3,9:2919\n1869#3:2928\n1870#3:2930\n1626#3:2931\n774#3:2932\n865#3,2:2933\n1869#3,2:2935\n295#3,2:2942\n808#3,11:2944\n1761#3,3:2955\n1869#3,2:2959\n15#4,5:2760\n20#4:2768\n18#4,2:2770\n20#4:2780\n18#4,2:2816\n20#4:2826\n15#4,5:2874\n20#4:2882\n15#4,5:2883\n20#4:2891\n18#4,2:2894\n20#4:2904\n18#4,2:2937\n20#4:2941\n15#4,5:2961\n20#4:2969\n13805#5:2765\n13806#5:2767\n13805#5:2772\n13806#5:2779\n13805#5:2818\n13806#5:2825\n19985#5,2:2847\n13805#5:2879\n13806#5:2881\n13805#5:2888\n13806#5:2890\n13805#5:2896\n13806#5:2903\n13805#5,2:2939\n13805#5:2966\n13806#5:2968\n15#6:2766\n15#6:2880\n15#6:2889\n15#6:2967\n41#7:2769\n42#7,6:2773\n48#7:2781\n40#7,2:2814\n42#7,2:2819\n45#7,3:2822\n48#7:2827\n40#7,2:2892\n42#7,2:2897\n45#7,3:2900\n48#7:2905\n40#8:2821\n40#8:2899\n*S KotlinDebug\n*F\n+ 1 PokemonEntity.kt\ncom/cobblemon/mod/common/entity/pokemon/PokemonEntity\n*L\n947#1:2958\n2285#1:2929\n293#1:2750\n293#1:2751,2\n569#1:2753\n569#1:2754,2\n570#1:2756,2\n574#1:2758,2\n1106#1:2782,11\n1109#1:2793\n1109#1:2794,2\n1158#1:2796,3\n1159#1:2799,3\n1160#1:2802,3\n1195#1:2805,3\n1198#1:2808,3\n1235#1:2811,3\n1392#1:2828,2\n1424#1:2830,11\n1459#1:2841\n1459#1:2842,5\n1477#1:2849,2\n1501#1:2851,11\n1503#1:2862\n1503#1:2863,2\n1503#1:2865,2\n1509#1:2867\n1509#1:2868,2\n1511#1:2870\n1511#1:2871,3\n1682#1:2906\n1682#1:2907,3\n1799#1:2910,2\n1825#1:2912,2\n2112#1:2914,2\n2275#1:2916,3\n2285#1:2919,9\n2285#1:2928\n2285#1:2930\n2285#1:2931\n2286#1:2932\n2286#1:2933,2\n2287#1:2935,2\n2306#1:2942,2\n2437#1:2944,11\n2746#1:2955,3\n1644#1:2959,2\n955#1:2760,5\n955#1:2768\n1057#1:2770,2\n1057#1:2780\n1362#1:2816,2\n1362#1:2826\n1615#1:2874,5\n1615#1:2882\n1618#1:2883,5\n1618#1:2891\n1626#1:2894,2\n1626#1:2904\n2291#1:2937,2\n2291#1:2941\n2113#1:2961,5\n2113#1:2969\n955#1:2765\n955#1:2767\n1057#1:2772\n1057#1:2779\n1362#1:2818\n1362#1:2825\n1463#1:2847,2\n1615#1:2879\n1615#1:2881\n1618#1:2888\n1618#1:2890\n1626#1:2896\n1626#1:2903\n2291#1:2939,2\n2113#1:2966\n2113#1:2968\n955#1:2766\n1615#1:2880\n1618#1:2889\n2113#1:2967\n1057#1:2769\n1057#1:2773,6\n1057#1:2781\n1362#1:2814,2\n1362#1:2819,2\n1362#1:2822,3\n1362#1:2827\n1626#1:2892,2\n1626#1:2897,2\n1626#1:2900,3\n1626#1:2905\n1362#1:2821\n1626#1:2899\n*E\n"])
open class PokemonEntity(world: Level, pokemon: Pokemon, type: EntityType<out PokemonEntity>) : ShoulderRidingEntity(type, world),
   PosableEntity,
   Shearable,
   Schedulable,
   Rideable,
   ScannableEntity,
   MoLangScriptingEntity,
   OmniPathingEntity {
   @JvmStatic
   public Companion Companion = new Companion(null);
   @JvmStatic
   private EntityDataAccessor<java.lang.String> SPECIES = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.STRING);
   @JvmStatic
   private EntityDataAccessor<Component> NICKNAME = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.COMPONENT);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> NICKNAME_VISIBLE = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.String> MARK = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.STRING);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> SHOULD_RENDER_NAME = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> MOVING = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.Byte> BEHAVIOUR_FLAGS = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BYTE);
   @JvmStatic
   private EntityDataAccessor<Integer> PHASING_TARGET_ID = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.INT);
   @JvmStatic
   private EntityDataAccessor<PlatformType> PLATFORM_TYPE = SynchedEntityData.defineId(
      PokemonEntity.class, PlatformTypeDataSerializer.INSTANCE as EntityDataSerializer
   );
   @JvmStatic
   private EntityDataAccessor<java.lang.Byte> BEAM_MODE = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BYTE);
   @JvmStatic
   private EntityDataAccessor<Optional<UUID>> BATTLE_ID = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.OPTIONAL_UUID);
   @JvmStatic
   private EntityDataAccessor<java.util.Set<java.lang.String>> ASPECTS = SynchedEntityData.defineId(
      PokemonEntity.class, StringSetDataSerializer.INSTANCE as EntityDataSerializer
   );
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> DYING_EFFECTS_STARTED = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<PoseType> POSE_TYPE = SynchedEntityData.defineId(PokemonEntity.class, PoseTypeDataSerializer.INSTANCE as EntityDataSerializer);
   @JvmStatic
   private EntityDataAccessor<Integer> LABEL_LEVEL = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.INT);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> HIDE_LABEL = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> UNBATTLEABLE = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> COUNTS_TOWARDS_SPAWN_CAP = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<java.lang.Float> SPAWN_DIRECTION = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.FLOAT);
   @JvmStatic
   private EntityDataAccessor<Integer> FRIENDSHIP = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.INT);
   @JvmStatic
   private EntityDataAccessor<java.lang.Float> FREEZE_FRAME = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.FLOAT);
   @JvmStatic
   private EntityDataAccessor<java.lang.String> CAUGHT_BALL = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.STRING);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> EVOLUTION_STARTED = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   private EntityDataAccessor<ItemStack> SHOWN_HELD_ITEM = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.ITEM_STACK);
   @JvmStatic
   private EntityDataAccessor<java.util.Map<RidingStat, java.lang.Float>> RIDE_BOOSTS = SynchedEntityData.defineId(
      PokemonEntity.class, RideBoostsDataSerializer.INSTANCE as EntityDataSerializer
   );
   @JvmStatic
   private EntityDataAccessor<java.lang.Float> RIDE_STAMINA = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.FLOAT);
   @JvmStatic
   private EntityDataAccessor<java.lang.Float> SCALE_MODIFIER = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.FLOAT);
   @JvmStatic
   private EntityDataAccessor<java.lang.Boolean> IS_ALPHA = SynchedEntityData.defineId(PokemonEntity.class, EntityDataSerializers.BOOLEAN);
   @JvmStatic
   public java.lang.String BATTLE_LOCK = "battle";
   @JvmStatic
   public java.lang.String EVOLUTION_LOCK = "evolving";
   @JvmStatic
   public float FALL_DAMAGE_MULT_FLYINGTYPE = 0.5F;
   @JvmStatic
   public float FALL_DAMAGE_MULT_MOVEMENT_FLY = 0.2F;

   public final val removalObservable: SimpleObservable<RemovalReason?> = new SimpleObservable()
   public final val subscriptions: MutableList<ObservableSubscription<*>> = (new ArrayList()) as java.util.List
   public open val schedulingTracker: SchedulingTracker = new SchedulingTracker()
   public final val form: FormData
      public final get() {
         return this.pokemon.getForm();
      }

   public final val behaviour: FormPokemonBehaviour
      public final get() {
         return this.getForm().getBehaviour();
      }

   private final var brainDynamic: Dynamic<*>?
   public final var pokemon: Pokemon
      public final set(value) {
         value.setClient$common(this.level().isClientSide);
         this.pokemon = value;
         this.getDelegate().changePokemon(value);
         this.refreshRiding();
         this.refreshDimensions();
         if (!this.level().isClientSide) {
            this.remakeBrain();
         }
      }

   public final var despawner: Despawner<PokemonEntity>
   public final var killer: ServerPlayer?
   public final val isEvolving: Boolean
      public final get() {
         var var10000: Any = this.entityData.get(EVOLUTION_STARTED);
         return var10000 as java.lang.Boolean;
      }

   public final var evolutionEntity: GenericBedrockEntity?
   public final var ticksLived: Int
   public final val busyLocks: MutableList<Any>
   public final val isBusy: Boolean
      public final get() {
         return !this.busyLocks.isEmpty();
      }

   public final val aspects: Set<String>
      public final get() {
         var var10000: Any = this.entityData.get(ASPECTS);
         return var10000 as java.util.Set<java.lang.String>;
      }

   public final var battleId: UUID?
      public final get() {
         return (this.entityData.get(BATTLE_ID) as Optional).orElse(null) as UUID;
      }

      public final set(value) {
         this.entityData.set(BATTLE_ID, Optional.ofNullable(value));
      }

   public final val battle: PokemonBattle?
      public final get() {
         var var10000: UUID = this.getBattleId();
         return if (var10000 != null) BattleRegistry.getBattle(var10000) else null;
      }

   public final val isBattling: Boolean
      public final get() {
         return (this.entityData.get(BATTLE_ID) as Optional).isPresent();
      }

   public final val friendship: Int
      public final get() {
         var var10000: Any = this.entityData.get(FRIENDSHIP);
         return (var10000 as java.lang.Number).intValue();
      }

   public final val seats: List<Seat>
      public final get() {
         var `$this$filter$iv`: java.lang.Iterable = this.getForm().getRiding().getSeats();
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filter$iv) {
            var var10000: Expression = (`element$iv$iv` as Seat).getCondition();
            if (var10000 == null || MoLangExtensionsKt.resolveBoolean$default(this.getRuntime(), var10000, null, 2, null)) {
               `destination$iv$iv`.add(`element$iv$iv`);
            }
         }

         return `destination$iv$iv` as java.util.List<Seat>;
      }

   public final val rideProp: RidingProperties
      public final get() {
         return this.getForm().getRiding();
      }

   public final var shownItem: ItemStack
      public final get() {
         var var10000: Any = this.entityData.get(SHOWN_HELD_ITEM);
         return var10000 as ItemStack;
      }

      public final set(value) {
         this.entityData.set(SHOWN_HELD_ITEM, value);
      }

   public final var lastLightningBoltUUID: UUID?
   public final var drops: DropTable?
   public final var tethering: Tethering?
   public final var queuedToDespawn: Boolean
   public final var enablePoseTypeRecalculation: Boolean
   public final val ridingAnimationData: RidingAnimationData
   public final var rideSoundManager: RideSoundManager
   private final val rideStatOverrides: MutableMap<RidingStyle, MutableMap<RidingStat, Double>>
   public final var ridingController: RidingController?
      private set
   public final var previousRidingState: RidingBehaviourState?
      private set
   public final val runtime: MoLangRuntime
      public final get() {
         return this.runtime$delegate.getValue() as MoLangRuntime;
      }

   public final var countsTowardsSpawnCap: Boolean
   public final var beamMode: Int
      public final get() {
         return (this.entityData.get(BEAM_MODE) as java.lang.Number).byteValue();
      }

      public final set(value) {
         this.entityData.set(BEAM_MODE, (byte)value);
      }

   public final var phasingTargetId: Int
      public final get() {
         var var10000: Any = this.entityData.get(PHASING_TARGET_ID);
         return (var10000 as java.lang.Number).intValue();
      }

      public final set(value) {
         this.entityData.set(PHASING_TARGET_ID, value);
      }

   public final var spawnCause: SpawnCause?
   public open val delegate: PokemonSideDelegate
   public final var effects: EffectTracker
   public final val exposedSpecies: Species
      public final get() {
         var var10000: MocKEffect = this.effects.getMockEffect();
         if (var10000 != null) {
            var var1: Species = var10000.getExposedSpecies();
            if (var1 != null) {
               return var1;
            }
         }

         return this.pokemon.getSpecies();
      }

   public final val exposedForm: FormData
      public final get() {
         var var10000: MocKEffect = this.effects.getMockEffect();
         if (var10000 != null) {
            var var1: FormData = var10000.getExposedForm();
            if (var1 != null) {
               return var1;
            }
         }

         return this.pokemon.getForm();
      }

   public final val exposedAspects: Set<String>
      public final get() {
         var var10000: MocKEffect = this.effects.getMockEffect();
         if (var10000 != null) {
            var var1: FormData = var10000.getExposedForm();
            if (var1 != null) {
               var var2: java.util.List = var1.getAspects();
               if (var2 != null) {
                  var var3: java.util.Set = CollectionsKt.toSet(var2);
                  if (var3 != null) {
                     return var3;
                  }
               }
            }
         }

         return this.pokemon.getAspects();
      }

   public final val exposedBall: PokeBall
      public final get() {
         var var10000: MocKEffect = this.effects.getMockEffect();
         if (var10000 != null) {
            var var1: PokeBall = var10000.getExposedBall();
            if (var1 != null) {
               return var1;
            }
         }

         return this.pokemon.getCaughtBall();
      }

   public open var behavioursAreCustom: Boolean
   public open val behaviours: MutableList<ResourceLocation>
   public open val registeredVariables: MutableList<MoLangConfigVariable>
   public open var config: VariableStruct
   public open var data: VariableStruct
   public open var callbacks: EntityCallbacks
   public final var platform: PlatformType
      public final get() {
         var var10000: Any = this.entityData.get(PLATFORM_TYPE);
         return var10000 as PlatformType;
      }

      public final set(value) {
         this.entityData.set(PLATFORM_TYPE, value);
      }

   public open val struct: ObjectValue<PokemonEntity>
   public final var flyDistO: Float
   public final var isPokemonWalking: Boolean
   public final var isPokemonFlying: Boolean
   public final var tickSpawned: Int
   public final var occupiedSeats: MutableMap<Seat, Entity>
   public final var jumpInputStrength: Int

   init {
      this.pokemon = pokemon;
      this.despawner = Cobblemon.INSTANCE.getBestSpawner().getDefaultPokemonDespawner();
      this.busyLocks = new ArrayList<>();
      this.enablePoseTypeRecalculation = true;
      this.ridingAnimationData = new RidingAnimationData(this);
      this.rideSoundManager = new RideSoundManager(this);
      this.rideStatOverrides = new LinkedHashMap<>();
      this.runtime$delegate = LazyKt.lazy(PokemonEntity::runtime_delegate$lambda$0);
      this.countsTowardsSpawnCap = true;
      this.delegate = if (world.isClientSide) (new PokemonClientDelegate()) as PokemonSideDelegate else (new PokemonServerDelegate()) as PokemonSideDelegate;
      this.effects = new EffectTracker(this);
      this.behaviours = new ArrayList<>();
      this.registeredVariables = new ArrayList<>();
      this.config = new VariableStruct();
      this.data = new VariableStruct();
      this.callbacks = new EntityCallbacks(this as Entity);
      var var4: ObjectValue = new ObjectValue(this, null, null, 6, null);
      MoLangFunctions.INSTANCE
         .addPokemonEntityFunctions(
            MoLangFunctions.INSTANCE
               .addPokemonFunctions(
                  MoLangFunctions.INSTANCE
                     .addLivingEntityFunctions(
                        MoLangFunctions.INSTANCE.addEntityFunctions(MoLangFunctions.INSTANCE.addStandardFunctions(var4 as QueryStruct), this as Entity),
                        this as LivingEntity
                     ),
                  pokemon
               ),
            this
         );
      this.struct = var4;
      this.occupiedSeats = new LinkedHashMap<>();
      this.getDelegate().initialize(this as Entity);
      this.getDelegate().changePokemon(pokemon);
      this.addPosableFunctions(this.getStruct() as QueryStruct);
      this.moveControl = (new PokemonMoveControl(this)) as MoveControl;
      if (!this.level().isClientSide) {
         this.remakeBrain();
      }

      this.refreshDimensions();
      this.refreshRiding();
   }

   public fun refreshRiding() {
      var var10000: PokemonEntity = this.pokemon.getEntity();
      if (var10000 != null) {
         var10000.ejectPassengers();
      }

      if (this.pokemon.getForm().getRiding().getBehaviours() != null) {
         var var10004: java.util.Map = this.pokemon.getForm().getRiding().getBehaviours();
         this.ridingController = new RidingController(this, var10004);
      }

      this.occupiedSeats.clear();
   }

   protected open fun defineSynchedData(builder: Builder) {
      super.defineSynchedData(builder);
      builder.define(SPECIES, "");
      builder.define(NICKNAME, Component.empty());
      builder.define(NICKNAME_VISIBLE, true);
      builder.define(MARK, "");
      builder.define(SHOULD_RENDER_NAME, true);
      builder.define(MOVING, false);
      builder.define(BEHAVIOUR_FLAGS, (byte)0);
      builder.define(BEAM_MODE, (byte)0);
      builder.define(PLATFORM_TYPE, PlatformType.NONE);
      builder.define(PHASING_TARGET_ID, -1);
      builder.define(BATTLE_ID, Optional.empty());
      builder.define(ASPECTS, SetsKt.emptySet());
      builder.define(DYING_EFFECTS_STARTED, false);
      builder.define(POSE_TYPE, PoseType.STAND);
      builder.define(LABEL_LEVEL, 1);
      builder.define(HIDE_LABEL, false);
      builder.define(UNBATTLEABLE, false);
      builder.define(SPAWN_DIRECTION, (float)this.random.nextIntBetweenInclusive(-180000, 180000) / 1000.0F);
      builder.define(COUNTS_TOWARDS_SPAWN_CAP, true);
      builder.define(FRIENDSHIP, 0);
      builder.define(FREEZE_FRAME, -1.0F);
      builder.define(CAUGHT_BALL, "");
      builder.define(EVOLUTION_STARTED, false);
      builder.define(SHOWN_HELD_ITEM, ItemStack.EMPTY);
      builder.define(RIDE_BOOSTS, MapsKt.emptyMap());
      builder.define(RIDE_STAMINA, 1.0F);
      builder.define(SCALE_MODIFIER, 1.0F);
      builder.define(IS_ALPHA, false);
   }

   public open fun onSyncedDataUpdated(data: EntityDataAccessor<*>) {
      super.onSyncedDataUpdated(data);
      if (this.getDelegate() != null) {
         this.getDelegate().onSyncedDataUpdated(data);
      }

      if (data == SPECIES) {
         this.refreshDimensions();
      } else if (data == POSE_TYPE) {
         var var10000: Any = this.entityData.get(data);
         this.setNoGravity(PoseType.Companion.getNO_GRAV_POSES().contains(var10000 as PoseType) && this.getPassengers().isEmpty());
      } else if (data == BATTLE_ID) {
         if (this.getBattleId() != null) {
            this.busyLocks.remove("battle");
            this.busyLocks.add("battle");
            this.brain.setMemory(CobblemonMemories.POKEMON_BATTLE, this.getBattleId());
         } else {
            this.busyLocks.remove("battle");
            this.brain.eraseMemory(CobblemonMemories.POKEMON_BATTLE);
         }
      } else if (data == EVOLUTION_STARTED) {
         if (this.isEvolving()) {
            this.busyLocks.remove("evolving");
            this.busyLocks.add("evolving");
         } else {
            this.busyLocks.remove("evolving");
         }
      } else if (data == SCALE_MODIFIER) {
         this.refreshDimensions();
      }
   }

   public open fun canStandOnFluid(state: FluidState): Boolean {
      if (this.hasRider() && this.getControllingPassenger() != null) {
         return false;
      } else {
         return if (state.is(FluidTags.WATER) && !this.isEyeInFluid(FluidTags.WATER))
            this.getExposedForm().getBehaviour().getMoving().getSwim().getCanWalkOnWater() || this.getPlatform() != PlatformType.NONE
            else
            (
               if (state.is(FluidTags.LAVA) && !this.isEyeInFluid(FluidTags.LAVA))
                  this.getExposedForm().getBehaviour().getMoving().getSwim().getCanWalkOnLava()
                  else
                  super.canStandOnFluid(state)
            );
      }
   }

   public open fun canSprint(): Boolean {
      return true;
   }

   public open fun handleEntityEvent(status: Byte) {
      this.getDelegate().handleStatus(status);
      super.handleEntityEvent(status);
   }

   protected open fun sendDebugPackets() {
      super.sendDebugPackets();
      DebugPackets.sendEntityBrain(this as LivingEntity);
      DebugPackets.sendGoalSelector(this.level(), this as Mob, this.goalSelector);
      var var10000: Level = this.level();
      var var10001: Mob = this as Mob;
      var var10003: Path = this.navigation.getPath();
      DebugPackets.sendPathFindingPacket(var10000, var10001, this.navigation.getPath(), if (var10003 != null) var10003.getDistToTarget() else 0.0F);
   }

   public open fun removePassenger(passenger: Entity) {
      this.occupiedSeats.entrySet().removeIf(PokemonEntity::removePassenger$lambda$1);
      var var2: PokemonSideDelegate = this.getDelegate();
      var var10000: PokemonServerDelegate = var2 as? PokemonServerDelegate;
      if ((var2 as? PokemonServerDelegate) != null) {
         var var3: java.util.Map = var10000.getPassengerOffsets();
         if (var3 != null) {
            var var4: Vec3 = var3.remove(passenger.getId()) as Vec3;
         }
      }

      if (this.level().isClientSide) {
         MountedCameraTypeHandler.INSTANCE.handleDismount(passenger, this);
      }

      super.removePassenger(passenger);
      if (this.getPassengers().isEmpty()) {
         this.ifRidingAvailable(PokemonEntity::removePassenger$lambda$2);
         if (this.ridingController != null) {
            var var5: ActiveRidingContext = this.ridingController.getContext();
            if (var5 != null) {
               var var6: RidingBehaviourState = var5.getState();
               if (var6 != null) {
                  var6.reset();
               }
            }
         }

         this.ridingAnimationData.clear();
         if (this is OrientationControllable) {
            (this as OrientationControllable).getOrientationController().reset();
         }
      }
   }

   public fun recheckSeatConditions() {
      var effectiveSeats: java.util.List = this.getSeats();
      var `$this$forEach$iv`: java.lang.Iterable = this.occupiedSeats.entrySet();
      var `element$iv`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$forEach$iv) {
         if (!effectiveSeats.contains((`element$iv$iv` as Entry).getKey())) {
            `element$iv`.add(`element$iv$iv`);
         }
      }

      for (Object element$ivx : $this$forEach$iv) {
         ((`element$ivx` as Entry).getValue() as Entity).stopRiding();
      }
   }

   public fun getSeatForPassenger(passenger: Entity): Seat? {
      var var4: java.util.Iterator = this.occupiedSeats.entrySet().iterator();

      var var10000: Any;
      while (true) {
         if (var4.hasNext()) {
            var `element$iv`: Any = var4.next();
            if (!((`element$iv` as Entry).getValue() == passenger)) {
               continue;
            }

            var10000 = `element$iv`;
            break;
         }

         var10000 = null;
         break;
      }

      return if (var10000 as Entry != null) (var10000 as Entry).getKey() as Seat else null;
   }

   public open fun thunderHit(level: ServerLevel, lightning: LightningBolt) {
      var isTypeImmune: Boolean;
      var var10000: Boolean;
      label57: {
         isTypeImmune = CollectionsKt.contains(this.pokemon.getTypes(), ElementalTypes.GROUND);
         var rotated: java.lang.String = this.pokemon.getAbility().getName();
         switch (rotated.hashCode()) {
            case 386687541:
               if (rotated.equals("motordrive")) {
                  this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 1));
                  var10000 = true;
                  break label57;
               }
               break;
            case 1509496430:
               if (rotated.equals("voltabsorb")) {
                  this.addEffect(new MobEffectInstance(MobEffects.HEAL, 1, 1));
                  var10000 = true;
                  break label57;
               }
               break;
            case 1551497917:
               if (rotated.equals("lightningrod")) {
                  this.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1200, 1));
                  var10000 = true;
                  break label57;
               }
            default:
         }

         var10000 = false;
      }

      var var12: Boolean = this.lastLightningBoltUUID == lightning.getUUID();
      if (!var12 && this.pokemon.getForm().getBehaviour().getLightningHit().isSpecial()) {
         for (RotateFeature rotateFeature : this.pokemon.getForm().getBehaviour().getLightningHit().getRotateFeatures()) {
            var var13: StringSpeciesFeature = this.pokemon.getFeature(rotateFeature.getKey());
            if (var13 != null) {
               var index: Int = rotateFeature.getChain().indexOf(var13.getValue());
               if (index >= 0) {
                  var13.setValue(rotateFeature.getChain().get(if (index + 1 < rotateFeature.getChain().size()) index + 1 else 0) as java.lang.String);
                  this.pokemon.markFeatureDirty(var13 as SpeciesFeature);
                  var12 = true;
               }
            }
         }

         if (var12) {
            this.lastLightningBoltUUID = lightning.getUUID();
            this.playSound(SoundEvents.MOOSHROOM_CONVERT, 2.0F, 1.0F);
            this.pokemon.updateAspects();
         }
      }

      if (!isTypeImmune && !var10000 && !var12) {
         super.thunderHit(level, lightning);
      }
   }

   public open fun makeStuckInBlock(state: BlockState, motionMultiplier: Vec3) {
      if (!this.pokemon.getSpecies().getBehaviour().getBlockInteract().getImmuneToCobwebBlock() || !state.is(Blocks.COBWEB)) {
         super.makeStuckInBlock(state, motionMultiplier);
      }
   }

   public open fun tick() {
      this.yBodyRot = Mth.wrapDegrees(this.yBodyRot);
      this.yBodyRotO = Mth.wrapDegrees(this.yBodyRotO);
      this.setYRot(Mth.wrapDegrees(this.getYRot()));
      this.yRotO = Mth.wrapDegrees(this.yRotO);
      this.setXRot(Mth.wrapDegrees(this.getXRot()));
      this.xRotO = Mth.wrapDegrees(this.xRotO);
      this.yHeadRot = Mth.wrapDegrees(this.yHeadRot);
      this.yHeadRotO = Mth.wrapDegrees(this.yHeadRotO);
      super.tick();
      this.isPokemonFlying = this.flyDist - this.flyDistO > 0.005F;
      this.isPokemonWalking = this.walkDist - this.walkDistO > 0.005F;
      var var10000: java.util.List = this.getPassengers();
      if (!var10000.isEmpty() && this.level().isClientSide) {
         this.rideSoundManager.tick();
         this.ridingAnimationData.update();
      } else {
         var10000 = this.getPassengers();
         if (var10000.isEmpty() && this.level().isClientSide) {
            this.rideSoundManager.stop();
         }
      }

      this.flyDistO = this.flyDist;
      if (this.ridingController != null) {
         this.ridingController.tick();
      }

      if (!this.level().isClientSide) {
         this.recheckSeatConditions();
      }

      if (this.isBattling()) {
         if (!this.level().isClientSide && this.ticksLived > 5) {
            if (this.getPlatform() === PlatformType.NONE
               && this.getOwnerUUID() != null
               && this.isInWater()
               && !this.isUnderWater()
               && !this.getExposedForm().getBehaviour().getMoving().getSwim().getCanBreatheUnderwater()
               && !this.getExposedForm().getBehaviour().getMoving().getSwim().getCanWalkOnWater()
               && !this.getBehaviourFlag(PokemonBehaviourFlag.FLYING)) {
               this.setPlatform(PlatformType.Companion.getPlatformTypeForPokemon(this.getExposedForm()));
            } else if (this.getPlatform() != PlatformType.NONE && this.onGround()) {
               this.setPlatform(PlatformType.NONE);
            }
         }
      } else if (this.getBeamMode() == 0 && this.isBattleClone()) {
         this.discard();
         return;
      }

      this.setNoActionTime(0);
      if (this.queuedToDespawn) {
         this.remove(RemovalReason.DISCARDED);
      } else {
         if (this.evolutionEntity != null) {
            var var14: GenericBedrockEntity = this.evolutionEntity;
            var var10001: PokemonEntity = this.pokemon.getEntity();
            var var19: Double = var10001.getX();
            var var10002: PokemonEntity = this.pokemon.getEntity();
            var var24: Double = var10002.getY();
            var var10003: PokemonEntity = this.pokemon.getEntity();
            var14.setPos(var19, var24, var10003.getZ());
            var var15: PokemonEntity = this.pokemon.getEntity();
            var15.navigation.stop();
         }

         this.getDelegate().tick(this as Entity);
         var spawnDirection: Int = this.ticksLived++;
         if (this.ticksLived <= 20) {
            this.clearRestriction();
            var ownerUUID: Any = this.entityData.get(SPAWN_DIRECTION);
            var var3: java.lang.Float = ownerUUID as java.lang.Float;
            var var16: java.lang.Float = (if (Math.abs(var3) <= java.lang.Float.MAX_VALUE) ownerUUID else null) as java.lang.Float;
            this.yBodyRot = (float)((int)((var16 ?: 0.0F) * 1000.0F)) / 1000.0F;
         }

         this.jumping = false;
         this.checkPastureTether();
         if (this.tethering != null && this.age % 20 == 0) {
            var var17: UUID = this.getOwnerUUID();
            if (var17 != null) {
               var var11: Player = this.level().getPlayerByUUID(var17);
               var player: ServerPlayer = var11 as? ServerPlayer;
               if ((var11 as? ServerPlayer) != null) {
                  var var18: PCStore = Cobblemon.INSTANCE.getStorage().getPC(player);
                  var var20: UUID = this.pokemon.getUuid();
                  var var12: Pokemon = var18.get(var20);
                  if (var12 != null) {
                     if (var12 != this.pokemon) {
                        this.setPokemon(var12);
                     }
                  }
               }
            }
         }

         var var23: RidingBehaviourState;
         label116: {
            if (this.ridingController != null) {
               var var21: ActiveRidingContext = this.ridingController.getContext();
               if (var21 != null) {
                  var23 = var21.getState();
                  if (var23 != null) {
                     var23 = var23.copy();
                     break label116;
                  }
               }
            }

            var23 = null;
         }

         this.previousRidingState = var23;
         this.getSchedulingTracker().update(0.05F);
      }
   }

   public fun checkPastureTether() {
      if (this.tethering != null) {
         var var10000: Tethering = this.tethering;
         if (!var10000.getBox().contains(this.getX(), this.getY(), this.getZ())) {
            var10000 = this.tethering;
            var pastureBlockPos: BlockPos = var10000.getPasturePos();
            var state: BlockState = this.level().getBlockState(pastureBlockPos);
            if (state.getBlock() == CobblemonBlocks.PASTURE) {
               var tpPos: Vec3 = pastureBlockPos.offset(
                     (state.getValue(HorizontalDirectionalBlock.FACING as Property) as Direction)
                        .getNormal()
                        .multiply((int)Math.ceil(this.getBoundingBox().getXsize()) + 1)
                  )
                  .getCenter();
               if (!this.randomTeleport(tpPos.x, tpPos.y + 4.0, tpPos.z, false)) {
                  this.tethering = null;
                  this.pokemon.recall();
               } else {
                  this.navigation.stop();
               }
            } else {
               this.tethering = null;
               this.pokemon.recall();
            }
         }
      }
   }

   protected open fun customServerAiStep() {
      var var10000: Brain = this.getBrain();
      var var10001: Level = this.level();
      var10000.tick(var10001 as ServerLevel, this as LivingEntity);
      super.customServerAiStep();
   }

   public fun getRideVelocity(): Vec3 {
      return this.ridingAnimationData.getVelocitySpring().getValue();
   }

   public fun setMoveControl(moveControl: MoveControl) {
      this.moveControl = moveControl;
   }

   public open fun canBreatheUnderwater(): Boolean {
      return this.getBehaviour().getMoving().getSwim().getCanBreatheUnderwater();
   }

   public open fun fireImmune(): Boolean {
      return this.pokemon.isFireImmune();
   }

   public open fun canFreeze(): Boolean {
      return !this.pokemon.isFreezeImmune() && super.canFreeze();
   }

   public open fun causeFallDamage(fallDistance: Float, damageMultiplier: Float, damageSource: DamageSource): Boolean {
      return super.causeFallDamage(fallDistance, damageMultiplier, damageSource);
   }

   public open fun isInvulnerableTo(damageSource: DamageSource): Boolean {
      if (!this.busyLocks.isEmpty()) {
         return true;
      } else if (this.getBeamMode() != 0) {
         return true;
      } else if (this.getOwnerUUID() == null || damageSource.getEntity() !is Player && !damageSource.is(DamageTypes.IN_WALL)) {
         return !Cobblemon.INSTANCE.getConfig().getPlayerDamagePokemon() && damageSource.getEntity() is Player || super.isInvulnerableTo(damageSource);
      } else {
         return true;
      }
   }

   public fun isUncatchable(): Boolean {
      return this.pokemon.isUncatchable();
   }

   public fun isBattleClone(): Boolean {
      return this.pokemon.isBattleClone();
   }

   public fun recallWithAnimation(): CompletableFuture<Pokemon> {
      var var10000: LivingEntity = this.getOwner();
      if (var10000 == null) {
         var10000 = this.pokemon.getOwnerEntity();
      }

      var future: CompletableFuture = new CompletableFuture();
      var var5: Int = this.entityData.get(PHASING_TARGET_ID) as Integer;
      if (var5 != null) {
         if (var5 == -1 && var10000 != null) {
            (if (var10000 is PokemonSender) (var10000 as PokemonSender).recalling(this) else CompletableFuture.completedFuture(Unit.INSTANCE))
               .thenAccept(PokemonEntity::recallWithAnimation$lambda$1);
            return future;
         }
      }

      this.pokemon.recall();
      future.complete(this.pokemon);
      return future;
   }

   public open fun saveWithoutId(nbt: CompoundTag): CompoundTag {
      var tethering: Tethering = this.tethering;
      if (this.tethering != null) {
         var battleIdToSave: CompoundTag = new CompoundTag();
         battleIdToSave.putUUID("TetheringId", tethering.getTetheringId());
         battleIdToSave.putUUID("UUID", tethering.getPokemonId());
         battleIdToSave.putUUID("PokemonOwnerId", tethering.getPlayerId());
         battleIdToSave.putUUID("PCId", tethering.getPcId());
         battleIdToSave.put("TetherMinRoamPos", NbtUtils.writeBlockPos(tethering.getMinRoamPos()));
         battleIdToSave.put("TetherMaxRoamPos", NbtUtils.writeBlockPos(tethering.getMaxRoamPos()));
         battleIdToSave.put("TetherPasturePos", NbtUtils.writeBlockPos(tethering.getPasturePos()));
         nbt.put("Tethering", battleIdToSave as Tag);
      } else {
         var var10002: Pokemon = this.pokemon;
         var var10003: RegistryAccess = this.registryAccess();
         nbt.put("Pokemon", Pokemon.saveToNBT$default(var10002, var10003, null, 2, null) as Tag);
      }

      var var15: UUID = this.getBattleId();
      if (var15 != null) {
         nbt.putUUID("BattleId", var15);
      }

      nbt.putString("PoseType", (this.entityData.get(POSE_TYPE) as PoseType).name());
      var var16: Any = this.entityData.get(BEHAVIOUR_FLAGS);
      nbt.putByte("BehaviourFlags", (var16 as java.lang.Number).byteValue());
      this.saveScriptingToNBT(nbt);
      if (this.entityData.get(HIDE_LABEL) as java.lang.Boolean) {
         nbt.putBoolean("HideLabel", true);
      }

      if (this.entityData.get(UNBATTLEABLE) as java.lang.Boolean) {
         nbt.putBoolean("Unbattleable", true);
      }

      if (!this.countsTowardsSpawnCap) {
         nbt.putBoolean("CountsTowardsSpawnCap", false);
      }

      if (!(this.entityData.get(FREEZE_FRAME) as java.lang.Float == -1.0F)) {
         var16 = this.entityData.get(FREEZE_FRAME);
         nbt.putFloat("FreezeFrame", (var16 as java.lang.Number).floatValue());
      }

      if (!this.enablePoseTypeRecalculation) {
         nbt.putBoolean("RecalculatePose", this.enablePoseTypeRecalculation);
      }

      this.brain
         .serializeStart(NbtOps.INSTANCE as DynamicOps)
         .resultOrPartial(PokemonEntity::saveWithoutId$error__proxy)
         .ifPresent(PokemonEntity::saveWithoutId$lambda$1);
      var16 = this.entityData.get(SCALE_MODIFIER);
      nbt.putFloat("ScaleModifier", (var16 as java.lang.Number).floatValue());
      var16 = this.effects;
      var var20: RegistryAccess = this.level().registryAccess();
      nbt.put("EntityEffects", var16.saveToNbt(var20 as Provider) as Tag);
      var `$this$iv`: EventObservable = CobblemonEvents.POKEMON_ENTITY_SAVE;
      var `events$iv`: Array<PokemonEntitySaveEvent> = new PokemonEntitySaveEvent[]{new PokemonEntitySaveEvent(this, nbt)};
      `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

      for (Object element$iv$iv : events$iv) {
         ;
      }

      var var10000: CompoundTag = super.saveWithoutId(nbt);
      return var10000;
   }

   public open fun load(nbt: CompoundTag) {
      super.load(nbt);
      if (nbt.contains("Tethering")) {
         var savedBattleId: CompoundTag = nbt.getCompound("Tethering");
         var battle: UUID = savedBattleId.getUUID("TetheringId");
         var var4: UUID = savedBattleId.getUUID("PCId");
         var `this_$iv`: UUID = savedBattleId.getUUID("UUID");
         var `event$iv`: UUID = savedBattleId.getUUID("PokemonOwnerId");
         var var10000: PokemonStoreManager = (PokemonStoreManager)NbtUtils.readBlockPos(savedBattleId, "TetherMinRoamPos").get();
         var `$i$f$postThen`: BlockPos = var10000 as BlockPos;
         var10000 = NbtUtils.readBlockPos(savedBattleId, "TetherMaxRoamPos").get();
         var `this_$iv$iv`: BlockPos = var10000 as BlockPos;
         var10000 = NbtUtils.readBlockPos(savedBattleId, "TetherPasturePos").get();
         var `events$iv$iv`: BlockPos = var10000 as BlockPos;
         var10000 = Cobblemon.INSTANCE.getStorage();
         var var10002: RegistryAccess = this.registryAccess();
         var var39: PCStore = var10000.getPC(var4, var10002);
         var `$i$f$post`: Pokemon = var39.get(`this_$iv`);
         if (`$i$f$post` != null && `$i$f$post`.getTetheringId() == battle) {
            this.setPokemon(`$i$f$post`);
            this.tethering = new Tethering(`$i$f$postThen`, `this_$iv$iv`, `event$iv`, "", battle, `this_$iv`, var4, this.getId(), `events$iv$iv`);
         } else {
            this.setPokemon(this.createSidedPokemon());
            this.setHealth(0.0F);
         }
      } else if (this.pokemon.getStoreCoordinates().get() == null) {
         var var22: RegistryOps = this.registryAccess().createSerializationContext(NbtOps.INSTANCE as DynamicOps);
         var var20: PokemonEntity = this;

         var var24: Pokemon;
         var var40: PokemonEntity;
         try {
            var40 = var20;
            var24 = (Pokemon)(this.sidedCodec().decode(var22 as DynamicOps, nbt.getCompound("Pokemon")).getOrThrow() as Pair).getFirst();
            var24 = var24;
         } catch (IllegalStateException var21) {
            var40 = this;
            this.setHealth(0.0F);
            var24 = this.createSidedPokemon();
         }

         var40.setPokemon(var24);
      }

      var var23: UUID = if (nbt.hasUUID("BattleId")) nbt.getUUID("BattleId") else null;
      if (var23 != null && BattleRegistry.getBattle(var23) != null) {
         this.setBattleId(var23);
      }

      this.loadScriptingFromNBT(nbt);
      if (nbt.contains("EntityEffects")) {
         var var41: EffectTracker = this.effects;
         var var10001: CompoundTag = nbt.getCompound("EntityEffects");
         var var52: RegistryAccess = this.level().registryAccess();
         var41.loadFromNBT(var10001, var52 as Provider);
      }

      var var42: SynchedEntityData;
      var var47: EntityDataAccessor;
      var var55: java.lang.String;
      label94: {
         var42 = this.entityData;
         var47 = SPECIES;
         var var53: MocKEffect = this.effects.getMockEffect();
         if (var53 != null) {
            var var54: PokemonProperties = var53.getMock();
            if (var54 != null) {
               var55 = var54.getSpecies();
               if (var55 != null) {
                  break label94;
               }
            }
         }

         var55 = this.pokemon.getSpecies().getResourceIdentifier().toString();
      }

      var42.set(var47, var55);
      var42 = this.entityData;
      var47 = NICKNAME;
      var var56: MutableComponent = this.pokemon.getNickname();
      if (var56 == null) {
         var56 = Component.empty();
      }

      var42.set(var47, var56);
      var42 = this.entityData;
      var47 = MARK;
      var var57: Mark = this.pokemon.getActiveMark();
      var42.set(var47, java.lang.String.valueOf(if (var57 != null) var57.getIdentifier() else null));
      this.entityData.set(LABEL_LEVEL, this.pokemon.getLevel());
      var42 = this.entityData;
      var47 = POSE_TYPE;
      var55 = nbt.getString("PoseType");
      var42.set(var47, PoseType.valueOf(var55));
      this.entityData.set(BEHAVIOUR_FLAGS, nbt.getByte("BehaviourFlags"));
      if (nbt.contains("FreezeFrame")) {
         this.entityData.set(FREEZE_FRAME, nbt.getFloat("FreezeFrame"));
      }

      if (nbt.contains("HideLabel")) {
         this.entityData.set(HIDE_LABEL, nbt.getBoolean("HideLabel"));
      }

      if (nbt.contains("Unbattleable")) {
         this.entityData.set(UNBATTLEABLE, nbt.getBoolean("Unbattleable"));
      }

      if (nbt.contains("CountsTowardsSpawnCap")) {
         this.countsTowardsSpawnCap = nbt.getBoolean("CountsTowardsSpawnCap");
      }

      if (nbt.contains("RecalculatePose")) {
         this.enablePoseTypeRecalculation = nbt.getBoolean("RecalculatePose");
      }

      if (nbt.contains("PlatformType")) {
         var42 = this.entityData;
         var47 = PLATFORM_TYPE;
         var55 = nbt.getString("PlatformType");
         var42.set(var47, PlatformType.valueOf(var55));
      }

      if (nbt.contains("Brain", 10)) {
         this.brain = this.makeBrain(new Dynamic(NbtOps.INSTANCE as DynamicOps, nbt.get("Brain")));
      }

      this.remakeBrain();
      if (nbt.contains("ScaleModifier")) {
         this.entityData.set(SCALE_MODIFIER, nbt.getFloat("ScaleModifier"));
      }

      var var27: CancelableObservable = CobblemonEvents.POKEMON_ENTITY_LOAD;
      var var29: Cancelable = (new PokemonEntityLoadEvent(this, nbt)) as Cancelable;
      var var31: EventObservable = var27 as EventObservable;
      var var32: Array<Cancelable> = new Cancelable[]{var29};
      var31.emit(Arrays.copyOf(var32, var32.length));

      for (Object element$iv$iv$iv : var32) {
         if (`element$iv$iv$iv`.isCanceled()) {
            var it: PokemonEntityLoadEvent = `element$iv$iv$iv` as PokemonEntityLoadEvent;
            this.discard();
         } else {
            var var34: PokemonEntityLoadEvent = `element$iv$iv$iv` as PokemonEntityLoadEvent;
         }
      }
   }

   public open fun getAddEntityPacket(entityTrackerEntry: ServerEntity): Packet<ClientGamePacketListener> {
      var var10005: Packet = super.getAddEntityPacket(entityTrackerEntry);
      return (new ClientboundCustomPayloadPacket((new SpawnPokemonPacket(this, var10005 as ClientboundAddEntityPacket)) as CustomPacketPayload)) as Packet<ClientGamePacketListener>;
   }

   public open fun getPathfindingMalus(nodeType: PathType): Float {
      return if (nodeType === PathType.OPEN) 0.0F else super.getPathfindingMalus(nodeType);
   }

   public open fun getNavigation(): OmniPathNavigation {
      var var10000: PathNavigation = this.navigation;
      return var10000 as OmniPathNavigation;
   }

   protected open fun createNavigation(world: Level): OmniPathNavigation {
      return new OmniPathNavigation(world, this as Mob);
   }

   protected open fun makeBrain(dynamic: Dynamic<*>): Brain<PokemonEntity> {
      this.brainDynamic = dynamic;
      if (this.pokemon != null) {
         PokemonBrain.INSTANCE.applyBrain(this, this.pokemon, dynamic);
         return this.getBrain();
      } else {
         var brain: Brain = this.brainProvider().makeBrain(dynamic);
         return brain;
      }
   }

   public open fun remakeBrain() {
      var var10002: Dynamic = this.brainDynamic;
      if (this.brainDynamic == null) {
         var10002 = EntityExtensionsKt.makeEmptyBrainDynamic(this as Entity);
      }

      this.brain = this.makeBrain(var10002);
   }

   public open fun assignNewBrainWithMemoriesAndSensors(dynamic: Dynamic<*>, memories: Set<MemoryModuleType<*>>, sensors: Set<SensorType<*>>): Brain<
         PokemonEntity
      > {
      var var10000: DefaultedRegistry = BuiltInRegistries.SENSOR_TYPE;
      var brain: java.lang.Iterable = CollectionsKt.toSet(var10000 as java.lang.Iterable);
      var `$this$filterTo$iv$iv`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$filterIsInstance$iv) {
         if (`element$iv$iv` is SensorType) {
            `$this$filterTo$iv$iv`.add(`element$iv$iv`);
         }
      }

      var allSensors: java.util.List = `$this$filterTo$iv$iv` as java.util.List;
      var var21: java.util.Collection = memories;
      var var17: java.lang.Iterable = allSensors;
      var `destination$iv$ivx`: java.util.Collection = new ArrayList();

      for (Object element$iv$ivx : var17) {
         if (sensors.contains(`element$iv$ivx` as SensorType)) {
            `destination$iv$ivx`.add(`element$iv$ivx`);
         }
      }

      var var16: Brain = Brain.provider(var21, CollectionsKt.toSet(`destination$iv$ivx` as java.util.List)).makeBrain(dynamic);
      this.brain = var16;
      return var16;
   }

   public open fun getBrain(): Brain<PokemonEntity> {
      var var10000: Brain = super.getBrain();
      return var10000;
   }

   protected open fun brainProvider(): net.minecraft.world.entity.ai.Brain.Provider<PokemonEntity> {
      var var10000: net.minecraft.world.entity.ai.Brain.Provider = Brain.provider(PokemonBrain.INSTANCE.getMEMORY_MODULES(), PokemonBrain.INSTANCE.getSENSORS());
      return var10000;
   }

   public open fun onPathfindingDone() {
      super.onPathfindingDone();
      var var10000: MoveControl = this.moveControl;
      (var10000 as PokemonMoveControl).stop();
   }

   public fun canSleepAt(pos: BlockPos): Boolean {
      var rest: RestBehaviour = this.getBehaviour().getResting();
      var var10000: Level = this.level();
      var world: ServerLevel = var10000 as ServerLevel;
      var light: Int = (var10000 as ServerLevel).getLightEmission(pos);
      var block: Block = (var10000 as ServerLevel).getBlockState(pos).getBlock();
      var biome: Biome = world.getBiome(pos).value() as Biome;
      var fluid: Fluid = world.getFluidState(pos.above()).getType();
      var seesSky: Boolean = world.canSeeSky(pos.above());
      var canStayAt: Boolean = WorldExtensionsKt.canEntityStayAt(
         world as Level, pos, (int)((float)Math.ceil((double)this.getBbWidth())), (int)((float)Math.ceil((double)this.getBbHeight())), PositionType.LAND
      );
      var `$this$any$iv`: IntRange = rest.getLight();
      if (light <= `$this$any$iv`.getLast()
         && `$this$any$iv`.getFirst() <= light
         && (rest.getSkyLight() == null || rest.getSkyLight().contains(world.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos)))) {
         if (!rest.getBlocks().isEmpty()) {
            var var18: java.lang.Iterable = rest.getBlocks();
            var var32: Boolean;
            if (var18 is java.util.Collection && (var18 as java.util.Collection).isEmpty()) {
               var32 = false;
            } else {
               var var14: java.util.Iterator = var18.iterator();

               while (true) {
                  if (!var14.hasNext()) {
                     var32 = false;
                     break;
                  }

                  var it: RegistryLikeCondition = var14.next() as RegistryLikeCondition;
                  if (it.fits(block, WorldExtensionsKt.getBlockRegistry(world as Level))) {
                     var32 = true;
                     break;
                  }
               }
            }

            if (!var32) {
               return false;
            }
         }

         if (!rest.getBiomes().isEmpty()) {
            var `$this$any$ivx`: java.lang.Iterable = rest.getBiomes();
            var var33: Boolean;
            if (`$this$any$ivx` is java.util.Collection && (`$this$any$ivx` as java.util.Collection).isEmpty()) {
               var33 = false;
            } else {
               var var24: java.util.Iterator = `$this$any$ivx`.iterator();

               while (true) {
                  if (!var24.hasNext()) {
                     var33 = false;
                     break;
                  }

                  var var28: RegistryLikeCondition = var24.next() as RegistryLikeCondition;
                  if (var28.fits(biome, WorldExtensionsKt.getBiomeRegistry(world as Level))) {
                     var33 = true;
                     break;
                  }
               }
            }

            if (!var33) {
               return false;
            }
         }

         if (fluid !is EmptyFluid || !rest.getFluids().isEmpty()) {
            var `$this$any$ivxx`: java.lang.Iterable = rest.getFluids();
            var var34: Boolean;
            if (`$this$any$ivxx` is java.util.Collection && (`$this$any$ivxx` as java.util.Collection).isEmpty()) {
               var34 = false;
            } else {
               var var25: java.util.Iterator = `$this$any$ivxx`.iterator();

               while (true) {
                  if (!var25.hasNext()) {
                     var34 = false;
                     break;
                  }

                  var var29: RegistryLikeCondition = var25.next() as RegistryLikeCondition;
                  if (var29.fits(fluid, WorldExtensionsKt.getFluidRegistry(world as Level))) {
                     var34 = true;
                     break;
                  }
               }
            }

            if (!var34) {
               return false;
            }
         }

         if ((rest.getCanSeeSky() == null || rest.getCanSeeSky() == seesSky) && canStayAt) {
            return true;
         }
      }

      return false;
   }

   public open fun getBreedOffspring(serverLevel: ServerLevel, ageableMob: AgeableMob): Nothing? {
      return null;
   }

   public open fun canSitOnShoulder(): Boolean {
      return this.pokemon.getForm().getShoulderMountable() && !this.pokemon.isAlpha();
   }

   public open fun wantsToPickUp(stack: ItemStack): Boolean {
      var pickupItems: java.util.List = MoLangExtensionsKt.getObjectList(this.getConfig(), "pickup_items");
      if (this.canHoldItem(stack)) {
         var var10000: java.util.Collection = pickupItems;
         var var10001: RegistryAccess = this.registryAccess();
         var var3: ObtainableItem = MiscUtilsKt.findMatchingEntry(var10000, var10001, stack);
         var var4: Int = if (var3 != null) var3.getPickupPriority() else 0;
         var var6: java.util.Collection = pickupItems;
         var var10002: RegistryAccess = this.registryAccess();
         var var7: ObtainableItem = MiscUtilsKt.findMatchingEntry(var6, var10002, this.pokemon.getHeldItem$common());
         if (var4 > (if (var7 != null) var7.getPickupPriority() else 0)) {
            return true;
         }
      }

      return false;
   }

   public open fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
      if (!this.isBattling() && this.isBattleClone()) {
         return InteractionResult.FAIL;
      } else {
         var itemStack: ItemStack = player.getItemInHand(hand);
         var item: java.util.Iterator = SpeciesFeatures.getFeaturesFor(this.pokemon.getSpecies()).iterator();

         var var10000: Any;
         while (true) {
            if (!item.hasNext()) {
               var10000 = null;
               break;
            }

            var newColorFeature: Any = item.next();
            if (newColorFeature as SpeciesFeatureProvider is ChoiceSpeciesFeatureProvider
               && ((newColorFeature as SpeciesFeatureProvider) as ChoiceSpeciesFeatureProvider).getKeys().contains("color")) {
               var10000 = (Pokemon)newColorFeature;
               break;
            }
         }

         var colorFeatureType: SpeciesFeatureProvider = var10000 as SpeciesFeatureProvider;
         var colorFeature: StringSpeciesFeature = this.pokemon.getFeature("color");
         if (this.getOwnerUUID() == player.getUUID() || this.getOwnerUUID() == null) {
            if (itemStack.is(Items.SHEARS) && this.readyForShearing()) {
               this.shear(SoundSource.PLAYERS);
               this.gameEvent(GameEvent.SHEAR as Holder, player as Entity);
               itemStack.hurtAndBreak(1, player as LivingEntity, EquipmentSlot.MAINHAND);
               return InteractionResult.SUCCESS;
            }

            if (itemStack.is(Items.BOWL)) {
               var var12: java.lang.Iterable = this.pokemon.getAspects();
               var var37: Boolean;
               if (var12 is java.util.Collection && (var12 as java.util.Collection).isEmpty()) {
                  var37 = false;
               } else {
                  var var21: java.util.Iterator = var12.iterator();

                  while (true) {
                     if (!var21.hasNext()) {
                        var37 = false;
                        break;
                     }

                     var var30: java.lang.String = var21.next() as java.lang.String;
                     if (StringsKt.contains$default(var30, "mooshtank", false, 2, null)
                        && !StringsKt.contains$default(var30, "mooshtank-false", false, 2, null)) {
                        var37 = true;
                        break;
                     }
                  }
               }

               if (var37) {
                  player.playSound(SoundEvents.MOOSHROOM_MILK, 1.0F, 1.0F);
                  if (!(this.pokemon.getLastFlowerFed() == ItemStack.EMPTY)) {
                     var12 = this.pokemon.getAspects();
                     var var38: Boolean;
                     if (var12 is java.util.Collection && (var12 as java.util.Collection).isEmpty()) {
                        var38 = false;
                     } else {
                        var var22: java.util.Iterator = var12.iterator();

                        while (true) {
                           if (!var22.hasNext()) {
                              var38 = false;
                              break;
                           }

                           if (StringsKt.contains$default(var22.next() as java.lang.String, "mooshtank-brown", false, 2, null)) {
                              var38 = true;
                              break;
                           }
                        }
                     }

                     if (var38) {
                        var10000 = SuspiciousEffectHolder.tryGet(this.pokemon.getLastFlowerFed().getItem() as ItemLike);
                        if (var10000 != null) {
                           var var32: ItemStack = Items.SUSPICIOUS_STEW.getDefaultInstance().copy();
                           var32.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, var10000.getSuspiciousEffects());
                           player.setItemInHand(hand, ItemUtils.createFilledResult(itemStack, player, var32));
                           var10000 = this.pokemon;
                           var var10001: ItemStack = ItemStack.EMPTY;
                           var10000.setLastFlowerFed(var10001);
                        }

                        var var42: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                        return var42;
                     }
                  }

                  player.setItemInHand(hand, ItemUtils.createFilledResult(itemStack, player, Items.MUSHROOM_STEW.getDefaultInstance()));
                  var var39: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                  return var39;
               }
            } else if (!itemStack.is(Items.ALLIUM)
               && !itemStack.is(Items.AZURE_BLUET)
               && !itemStack.is(Items.BLUE_ORCHID)
               && !itemStack.is(Items.DANDELION)
               && !itemStack.is(Items.CORNFLOWER)
               && !itemStack.is(Items.LILY_OF_THE_VALLEY)
               && !itemStack.is(Items.OXEYE_DAISY)
               && !itemStack.is(Items.POPPY)
               && !itemStack.is(Items.TORCHFLOWER)
               && !itemStack.is(Items.PINK_TULIP)
               && !itemStack.is(Items.RED_TULIP)
               && !itemStack.is(Items.WHITE_TULIP)
               && !itemStack.is(Items.ORANGE_TULIP)
               && !itemStack.is(Items.WITHER_ROSE)
               && !itemStack.is(CobblemonItems.PEP_UP_FLOWER)) {
               if (!player.isShiftKeyDown() && StashHandler.INSTANCE.interactMob(player, this.pokemon, itemStack)) {
                  return InteractionResult.SUCCESS;
               }

               if (itemStack.getItem() is DyeItem && colorFeatureType != null) {
                  label192: {
                     if (colorFeature != null) {
                        var49 = colorFeature.getValue();
                        if (var49 != null) {
                           break label192;
                        }
                     }

                     var49 = "";
                  }

                  var var50: Item = itemStack.getItem();
                  var var20: DyeItem = var50 as DyeItem;
                  if (!StringsKt.equals((var50 as DyeItem).getDyeColor().name(), var49, true)) {
                     if (player is ServerPlayer) {
                        if (colorFeature != null) {
                           var var59: java.lang.String = var20.getDyeColor().name().toLowerCase(Locale.ROOT);
                           colorFeature.setValue(var59);
                           this.pokemon.markFeatureDirty(colorFeature as SpeciesFeature);
                        } else {
                           var var10003: java.lang.String = var20.getDyeColor().name().toLowerCase(Locale.ROOT);
                           this.pokemon.getFeatures().add(new StringSpeciesFeature("color", var10003));
                           Pokemon.onChange$default(this.pokemon, null, 1, null);
                        }

                        this.pokemon.updateAspects();
                        itemStack.consume(1, player as LivingEntity);
                     }

                     var var54: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                     return var54;
                  }
               } else {
                  if (itemStack.getItem().equals(Items.WATER_BUCKET) && colorFeatureType != null) {
                     if (player is ServerPlayer && colorFeature != null) {
                        if (!player.hasInfiniteMaterials()) {
                           itemStack.shrink(1);
                           var var57: ItemStack = Items.BUCKET.getDefaultInstance();
                           PlayerExtensionsKt.giveOrDropItemStack$default(player, var57, false, 2, null);
                        }

                        colorFeature.setValue("");
                        this.pokemon.markFeatureDirty(colorFeature as SpeciesFeature);
                        this.pokemon.updateAspects();
                     }

                     var var48: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                     return var48;
                  }

                  if (itemStack.is(CobblemonItems.NPC_EDITOR as Item) && player is ServerPlayer && (player as ServerPlayer).isCreative()) {
                     BehaviourEditingTracker.INSTANCE.startEditing(player as ServerPlayer, this as LivingEntity);
                     var var46: CobblemonNetwork = CobblemonNetwork.INSTANCE;
                     var var56: ServerPlayer = player as ServerPlayer;
                     var var10004: Int = this.getId();
                     var46.sendPacket(var56, (new OpenBehaviourEditorPacket(var10004, CollectionsKt.toSet(this.getBehaviours()))) as NetworkPacket);
                     var var47: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                     return var47;
                  }
               }
            } else {
               var `$this$any$ivx`: java.lang.Iterable = this.pokemon.getAspects();
               var var43: Boolean;
               if (`$this$any$ivx` is java.util.Collection && (`$this$any$ivx` as java.util.Collection).isEmpty()) {
                  var43 = false;
               } else {
                  var var24: java.util.Iterator = `$this$any$ivx`.iterator();

                  while (true) {
                     if (!var24.hasNext()) {
                        var43 = false;
                        break;
                     }

                     if (StringsKt.contains$default(var24.next() as java.lang.String, "mooshtank-brown", false, 2, null)) {
                        var43 = true;
                        break;
                     }
                  }
               }

               if (var43) {
                  player.playSound(SoundEvents.MOOSHROOM_EAT, 1.0F, 1.0F);
                  var10000 = this.pokemon;
                  var var55: ItemStack = itemStack.copyWithCount(1);
                  var10000.setLastFlowerFed(var55);
                  if (!player.isCreative() && !this.level().isClientSide) {
                     itemStack.shrink(1);
                  }

                  var var45: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                  return var45;
               }
            }
         }

         if (hand === InteractionHand.MAIN_HAND) {
            if (player.isShiftKeyDown() && player is ServerPlayer) {
               var var58: ServerPlayer = player as ServerPlayer;
               this.showInteractionWheel(var58, itemStack);
               var var53: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
               return var53;
            }

            if (this.getOwnerUUID() == player.getUUID()) {
               var var10002: ItemStack = player.getItemInHand(hand);
               if (this.attemptItemInteraction(player, var10002)) {
                  var var52: InteractionResult = InteractionResult.sidedSuccess(this.level().isClientSide);
                  return var52;
               }
            }
         }

         var var51: InteractionResult = super.mobInteract(player, hand);
         return var51;
      }
   }

   private fun showInteractionWheel(player: ServerPlayer, itemStack: ItemStack) {
      var canRide: Boolean = this.isRideableFor(player);
      if (this.pokemon.getOwnerPlayer() == player) {
         var var10000: RegistryAccess = player.level().registryAccess();
         var cosmeticItemDefinition: CosmeticItemDefinition = CobblemonCosmeticItems.findValidCosmeticForPokemonAndItem(var10000, this.pokemon, itemStack);
         var var10002: UUID = this.getUUID();
         new InteractPokemonUIPacket(
               var10002,
               this.canSitOnShoulder() && CollectionsKt.contains(PlayerExtensionsKt.party(player) as java.lang.Iterable, this.pokemon),
               !this.pokemon.heldItemNoCopy$common().isEmpty() || !itemStack.isEmpty(),
               !this.pokemon.getCosmeticItem().isEmpty() && itemStack.isEmpty() || cosmeticItemDefinition != null,
               canRide
            )
            .sendToPlayer(player);
      } else if (!this.pokemon.isWild() && canRide) {
         player.setShiftKeyDown(false);
         this.tryRidingPokemon(player);
      }
   }

   public fun isRideableFor(player: ServerPlayer): Boolean {
      return this.ifRidingAvailableSupply(false, PokemonEntity::isRideableFor$lambda$0);
   }

   public open fun getDimensions(pose: Pose): EntityDimensions {
      var var10000: MocKEffect = this.effects.getMockEffect();
      var result: EntityDimensions = this.getExposedForm()
         .getHitbox()
         .scale(if (var10000 != null) var10000.getScale() else this.getForm().getBaseScale() * this.pokemon.getEffectiveScale());
      result = result.withEyeHeight(this.getExposedForm().eyeHeight(this) * result.height()).scale(this.getScale());
      return result;
   }

   public open fun canBeSeenAsEnemy(): Boolean {
      return super.canBeSeenAsEnemy() && !this.isBusy();
   }

   public open fun doHurtTarget(target: Entity): Boolean {
      return this.getBeamMode() == 0 && super.doHurtTarget(target);
   }

   public open fun hurt(source: DamageSource, amount: Float): Boolean {
      var var7: Boolean;
      if (super.hurt(source, amount)) {
         var var10000: MocKEffect = this.effects.getMockEffect();
         if (var10000 != null) {
            var var6: MocKEffect = if (var10000 is IllusionEffect && this.getBattleId() == null) var10000 else null;
            if (var6 != null) {
               var6.end(this);
            }
         }

         if (this.getHealth() == 0.0F) {
            this.pokemon.setCurrentHealth(0);
         } else if (this.getOwnerUUID() != null) {
            this.pokemon.setCurrentHealth((int)((float)this.pokemon.getMaxHealth() * (this.getHealth() / this.getMaxHealth())));
         }

         var7 = true;
      } else {
         var7 = false;
      }

      return var7;
   }

   public open fun shouldBeSaved(): Boolean {
      if (this.getOwnerUUID() == null && !this.pokemon.isNPCOwned() && (Cobblemon.INSTANCE.getConfig().getSavePokemonToWorld() || this.isPersistenceRequired())
         )
       {
         var `$this$iv`: CancelableObservable = CobblemonEvents.POKEMON_ENTITY_SAVE_TO_WORLD;
         var `event$iv`: Cancelable = (new PokemonEntitySaveToWorldEvent(this)) as Cancelable;
         var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
         var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
         `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

         for (Object element$iv$iv$iv : events$iv$iv) {
            if (!`element$iv$iv$iv`.isCanceled()) {
               var it: PokemonEntitySaveToWorldEvent = `element$iv$iv$iv` as PokemonEntitySaveToWorldEvent;
               return true;
            }
         }
      }

      return this.tethering != null;
   }

   public open fun checkDespawn() {
      if (this.pokemon.getOwnerUUID() == null && !this.isPersistenceRequired() && this.despawner.shouldDespawn(this as Entity)) {
         this.discard();
      }
   }

   public open fun isPersistenceRequired(): Boolean {
      return super.isPersistenceRequired()
         || this.pokemon.getCanDropHeldItem$common() && !this.pokemon.getHeldItem$common().isEmpty()
         || this.brain.checkMemory(CobblemonMemories.HIVE_LOCATION, MemoryStatus.VALUE_PRESENT)
         || this.brain.checkMemory(CobblemonMemories.HIVE_COOLDOWN, MemoryStatus.VALUE_PRESENT)
         || this.brain.checkMemory(CobblemonMemories.NEARBY_SACC_LEAVES, MemoryStatus.VALUE_PRESENT);
   }

   public fun setBehaviourFlag(flag: PokemonBehaviourFlag, on: Boolean) {
      var var10000: SynchedEntityData = this.entityData;
      var var10001: EntityDataAccessor = BEHAVIOUR_FLAGS;
      var var10002: Any = this.entityData.get(BEHAVIOUR_FLAGS);
      var10000.set(var10001, BitUtilitiesKt.setBitForByte((var10002 as java.lang.Number).byteValue(), flag.getBit(), on));
   }

   public fun getBehaviourFlag(flag: PokemonBehaviourFlag): Boolean {
      var var10000: Any = this.entityData.get(BEHAVIOUR_FLAGS);
      return BitUtilitiesKt.getBitForByte((var10000 as java.lang.Number).byteValue(), flag.getBit());
   }

   public fun getActiveBehaviourFlags(): Set<PokemonBehaviourFlag> {
      var flagsByte: Int = (this.entityData.get(BEHAVIOUR_FLAGS) as java.lang.Number).byteValue();
      var `$this$filterTo$iv`: java.lang.Iterable = PokemonBehaviourFlag.getEntries() as java.lang.Iterable;
      var `destination$iv`: java.util.Collection = new LinkedHashSet();

      for (Object element$iv : $this$filterTo$iv) {
         if ((flagsByte and 1 shl (`element$iv` as PokemonBehaviourFlag).getBit() - 1) != 0) {
            `destination$iv`.add(`element$iv`);
         }
      }

      return `destination$iv` as java.util.Set<PokemonBehaviourFlag>;
   }

   public fun canBattle(player: Player): Boolean {
      if (this.entityData.get(UNBATTLEABLE) as java.lang.Boolean) {
         return false;
      } else if (this.isBusy()) {
         return false;
      } else if (this.getBattleId() != null) {
         return false;
      } else if (this.getOwnerUUID() != null) {
         return false;
      } else if (this.getHealth() <= 0.0F || this.isDeadOrDying()) {
         return false;
      } else {
         return !PlayerExtensionsKt.isPartyBusy(player);
      }
   }

   public fun labelLevel(): Int {
      return this.entityData.get(LABEL_LEVEL) as Integer;
   }

   public open fun playAmbientSound() {
      if (this.isSilent()) {
         var sound: java.lang.Iterable = this.busyLocks;
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filterIsInstance$iv) {
            if (`element$iv$iv` is EmptyPokeBallEntity) {
               `destination$iv$iv`.add(`element$iv$iv`);
            }
         }

         if (!(`destination$iv$iv` as java.util.List).isEmpty()) {
            return;
         }
      }

      var var8: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
         this.pokemon.getSpecies().getResourceIdentifier().getNamespace(), "pokemon." + this.pokemon.showdownId() + ".ambient"
      );
      var var10003: SoundSource = this.getSoundSource();
      var var10000: NetworkPacket = (
         new UnvalidatedPlaySoundS2CPacket(var8, var10003, this.getX(), this.getY(), this.getZ(), this.getSoundVolume(), this.getVoicePitch())
      ) as NetworkPacket;
      var var10001: Double = this.getX();
      var var10002: Double = this.getY();
      var var9: Double = this.getZ();
      var var10005: ResourceKey = this.level().dimension();
      NetworkPacket.sendToPlayersAround$default(var10000, var10001, var10002, var9, 16.0, var10005, null, 32, null);
   }

   protected open fun getAmbientSound(): Nothing? {
      return null;
   }

   public open fun getAmbientSoundInterval(): Int {
      return Cobblemon.INSTANCE.getConfig().getAmbientPokemonCryTicks();
   }

   private fun attemptItemInteraction(player: Player, stack: ItemStack): Boolean {
      if (stack.isEmpty()) {
         return false;
      } else if (player is ServerPlayer && this.isBattling()) {
         var var83: UUID = this.getBattleId();
         if (var83 != null) {
            var var84: PokemonBattle = BattleRegistry.getBattle(var83);
            if (var84 != null) {
               var var85: BagItemLike = BagItems.getConvertibleForStack(stack);
               if (var85 == null) {
                  return false;
               }

               var var38: java.lang.Iterable = var84.getActors();
               var var59: java.util.Collection = new ArrayList();

               for (Object element$iv$iv : var38) {
                  CollectionsKt.addAll(var59, (var76 as BattleActor).getPokemonList());
               }

               var var53: java.util.Iterator = (var59 as java.util.List).iterator();

               while (true) {
                  if (var53.hasNext()) {
                     var59 = (java.util.Collection)var53.next();
                     if (!((var59 as BattlePokemon).getEffectedPokemon().getUuid() == this.pokemon.getUuid())) {
                        continue;
                     }

                     var83 = var59;
                     break;
                  }

                  var83 = null;
                  break;
               }

               var var87: BattlePokemon = var83 as BattlePokemon;
               if (var83 as BattlePokemon == null) {
                  return false;
               }

               var var29: Array<Any> = var87.getActor().getSide().getActors();
               var var46: Int = 0;
               var var54: Int = var29.length;

               while (true) {
                  if (var46 >= var54) {
                     var88 = true;
                     break;
                  }

                  if (var29[var46].isForPlayer(player as ServerPlayer)) {
                     var88 = false;
                     break;
                  }

                  var46++;
               }

               if (var88) {
                  return true;
               }

               return var85.handleInteraction(player as ServerPlayer, var87, stack);
            }
         }

         return false;
      } else if (this.isBusy()) {
         return false;
      } else {
         var interaction: PokemonInteraction = PokemonInteractions.INSTANCE.findInteraction(this);
         if (interaction != null && !this.pokemon.isOnInteractionCooldown(interaction.getGrouping())) {
            if (player is ServerPlayer) {
               var var18: java.lang.Iterable;
               for (Object element$iv : var18) {
                  (var36 as InteractionEffect).applyEffect(this, player as ServerPlayer);
               }

               this.pokemon
                  .getInteractionCooldowns()
                  .put(interaction.getGrouping(), MoLangExtensionsKt.resolveInt$default(this.getRuntime(), interaction.getCooldown(), null, 2, null));
            }

            return true;
         } else if (player !is ServerPlayer) {
            return false;
         } else {
            if (this.pokemon.getOwnerPlayer() == player) {
               var var10003: Level = player.level();
               var context: ItemInteractionContext = new ItemInteractionContext(stack, var10003);
               var it: java.lang.Iterable = this.pokemon.getLockedEvolutions();
               var var9: java.util.Collection = new ArrayList();

               for (Object element$iv$iv : $this$filterIsInstance$iv) {
                  if (`item$iv$iv` is ItemInteractionEvolution) {
                     var9.add(`item$iv$iv`);
                  }
               }

               var lockedItemEvolutions: java.util.List = var9 as java.util.List;
               it = var9 as java.util.List;
               var9 = new ArrayList();

               for (Object element$iv$ivx : $this$filterIsInstance$iv) {
                  if (!(`element$iv$ivx` as ItemInteractionEvolution).getOptional()) {
                     var9.add(`element$iv$ivx`);
                  }
               }

               for (Object element$iv : $this$filterIsInstance$iv) {
                  if ((var48 as ItemInteractionEvolution).attemptEvolution(this.pokemon, context)) {
                     return attemptItemInteraction$evolutionUnlocked(stack, player, this);
                  }
               }

               it = lockedItemEvolutions;
               var9 = new ArrayList();

               for (Object element$iv$ivxx : $this$filterIsInstance$iv) {
                  if ((`element$iv$ivxx` as ItemInteractionEvolution).getOptional()) {
                     var9.add(`element$iv$ivxx`);
                  }
               }

               it = var9 as java.util.List;
               var9 = new ArrayList(CollectionsKt.collectionSizeOrDefault(var9 as java.util.List, 10));

               for (Object item$iv$iv : $this$filterIsInstance$iv) {
                  var9.add((var70 as ItemInteractionEvolution).attemptEvolution(this.pokemon, context));
               }

               if ((var9 as java.util.List).contains(true)) {
                  return attemptItemInteraction$evolutionUnlocked(stack, player, this);
               }
            }

            var var20: Item = stack.getItem();
            var var10000: PokemonEntityInteraction = var20 as? PokemonEntityInteraction;
            if ((var20 as? PokemonEntityInteraction) != null) {
               if (var10000.onInteraction(player as ServerPlayer, this, stack)) {
                  var var81: SoundEvent = var10000.getSound();
                  if (var81 != null) {
                     var var82: Level = this.level();
                     var var10001: Vec3 = this.position();
                     WorldExtensionsKt.playSoundServer$default(var82, var10001, var81, null, 1.0F, 1.0F, 4, null);
                  }

                  return true;
               }
            }

            return false;
         }
      }
   }

   public open fun getOwner(): LivingEntity? {
      return this.pokemon.getOwnerEntity();
   }

   public fun offerHeldItem(player: Player, stack: ItemStack): Boolean {
      return this.offerItem(player, stack, false);
   }

   public fun offerCosmeticItem(player: Player, stack: ItemStack): Boolean {
      return this.offerItem(player, stack, true);
   }

   public fun offerItem(player: Player, stack: ItemStack, isCosmetic: Boolean): Boolean {
      if (player is ServerPlayer && !this.isBusy() && this.pokemon.getOwnerPlayer() == player) {
         if (stack.isEmpty() || isCosmetic || !this.isBlacklisted(stack) && this.isWhitelisted(stack)) {
            var var9: ItemStack = if (isCosmetic) this.pokemon.getCosmeticItem().copy() else this.pokemon.heldItemNoCopy$common();
            var returned: ItemStack = stack.copy();
            returned.setCount(1);
            if (ItemStack.isSameItemSameComponents(returned, var9)) {
               var var22: MutableComponent;
               if (isCosmetic) {
                  var var12: Array<Any> = new Object[]{Pokemon.getDisplayName$default(this.pokemon, false, 1, null), null};
                  var var30: Component = stack.getHoverName();
                  var12[1] = var30;
                  var22 = LocalizationUtilsKt.lang("cosmetic_item.already_wearing", var12);
               } else {
                  var var13: Array<Any> = new Object[]{Pokemon.getDisplayName$default(this.pokemon, false, 1, null), null};
                  var var31: Component = stack.getHoverName();
                  var13[1] = var31;
                  var22 = LocalizationUtilsKt.lang("held_item.already_holding", var13);
               }

               (player as ServerPlayer).sendSystemMessage(var22 as Component);
               return false;
            } else {
               returned = if (isCosmetic)
                  this.pokemon.swapCosmeticItem(stack, !(player as ServerPlayer).isCreative())
                  else
                  this.pokemon.swapHeldItem(stack, !(player as ServerPlayer).isCreative(), false);
               var var20: MutableComponent;
               if (isCosmetic && returned.isEmpty()) {
                  var var19: Array<Any> = new Object[2];
                  var var29: Component = returned.getDisplayName();
                  var19[0] = var29;
                  var19[1] = Pokemon.getDisplayName$default(this.pokemon, false, 1, null);
                  var20 = LocalizationUtilsKt.lang("cosmetic_item.take", var19);
               } else if (isCosmetic && returned.isEmpty()) {
                  var var18: Array<Any> = new Object[]{Pokemon.getDisplayName$default(this.pokemon, false, 1, null), null};
                  var var28: Component = returned.getDisplayName();
                  var18[1] = var28;
                  var20 = LocalizationUtilsKt.lang("cosmetic_item.give", var18);
               } else if (!isCosmetic && returned.isEmpty()) {
                  var var17: Array<Any> = new Object[2];
                  var var27: Component = returned.getDisplayName();
                  var17[0] = var27;
                  var17[1] = Pokemon.getDisplayName$default(this.pokemon, false, 1, null);
                  var20 = LocalizationUtilsKt.lang("held_item.take", var17);
               } else if (!isCosmetic && returned.isEmpty()) {
                  var var16: Array<Any> = new Object[]{Pokemon.getDisplayName$default(this.pokemon, false, 1, null), null};
                  var var26: Component = returned.getDisplayName();
                  var16[1] = var26;
                  var20 = LocalizationUtilsKt.lang("held_item.give", var16);
               } else if (isCosmetic) {
                  var var14: Array<Any> = new Object[3];
                  var var10003: Component = returned.getDisplayName();
                  var14[0] = var10003;
                  var14[1] = Pokemon.getDisplayName$default(this.pokemon, false, 1, null);
                  var10003 = returned.getDisplayName();
                  var14[2] = var10003;
                  var20 = LocalizationUtilsKt.lang("cosmetic_item.replace", var14);
               } else {
                  var var15: Array<Any> = new Object[3];
                  var var24: Component = returned.getDisplayName();
                  var15[0] = var24;
                  var15[1] = Pokemon.getDisplayName$default(this.pokemon, false, 1, null);
                  var24 = returned.getDisplayName();
                  var15[2] = var24;
                  var20 = LocalizationUtilsKt.lang("held_item.replace", var15);
               }

               PlayerExtensionsKt.giveOrDropItemStack(player, returned, false);
               (player as ServerPlayer).sendSystemMessage(var20 as Component);
               var var21: Level = this.level();
               var var10001: Vec3 = this.position();
               var var10002: SoundEvent = SoundEvents.ITEM_PICKUP;
               WorldExtensionsKt.playSoundServer$default(var21, var10001, var10002, null, 0.6F, 1.4F, 4, null);
               return true;
            }
         } else {
            var var10000: ServerPlayer = player as ServerPlayer;
            var possibleReturn: Array<Any> = new Object[2];
            var var10004: Component = stack.getHoverName();
            possibleReturn[0] = var10004;
            possibleReturn[1] = Pokemon.getDisplayName$default(this.pokemon, false, 1, null);
            var10000.sendSystemMessage(LocalizationUtilsKt.lang("held_item.forbidden", possibleReturn) as Component);
            return false;
         }
      } else {
         return false;
      }
   }

   public fun isBlacklisted(stack: ItemStack): Boolean {
      var var10000: java.lang.Iterable = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD);
      return CollectionsKt.any(var10000) && stack.is(CobblemonItemTags.BLACKLISTED_ITEMS_TO_HOLD);
   }

   public fun isWhitelisted(stack: ItemStack): Boolean {
      var var10000: java.lang.Iterable = BuiltInRegistries.ITEM.getTagOrEmpty(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD);
      return CollectionsKt.none(var10000) || stack.is(CobblemonItemTags.WHITELISTED_ITEMS_TO_HOLD);
   }

   public fun tryRidingPokemon(player: ServerPlayer): Boolean {
      if (!this.isRideableFor(player)) {
         return false;
      } else {
         var event: Pre = new Pre(player, this);
         var `$this$iv`: EventObservable = CobblemonEvents.RIDE_EVENT_PRE as EventObservable;
         var `events$iv`: Array<Pre> = new Pre[]{event};
         `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

         for (Object element$iv$iv : events$iv) {
            ;
         }

         if (event.isCanceled()) {
            return false;
         } else {
            player.startRiding(this as Entity);
            `$this$iv` = CobblemonEvents.RIDE_EVENT_POST;
            var var14: Array<Post> = new Post[]{new Post(player, this)};
            `$this$iv`.emit(Arrays.copyOf(var14, var14.length));

            for (Object element$iv$iv : var14) {
               ;
            }

            return true;
         }
      }
   }

   public fun tryMountingShoulder(player: ServerPlayer): Boolean {
      if (this.pokemon.belongsTo(player as Player) && this.hasRoomToMount(player as Player) && this.canSitOnShoulder()) {
         var `$this$iv`: CancelableObservable = CobblemonEvents.SHOULDER_MOUNT;
         var `event$iv`: Cancelable = (new ShoulderMountEvent(player, this.pokemon, player.getShoulderEntityLeft().isEmpty())) as Cancelable;
         var `this_$iv$iv`: EventObservable = `$this$iv` as EventObservable;
         var `events$iv$iv`: Array<Cancelable> = new Cancelable[]{`event$iv`};
         `this_$iv$iv`.emit(Arrays.copyOf(`events$iv$iv`, `events$iv$iv`.length));

         for (Object element$iv$iv$iv : events$iv$iv) {
            if (!`element$iv$iv$iv`.isCanceled()) {
               var it: ShoulderMountEvent = `element$iv$iv$iv` as ShoulderMountEvent;
               this.setDeltaMovement(player.getEyePosition().subtract(this.position()).multiply(1.0, 0.0, 1.0).normalize().scale(0.8).add(0.0, 0.5, 0.0));
               var lock: Any = new Object();
               this.busyLocks.add(lock);
               this.after(0.5F, PokemonEntity::tryMountingShoulder$lambda$0$0);
               return true;
            }
         }
      }

      return false;
   }

   public open fun setEntityOnShoulder(player: ServerPlayer): Boolean {
      if (!super.setEntityOnShoulder(player)) {
         return false;
      } else {
         var isLeft: Boolean = false;
         var var10000: CompoundTag = player.getShoulderEntityRight();
         if (CompoundTagExtensionsKt.isPokemonEntity(var10000)
            && player.getShoulderEntityRight().getCompound("Pokemon").getUUID("UUID") == this.pokemon.getUuid()) {
            var10000 = player.getShoulderEntityRight().copy();
         } else {
            var10000 = player.getShoulderEntityLeft();
            if (!CompoundTagExtensionsKt.isPokemonEntity(var10000)
               || !(player.getShoulderEntityLeft().getCompound("Pokemon").getUUID("UUID") == this.pokemon.getUuid())) {
               return true;
            }

            isLeft = true;
            var10000 = player.getShoulderEntityLeft().copy();
         }

         var10000.putUUID("shoulder_uuid", this.pokemon.getUuid());
         var10000.putString("shoulder_species", this.pokemon.getSpecies().getResourceIdentifier().toString());
         var10000.putString("shoulder_form", this.pokemon.getForm().getName());
         var `$this$map$iv`: java.lang.Iterable = this.pokemon.getAspects();
         var var7: java.util.Collection = new ArrayList(CollectionsKt.collectionSizeOrDefault(`$this$map$iv`, 10));

         for (Object item$iv$iv : $this$map$iv) {
            var7.add(StringTag.valueOf(`item$iv$iv` as java.lang.String));
         }

         var10000.put("shoulder_aspects", CollectionUtilsKt.toNbtList(var7 as java.util.List) as Tag);
         var10000.putFloat("shoulder_scale", this.pokemon.getEffectiveScale());
         var var18: Tag = if (this.getShownItem().isEmpty())
            (new CompoundTag()) as Tag
            else
            this.getShownItem().saveOptional(this.level().registryAccess() as Provider);
         var10000.put("shoulder_item", (var18 as CompoundTag) as Tag);
         if (isLeft) {
            player.setShoulderEntityLeft(var10000);
         } else {
            player.setShoulderEntityRight(var10000);
         }

         return true;
      }
   }

   public fun getAdjustedSendoutPosition(pos: Vec3): Vec3 {
      var platform: PlatformType = PlatformType.NONE;
      var blockPos: BlockPos = new BlockPos((int)pos.x, (int)pos.y, (int)pos.z);
      var foundSurface: Boolean = false;
      var exposedForm: FormData = this.getExposedForm();
      var result: Vec3 = pos;
      if (this.level().isWaterAt(blockPos)) {
         var canFly: BlockPos = blockPos;
         if (!exposedForm.getBehaviour().getMoving().getSwim().getCanBreatheUnderwater() || exposedForm.getBehaviour().getMoving().getFly().getCanFly()) {
            var halfHeight: Int = 0;
            var var10: Int = 5;

            while (true) {
               var i: BlockState = this.level().getBlockState(canFly);
               if (i.getFluidState().isEmpty()) {
                  if (i.getCollisionShape(this.level() as BlockGetter, canFly).isEmpty()) {
                     foundSurface = true;
                  }
                  break;
               }

               var var10000: BlockPos = canFly.above();
               canFly = var10000;
               if (halfHeight == var10) {
                  break;
               }

               halfHeight++;
            }

            if (foundSurface) {
               if (!this.collidesWithBlock(this as Entity, new Vec3((double)blockPos.getX(), (double)blockPos.getY(), (double)blockPos.getZ()))) {
                  result = new Vec3(pos.x, (double)canFly.getY(), pos.z);
               }
            } else {
               foundSurface = false;
            }
         }
      } else if (this.level().isEmptyBlock(blockPos)) {
         var var14: BlockPos = new BlockPos((int)pos.x, (int)pos.y, (int)pos.z);
         var var17: Int = 0;
         var var20: Byte = 64;

         while (true) {
            var blockStatex: BlockState = this.level().getBlockState(var14);
            if (!blockStatex.getFluidState().isEmpty()) {
               foundSurface = true;
               break;
            }

            if (!blockStatex.getCollisionShape(this.level() as BlockGetter, var14).isEmpty()) {
               break;
            }

            var var23: BlockPos = var14.below();
            var14 = var23;
            if (var17 == var20) {
               break;
            }

            var17++;
         }
      }

      if (foundSurface) {
         if (exposedForm.getBehaviour().getMoving().getFly().getCanFly()) {
            if (!this.collidesWithBlock(this as Entity, new Vec3((double)blockPos.getX(), result.y + (double)1, (double)blockPos.getZ()))) {
               result = new Vec3(result.x, result.y + 1.0, result.z);
            }
         } else if (exposedForm.getBehaviour().getMoving().getSwim().getCanBreatheUnderwater()
            && !exposedForm.getBehaviour().getMoving().getSwim().getCanWalkOnWater()) {
            var var10001: Pose = this.getPose();
            var var19: Double = (double)this.getDimensions(var10001).height() / 2.0;
            var var22: Int = 1;
            var var12: Int = (int)var19;
            if (1 <= (int)var19) {
               while (true) {
                  var var24: BlockPos = blockPos.below();
                  blockPos = var24;
                  if (!this.level().isWaterAt(var24)
                     || !this.level().getBlockState(var24).getCollisionShape(this.level() as BlockGetter, var24).isEmpty()
                     || var22 == var12) {
                     break;
                  }

                  var22++;
               }
            }

            result = new Vec3(result.x, result.y + var19 - (double)((int)var19), result.z);
         } else {
            platform = if (!exposedForm.getBehaviour().getMoving().getSwim().getCanWalkOnWater()
                  && !this.collidesWithBlock(this as Entity, new Vec3(result.x, result.y, result.z)))
               PlatformType.Companion.getPlatformTypeForPokemon(exposedForm)
               else
               PlatformType.NONE;
         }
      }

      this.setPlatform(platform);
      return result;
   }

   private fun Entity.collidesWithBlock(pos: Vec3): Boolean {
      return `$this$collidesWithBlock`.level()
         .getBlockCollisions(`$this$collidesWithBlock`, `$this$collidesWithBlock`.getBoundingBox().move(pos))
         .iterator()
         .hasNext();
   }

   public open fun remove(reason: RemovalReason) {
      var `$i$f$forEach`: PokemonState = this.pokemon.getState();
      var stateEntity: PokemonEntity = if ((`$i$f$forEach` as? ActivePokemonState) != null) (`$i$f$forEach` as? ActivePokemonState).getEntity() else null;
      super.remove(reason);
      if (stateEntity == this) {
         this.pokemon.setState((new InactivePokemonState()) as PokemonState);
      }

      var `$this$forEach$iv`: java.lang.Iterable;
      for (Object element$iv : $this$forEach$iv) {
         (`element$iv` as ObservableSubscription).unsubscribe();
      }

      this.removalObservable.emit(new RemovalReason[]{reason});
      this.brain.clearMemories();
      if (reason.shouldDestroy() && this.pokemon.getTetheringId() != null) {
         this.pokemon.setTetheringId(null);
      }

      if (this.evolutionEntity != null) {
         var var11: GenericBedrockEntity = this.evolutionEntity;
         var11.kill();
         var var12: PokemonEntity = this.pokemon.getEntity();
         if (var12 != null) {
            var12.evolutionEntity = null;
         }
      }
   }

   public fun hasRoomToMount(player: Player): Boolean {
      return (player.getShoulderEntityLeft().isEmpty() || player.getShoulderEntityRight().isEmpty())
         && !player.isPassenger()
         && player.onGround()
         && !player.isInWater()
         && !player.isInPowderSnow;
   }

   public fun cry() {
      if (!this.isSilent()) {
         var pkt: PlayPosableAnimationPacket = new PlayPosableAnimationPacket(this.getId(), SetsKt.setOf("cry"), CollectionsKt.emptyList());

         var `$this$forEach$iv`: java.lang.Iterable;
         for (Object element$iv : $this$forEach$iv) {
            var it: ServerPlayer = `element$iv` as ServerPlayer;
            var var8: CobblemonNetwork = CobblemonNetwork.INSTANCE;
            var8.sendPacket(it, pkt as NetworkPacket);
         }
      }
   }

   protected open fun dropAllDeathLoot(world: ServerLevel, source: DamageSource) {
      if (this.pokemon.isWild()) {
         super.dropAllDeathLoot(world, source);
         this.getDelegate().drop(source);
      }
   }

   protected open fun dropExperience(attacker: Entity?) {
      if (this.level() is ServerLevel
         && !this.wasExperienceConsumed()
         && (
            this.isAlwaysExperienceDropper()
               || this.lastHurtByPlayerTime > 0 && this.shouldDropExperience() && this.level().getGameRules().getBoolean(CobblemonGameRules.DO_POKEMON_LOOT)
         )) {
         var var10000: Level = this.level();
         ExperienceOrb.award(var10000 as ServerLevel, this.position(), this.getBaseExperienceReward());
      }
   }

   protected open fun tickDeath() {
      this.getDelegate().updatePostDeath();
   }

   public open fun ate() {
      super.ate();
      var feature: FlagSpeciesFeature = this.pokemon.getFeature("sheared");
      if (feature != null) {
         feature.setEnabled(false);
         this.pokemon.markFeatureDirty(feature as SpeciesFeature);
         this.pokemon.updateAspects();
      }
   }

   public open fun handleRelativeFrictionAndCalculateMovement(deltaMovement: Vec3, friction: Float): Vec3 {
      if (this.hasRider() && this.getControllingPassenger() != null) {
         this.setDeltaMovement(
            this.getDeltaMovement()
               .lerp(
                  ShoulderRidingEntity.getInputVector(
                     this.ifRidingAvailableSupply(Vec3.ZERO, PokemonEntity::handleRelativeFrictionAndCalculateMovement$lambda$0), 1.0F, this.getYRot()
                  ),
                  this.ifRidingAvailableSupply(0.5, PokemonEntity::handleRelativeFrictionAndCalculateMovement$lambda$1).doubleValue()
               )
         );
         var pos: Vec3 = this.getDeltaMovement().scale((double)this.getSpeed());
         if (super.onGround() && this.getDeltaMovement().y == 0.0) {
            pos = pos.subtract(0.0, 1.0E-4, 0.0);
         }

         var var10001: MoverType = MoverType.SELF;
         this.move(var10001, pos);
      } else {
         super.handleRelativeFrictionAndCalculateMovement(deltaMovement, friction);
      }

      var var10000: Vec3 = this.getDeltaMovement();
      return var10000;
   }

   public open fun move(type: MoverType, pos: Vec3) {
      if ((this.getControllingPassenger() != null || this.hasRider()) && this.getDeltaMovement().y() > -0.5 && this.fallDistance > 1.0F) {
         this.fallDistance = 1.0F;
      }

      super.move(type, pos);
   }

   public open fun travel(movementInput: Vec3) {
      var prevBlockPos: BlockPos = this.blockPosition();
      if (this.getBeamMode() != 3) {
         if (this.hasRider() && this.getControllingPassenger() != null) {
            var inp: Vec3 = this.ifRidingAvailableSupply(Vec3.ZERO, PokemonEntity::travel$lambda$0);
            var f: Float = Mth.sin(AngleExtensionsKt.toRadians(this.getYRot()));
            var g: Float = Mth.cos(AngleExtensionsKt.toRadians(this.getYRot()));
            this.setDeltaMovement(
               this.getDeltaMovement()
                  .add(
                     new Vec3(inp.x * (double)g - inp.z * (double)f, inp.y, inp.z * (double)g + inp.x * (double)f)
                        .subtract(this.getDeltaMovement())
                        .scale(this.ifRidingAvailableSupply(0.5, PokemonEntity::travel$lambda$1).doubleValue())
                  )
            );
            var triedMovement: Vec3 = this.getDeltaMovement();
            var var10001: MoverType = MoverType.SELF;
            var var10002: Vec3 = this.getDeltaMovement();
            this.move(var10001, var10002);
            if (this.horizontalCollision && this.isControlledByLocalInstance()) {
               this.ifRidingAvailable(PokemonEntity::travel$lambda$2);
            }
         } else {
            super.travel(movementInput);
         }

         if (this.pokemon.hasBlocksTraveledRequirement()) {
            this.updateBlocksTraveled(prevBlockPos);
         }
      }

      if (this.isBattling() && this.isInWater()) {
         this.setDeltaMovement(new Vec3(this.getDeltaMovement().x, 0.0, this.getDeltaMovement().z));
      }
   }

   private fun updateBlocksTraveled(fromBp: BlockPos) {
      if (!this.isPassenger() && !this.isFalling()) {
         var blocksTaken: Double = this.blockPosition().distSqr(fromBp as Vec3i);
         if (blocksTaken > 0.0) {
            this.pokemon.addBlocksTraveled((int)blocksTaken);
         }
      }
   }

   protected open fun pushEntities() {
      if (this.getBeamMode() != 3) {
         super.pushEntities();
      }
   }

   public open fun isPushable(): Boolean {
      return this.getBeamMode() != 3 && super.isPushable();
   }

   public open fun tame(player: Player) {
      if (this.pokemon.isWild() && this.isAlive() && this.getOwnerUUID() == null) {
         super.tame(player);
         if (player is ServerPlayer) {
            var party: PlayerPartyStore = PlayerExtensionsKt.party(player as ServerPlayer);
            if (party.getFirstAvailablePosition() == null) {
               this.discard();
            }

            party.add(this.pokemon);
            this.pokemon.setState((new SentOutState(this)) as PokemonState);
         }
      }
   }

   public open fun isTame(): Boolean {
      return this.getOwnerUUID() != null || !this.pokemon.isWild();
   }

   public fun isFalling(): Boolean {
      return this.fallDistance > 0.0F && this.level().getBlockState(this.blockPosition().below()).isAir() && !this.isFlying();
   }

   protected open fun checkFallDamage(y: Double, onGround: Boolean, state: BlockState, pos: BlockPos) {
      super.checkFallDamage(y, onGround, state, pos);
      if (this.isFlying() && this.getPassengers().isEmpty() && y < 0.0 && !onGround) {
         this.fallDistance = 0.0F;
      }
   }

   public open fun getCurrentPoseType(): PoseType {
      var var10000: Any = this.entityData.get(POSE_TYPE);
      return var10000 as PoseType;
   }

   protected open fun getTypeName(): Component {
      return this.pokemon.getSpecies().getTranslatedName() as Component;
   }

   public open fun getName(): Component {
      if (!this.entityData.get(NICKNAME_VISIBLE) as java.lang.Boolean) {
         return this.getTypeName();
      } else {
         var var1: Any = this.entityData.get(NICKNAME);
         var var10000: Component = (if (!((var1 as Component).getContents() == PlainTextContents.EMPTY)) var1 else null) as Component;
         if (var10000 == null) {
            var10000 = Pokemon.getDisplayName$default(this.pokemon, false, 1, null) as Component;
         }

         return var10000;
      }
   }

   public fun getTitledName(): MutableComponent {
      var it: java.lang.String = this.entityData.get(MARK) as java.lang.String;
      var var10000: ResourceLocation = MiscUtilsKt.asResource(it);
      var var4: Mark = Marks.getByIdentifier(var10000);
      if (var4 == null) {
         var4 = this.pokemon.getActiveMark();
      }

      if (var4 != null) {
         var var10001: MutableComponent = this.getName().copy();
         var var5: MutableComponent = var4.getTitle(var10001);
         if (var5 != null) {
            return var5;
         }
      }

      var var6: MutableComponent = this.getName().copy();
      return var6;
   }

   public open fun getCustomName(): Component? {
      return this.pokemon.getNickname() as Component;
   }

   public open fun setCustomName(name: Component?) {
      this.pokemon.setNickname(Component.literal(if (name != null) name.getString() else null));
   }

   public open fun hasCustomName(): Boolean {
      if (this.pokemon.getNickname() != null) {
         var var10000: MutableComponent = this.pokemon.getNickname();
         if (!((if (var10000 != null) var10000.getContents() else null) == PlainTextContents.EMPTY)) {
            return true;
         }
      }

      return false;
   }

   public open fun setCustomNameVisible(visible: Boolean) {
      this.entityData.set(NICKNAME_VISIBLE, visible);
   }

   public fun forceBattle(player: ServerPlayer): Boolean {
      if (!this.canBattle(player as Player)) {
         return false;
      } else {
         var var5: java.util.Iterator = (PlayerExtensionsKt.party(player) as java.lang.Iterable).iterator();

         var var10000: Any;
         while (true) {
            if (var5.hasNext()) {
               var `element$iv`: Any = var5.next();
               if ((`element$iv` as Pokemon).getEntity() == null) {
                  continue;
               }

               var10000 = `element$iv`;
               break;
            }

            var10000 = null;
            break;
         }

         return BattleBuilder.pve$default(
               BattleBuilder.INSTANCE,
               player,
               this,
               if (var10000 as Pokemon != null) (var10000 as Pokemon).getUuid() else null,
               null,
               false,
               false,
               0.0F,
               null,
               248,
               null
            )
            .ifSuccessful(PokemonEntity::forceBattle$lambda$1) is SuccessfulBattleStart;
      }
   }

   public open fun isCustomNameVisible(): Boolean {
      var var10000: Any = this.entityData.get(NICKNAME_VISIBLE);
      return var10000 as java.lang.Boolean;
   }

   public open fun shouldShowName(): Boolean {
      var var10000: Any = this.entityData.get(SHOULD_RENDER_NAME);
      return var10000 as java.lang.Boolean;
   }

   public fun hideNameRendering() {
      this.entityData.set(SHOULD_RENDER_NAME, false);
   }

   public open fun isFood(stack: ItemStack): Boolean {
      return false;
   }

   public open fun canMate(other: Animal): Boolean {
      return false;
   }

   public open fun spawnChildFromBreeding(world: ServerLevel, other: Animal) {
   }

   public open fun dampensVibrations(): Boolean {
      return this.pokemon.dampensVibrations();
   }

   public open fun shear(shearedSoundCategory: SoundSource) {
      var slowpokeTailFeature: SlowpokeTailRegrowthSpeciesFeature = SlowpokeTailRegrowthSpeciesFeatureProvider.INSTANCE.getFromPokemon(this.pokemon);
      if (slowpokeTailFeature != null && slowpokeTailFeature.getRegrowthSeconds() <= 0) {
         this.level().playSound(null, this as Entity, SoundEvents.SHEEP_SHEAR, shearedSoundCategory, 1.0F, 1.0F);
         slowpokeTailFeature.onShear(this);
      } else {
         var var10000: FlagSpeciesFeature = this.pokemon.getFeature("sheared");
         if (var10000 != null) {
            this.level().playSound(null, this as Entity, SoundEvents.SHEEP_SHEAR, shearedSoundCategory, 1.0F, 1.0F);
            var10000.setEnabled(true);
            this.pokemon.markFeatureDirty(var10000 as SpeciesFeature);
            this.pokemon.updateAspects();
            var i: Int = this.random.nextInt(3) + 1;
            var j: Int = 0;
            if (0 <= i) {
               while (true) {
                  label76: {
                     var var9: StringSpeciesFeature = this.pokemon.getFeature("color");
                     if (var9 != null) {
                        var10 = var9.getValue();
                        if (var10 != null) {
                           break label76;
                        }
                     }

                     var10 = "white";
                  }

                  label96: {
                     switch (var10.hashCode()) {
                        case -1008851410:
                           if (var10.equals("orange")) {
                              var11 = Items.ORANGE_WOOL;
                              break label96;
                           }
                           break;
                        case -976943172:
                           if (var10.equals("purple")) {
                              var11 = Items.PURPLE_WOOL;
                              break label96;
                           }
                           break;
                        case -734239628:
                           if (var10.equals("yellow")) {
                              var11 = Items.YELLOW_WOOL;
                              break label96;
                           }
                           break;
                        case -209096221:
                           if (var10.equals("light_blue")) {
                              var11 = Items.LIGHT_BLUE_WOOL;
                              break label96;
                           }
                           break;
                        case -208942100:
                           if (var10.equals("light_gray")) {
                              var11 = Items.LIGHT_GRAY_WOOL;
                              break label96;
                           }
                           break;
                        case 112785:
                           if (var10.equals("red")) {
                              var11 = Items.RED_WOOL;
                              break label96;
                           }
                           break;
                        case 3027034:
                           if (var10.equals("blue")) {
                              var11 = Items.BLUE_WOOL;
                              break label96;
                           }
                           break;
                        case 3068707:
                           if (var10.equals("cyan")) {
                              var11 = Items.CYAN_WOOL;
                              break label96;
                           }
                           break;
                        case 3181155:
                           if (var10.equals("gray")) {
                              var11 = Items.GRAY_WOOL;
                              break label96;
                           }
                           break;
                        case 3321813:
                           if (var10.equals("lime")) {
                              var11 = Items.LIME_WOOL;
                              break label96;
                           }
                           break;
                        case 3441014:
                           if (var10.equals("pink")) {
                              var11 = Items.PINK_WOOL;
                              break label96;
                           }
                           break;
                        case 93818879:
                           if (var10.equals("black")) {
                              var11 = Items.BLACK_WOOL;
                              break label96;
                           }
                           break;
                        case 94011702:
                           if (var10.equals("brown")) {
                              var11 = Items.BROWN_WOOL;
                              break label96;
                           }
                           break;
                        case 98619139:
                           if (var10.equals("green")) {
                              var11 = Items.GREEN_WOOL;
                              break label96;
                           }
                           break;
                        case 828922025:
                           if (var10.equals("magenta")) {
                              var11 = Items.MAGENTA_WOOL;
                              break label96;
                           }
                        default:
                     }

                     var11 = Items.WHITE_WOOL;
                  }

                  var var12: ItemEntity = this.spawnAtLocation(var11 as ItemLike, 1);
                  if (var12 == null) {
                     return;
                  }

                  EntityExtensionsKt.jitterDropItem(this as LivingEntity, var12);
                  if (j == i) {
                     break;
                  }

                  j++;
               }
            }
         }
      }
   }

   public open fun readyForShearing(): Boolean {
      if (!this.isBusy() && !this.pokemon.isFainted()) {
         var slowpokeRegrowthFeature: SlowpokeTailRegrowthSpeciesFeature = SlowpokeTailRegrowthSpeciesFeatureProvider.INSTANCE.getFromPokemon(this.pokemon);
         var var10000: Boolean;
         if (slowpokeRegrowthFeature != null && slowpokeRegrowthFeature.getRegrowthSeconds() > 0) {
            var10000 = false;
         } else if (slowpokeRegrowthFeature != null) {
            var10000 = true;
         } else {
            var var2: FlagSpeciesFeature = this.pokemon.getFeature("sheared");
            var10000 = var2 != null && !var2.getEnabled();
         }

         return var10000;
      } else {
         return false;
      }
   }

   public open fun canUsePortal(allowsVehicles: Boolean): Boolean {
      return false;
   }

   public open fun setAirSupply(air: Int) {
      if (this.isBattling()) {
         this.entityData.set(ShoulderRidingEntity.DATA_AIR_SUPPLY_ID, 300);
      } else {
         super.setAirSupply(air);
      }
   }

   public open fun stopSeenByPlayer(player: ServerPlayer) {
      if (this.getOwnerUUID() == player.getUUID() && this.tethering == null) {
         if (this.getRemovalReason() != RemovalReason.DISCARDED) {
            this.remove(RemovalReason.DISCARDED);
         }
      }
   }

   public open fun canBeLeashed(): Boolean {
      return true;
   }

   public open fun setLeashedTo(entity: Entity, bl: Boolean) {
      super.setLeashedTo(entity, bl);
      if (this.getOwnerUUID() != null && !(this.getOwnerUUID() == entity.getUUID())) {
         this.dropLeash(true, true);
      }
   }

   public fun getBattleTheme(): ResourceLocation {
      return this.getForm().getBattleTheme();
   }

   private fun createSidedPokemon(): Pokemon {
      var var1: Pokemon = new Pokemon();
      var1.setClient$common(this.level().isClientSide);
      return var1;
   }

   protected open fun canRide(vehicle: Entity): Boolean {
      return this.getPlatform() === PlatformType.NONE && super.canRide(vehicle) && this.occupiedSeats.isEmpty();
   }

   public fun getRideStat(rideStat: RidingStat, style: RidingStyle, baseMin: Double, baseMax: Double): Double {
      if (this.rideStatOverrides.get(style) != null) {
         var var10000: Any = this.rideStatOverrides.get(style);
         if ((var10000 as java.util.Map).get(rideStat) != null) {
            var var12: Double = (baseMax - baseMin) / (double)100;
            var var10001: Any = this.rideStatOverrides.get(style);
            var10001 = (var10001 as java.util.Map).get(rideStat);
            return var12 * (var10001 as java.lang.Number).doubleValue() + baseMin;
         }
      }

      var var10: java.util.Map = this.getRideProp().getBehaviours();
      if (var10 != null) {
         var var11: RidingBehaviourSettings = var10.get(style) as RidingBehaviourSettings;
         if (var11 != null) {
            var var10002: java.lang.Float = (this.entityData.get(RIDE_BOOSTS) as java.util.Map).get(rideStat) as java.lang.Float;
            return Math.max((baseMax - baseMin) / (double)100 * (double)var11.calculate(rideStat, var10002 ?: 0.0F) + baseMin, 1.0E-6);
         }
      }

      return 0.0;
   }

   protected open fun couldAcceptPassenger(): Boolean {
      return !this.getSeats().isEmpty() && super.couldAcceptPassenger();
   }

   public fun getRawRideStat(stat: RidingStat, style: RidingStyle): Double {
      if (this.rideStatOverrides.get(style) != null) {
         var var10000: Any = this.rideStatOverrides.get(style);
         if ((var10000 as java.util.Map).get(stat) != null) {
            var10000 = this.rideStatOverrides.get(style);
            var10000 = (var10000 as java.util.Map).get(stat);
            return (var10000 as java.lang.Number).doubleValue();
         }
      }

      var var3: java.util.Map = this.getRideProp().getBehaviours();
      if (var3 != null) {
         var var4: RidingBehaviourSettings = var3.get(style) as RidingBehaviourSettings;
         if (var4 != null) {
            return (double)var4.calculate(stat, 0.0F);
         }
      }

      return 0.0;
   }

   internal fun overrideRideStat(style: RidingStyle, stat: RidingStat, value: Double) {
      if (this.rideStatOverrides.get(style) == null) {
         this.rideStatOverrides.put(style, new LinkedHashMap<>());
      }

      var var10000: Any = this.rideStatOverrides.get(style);
      (var10000 as java.util.Map).put(stat, value);
   }

   protected open fun canAddPassenger(passenger: Entity): Boolean {
      var passengerSeated: java.lang.Iterable = this.getSeats();
      var var10000: Boolean;
      if (passengerSeated is java.util.Collection && (passengerSeated as java.util.Collection).isEmpty()) {
         var10000 = false;
      } else {
         label44: {
            for (Object element$iv : $this$any$iv) {
               if (!this.occupiedSeats.keySet().contains(`element$iv` as Seat)) {
                  var10000 = true;
                  break label44;
               }
            }

            var10000 = false;
         }
      }

      return var10000 && !this.occupiedSeats.values().contains(passenger);
   }

   public open fun addPassenger(passenger: Entity) {
      if (this.getBeamMode() != 3) {
         if (passenger is ServerPlayer) {
            var availableSeat: java.lang.Iterable = PlayerExtensionsKt.party(passenger as ServerPlayer) as java.lang.Iterable;
            var `$this$forEach$iv$iv`: java.util.Collection = new ArrayList();

            for (Object element$iv$iv$iv : availableSeat) {
               var var10000: PokemonEntity = (event as Pokemon).getEntity();
               if (var10000 != null) {
                  `$this$forEach$iv$iv`.add(var10000);
               }
            }

            availableSeat = `$this$forEach$iv$iv` as java.util.List;
            `$this$forEach$iv$iv` = new ArrayList();

            for (Object element$iv$iv : availableSeat) {
               if (!(var39 as PokemonEntity == this)) {
                  `$this$forEach$iv$iv`.add(var39);
               }
            }

            for (Object element$iv : availableSeat) {
               (var29 as PokemonEntity).recallWithAnimation();
            }

            if (this.getPassengers().isEmpty()) {
               var var19: EventObservable = CobblemonEvents.RIDE_EVENT_APPLY_STAMINA;
               var var23: Array<ApplyStamina> = new ApplyStamina[1];
               var var10004: ServerPlayer = passenger as ServerPlayer;
               var var25: java.lang.Float = if (Cobblemon.INSTANCE.getConfig().getInfiniteRideStamina())
                  -1.0F
                  else
                  this.entityData.get(RIDE_STAMINA) as java.lang.Float;
               var23[0] = new ApplyStamina(var10004, this, var25.floatValue());
               var19.emit(Arrays.copyOf(var23, var23.length));

               for (Object element$iv$ivx : var23) {
                  this.entityData.set(RIDE_STAMINA, `element$iv$ivx`.getRideStamina());
               }
            }
         } else if (this.level().isClientSide) {
            MountedCameraTypeHandler.INSTANCE.handleMount(passenger, this);
         }

         if (!this.occupiedSeats.values().contains(passenger)) {
            var var45: Any;
            label67: {
               var var24: java.lang.Iterable;
               for (Object element$iv : var24) {
                  if (!this.occupiedSeats.containsKey(var35 as Seat)) {
                     var45 = var35;
                     break label67;
                  }
               }

               var45 = null;
            }

            var var20: Seat = var45 as Seat;
            if (var45 as Seat != null) {
               this.occupiedSeats.put(var20, passenger);
            }
         }

         super.addPassenger(passenger);
         if (this.getPassengers().size() == 1) {
            this.ifRidingAvailable(PokemonEntity::addPassenger$lambda$5);
         }
      }
   }

   public fun getIsJumping(): Boolean {
      return this.jumping;
   }

   public fun setIsJumping(value: Boolean) {
      this.jumping = value;
   }

   public fun ifRidingAvailable(block: (RidingBehaviour<RidingBehaviourSettings, RidingBehaviourState>, RidingBehaviourSettings, RidingBehaviourState) -> Unit) {
      if (this.ridingController != null) {
         var var10000: RidingBehaviour = this.ridingController.getBehaviour();
         if (var10000 != null) {
            if (this.ridingController != null) {
               var var5: ActiveRidingContext = this.ridingController.getContext();
               if (var5 != null) {
                  var var6: RidingBehaviourSettings = var5.getSettings();
                  if (var6 != null) {
                     if (this.ridingController != null) {
                        var var7: ActiveRidingContext = this.ridingController.getContext();
                        if (var7 != null) {
                           var var8: RidingBehaviourState = var7.getState();
                           if (var8 != null) {
                              block.invoke(var10000, var6, var8);
                              return;
                           }
                        }
                     }

                     return;
                  }
               }
            }

            return;
         }
      }
   }

   public fun <T> ifRidingAvailableSupply(
      fallback: Any,
      block: (RidingBehaviour<RidingBehaviourSettings, RidingBehaviourState>, RidingBehaviourSettings, RidingBehaviourState) -> Any
   ): Any {
      var result: ObjectRef = new ObjectRef();
      result.element = fallback;
      this.ifRidingAvailable(PokemonEntity::ifRidingAvailableSupply$lambda$0);
      return (T)result.element;
   }

   protected open fun tickRidden(driver: Player, movementInput: Vec3) {
      super.tickRidden(driver, movementInput);
      this.ifRidingAvailable(PokemonEntity::tickRidden$lambda$0);
   }

   public fun Entity.isNearGround(): Boolean {
      var var10000: BlockPos = `$this$isNearGround`.blockPosition().below();
      return `$this$isNearGround`.level().getBlockState(var10000).isSolid();
   }

   public fun clampPassengerRotation(entityToUpdate: Entity) {
      if (entityToUpdate is LivingEntity) {
         this.ifRidingAvailable(PokemonEntity::clampPassengerRotation$lambda$0);
      }
   }

   protected open fun positionRider(passenger: Entity, positionUpdater: MoveFunction) {
      if (this.hasPassenger(passenger)) {
         this.getDelegate().positionRider(passenger, positionUpdater);
         if (passenger is LivingEntity) {
            this.ifRidingAvailable(PokemonEntity::positionRider$lambda$0);
         }
      }
   }

   public open fun shouldRenderAtSqrDistance(distance: Double): Boolean {
      if (!this.getPassengers().isEmpty()) {
         var d: Double = this.getBoundingBox().getSize() * 10.0;
         if (java.lang.Double.isNaN(d)) {
            d = 1.0;
         }

         var scale: Double = 64.0 * ShoulderRidingEntity.getViewScale();
         return distance < d * scale * scale;
      } else {
         return super.shouldRenderAtSqrDistance(distance);
      }
   }

   public open fun getControllingPassenger(): LivingEntity? {
      var var10000: java.util.List = this.getPassengers();
      var event: java.lang.Iterable = var10000;
      var `destination$iv$iv`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$filterIsInstance$iv) {
         if (it is LivingEntity) {
            `destination$iv$iv`.add(it);
         }
      }

      var riders: java.util.List = `destination$iv$iv` as java.util.List;
      if ((`destination$iv$iv` as java.util.List).isEmpty()) {
         if (this.ridingController != null) {
            var var18: ActiveRidingContext = this.ridingController.getContext();
            if (var18 != null) {
               var var19: RidingBehaviourState = var18.getState();
               if (var19 != null) {
                  var19.reset();
               }
            }
         }

         return null;
      } else {
         var var10: SelectDriverEvent = new SelectDriverEvent(CollectionsKt.toSet(riders));
         var var13: java.util.Iterator = riders.iterator();

         while (true) {
            if (var13.hasNext()) {
               var var14: Any = var13.next();
               if (!((var14 as LivingEntity).getUUID() == this.getOwnerUUID())) {
                  continue;
               }

               var10000 = (java.util.List)var14;
               break;
            }

            var10000 = null;
            break;
         }

         var var11: LivingEntity = var10000 as LivingEntity;
         if (var10000 as LivingEntity != null) {
            var10.suggest(var11, 0);
         }

         CobblemonEvents.SELECT_DRIVER.emit(new SelectDriverEvent[]{var10});
         return var10.result();
      }
   }

   public open fun getDismountLocationForPassenger(passenger: LivingEntity): Vec3 {
      var vec3: Vec3 = ShoulderRidingEntity.getCollisionHorizontalEscapeVector(
         (double)this.getBbWidth(), (double)passenger.getBbWidth(), this.getYRot() + (if (passenger.getMainArm() === HumanoidArm.RIGHT) 90.0F else -90.0F)
      );
      var vec32: Vec3 = this.getDismountLocationInDirection(vec3, passenger);
      if (vec32 != null) {
         return vec32;
      } else {
         var vec33: Vec3 = ShoulderRidingEntity.getCollisionHorizontalEscapeVector(
            (double)this.getBbWidth(), (double)passenger.getBbWidth(), this.getYRot() + (if (passenger.getMainArm() === HumanoidArm.LEFT) 90.0F else -90.0F)
         );
         var vec34: Vec3 = this.getDismountLocationInDirection(vec33, passenger);
         var var10000: Vec3 = vec34;
         if (vec34 == null) {
            var10000 = this.position();
         }

         return var10000;
      }
   }

   private fun getDismountLocationInDirection(direction: Vec3, passenger: LivingEntity): Vec3? {
      var d: Double = this.getX() + direction.x;
      var e: Double = this.getBoundingBox().minY;
      var f: Double = this.getZ() + direction.z;
      var mutableBlockPos: MutableBlockPos = new MutableBlockPos();
      var var10000: UnmodifiableIterator = passenger.getDismountPoses().iterator();
      var var10: UnmodifiableIterator = var10000;

      while (var10.hasNext()) {
         var10000 = (UnmodifiableIterator)var10.next();
         var pose: Pose = var10000 as Pose;
         mutableBlockPos.set(d, e, f);
         var g: Double = this.getBoundingBox().maxY + 0.75;

         do {
            var h: Double = this.level().getBlockFloorHeight(mutableBlockPos as BlockPos);
            if ((double)mutableBlockPos.getY() + h > g) {
               break;
            }

            if (DismountHelper.isBlockFloorValid(h)) {
               var aABB: AABB = passenger.getLocalBoundsForPose(pose);
               var vec3: Vec3 = new Vec3(d, (double)mutableBlockPos.getY() + h, f);
               if (DismountHelper.canDismountTo(this.level() as CollisionGetter, passenger, aABB.move(vec3))) {
                  passenger.setPose(pose);
                  return vec3;
               }
            }

            mutableBlockPos.move(Direction.UP);
         } while (!((double)mutableBlockPos.getY() < g));
      }

      return null;
   }

   protected open fun getRiddenInput(controller: Player, movementInput: Vec3): Vec3 {
      return this.ifRidingAvailableSupply(Vec3.ZERO, PokemonEntity::getRiddenInput$lambda$0);
   }

   public open fun maxUpStep(): Float {
      var upStep: java.lang.Float = this.ifRidingAvailableSupply(null, PokemonEntity::maxUpStep$lambda$0);
      return upStep ?: super.maxUpStep();
   }

   protected open fun getRiddenSpeed(controller: Player): Float {
      return this.ifRidingAvailableSupply(0.05F, PokemonEntity::getRiddenSpeed$lambda$0).floatValue();
   }

   public open fun onPlayerJump(strength: Int) {
      var strengthx: Int = strength;
      if (strength < 0) {
         strengthx = 0;
      }

      this.jumpInputStrength = strengthx;
      if (strengthx >= 90) {
      }
   }

   public open fun canJump(): Boolean {
      return true;
   }

   public open fun handleStartJump(height: Int) {
      this.jumping = true;
   }

   public fun side(): String {
      return if (this.getDelegate() is PokemonServerDelegate) "SERVER" else "CLIENT";
   }

   public open fun handleStopJump() {
      this.jumping = false;
   }

   protected open fun isAffectedByFluids(): Boolean {
      var fluidAffected: Boolean = true;
      if (this.hasControllingPassenger()) {
         fluidAffected = false;
      }

      return fluidAffected;
   }

   public open fun onGround(): Boolean {
      var result: java.lang.Boolean = this.ifRidingAvailableSupply(null, PokemonEntity::onGround$lambda$0);
      if (result != null && result) {
         return false;
      } else {
         return (this.getBehaviour().getMoving().getWalk().getCanWalk() || !this.getBehaviour().getMoving().getFly().getCanFly()) && super.onGround();
      }
   }

   public open fun dismountsUnderwater(): Boolean {
      return false;
   }

   protected open fun getDefaultGravity(): Double {
      var regularGravity: Double = super.getDefaultGravity();
      return if (this.getPassengers().isEmpty())
         regularGravity
         else
         this.ifRidingAvailableSupply(regularGravity, PokemonEntity::getDefaultGravity$lambda$0).doubleValue();
   }

   public fun setRideBar(): Float {
      var var2: LivingEntity = this.getControllingPassenger();
      return if ((var2 as? Player) == null) 0.0F else this.ifRidingAvailableSupply(0.0F, PokemonEntity::setRideBar$lambda$0).floatValue();
   }

   public fun rideFovMult(): Float {
      var var3: LivingEntity = this.getControllingPassenger();
      return if ((var3 as? Player) == null) 1.0F else this.ifRidingAvailableSupply(1.0F, PokemonEntity::rideFovMult$lambda$0).floatValue();
   }

   private fun sidedCodec(): Codec<Pokemon> {
      return if (this.level().isClientSide) Pokemon.Companion.getCLIENT_CODEC() else Pokemon.Companion.getCODEC();
   }

   public open fun resolvePokemonScan(): PokedexEntityData? {
      var var10000: Pokemon = this.pokemon;
      var var10001: MocKEffect = this.effects.getMockEffect();
      var var7: DisguiseData;
      if (var10001 != null) {
         var var6: DisguiseData = new DisguiseData;
         var var10002: Species = var10001.getExposedSpecies();
         if (var10002 == null) {
            var10002 = this.pokemon.getSpecies();
         }

         var var10003: FormData = var10001.getExposedForm();
         if (var10003 == null) {
            var10003 = this.pokemon.getForm();
         }

         var6./* $VF: Unable to resugar constructor */<init>(var10002, var10003);
         var7 = var6;
      } else {
         var7 = null;
      }

      return new PokedexEntityData(var10000, var7);
   }

   public open fun resolveEntityScan(): LivingEntity {
      return this as LivingEntity;
   }

   public open fun canWalk(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getWalk().getCanWalk();
   }

   public open fun canSwimInWater(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getSwim().getCanSwimInWater();
   }

   public open fun canWalkOnWater(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getSwim().getCanWalkOnWater();
   }

   public open fun canFly(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getFly().getCanFly();
   }

   public open fun canSwimInLava(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getSwim().getCanSwimInLava();
   }

   public open fun canPathThroughSaccLeaves(): Boolean {
      return (this.getConfig().getMap().getOrDefault("can_path_through_sacc_leaves", DoubleValue.ZERO) as MoValue).asDouble() == 1.0;
   }

   public open fun canWalkOnLava(): Boolean {
      return this.getExposedForm().getBehaviour().getMoving().getSwim().getCanWalkOnLava();
   }

   public open fun entityOnGround(): Boolean {
      return this.onGround();
   }

   public open fun canSwimUnderFluid(fluidState: FluidState): Boolean {
      return if (fluidState.is(FluidTags.LAVA))
         this.getExposedForm().getBehaviour().getMoving().getSwim().getCanBreatheUnderlava()
         else
         fluidState.is(FluidTags.WATER) && this.getExposedForm().getBehaviour().getMoving().getSwim().getCanBreatheUnderwater();
   }

   public open fun isFlying(): Boolean {
      return this.getBehaviourFlag(PokemonBehaviourFlag.FLYING);
   }

   public open fun couldStopFlying(): Boolean {
      return this.isFlying() && !this.getBehaviour().getMoving().getWalk().getAvoidsLand() && this.getBehaviour().getMoving().getWalk().getCanWalk();
   }

   public open fun setFlying(state: Boolean) {
      this.setBehaviourFlag(PokemonBehaviourFlag.FLYING, state);
   }

   public fun getHerdSize(): Int {
      var var3: Level = this.level();
      var var10000: ServerLevel = var3 as? ServerLevel;
      if ((var3 as? ServerLevel) == null) {
         return 0;
      } else {
         label30: {
            var var11: Brain = this.brain;
            var var12: java.lang.String = BrainExtensionsKt.getMemorySafely(var11, CobblemonMemories.HERD_LEADER).orElse(null) as java.lang.String;
            if (var12 != null) {
               var var13: UUID = UUID.fromString(var12);
               if (var13 != null) {
                  var14 = var10000.getEntity(var13);
                  break label30;
               }
            }

            var14 = null;
         }

         var herdLeader: PokemonEntity = var14 as? PokemonEntity;
         var var16: Int;
         if ((var14 as? PokemonEntity) == null) {
            var var15: Brain = this.brain;
            var3 = BrainExtensionsKt.getMemorySafely(var15, CobblemonMemories.HERD_SIZE).orElse(0);
            var16 = (var3 as java.lang.Number).intValue();
         } else {
            var var17: Brain = herdLeader.brain;
            var3 = BrainExtensionsKt.getMemorySafely(var17, CobblemonMemories.HERD_SIZE).orElse(0);
            var16 = (var3 as java.lang.Number).intValue();
         }

         return var16;
      }
   }

   internal fun adjustHerdSize(difference: Int): Int {
      var var10000: Brain = this.brain;
      var newSize: Int = RangesKt.coerceAtLeast(BrainExtensionsKt.getMemorySafely(var10000, CobblemonMemories.HERD_SIZE).orElse(0) + difference, 0);
      if (newSize == 0) {
         this.brain.eraseMemory(CobblemonMemories.HERD_SIZE);
      } else {
         this.brain.setMemory(CobblemonMemories.HERD_SIZE, newSize);
      }

      return newSize;
   }

   public fun getHerdTier(): Int {
      var nearbyEntities: Level = this.level();
      var var10000: ServerLevel = nearbyEntities as? ServerLevel;
      if ((nearbyEntities as? ServerLevel) == null) {
         return 0;
      } else {
         label76: {
            var var17: Brain = this.brain;
            var var18: java.lang.String = BrainExtensionsKt.getMemorySafely(var17, CobblemonMemories.HERD_LEADER).orElse(null) as java.lang.String;
            if (var18 != null) {
               var var19: UUID = UUID.fromString(var18);
               if (var19 != null) {
                  var20 = var10000.getEntity(var19);
                  break label76;
               }
            }

            var20 = null;
         }

         var herdLeader: PokemonEntity = var20 as? PokemonEntity;
         var var24: Int;
         if ((var20 as? PokemonEntity) == null) {
            if (this.pokemon.isAlpha()) {
               return Integer.MAX_VALUE;
            }

            if (!this.brain.hasMemoryValue(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)) {
               return 0;
            }

            var var9: java.lang.Iterable = (this.brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).get() as NearestVisibleLivingEntities)
               .findAll(PokemonEntity::getHerdTier$lambda$1);
            var var4: java.util.Iterator = var9.iterator();
            var var23: java.lang.Comparable;
            if (!var4.hasNext()) {
               var23 = null;
            } else {
               var var11: LivingEntity = var4.next() as LivingEntity;
               var var21: ToleratedHerdLeader = (var11 as PokemonEntity).getBehaviour().getHerd().bestMatchLeader(var11 as PokemonEntity, this);
               var var12: java.lang.Comparable = if (var21 != null) var21.getTier() else 0;

               while (var4.hasNext()) {
                  var itx: LivingEntity = var4.next() as LivingEntity;
                  var var22: ToleratedHerdLeader = (itx as PokemonEntity).getBehaviour().getHerd().bestMatchLeader(itx as PokemonEntity, this);
                  var var16: java.lang.Comparable = if (var22 != null) var22.getTier() else 0;
                  if (var12.compareTo(var16) < 0) {
                     var12 = var16;
                  }
               }

               var23 = var12;
            }

            var24 = if (var23 as Integer != null) var23 as Integer else 0;
         } else {
            if (herdLeader.pokemon.isAlpha()) {
               return Integer.MAX_VALUE;
            }

            var var25: ToleratedHerdLeader = herdLeader.getBehaviour().getHerd().bestMatchLeader(this, herdLeader);
            var24 = if (var25 != null) var25.getTier() else 0;
         }

         return var24;
      }
   }

   public fun hasRider(): Boolean {
      var var10000: java.util.List = this.getPassengers();
      var `$this$any$iv`: java.lang.Iterable = var10000;
      var var7: Boolean;
      if (var10000 is java.util.Collection && (var10000 as java.util.Collection).isEmpty()) {
         var7 = false;
      } else {
         var var3: java.util.Iterator = `$this$any$iv`.iterator();

         while (true) {
            if (!var3.hasNext()) {
               var7 = false;
               break;
            }

            if (var3.next() as Entity is LivingEntity) {
               var7 = true;
               break;
            }
         }
      }

      return var7;
   }

   @JvmStatic
   fun `runtime_delegate$lambda$0`(`this$0`: PokemonEntity): MoLangRuntime {
      var var1: MoLangRuntime = MoLangExtensionsKt.withQueryValue(
         MoLangFunctions.INSTANCE.setup(new MoLangRuntime()), "entity", `this$0`.getStruct() as MoValue
      );
      var1.getEnvironment().query.addFunction("passenger_count", PokemonEntity::runtime_delegate$lambda$0$0$0);
      var1.getEnvironment().query.addFunction("ride_velocity", PokemonEntity::runtime_delegate$lambda$0$0$1);
      var1.getEnvironment().query.addFunction("driver_input", PokemonEntity::runtime_delegate$lambda$0$0$2);
      var1.getEnvironment().query.addFunction("get_ride_stats", PokemonEntity::runtime_delegate$lambda$0$0$3);
      return var1;
   }

   @JvmStatic
   fun `runtime_delegate$lambda$0$0$0`(`this$0`: PokemonEntity, it: MoParams): Any {
      return new DoubleValue((double)`this$0`.getPassengers().size());
   }

   @JvmStatic
   fun `runtime_delegate$lambda$0$0$1`(`this$0`: PokemonEntity, it: MoParams): Any {
      return new DoubleValue(Math.min(`this$0`.ridingAnimationData.getVelocitySpring().getValue().length() * 1.5, 1.5));
   }

   @JvmStatic
   fun `runtime_delegate$lambda$0$0$2`(`this$0`: PokemonEntity, it: MoParams): Any {
      return new DoubleValue(Math.min(`this$0`.ridingAnimationData.getDriverInputSpring().getValue().length(), 1.0));
   }

   @JvmStatic
   fun `runtime_delegate$lambda$0$0$3`(`this$0`: PokemonEntity, params: MoParams): Any {
      var var10000: java.lang.String = params.getString(0);
      var10000 = var10000.toUpperCase(Locale.ROOT);
      var rideStat: RidingStat = RidingStat.valueOf(var10000);
      var10000 = params.getString(1);
      var10000 = var10000.toUpperCase(Locale.ROOT);
      return new DoubleValue(`this$0`.getRideStat(rideStat, RidingStyle.valueOf(var10000), params.getDouble(3), params.getDouble(2)));
   }

   @JvmStatic
   fun `removePassenger$lambda$0`(`$passenger`: Entity, it: Entry): Boolean {
      return it.getValue() == `$passenger`;
   }

   @JvmStatic
   fun `removePassenger$lambda$1`(`$tmp0`: Function1, p0: Any): Boolean {
      return `$tmp0`.invoke(p0) as java.lang.Boolean;
   }

   @JvmStatic
   fun `removePassenger$lambda$2`(`this$0`: PokemonEntity, var1: RidingBehaviour, var2: RidingBehaviourSettings, state: RidingBehaviourState): Unit {
      `this$0`.pokemon.setRideStamina((state.getStamina().get() as java.lang.Number).floatValue());
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `recallWithAnimation$lambda$0`(`$owner`: LivingEntity, `this$0`: PokemonEntity, `$future`: CompletableFuture, it: Unit): Unit {
      var var10000: Level = `$owner`.level();
      var var10001: Vec3 = `this$0`.position();
      WorldExtensionsKt.playSoundServer$default(var10000, var10001, CobblemonSounds.POKE_BALL_RECALL, null, 0.6F, 0.0F, 20, null);
      `this$0`.entityData.set(PHASING_TARGET_ID, `$owner`.getId());
      `this$0`.entityData.set(BEAM_MODE, (byte)3);
      `this$0`.ejectPassengers();
      var state: PokemonState = `this$0`.pokemon.getState();
      `this$0`.noPhysics = true;
      `this$0`.setNoGravity(true);
      SchedulingFunctionsKt.afterOnServer(1.5F, PokemonEntity::recallWithAnimation$lambda$0$0);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `recallWithAnimation$lambda$0$0`(`$state`: PokemonState, `this$0`: PokemonEntity, `$owner`: LivingEntity, `$future`: CompletableFuture): Unit {
      if (`$state` == `this$0`.pokemon.getState()) {
         `this$0`.pokemon.recall();
      }

      if (`$owner` is NPCEntity) {
         (`$owner` as NPCEntity).after(1.0F, PokemonEntity::recallWithAnimation$lambda$0$0$0);
      } else {
         `$future`.complete(`this$0`.pokemon);
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `recallWithAnimation$lambda$0$0$0`(`$future`: CompletableFuture, `this$0`: PokemonEntity): Unit {
      `$future`.complete(`this$0`.pokemon);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `recallWithAnimation$lambda$1`(`$tmp0`: Function1, p0: Any) {
      `$tmp0`.invoke(p0);
   }

   @JvmStatic
   fun `saveWithoutId$error__proxy`(`p0$p0`: Any) {
      throw new IllegalStateException(`p0$p0`.toString());
   }

   @JvmStatic
   fun `saveWithoutId$lambda$0`(`$nbt`: CompoundTag, brain: Tag): Unit {
      `$nbt`.put("Brain", brain);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `saveWithoutId$lambda$1`(`$tmp0`: Function1, p0: Any) {
      `$tmp0`.invoke(p0);
   }

   @JvmStatic
   fun `isRideableFor$lambda$0`(
      `$player`: ServerPlayer, `this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Boolean {
      if (!`$player`.canInteractWithEntity(`this$0` as Entity, `$player`.entityInteractionRange())) {
         return false;
      } else if (`this$0`.getPlatform() != PlatformType.NONE) {
         return false;
      } else if (`this$0`.tethering != null) {
         return false;
      } else if (`this$0`.getSeats().isEmpty()) {
         return false;
      } else {
         var var5: LivingEntity = `this$0`.getOwner();
         if ((var5 as? ServerPlayer) != null && PlayerExtensionsKt.isInBattle(var5 as? ServerPlayer)) {
            return false;
         } else if (!(`this$0`.getOwner() == `$player`) && `this$0`.getPassengers().isEmpty()) {
            return false;
         } else if (`this$0`.pokemon.getEffectiveScale() < Cobblemon.INSTANCE.getConfig().getMinimumRidingScale()) {
            return false;
         } else {
            return `this$0`.getBeamMode() != 3 && behaviour.isActive(settings, state, `this$0`);
         }
      }
   }

   @JvmStatic
   fun `attemptItemInteraction$evolutionUnlocked`(`$stack`: ItemStack, `$player`: Player, `this$0`: PokemonEntity): Boolean {
      `$stack`.consume(1, `$player` as LivingEntity);
      var var10000: Level = `this$0`.level();
      var var10001: Vec3 = `this$0`.position();
      WorldExtensionsKt.playSoundServer$default(var10000, var10001, CobblemonSounds.ITEM_USE, null, 1.0F, 1.0F, 4, null);
      return true;
   }

   @JvmStatic
   fun `tryMountingShoulder$lambda$0$0`(`this$0`: PokemonEntity, `$lock`: Any, `$player`: ServerPlayer): Unit {
      `this$0`.busyLocks.remove(`$lock`);
      if (!`this$0`.isBusy() && `this$0`.isAlive()) {
         var isLeft: Boolean = `$player`.getShoulderEntityLeft().isEmpty();
         if (isLeft || `$player`.getShoulderEntityRight().isEmpty()) {
            var var10000: Pokemon = `this$0`.pokemon;
            var var10003: UUID = `$player`.getUUID();
            var var10005: UUID = `this$0`.pokemon.getUuid();
            var10000.setState((new ShoulderedState(var10003, isLeft, var10005)) as PokemonState);
            `this$0`.setEntityOnShoulder(`$player`);

            var `$this$forEach$iv`: java.lang.Iterable;
            for (Object element$iv : $this$forEach$iv) {
               (`element$iv` as ShoulderEffect).applyEffect(`this$0`.pokemon, `$player`, isLeft);
            }

            var var10: Level = `this$0`.level();
            var var10001: Vec3 = `this$0`.position();
            var var10002: SoundEvent = SoundEvents.ITEM_PICKUP;
            WorldExtensionsKt.playSoundServer$default(var10, var10001, var10002, null, 0.7F, 1.4F, 4, null);
            `this$0`.discard();
         }
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `cry$lambda$0`(it: ServerPlayer): Boolean {
      return true;
   }

   @JvmStatic
   fun `cry$lambda$1`(`$tmp0`: Function1, p0: Any): Boolean {
      return `$tmp0`.invoke(p0) as java.lang.Boolean;
   }

   @JvmStatic
   fun `handleRelativeFrictionAndCalculateMovement$lambda$0`(
      `this$0`: PokemonEntity, `$deltaMovement`: Vec3, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Vec3 {
      var var10004: LivingEntity = `this$0`.getControllingPassenger();
      return behaviour.velocity(settings, state, `this$0`, var10004 as Player, `$deltaMovement`);
   }

   @JvmStatic
   fun `handleRelativeFrictionAndCalculateMovement$lambda$1`(
      `this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Double {
      return behaviour.inertia(settings, state, `this$0`);
   }

   @JvmStatic
   fun `travel$lambda$0`(`this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState): Vec3 {
      var var10004: LivingEntity = `this$0`.getControllingPassenger();
      var var4: Player = var10004 as Player;
      var var10005: Vec3 = `this$0`.getDeltaMovement();
      return behaviour.velocity(settings, state, `this$0`, var4, var10005);
   }

   @JvmStatic
   fun `travel$lambda$1`(`this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState): Double {
      return behaviour.inertia(settings, state, `this$0`);
   }

   @JvmStatic
   fun `travel$lambda$2`(
      `$triedMovement`: Vec3, `this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Unit {
      var delta: Vec3 = `$triedMovement`.subtract(`this$0`.getDeltaMovement());
      if (behaviour.damageOnCollision(settings, state, `this$0`, delta)) {
         new DamageOnCollisionPacket(delta).sendToServer();

         for (Entity passenger : this$0.getPassengers()) {
            passenger.setDeltaMovement(`this$0`.getDeltaMovement());
         }

         new DismountPokemonPacket().sendToServer();
         var var10000: SidedRidingState = state.getRideVelocity();
         var var10001: Vec3 = (state.getRideVelocity().get() as Vec3).multiply(0.0, 1.0, 0.0);
         SidedRidingState.set$default(var10000, var10001, false, 2, null);
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `forceBattle$lambda$1`(`$player`: ServerPlayer, `this$0`: PokemonEntity, it: PokemonBattle): Unit {
      var `$this$iv`: EventObservable = CobblemonEvents.POKEMON_SEEN as EventObservable;
      var `events$iv`: Array<PokemonSeenEvent> = new PokemonSeenEvent[1];
      var var10004: UUID = `$player`.getUUID();
      `events$iv`[0] = new PokemonSeenEvent(var10004, `this$0`.pokemon);
      `$this$iv`.emit(Arrays.copyOf(`events$iv`, `events$iv`.length));

      for (Object element$iv$iv : events$iv) {
         ;
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `addPassenger$lambda$5`(`this$0`: PokemonEntity, var1: RidingBehaviour, var2: RidingBehaviourSettings, state: RidingBehaviourState): Unit {
      var var10000: SidedRidingState = state.getStamina();
      var var10001: Any = `this$0`.entityData.get(RIDE_STAMINA);
      SidedRidingState.set$default(var10000, var10001, false, 2, null);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `ifRidingAvailableSupply$lambda$0`(
      `$result`: ObjectRef, `$block`: Function3, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Unit {
      `$result`.element = `$block`.invoke(behaviour, settings, state);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `tickRidden$lambda$0`(
      `this$0`: PokemonEntity,
      `$driver`: Player,
      `$movementInput`: Vec3,
      behaviour: RidingBehaviour,
      settings: RidingBehaviourSettings,
      state: RidingBehaviourState
   ): Unit {
      behaviour.tick(settings, state, `this$0`, `$driver`, `$movementInput`);
      if (`this$0`.entityData.get(RIDE_STAMINA) as java.lang.Float == -1.0F) {
         state.getStamina().set(1.0F, true);
      }

      if (!`this$0`.level().isClientSide) {
         var f: PoseType = behaviour.pose(settings, state, `this$0`);
         if (f != `this$0`.entityData.get(POSE_TYPE)) {
            `this$0`.entityData.set(POSE_TYPE, f);
         }
      }

      `this$0`.yRotO = `this$0`.getYRot();
      if (`this$0` is OrientationControllable && !(`this$0` as OrientationControllable).getOrientationController().isActive()) {
         `this$0`.setRot(behaviour.rotation(settings, state, `this$0`, `$driver` as LivingEntity).y, 0.0F);
      }

      `this$0`.yHeadRot = `this$0`.getYRot();
      `this$0`.yBodyRot = `this$0`.getYRot();
      if (behaviour.isActive(settings, state, `this$0`) && behaviour.canJump(settings, state, `this$0`, `$driver`)) {
         if (`this$0`.jumpInputStrength > 0) {
            `this$0`.addDeltaMovement(
               behaviour.jumpForce(settings, state, `this$0`, `$driver`, `this$0`.jumpInputStrength)
                  .yRot((float) Math.PI - `this$0`.getYRot() * (float) Math.PI / (float)180)
            );
            `this$0`.hasImpulse = true;
            `this$0`.jumping = false;
         }

         `this$0`.jumpInputStrength = 0;
      }

      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `clampPassengerRotation$lambda$0`(
      `this$0`: PokemonEntity, `$entityToUpdate`: Entity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Unit {
      behaviour.clampPassengerRotation(settings, state, `this$0`, `$entityToUpdate` as LivingEntity);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `positionRider$lambda$0`(
      `this$0`: PokemonEntity, `$passenger`: Entity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Unit {
      behaviour.updatePassengerRotation(settings, state, `this$0`, `$passenger` as LivingEntity);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `getRiddenInput$lambda$0`(
      `this$0`: PokemonEntity,
      `$controller`: Player,
      `$movementInput`: Vec3,
      behaviour: RidingBehaviour,
      settings: RidingBehaviourSettings,
      state: RidingBehaviourState
   ): Vec3 {
      return behaviour.velocity(settings, state, `this$0`, `$controller`, `$movementInput`);
   }

   @JvmStatic
   fun `maxUpStep$lambda$0`(`this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState): java.lang.Float {
      return behaviour.maxUpStep(settings, state, `this$0`);
   }

   @JvmStatic
   fun `getRiddenSpeed$lambda$0`(
      `this$0`: PokemonEntity, `$controller`: Player, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Float {
      return behaviour.speed(settings, state, `this$0`, `$controller`);
   }

   @JvmStatic
   fun `onGround$lambda$0`(`this$0`: PokemonEntity, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState): java.lang.Boolean {
      return behaviour.turnOffOnGround(settings, state, `this$0`);
   }

   @JvmStatic
   fun `getDefaultGravity$lambda$0`(
      `this$0`: PokemonEntity, `$regularGravity`: Double, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Double {
      return behaviour.gravity(settings, state, `this$0`, `$regularGravity`);
   }

   @JvmStatic
   fun `setRideBar$lambda$0`(
      `this$0`: PokemonEntity, `$driver`: Player, behaviour: RidingBehaviour, settings: RidingBehaviourSettings, state: RidingBehaviourState
   ): Float {
      return behaviour.setRideBar(settings, state, `this$0`, `$driver`);
   }

   @JvmStatic
   fun `rideFovMult$lambda$0`(
      `this$0`: PokemonEntity,
      `$driver`: Player,
      `$fovEffectScale`: Float,
      behaviour: RidingBehaviour,
      settings: RidingBehaviourSettings,
      state: RidingBehaviourState
   ): Float {
      return 1.0F + (behaviour.rideFovMultiplier(settings, state, `this$0`, `$driver`) - 1.0F) * `$fovEffectScale`;
   }

   @JvmStatic
   fun `getHerdTier$lambda$1`(`this$0`: PokemonEntity, it: LivingEntity): Boolean {
      if (it is PokemonEntity) {
         var var10000: Brain = (it as PokemonEntity).brain;
         if (BrainExtensionsKt.getMemorySafely(var10000, CobblemonMemories.HERD_LEADER).map(PokemonEntity::getHerdTier$lambda$1$1).orElse(false)) {
            return true;
         }
      }

      return false;
   }

   @JvmStatic
   fun `getHerdTier$lambda$1$0`(`this$0`: PokemonEntity, it: java.lang.String): java.lang.Boolean {
      return it == `this$0`.uuid.toString();
   }

   @JvmStatic
   fun `getHerdTier$lambda$1$1`(`$tmp0`: Function1, p0: Any): java.lang.Boolean {
      return `$tmp0`.invoke(p0) as java.lang.Boolean;
   }

   @JvmStatic
   fun getSPECIES(): EntityDataAccessor<java.lang.String> {
      return Companion.getSPECIES();
   }

   @JvmStatic
   fun getNICKNAME(): EntityDataAccessor<Component> {
      return Companion.getNICKNAME();
   }

   @JvmStatic
   fun getNICKNAME_VISIBLE(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getNICKNAME_VISIBLE();
   }

   @JvmStatic
   fun getMARK(): EntityDataAccessor<java.lang.String> {
      return Companion.getMARK();
   }

   @JvmStatic
   fun getSHOULD_RENDER_NAME(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getSHOULD_RENDER_NAME();
   }

   @JvmStatic
   fun getMOVING(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getMOVING();
   }

   @JvmStatic
   fun getBEHAVIOUR_FLAGS(): EntityDataAccessor<java.lang.Byte> {
      return Companion.getBEHAVIOUR_FLAGS();
   }

   @JvmStatic
   fun getPHASING_TARGET_ID(): EntityDataAccessor<Integer> {
      return Companion.getPHASING_TARGET_ID();
   }

   @JvmStatic
   fun getPLATFORM_TYPE(): EntityDataAccessor<PlatformType> {
      return Companion.getPLATFORM_TYPE();
   }

   @JvmStatic
   fun getBEAM_MODE(): EntityDataAccessor<java.lang.Byte> {
      return Companion.getBEAM_MODE();
   }

   @JvmStatic
   fun getBATTLE_ID(): EntityDataAccessor<Optional<UUID>> {
      return Companion.getBATTLE_ID();
   }

   @JvmStatic
   fun getASPECTS(): EntityDataAccessor<java.util.Set<java.lang.String>> {
      return Companion.getASPECTS();
   }

   @JvmStatic
   fun getDYING_EFFECTS_STARTED(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getDYING_EFFECTS_STARTED();
   }

   @JvmStatic
   fun getPOSE_TYPE(): EntityDataAccessor<PoseType> {
      return Companion.getPOSE_TYPE();
   }

   @JvmStatic
   fun getLABEL_LEVEL(): EntityDataAccessor<Integer> {
      return Companion.getLABEL_LEVEL();
   }

   @JvmStatic
   fun getHIDE_LABEL(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getHIDE_LABEL();
   }

   @JvmStatic
   fun getUNBATTLEABLE(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getUNBATTLEABLE();
   }

   @JvmStatic
   fun getCOUNTS_TOWARDS_SPAWN_CAP(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getCOUNTS_TOWARDS_SPAWN_CAP();
   }

   @JvmStatic
   fun getSPAWN_DIRECTION(): EntityDataAccessor<java.lang.Float> {
      return Companion.getSPAWN_DIRECTION();
   }

   @JvmStatic
   fun getFRIENDSHIP(): EntityDataAccessor<Integer> {
      return Companion.getFRIENDSHIP();
   }

   @JvmStatic
   fun getFREEZE_FRAME(): EntityDataAccessor<java.lang.Float> {
      return Companion.getFREEZE_FRAME();
   }

   @JvmStatic
   fun getCAUGHT_BALL(): EntityDataAccessor<java.lang.String> {
      return Companion.getCAUGHT_BALL();
   }

   @JvmStatic
   fun getEVOLUTION_STARTED(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getEVOLUTION_STARTED();
   }

   @JvmStatic
   fun getSHOWN_HELD_ITEM(): EntityDataAccessor<ItemStack> {
      return Companion.getSHOWN_HELD_ITEM();
   }

   @JvmStatic
   fun setSHOWN_HELD_ITEM(`<set-?>`: EntityDataAccessor<ItemStack>) {
      Companion.setSHOWN_HELD_ITEM(`<set-?>`);
   }

   @JvmStatic
   fun getRIDE_BOOSTS(): EntityDataAccessor<java.util.Map<RidingStat, java.lang.Float>> {
      return Companion.getRIDE_BOOSTS();
   }

   @JvmStatic
   fun setRIDE_BOOSTS(`<set-?>`: EntityDataAccessor<java.util.Map<RidingStat, java.lang.Float>>) {
      Companion.setRIDE_BOOSTS(`<set-?>`);
   }

   @JvmStatic
   fun getRIDE_STAMINA(): EntityDataAccessor<java.lang.Float> {
      return Companion.getRIDE_STAMINA();
   }

   @JvmStatic
   fun setRIDE_STAMINA(`<set-?>`: EntityDataAccessor<java.lang.Float>) {
      Companion.setRIDE_STAMINA(`<set-?>`);
   }

   @JvmStatic
   fun getSCALE_MODIFIER(): EntityDataAccessor<java.lang.Float> {
      return Companion.getSCALE_MODIFIER();
   }

   @JvmStatic
   fun setSCALE_MODIFIER(`<set-?>`: EntityDataAccessor<java.lang.Float>) {
      Companion.setSCALE_MODIFIER(`<set-?>`);
   }

   @JvmStatic
   fun getIS_ALPHA(): EntityDataAccessor<java.lang.Boolean> {
      return Companion.getIS_ALPHA();
   }

   @JvmStatic
   fun setIS_ALPHA(`<set-?>`: EntityDataAccessor<java.lang.Boolean>) {
      Companion.setIS_ALPHA(`<set-?>`);
   }
}
