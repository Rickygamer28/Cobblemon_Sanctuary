package com.cobblemon.mod.common.block.entity

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonBlockEntities
import com.cobblemon.mod.common.CobblemonBlocks
import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.advancement.CobblemonCriteria
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.pasture.PastureLink
import com.cobblemon.mod.common.api.pasture.PastureLinkManager
import com.cobblemon.mod.common.api.scheduling.SchedulingFunctionsKt
import com.cobblemon.mod.common.api.storage.PokemonStoreManager
import com.cobblemon.mod.common.api.storage.pc.PCStore
import com.cobblemon.mod.common.api.text.TextKt
import com.cobblemon.mod.common.block.PastureBlock
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.effect.SpawnSnowstormEntityParticlePacket
import com.cobblemon.mod.common.net.messages.client.pasture.ClosePasturePacket
import com.cobblemon.mod.common.net.messages.client.pasture.PokemonPasturedPacket
import com.cobblemon.mod.common.net.messages.client.pasture.OpenPasturePacket.PasturePokemonDataDTO
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.BlockPosExtensionsKt
import com.cobblemon.mod.common.util.DistributionUtilsKt
import com.cobblemon.mod.common.util.LocalizationUtilsKt
import com.cobblemon.mod.common.util.MiscUtilsKt
import java.util.ArrayList
import java.util.UUID
import kotlin.jvm.functions.Function1
import kotlin.jvm.internal.SourceDebugExtension
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.Vec3i
import net.minecraft.core.HolderLookup.Provider
import net.minecraft.core.RegistryAccess.Frozen
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB

@SourceDebugExtension(["SMAP\nPokemonPastureBlockEntity.kt\nKotlin\n*S Kotlin\n*F\n+ 1 PokemonPastureBlockEntity.kt\ncom/cobblemon/mod/common/block/entity/PokemonPastureBlockEntity\n+ 2 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n+ 3 fake.kt\nkotlin/jvm/internal/FakeKt\n*L\n1#1,399:1\n1788#2,4:400\n1869#2,2:405\n1869#2,2:407\n1869#2,2:409\n774#2:411\n865#2,2:412\n1869#2,2:414\n774#2:416\n865#2,2:417\n1#3:404\n*S KotlinDebug\n*F\n+ 1 PokemonPastureBlockEntity.kt\ncom/cobblemon/mod/common/block/entity/PokemonPastureBlockEntity\n*L\n138#1:400,4\n293#1:405,2\n301#1:407,2\n308#1:409,2\n322#1:411\n322#1:412,2\n322#1:414,2\n108#1:416\n108#1:417,2\n*E\n"])
class PokemonPastureBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(CobblemonBlockEntities.PASTURE, pos, state) {
   @JvmStatic
   public PokemonPastureBlockEntity.Companion Companion = new PokemonPastureBlockEntity.Companion(null);
   @JvmStatic
   private BlockEntityTicker<PokemonPastureBlockEntity> TICKER = PokemonPastureBlockEntity::TICKER$lambda$0;

   public final var ticksUntilCheck: Int = Cobblemon.INSTANCE.getConfig().getPastureBlockUpdateTicks()
   public final val tetheredPokemon: MutableList<com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity.Tethering> =
      (new ArrayList()) as java.util.List
      public final var minRoamPos: BlockPos
   public final var maxRoamPos: BlockPos
   public final var ownerId: UUID?
   public final var ownerName: String = ""

   init {
      var radius: Int = Cobblemon.INSTANCE.getConfig().getPastureMaxWanderDistance();
      var var10001: BlockPos = pos.subtract(new Vec3i(radius, radius, radius));
      this.minRoamPos = var10001;
      var10001 = pos.offset(new Vec3i(radius, radius, radius));
      this.maxRoamPos = var10001;
   }

   public fun getMaxTethered(): Int {
      return Cobblemon.INSTANCE.getConfig().getDefaultPasturedPokemonLimit();
   }

