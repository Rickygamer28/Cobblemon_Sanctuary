package com.cobblemon.mod.common.block

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.CobblemonBlockEntities
import com.cobblemon.mod.common.CobblemonNetwork
import com.cobblemon.mod.common.CobblemonSounds
import com.cobblemon.mod.common.api.net.NetworkPacket
import com.cobblemon.mod.common.api.pasture.PastureLink
import com.cobblemon.mod.common.api.pasture.PastureLinkManager
import com.cobblemon.mod.common.api.pasture.PasturePermissionControllers
import com.cobblemon.mod.common.api.pasture.PasturePermissions
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity
import com.cobblemon.mod.common.net.messages.client.pasture.OpenPasturePacket
import com.cobblemon.mod.common.net.messages.client.pasture.OpenPasturePacket.PasturePokemonDataDTO
import com.cobblemon.mod.common.util.PlayerExtensionsKt
import com.cobblemon.mod.common.util.VectorShapeExtensionsKt
import com.cobblemon.mod.common.util.WorldExtensionsKt
import com.mojang.serialization.MapCodec
import java.util.ArrayList
import java.util.UUID
import java.util.function.BiConsumer
import kotlin.enums.EnumEntries
import kotlin.jvm.internal.SourceDebugExtension
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.Explosion.BlockInteraction
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.StateDefinition.Builder
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

@SourceDebugExtension(["SMAP\nPastureBlock.kt\nKotlin\n*S Kotlin\n*F\n+ 1 PastureBlock.kt\ncom/cobblemon/mod/common/block/PastureBlock\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n*L\n1#1,369:1\n1#2:370\n1#2:381\n1617#3,9:371\n1869#3:380\n1870#3:382\n1626#3:383\n*S KotlinDebug\n*F\n+ 1 PastureBlock.kt\ncom/cobblemon/mod/common/block/PastureBlock\n*L\n286#1:381\n286#1:371,9\n286#1:380\n286#1:382\n286#1:383\n*E\n"])
class PastureBlock(settings: Properties) : BaseEntityBlock(settings), SimpleWaterloggedBlock {
   @JvmStatic
   public PastureBlock.Companion Companion = new PastureBlock.Companion(null);
   @JvmStatic
   private MapCodec<PastureBlock> CODEC = BaseEntityBlock.simpleCodec(PastureBlock::new);
   @JvmStatic
   private EnumProperty<PastureBlock.PasturePart> PART;
   @JvmStatic
   private BooleanProperty ON;
   @JvmStatic
   private VoxelShape SOUTH_AABB_TOP = PastureBlock.Companion.access$buildCollider(Companion, true, Direction.NORTH);
   @JvmStatic
   private VoxelShape NORTH_AABB_TOP = PastureBlock.Companion.access$buildCollider(Companion, true, Direction.SOUTH);
   @JvmStatic
   private VoxelShape WEST_AABB_TOP = PastureBlock.Companion.access$buildCollider(Companion, true, Direction.WEST);
   @JvmStatic
   private VoxelShape EAST_AABB_TOP = PastureBlock.Companion.access$buildCollider(Companion, true, Direction.EAST);
   @JvmStatic
   private VoxelShape EAST_AABB_BOTTOM = PastureBlock.Companion.access$buildCollider(Companion, false, Direction.EAST);
   @JvmStatic
   private VoxelShape SOUTH_AABB_BOTTOM = VectorShapeExtensionsKt.rotateShape(Direction.EAST, Direction.SOUTH, EAST_AABB_BOTTOM);
   @JvmStatic
   private VoxelShape NORTH_AABB_BOTTOM = VectorShapeExtensionsKt.rotateShape(Direction.EAST, Direction.NORTH, EAST_AABB_BOTTOM);
   @JvmStatic
   private VoxelShape WEST_AABB_BOTTOM = VectorShapeExtensionsKt.rotateShape(Direction.EAST, Direction.WEST, EAST_AABB_BOTTOM);

   init {
      this.registerDefaultState(
         (((this.stateDefinition.any() as BlockState).setValue(HorizontalDirectionalBlock.FACING as Property, Direction.NORTH as java.lang.Comparable) as BlockState)
               .setValue(PART as Property, PastureBlock.PasturePart.BOTTOM) as BlockState)
            .setValue(ON as Property, false) as BlockState
      );
   }

   public open fun newBlockEntity(blockPos: BlockPos, blockState: BlockState): PokemonPastureBlockEntity? {
      return if (blockState.getValue(PART as Property) === PastureBlock.PasturePart.BOTTOM) new PokemonPastureBlockEntity(blockPos, blockState) else null;
   }

   protected open fun getRenderShape(state: BlockState): RenderShape {
      return RenderShape.MODEL;
   }

   public open fun getStateForPlacement(blockPlaceContext: BlockPlaceContext): BlockState? {
      var abovePosition: BlockPos = blockPlaceContext.getClickedPos().above();
      var world: Level = blockPlaceContext.getLevel();
      return if (world.getBlockState(abovePosition).canBeReplaced(blockPlaceContext) && !world.isOutsideBuildHeight(abovePosition))
         ((this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING as Property, blockPlaceContext.getHorizontalDirection() as java.lang.Comparable) as BlockState)
               .setValue(PART as Property, PastureBlock.PasturePart.BOTTOM) as BlockState)
            .setValue(
               BlockStateProperties.WATERLOGGED as Property,
               blockPlaceContext.getLevel().getFluidState(blockPlaceContext.getClickedPos()).getType() == Fluids.WATER
            ) as BlockState
         else
         null;
   }

   protected open fun canSurvive(state: BlockState, world: LevelReader, pos: BlockPos): Boolean {
      return true;
   }

   public fun getPositionOfOtherPart(state: BlockState, pos: BlockPos): BlockPos {
      var var10000: BlockPos;
      if (state.hasProperty(PART as Property) && state.getValue(PART as Property) === PastureBlock.PasturePart.BOTTOM) {
         var var4: BlockPos = pos.above();
         var10000 = var4;
      } else {
         var var3: BlockPos = pos.below();
         var10000 = var3;
      }

      return var10000;
   }

   public fun getBasePosition(state: BlockState, pos: BlockPos): BlockPos {
      var var10000: BlockPos;
      if (this.isBase(state)) {
         var10000 = pos;
      } else {
         var var3: BlockPos = pos.below();
         var10000 = var3;
      }

      return var10000;
   }

   private fun isBase(state: BlockState): Boolean {
      return state.hasProperty(PART as Property) && state.getValue(PART as Property) === PastureBlock.PasturePart.BOTTOM;
   }

   protected open fun codec(): MapCodec<out BaseEntityBlock> {
      var var10000: MapCodec = CODEC;
      return var10000;
   }

   protected open fun isPathfindable(blockState: BlockState, pathComputationType: PathComputationType): Boolean {
      return false;
   }

   protected open fun createBlockStateDefinition(builder: Builder<Block, BlockState>) {
      builder.add(new Property[]{HorizontalDirectionalBlock.FACING});
      builder.add(new Property[]{PART});
      builder.add(new Property[]{ON});
      builder.add(new Property[]{BlockStateProperties.WATERLOGGED});
   }

   public fun checkBreakEntity(world: LevelAccessor, state: BlockState, pos: BlockPos) {
      if (state.getValue(PART as Property) != PastureBlock.PasturePart.TOP) {
         var blockEntity: BlockEntity = world.getBlockEntity(pos);
         if (blockEntity is PokemonPastureBlockEntity) {
            (blockEntity as PokemonPastureBlockEntity).onBroken();
         }
      }
   }

   public open fun playerWillDestroy(world: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
      this.checkBreakEntity(world as LevelAccessor, state, pos);
      if (!world.isClientSide && player.isCreative()) {
         if (state.getValue(PART as Property) as PastureBlock.PasturePart === PastureBlock.PasturePart.TOP) {
            var blockState2: BlockPos = pos.below();
            var var14: BlockState = world.getBlockState(blockState2);
            if (var14.is(state.getBlock()) && var14.getValue(PART as Property) === PastureBlock.PasturePart.BOTTOM) {
               this.checkBreakEntity(world as LevelAccessor, var14, blockState2);
               world.setBlock(
                  blockState2, if (var14.getFluidState().is(Fluids.WATER as Fluid)) Blocks.WATER.defaultBlockState() else Blocks.AIR.defaultBlockState(), 35
               );
               world.levelEvent(player, 2001, blockState2, BaseEntityBlock.getId(var14));
            }
         }
      }

      var var10000: BlockState = super.playerWillDestroy(world, pos, state, player);
      return var10000;
   }

   protected open fun onExplosionHit(state: BlockState, level: Level, pos: BlockPos, explosion: Explosion, dropConsumer: BiConsumer<ItemStack?, BlockPos?>) {
      var interaction: BlockInteraction = explosion.getBlockInteraction();
      if (interaction === BlockInteraction.DESTROY || interaction === BlockInteraction.DESTROY_WITH_DECAY) {
         var var8: BlockEntity = level.getBlockEntity(pos);
         var var10000: PokemonPastureBlockEntity = var8 as? PokemonPastureBlockEntity;
         if ((var8 as? PokemonPastureBlockEntity) == null) {
            return;
         }

         var10000.onBroken();
      }

      super.onExplosionHit(state, level, pos, explosion, dropConsumer);
   }

   public open fun <T : BlockEntity?> getTicker(world: Level, state: BlockState, type: BlockEntityType<Any>): BlockEntityTicker<Any>? {
      return BaseEntityBlock.createTickerHelper(
         type, CobblemonBlockEntities.PASTURE, new BlockEntityTicker(PokemonPastureBlockEntity.Companion.getTICKER$common()) {
            {
               this.$tmp0 = `$tmp0`;
            }

            public final void tick(Level p0, BlockPos p1, BlockState p2, PokemonPastureBlockEntity p3) {
               this.$tmp0.tick(p0, p1, p2, p3);
            }
         }
      );
   }

   public open fun setPlacedBy(world: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, itemStack: ItemStack?) {
      world.setBlock(
         pos.above(),
         (state.setValue(PART as Property, PastureBlock.PasturePart.TOP) as BlockState)
            .setValue(BlockStateProperties.WATERLOGGED as Property, world.getFluidState(pos.above()).getType() == Fluids.WATER) as BlockState,
         3
      );
      world.blockUpdated(pos, Blocks.AIR);
      state.updateNeighbourShapes(world as LevelAccessor, pos, 3);
      if (world is ServerLevel && placer is ServerPlayer) {
         var var7: BlockEntity = (world as ServerLevel).getBlockEntity(pos);
         var var10000: PokemonPastureBlockEntity = var7 as? PokemonPastureBlockEntity;
         if ((var7 as? PokemonPastureBlockEntity) == null) {
            return;
         }

         var10000.setOwnerId((placer as ServerPlayer).getUUID());
         var var10001: java.lang.String = (placer as ServerPlayer).getGameProfile().getName();
         var10000.setOwnerName(var10001);
         var10000.setChanged();
      }
   }

   protected open fun useWithoutItem(state: BlockState, world: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
      if (player is ServerPlayer && !PlayerExtensionsKt.isInBattle(player as ServerPlayer)) {
         var basePos: BlockPos = this.getBasePosition(state, pos);
         var var10000: BlockEntity = world.getBlockEntity(basePos.above());
         if (var10000 != null) {
            var10000.setRemoved();
         }

         var baseEntity: BlockEntity = world.getBlockEntity(basePos);
         if (baseEntity !is PokemonPastureBlockEntity) {
            return InteractionResult.SUCCESS;
         } else {
            var pcId: UUID = Cobblemon.INSTANCE.getStorage().getPC(player as ServerPlayer).getUuid();
            var linkId: UUID = UUID.randomUUID();
            var perms: PasturePermissions = PasturePermissionControllers.permit(player as ServerPlayer, baseEntity as PokemonPastureBlockEntity);
            var var32: CobblemonNetwork = CobblemonNetwork.INSTANCE;
            var var10001: ServerPlayer = player as ServerPlayer;
            var var12: Int = (baseEntity as PokemonPastureBlockEntity).getMaxTethered();
            var `$this$mapNotNull$iv`: java.lang.Iterable = (baseEntity as PokemonPastureBlockEntity).getTetheredPokemon();
            var `destination$iv$iv`: java.util.Collection = new ArrayList();

            for (Object element$iv$iv$iv : $this$mapNotNull$iv) {
               var var33: PasturePokemonDataDTO = (`element$iv$iv$iv` as PokemonPastureBlockEntity.Tethering).toDTO(player as ServerPlayer);
               if (var33 != null) {
                  `destination$iv$iv`.add(var33);
               }
            }

            var32.sendPacketToPlayer(var10001, (new OpenPasturePacket(pcId, linkId, var12, `destination$iv$iv` as java.util.List, perms)) as NetworkPacket);
            var var34: UUID = (player as ServerPlayer).getUUID();
            var var10005: ResourceLocation = ResourceLocation.tryParse(world.dimensionTypeRegistration().getRegisteredName());
            PastureLinkManager.createLink(var34, new PastureLink(linkId, pcId, var10005, this.getBasePosition(state, pos), perms));
            var var35: Vec3 = pos.getBottomCenter();
            WorldExtensionsKt.playSoundServer$default(world, var35, CobblemonSounds.PC_ON, null, 0.5F, 1.0F, 4, null);
            world.gameEvent(player as Entity, GameEvent.BLOCK_OPEN as Holder, pos);
            return InteractionResult.SUCCESS;
         }
      } else {
         return InteractionResult.SUCCESS;
      }
   }

   protected open fun getShape(blockState: BlockState, blockGetter: BlockGetter, blockPos: BlockPos, collisionContext: CollisionContext): VoxelShape {
      var var5: VoxelShape;
      if (blockState.getValue(PART as Property) === PastureBlock.PasturePart.TOP) {
         var var10000: Direction = blockState.getValue(HorizontalDirectionalBlock.FACING as Property) as Direction;
         switch (var10000 == null ? -1 : PastureBlock.WhenMappings.$EnumSwitchMapping$0[var10000.ordinal()]) {
            case 1:
               var5 = SOUTH_AABB_TOP;
               break;
            case 2:
               var5 = WEST_AABB_TOP;
               break;
            case 3:
               var5 = EAST_AABB_TOP;
               break;
            default:
               var5 = NORTH_AABB_TOP;
         }
      } else {
         var var6: Direction = blockState.getValue(HorizontalDirectionalBlock.FACING as Property) as Direction;
         switch (var6 == null ? -1 : PastureBlock.WhenMappings.$EnumSwitchMapping$0[var6.ordinal()]) {
            case 1:
               var5 = SOUTH_AABB_BOTTOM;
               break;
            case 2:
               var5 = WEST_AABB_BOTTOM;
               break;
            case 3:
               var5 = EAST_AABB_BOTTOM;
               break;
            default:
               var5 = NORTH_AABB_BOTTOM;
         }
      }

      return var5;
   }

   protected open fun rotate(blockState: BlockState, rotation: Rotation): BlockState {
      var var10000: Any = blockState.setValue(
         HorizontalDirectionalBlock.FACING as Property,
         rotation.rotate(blockState.getValue(HorizontalDirectionalBlock.FACING as Property) as Direction) as java.lang.Comparable
      );
      return var10000 as BlockState;
   }

   protected open fun mirror(blockState: BlockState, mirror: Mirror): BlockState {
      var var10000: BlockState = blockState.rotate(mirror.getRotation(blockState.getValue(HorizontalDirectionalBlock.FACING as Property) as Direction));
      return var10000;
   }

   protected open fun onRemove(state: BlockState, world: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
      if (!state.is(newState.getBlock())) {
         super.onRemove(state, world, pos, newState, moved);
      }
   }

   protected open fun getFluidState(state: BlockState): FluidState {
      var var10000: FluidState;
      if (state.getValue(BlockStateProperties.WATERLOGGED as Property) as java.lang.Boolean) {
         var var2: FluidState = Fluids.WATER.getSource(false);
         var10000 = var2;
      } else {
         var10000 = super.getFluidState(state);
      }

      return var10000;
   }

   protected open fun updateShape(
      state: BlockState,
      direction: Direction,
      neighborState: BlockState,
      world: LevelAccessor,
      pos: BlockPos,
      neighborPos: BlockPos
   ): BlockState {
      if (state.getValue(BlockStateProperties.WATERLOGGED as Property) as java.lang.Boolean) {
         world.scheduleTick(pos, Fluids.WATER as Fluid, Fluids.WATER.getTickDelay(world as LevelReader));
      }

      var isPasture: Boolean = neighborState.is(this as Block);
      var part: PastureBlock.PasturePart = state.getValue(PART as Property) as PastureBlock.PasturePart;
      if (!isPasture && part === PastureBlock.PasturePart.TOP && neighborPos == pos.below()) {
         var var9: BlockState = Blocks.AIR.defaultBlockState();
         return var9;
      } else if (!isPasture && part === PastureBlock.PasturePart.BOTTOM && neighborPos == pos.above()) {
         this.checkBreakEntity(world, state, pos);
         var var10000: BlockState = Blocks.AIR.defaultBlockState();
         return var10000;
      } else {
         return state;
      }
   }

   @JvmStatic
   fun {
      var var10000: EnumProperty = EnumProperty.create("part", PastureBlock.PasturePart.class);
      PART = var10000;
      var var0: BooleanProperty = BooleanProperty.create("on");
      ON = var0;
   }

   companion object Companion private constructor() {
      public final val CODEC: MapCodec<PastureBlock>
      public final val PART: EnumProperty<com.cobblemon.mod.common.block.PastureBlock.PasturePart>
      public final val ON: BooleanProperty
      private final val SOUTH_AABB_TOP: VoxelShape
      private final val NORTH_AABB_TOP: VoxelShape
      private final val WEST_AABB_TOP: VoxelShape
      private final val EAST_AABB_TOP: VoxelShape
      private final val EAST_AABB_BOTTOM: VoxelShape
      private final val SOUTH_AABB_BOTTOM: VoxelShape
      private final val NORTH_AABB_BOTTOM: VoxelShape
      private final val WEST_AABB_BOTTOM: VoxelShape


      private fun buildCollider(top: Boolean, direction: Direction): VoxelShape {
         if (top) {
            var var7: VoxelShape = Shapes.or(
               VectorShapeExtensionsKt.voxelShape(0.1875, 0.0, 0.0625, 0.8125, 0.0625, 0.3125, direction),
               new VoxelShape[]{
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.0, 0.375, 0.875, 0.1875, 0.9375, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.1875, 0.4375, 0.8125, 0.6875, 0.9375, direction),
                  VectorShapeExtensionsKt.voxelShape(0.8125, 0.1875, 0.375, 0.875, 0.6875, 0.9375, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.1875, 0.375, 0.1875, 0.6875, 0.9375, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.6875, 0.375, 0.875, 0.75, 0.9375, direction)
               }
            );
            return var7;
         } else {
            var var5: VoxelShape = Shapes.or(
               VectorShapeExtensionsKt.voxelShape(0.875, 0.0, 0.0, 1.0, 1.0, 0.125, direction),
               new VoxelShape[]{
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.0, 0.0, 0.875, 0.125, 0.125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.875, 0.0, 0.875, 1.0, 0.125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.125, 0.0625, 0.875, 0.875, 0.125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0625, 0.125, 0.125, 0.125, 0.875, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.0, 0.0, 0.125, 1.0, 0.125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.0, 0.125, 0.875, 0.125, 1.0, direction),
                  VectorShapeExtensionsKt.voxelShape(0.875, 0.125, 0.125, 0.9375, 0.875, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.125, 0.875, 0.125, 0.875, 1.0, 1.0, direction),
                  VectorShapeExtensionsKt.voxelShape(0.875, 0.0, 0.875, 1.0, 1.0, 1.0, direction),
                  VectorShapeExtensionsKt.voxelShape(0.875, 0.0, 0.125, 1.0, 0.125, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.875, 0.875, 0.125, 1.0, 1.0, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.875, 0.125, 0.125, 1.0, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.0, 0.125, 0.125, 0.125, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.0, 0.875, 0.125, 1.0, 1.0, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.125, 0.375, 0.0625, 0.875, 0.625, direction),
                  VectorShapeExtensionsKt.voxelShape(0.9375, 0.125, 0.375, 1.0, 0.875, 0.625, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.1875, 0.05625, 0.8125, 0.75, 0.05625, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.125, 0.3125, 0.8125, 0.3125, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.125, 0.3125, 0.8125, 0.3125, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.0625, 0.875, 0.8125, 0.25, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.25, 0.25, 0.1875, 0.4375, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.8125, 0.25, 0.25, 0.8125, 0.4375, 0.875, direction),
                  VectorShapeExtensionsKt.voxelShape(0.1875, 0.3125, 0.3125, 0.8125, 0.5, 0.3125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.25, 0.75, 0.3125, 0.75, 1.0, 0.8125, direction),
                  VectorShapeExtensionsKt.voxelShape(0.0, 0.0, 0.0, 0.0625, 1.0, 1.0, direction),
                  VectorShapeExtensionsKt.voxelShape(0.9375, 0.0, 0.0, 1.0, 1.0, 1.0, direction)
               }
            );
            return var5;
         }
      }
   }

   enum class PasturePart : StringRepresentable {
      @JvmStatic
      TOP("top"),
      @JvmStatic
      BOTTOM("bottom");
      private java.lang.String label;

      private final val label: String

      init {
         this.label = label;
      }

      public open fun getSerializedName(): String {
         return this.label;
      }

      @JvmStatic
      fun getEntries(): EnumEntries<PastureBlock.PasturePart> {
         return $ENTRIES;
      }
   }
}