   public fun canAddPokemon(player: ServerPlayer, pokemon: Pokemon, maxPerPlayer: Int): Boolean {
      var radius: java.lang.Iterable = this.tetheredPokemon;
      var var10000: Int;
      if (this.tetheredPokemon is java.util.Collection && this.tetheredPokemon.isEmpty()) {
         var10000 = 0;
      } else {
         var bottom: Int = 0;

         for (Object element$iv : $this$count$iv) {
            if ((chunkDiameter as PokemonPastureBlockEntity.Tethering).getPlayerId() == player.getUUID()) {
               if (++bottom < 0) {
                  CollectionsKt.throwCountOverflow();
               }
            }
         }

         var10000 = bottom;
      }

      if (var10000 < maxPerPlayer && this.tetheredPokemon.size() < this.getMaxTethered() && !pokemon.isFainted()) {
         var var12: Double = (double)Cobblemon.INSTANCE.getConfig().getPastureMaxWanderDistance();
         var var16: BlockPos = this.getBlockPos();
         var var17: java.util.List = player.level()
            .getEntitiesOfClass(
               PokemonEntity.class,
               AABB.ofSize(BlockPosExtensionsKt.toVec3d(var16).multiply(1.0, 0.0, 1.0), var12, 99999.0, var12),
               PokemonPastureBlockEntity::canAddPokemon$lambda$2
            );
         if ((double)var17.size()
            >= (double)Cobblemon.INSTANCE.getConfig().getPastureMaxPerChunk() * var12 / (double)16 * (double)2 * var12 / (double)16 * (double)2) {
            CobblemonNetwork.INSTANCE.sendPacket(player, (new ClosePasturePacket()) as NetworkPacket);
            var var10001: MutableComponent = LocalizationUtilsKt.lang("pasture.too_many_nearby", new Object[0]);
            player.sendSystemMessage(TextKt.red(var10001) as Component, true);
            return false;
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public fun tether(player: ServerPlayer, pokemon: Pokemon, directionToBehind: Direction): Boolean {
      if (this.level == null) {
         return false;
      } else {
         var world: Level = this.level;
         var entity: PokemonEntity = new PokemonEntity(this.level, pokemon, null, 4, null);
         entity.refreshDimensions();
         var idealPlace: BlockPos = this.getBlockPos().offset(directionToBehind.getNormal().multiply((int)Math.ceil(entity.getBoundingBox().getXsize()) + 1));
         var box: AABB = entity.getDimensions(Pose.STANDING).makeBoundingBox(idealPlace.getCenter().subtract(0.0, 0.5, 0.0));

         for (int i = 0; i < 6; i++) {
            box = box.move((double)directionToBehind.getNormal().getX(), 0.0, (double)directionToBehind.getNormal().getZ());
            var var10002: BlockPos = idealPlace.offset(directionToBehind.getNormal().multiply(i + 1));
            var fixedPosition: BlockPos = this.makeSuitableY(world, var10002, entity, box);
            if (fixedPosition != null) {
               entity.setPos(fixedPosition.getCenter().subtract(0.0, 0.5, 0.0));
               var pc: PCStore = Cobblemon.INSTANCE.getStorage().getPC(player);
               entity.setBeamMode(2);
               SchedulingFunctionsKt.afterOnServer(0.5F, PokemonPastureBlockEntity::tether$lambda$0);
               if (world.addFreshEntity(entity as Entity)) {
                  var10002 = this.minRoamPos;
                  var var10003: BlockPos = this.maxRoamPos;
                  var var10004: UUID = player.getUUID();
                  var var10005: java.lang.String = player.getGameProfile().getName();
                  var var10006: UUID = UUID.randomUUID();
                  var var10007: UUID = pokemon.getUuid();
                  var var10008: UUID = pc.getUuid();
                  var var10009: Int = entity.getId();
                  var var10010: BlockPos = this.getBlockPos();
                  var tethering: PokemonPastureBlockEntity.Tethering = new PokemonPastureBlockEntity.Tethering(
                     var10002, var10003, var10004, var10005, var10006, var10007, var10008, var10009, var10010
                  );
                  pokemon.setTetheringId(tethering.getTetheringId());
                  this.tetheredPokemon.add(tethering);
                  entity.setTethering(tethering);
                  var var10000: PasturePokemonDataDTO = tethering.toDTO(player);
                  if (var10000 != null) {
                     CobblemonNetwork.INSTANCE.sendPacket(player, (new PokemonPasturedPacket(var10000)) as NetworkPacket);
                  }

                  this.setChanged();
                  CobblemonCriteria.PASTURE_USE.trigger(player, pokemon);
                  return true;
               }

               Cobblemon.LOGGER.warn("Couldn't spawn pastured Pokémon for some reason");
               break;
            }
         }

         return false;
      }
   }

   private fun togglePastureOn(on: Boolean) {
      var var10000: Block = this.getBlockState().getBlock();
      var pastureBlock: PastureBlock = var10000 as PastureBlock;
      var level: Level = this.level;
      if (this.level != null && !this.level.isClientSide) {
         var world: Level = this.level;
         var var10001: BlockState = this.getBlockState();
         var var10002: BlockPos = this.getBlockPos();
         var posBottom: BlockPos = pastureBlock.getBasePosition(var10001, var10002);
         var stateBottom: BlockState = level.getBlockState(posBottom);
         var posTop: BlockPos = pastureBlock.getPositionOfOtherPart(stateBottom, posBottom);
         var stateTop: BlockState = level.getBlockState(posTop);

         try {
            if (!(stateBottom.getValue(PastureBlock.Companion.getON() as Property) == on)) {
               world.setBlockAndUpdate(posTop, stateTop.setValue(PastureBlock.Companion.getON() as Property, on) as BlockState);
               world.setBlockAndUpdate(posBottom, stateBottom.setValue(PastureBlock.Companion.getON() as Property, on) as BlockState);
            }
         } catch (IllegalArgumentException var10) {
            if (level.getBlockState(this.getBlockPos().above()).getBlock() is PastureBlock) {
               level.setBlockAndUpdate(this.getBlockPos().above(), Blocks.AIR.defaultBlockState());
            } else {
               level.setBlockAndUpdate(this.getBlockPos().below(), Blocks.AIR.defaultBlockState());
            }

            level.setBlockAndUpdate(this.getBlockPos(), Blocks.AIR.defaultBlockState());
            level.addFreshEntity(
               (
                  new ItemEntity(
                     level,
                     (double)this.getBlockPos().getX() + 0.5,
                     (double)this.getBlockPos().getY() + 1.0,
                     (double)this.getBlockPos().getZ() + 0.5,
                     new ItemStack(CobblemonBlocks.PASTURE as ItemLike)
                  )
               ) as Entity
            );
         }
      }
   }

   public fun isSafeFloor(world: Level, pos: BlockPos, entity: PokemonEntity): Boolean {
      var state: BlockState = world.getBlockState(pos);
      return !state.isAir()
         && (
            state.entityCanStandOn(world as BlockGetter, pos, entity as Entity)
               || state.entityCanStandOnFace(world as BlockGetter, pos, entity as Entity, Direction.DOWN)
               || (entity.getBehaviour().getMoving().getSwim().getCanWalkOnWater() || entity.getBehaviour().getMoving().getSwim().getCanSwimInWater())
                  && state.getFluidState().is(FluidTags.WATER)
               || (entity.getBehaviour().getMoving().getSwim().getCanWalkOnLava() || entity.getBehaviour().getMoving().getSwim().getCanSwimInLava())
                  && state.getFluidState().is(FluidTags.LAVA)
         );
   }

   public fun makeSuitableY(world: Level, pos: BlockPos, entity: PokemonEntity, box: AABB): BlockPos? {
      if (world.collidesWithSuffocatingBlock(entity as Entity, box)) {
         for (int i = 1; i < 16; i++) {
            if (!world.collidesWithSuffocatingBlock(entity as Entity, box.move(0.5, (double)i, 0.5))) {
               var var10002: BlockPos = pos.offset(0, i - 1, 0);
               if (this.isSafeFloor(world, var10002, entity)) {
                  return pos.offset(0, i, 0);
               }
            }
         }
      } else {
         for (int ix = 1; ix < 16; ix++) {
            if (world.collidesWithSuffocatingBlock(entity as Entity, box.move(0.5, -((double)ix), 0.5))) {
               var var9: BlockPos = pos.offset(0, -ix, 0);
               if (this.isSafeFloor(world, var9, entity)) {
                  return pos.offset(0, -ix + 1, 0);
               }
            }
         }
      }

      return null;
   }

   public fun checkPokemon() {
      var deadLinks: java.util.List = new ArrayList();

      var `$this$forEach$iv`: java.lang.Iterable;
      for (Object element$iv : $this$forEach$iv) {
         var p0: PokemonPastureBlockEntity.Tethering = `element$iv` as PokemonPastureBlockEntity.Tethering;
         var pokemon: Pokemon = (`element$iv` as PokemonPastureBlockEntity.Tethering).getPokemon();
         if (pokemon == null) {
            deadLinks.add(p0.getPokemonId());
         } else if (pokemon.getTetheringId() == null || !(pokemon.getTetheringId() == p0.getTetheringId())) {
            deadLinks.add(p0.getPokemonId());
         }
      }

      for (Object element$ivx : $this$forEach$iv) {
         this.releasePokemon(`element$ivx` as UUID);
      }

      this.ticksUntilCheck = Cobblemon.INSTANCE.getConfig().getPastureBlockUpdateTicks();
      this.setChanged();
   }

   public fun onBroken() {
      if (this.level is ServerLevel) {
         var `$this$forEach$iv`: java.lang.Iterable;
         for (Object element$iv : $this$forEach$iv) {
            this.releasePokemon((`element$iv` as PokemonPastureBlockEntity.Tethering).getPokemonId());
         }

         var var10000: Level = this.level;
         var var7: ServerLevel = var10000 as ServerLevel;
         var var10001: BlockPos = this.getBlockPos();
         PastureLinkManager.removeAt(var7, var10001);
      }
   }

   public fun releasePokemon(pokemonId: UUID) {
      var var4: java.util.Iterator = this.tetheredPokemon.iterator();

      var var10000: Any;
      while (true) {
         if (var4.hasNext()) {
            var var5: Any = var4.next();
            if (!((var5 as PokemonPastureBlockEntity.Tethering).getPokemonId() == pokemonId)) {
               continue;
            }

            var10000 = (PokemonPastureBlockEntity.Tethering)var5;
            break;
         }

         var10000 = null;
         break;
      }

      var10000 = var10000;
      if (var10000 as PokemonPastureBlockEntity.Tethering != null) {
         var var9: Pokemon = var10000.getPokemon();
         if (var9 != null) {
            var9.setTetheringId(null);
         }

         this.tetheredPokemon.remove(var10000);
         this.setChanged();
      }
   }

   public fun releaseAllPokemon(playerId: UUID): List<UUID> {
      var unpastured: java.util.List = new ArrayList();
      var `$this$forEach$iv`: java.lang.Iterable = this.tetheredPokemon;
      var `element$iv`: java.util.Collection = new ArrayList();

      for (Object element$iv$iv : $this$filter$iv) {
         if ((`element$iv$iv` as PokemonPastureBlockEntity.Tethering).getPlayerId() == playerId) {
            `element$iv`.add(`element$iv$iv`);
         }
      }

      for (Object element$ivx : $this$filter$iv) {
         var var15: PokemonPastureBlockEntity.Tethering = `element$ivx` as PokemonPastureBlockEntity.Tethering;
         var var10000: Pokemon = (`element$ivx` as PokemonPastureBlockEntity.Tethering).getPokemon();
         if (var10000 != null) {
            var10000.setTetheringId(null);
         }

         this.tetheredPokemon.remove(var15);
         unpastured.add(var15.getPokemonId());
      }

      this.setChanged();
      return unpastured;
   }

   private fun getInRangeViewerCount(world: Level, pos: BlockPos, range: Double): Int {
      return world.getEntities(
            EntityTypeTest.forClass(ServerPlayer.class),
            new AABB(
               (double)pos.getX() - range,
               (double)pos.getY() - range,
               (double)pos.getZ() - range,
               (double)(pos.getX() + 1) + range,
               (double)(pos.getY() + 1) + range,
               (double)(pos.getZ() + 1) + range
            ),
            PokemonPastureBlockEntity::getInRangeViewerCount$lambda$0
         )
         .size();
   }

   private fun isPlayerViewing(player: ServerPlayer): Boolean {
      var pastureLink: PastureLink = PastureLinkManager.getLinkByPlayer(player);
      return pastureLink != null
         && pastureLink.getPos() == this.getBlockPos()
         && pastureLink.getDimension() == ResourceLocation.tryParse(player.level().dimensionTypeRegistration().getRegisteredName());
   }

   protected open fun loadAdditional(nbt: CompoundTag, registryLookup: Provider) {
      super.loadAdditional(nbt, registryLookup);
      var list: ListTag = nbt.getList("TetherPokemon", 10);
      this.ownerId = if (nbt.hasUUID("TetherOwnerId")) nbt.getUUID("TetherOwnerId") else null;
      var tetheringNBT: java.lang.String = nbt.getString("TetherOwnerName");
      var var12: Boolean = tetheringNBT.length() > 0;
      var var10001: java.lang.String = if (var12) tetheringNBT else null;
      if ((if (var12) tetheringNBT else null) == null) {
         var10001 = "";
      }

      this.ownerName = var10001;
      var var10000: java.util.Iterator = list.iterator();
      var var4: java.util.Iterator = var10000;

      while (var4.hasNext()) {
         var var13: Tag = var4.next() as Tag;
         var tetheringId: UUID = (var13 as CompoundTag).getUUID("TetheringId");
         var var14: UUID = (var13 as CompoundTag).getUUID("UUID");
         var pcId: UUID = (var13 as CompoundTag).getUUID("PCId");
         var playerId: UUID = (var13 as CompoundTag).getUUID("PlayerId");
         var entityId: Int = (var13 as CompoundTag).getInt("EntityId");
         var var15: java.util.List = this.tetheredPokemon;
         var var10003: BlockPos = this.minRoamPos;
         var var10004: BlockPos = this.maxRoamPos;
         var var10006: java.lang.String = this.ownerName;
         var var10011: BlockPos = this.getBlockPos();
         var15.add(new PokemonPastureBlockEntity.Tethering(var10003, var10004, playerId, var10006, tetheringId, var14, pcId, entityId, var10011));
      }

      var10001 = (java.lang.String)NbtUtils.readBlockPos(nbt, "TetherMinRoamPos").get();
      this.minRoamPos = var10001 as BlockPos;
      var10001 = (java.lang.String)NbtUtils.readBlockPos(nbt, "TetherMaxRoamPos").get();
      this.maxRoamPos = var10001 as BlockPos;
   }

   protected open fun saveAdditional(nbt: CompoundTag, registryLookup: Provider) {
      super.saveAdditional(nbt, registryLookup);
      var list: ListTag = new ListTag();

      for (PokemonPastureBlockEntity.Tethering tethering : this.tetheredPokemon) {
         var it: CompoundTag = new CompoundTag();
         it.putUUID("TetheringId", tethering.getTetheringId());
         it.putUUID("PlayerId", tethering.getPlayerId());
         it.putUUID("UUID", tethering.getPokemonId());
         it.putUUID("PCId", tethering.getPcId());
         it.putInt("EntityId", tethering.getEntityId());
         list.add(it);
      }

      nbt.put("TetherPokemon", list as Tag);
      nbt.put("TetherMinRoamPos", NbtUtils.writeBlockPos(this.minRoamPos));
      nbt.put("TetherMaxRoamPos", NbtUtils.writeBlockPos(this.maxRoamPos));
      if (this.ownerId != null) {
         nbt.putUUID("TetherOwnerId", this.ownerId);
      }

      nbt.putString("TetherOwnerName", this.ownerName);
   }

   @JvmStatic
   fun `canAddPokemon$lambda$1`(it: PokemonEntity): Boolean {
      return true;
   }

   @JvmStatic
   fun `canAddPokemon$lambda$2`(`$tmp0`: Function1, p0: Any): Boolean {
      return `$tmp0`.invoke(p0) as java.lang.Boolean;
   }

   @JvmStatic
   fun `tether$lambda$0`(`$entity`: PokemonEntity): Unit {
      if (`$entity`.getPokemon().getShiny()) {
         var var10000: NetworkPacket = (
            new SpawnSnowstormEntityParticlePacket(
               MiscUtilsKt.cobblemonResource("shiny_ring"),
               `$entity`.getId(),
               CollectionsKt.listOf(new java.lang.String[]{"shiny_particles", "middle"}),
               null,
               null,
               24,
               null
            )
         ) as NetworkPacket;
         var var10001: Double = `$entity`.getX();
         var var2: Double = `$entity`.getY();
         var var3: Double = `$entity`.getZ();
         var var10005: ResourceKey = `$entity`.level().dimension();
         NetworkPacket.sendToPlayersAround$default(var10000, var10001, var2, var3, 64.0, var10005, null, 32, null);
      }

      `$entity`.after(1.5F, PokemonPastureBlockEntity::tether$lambda$0$0);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `tether$lambda$0$0`(`$entity`: PokemonEntity): Unit {
      `$entity`.setBeamMode(0);
      return Unit.INSTANCE;
   }

   @JvmStatic
   fun `getInRangeViewerCount$lambda$0`(`$tmp0`: Function1, p0: Any): Boolean {
      return `$tmp0`.invoke(p0) as java.lang.Boolean;
   }

   @JvmStatic
   fun `TICKER$lambda$0`(world: Level, var1: BlockPos, var2: BlockState, blockEntity: PokemonPastureBlockEntity) {
      if (!world.isClientSide) {
         blockEntity.ticksUntilCheck += -1;
         if (blockEntity.ticksUntilCheck <= 0) {
            blockEntity.checkPokemon();
         }

         var `$this$filter$iv`: java.lang.Iterable = blockEntity.tetheredPokemon;
         var `destination$iv$iv`: java.util.Collection = new ArrayList();

         for (Object element$iv$iv : $this$filter$iv) {
            if ((`element$iv$iv` as PokemonPastureBlockEntity.Tethering).getPokemon() != null) {
               `destination$iv$iv`.add(`element$iv$iv`);
            }
         }

         for (PokemonPastureBlockEntity.Tethering tetheredPokemon : (java.util.List)destination$iv$iv) {
            var var10000: Pokemon = var16.getPokemon();
            if (var10000.getCurrentFullness() > 0) {
               var10000.tickMetabolism(1);
            }

            if (MapsKt.any(var10000.getInteractionCooldowns())) {
               var10000.tickInteractionCooldown(1);
            }
         }

         var var10003: BlockPos = blockEntity.getBlockPos();
         blockEntity.togglePastureOn(getInRangeViewerCount$default(blockEntity, world, var10003, 0.0, 4, null) > 0);
      }
   }

   companion object Companion private constructor() {
      internal final val TICKER: BlockEntityTicker<PokemonPastureBlockEntity>

   }

   open class Tethering(minRoamPos: BlockPos,
      maxRoamPos: BlockPos,
      playerId: UUID,
      playerName: String,
      tetheringId: UUID,
      pokemonId: UUID,
      pcId: UUID,
      entityId: Int,
      pasturePos: BlockPos
   ) {
      public final val minRoamPos: BlockPos
      public final val maxRoamPos: BlockPos
      public final val playerId: UUID
      public final val playerName: String
      public final val tetheringId: UUID
      public final val pokemonId: UUID
      public final val pcId: UUID
      public final val entityId: Int
      public final val pasturePos: BlockPos
      public final val box: AABB

      init {
         this.minRoamPos = minRoamPos;
         this.maxRoamPos = maxRoamPos;
         this.playerId = playerId;
         this.playerName = playerName;
         this.tetheringId = tetheringId;
         this.pokemonId = pokemonId;
         this.pcId = pcId;
         this.entityId = entityId;
         this.pasturePos = pasturePos;
         this.box = new AABB(BlockPosExtensionsKt.toVec3d(this.minRoamPos), BlockPosExtensionsKt.toVec3d(this.maxRoamPos));
      }

      public fun getPokemon(): Pokemon? {
         var var10000: MinecraftServer = DistributionUtilsKt.server();
         if (var10000 == null) {
            return null;
         } else {
            var var3: PokemonStoreManager = Cobblemon.INSTANCE.getStorage();
            var var10001: UUID = this.pcId;
            var var10002: Frozen = var10000.registryAccess();
            return var3.getPC(var10001, var10002 as RegistryAccess).get(this.pokemonId);
         }
      }

      public open fun canRoamTo(pos: BlockPos): Boolean {
         return this.box.contains(pos.getCenter());
      }

      public fun toDTO(player: ServerPlayer): PasturePokemonDataDTO? {
         var var10000: Pokemon = this.getPokemon();
         if (var10000 == null) {
            return null;
         } else {
            var entity: PokemonEntity;
            label33: {
               entity = var10000.getEntity();
               if (entity != null) {
                  var5 = entity.getActiveBehaviourFlags();
                  if (var5 != null) {
                     break label33;
                  }
               }

               var5 = SetsKt.emptySet();
            }

            var var7: UUID;
            var var10002: UUID;
            var var10003: UUID;
            var var10004: Component;
            var var10005: java.lang.String;
            var var10006: ResourceLocation;
            var var10007: java.util.Set;
            var var10008: ItemStack;
            var var10009: Int;
            label27: {
               var6 = new PasturePokemonDataDTO;
               var10002 = this.pokemonId;
               var10003 = this.playerId;
               var10004 = (
                  if (this.playerId == player.getUUID())
                     Pokemon.getDisplayName$default(var10000, false, 1, null)
                     else
                     TextKt.italicise(Pokemon.getDisplayName$default(var10000, false, 1, null))
               ) as Component;
               var10005 = if (this.playerId == player.getUUID()) null else this.playerName;
               var10006 = var10000.getSpecies().getResourceIdentifier();
               var10007 = var10000.getAspects();
               var10008 = var10000.heldItem();
               var10009 = var10000.getLevel();
               if (entity != null) {
                  var var10010: PokemonPastureBlockEntity.Tethering = entity.getTethering();
                  if (var10010 != null) {
                     var7 = var10010.tetheringId;
                     break label27;
                  }
               }

               var7 = null;
            }

            var6./* $VF: Unable to resugar constructor */<init>(
               var10002, var10003, var10004, var10005, var10006, var10007, var10008, var10009, var7 == this.tetheringId, var5
            );
            return var6;
         }
      }
   }
}
