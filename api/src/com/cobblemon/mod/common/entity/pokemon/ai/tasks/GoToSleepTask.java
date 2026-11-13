package com.cobblemon.mod.common.entity.pokemon.ai.tasks

import com.cobblemon.mod.common.CobblemonActivities
import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.status.PersistentStatus
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer
import com.mojang.datafixers.kinds.App
import com.mojang.datafixers.kinds.Applicative
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.OneShot
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor
import net.minecraft.world.entity.ai.behavior.declarative.Trigger
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder.Instance
import net.minecraft.world.entity.ai.memory.MemoryModuleType

object GoToSleepTask private constructor() {
   @JvmStatic
   public GoToSleepTask INSTANCE = new GoToSleepTask();


   public fun create(onlyFromStatus: Boolean): OneShot<PokemonEntity> {
      var var10000: OneShot = BehaviorBuilder.create(GoToSleepTask::create$lambda$0);
      return var10000;
   }

   @JvmStatic
   fun `create$lambda$0`(`$onlyFromStatus`: Boolean, it: Instance): App {
      return it.group(it.absent(MemoryModuleType.WALK_TARGET) as App, it.registered(CobblemonMemories.POKEMON_DROWSY) as App)
         .apply(it as Applicative, GoToSleepTask::create$lambda$0$0);
   }

   @JvmStatic
   fun `create$lambda$0$0`(`$it`: Instance, `$onlyFromStatus`: Boolean, var2: MemoryAccessor, pokemonDrowsy: MemoryAccessor): Trigger {
      return GoToSleepTask::create$lambda$0$0$0;
   }

   @JvmStatic
   fun `create$lambda$0$0$0`(
      `$it`: Instance, `$pokemonDrowsy`: MemoryAccessor, `$onlyFromStatus`: Boolean, var3: ServerLevel, entity: PokemonEntity, var5: Long
   ): Boolean {
      var var10000: PersistentStatusContainer = entity.getPokemon().getStatus();
      var hasSleepStatus: Boolean = (if (var10000 != null) var10000.getStatus() else null) === Statuses.SLEEP;
      if (entity.getBehaviour().getResting().getCanSleep()) {
         label34: {
            if (`$it`.tryGet(`$pokemonDrowsy`).orElse(false)) {
               var var10001: BlockPos = entity.blockPosition().below();
               if (entity.canSleepAt(var10001)) {
                  break label34;
               }
            }

            if (!hasSleepStatus) {
               return false;
            }
         }

         if (!hasSleepStatus) {
            if (`$onlyFromStatus`) {
               return false;
            }

            entity.getPokemon().setStatus(new PersistentStatusContainer(Statuses.SLEEP as PersistentStatus, 0, 2, null));
         }

         entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
         entity.getNavigation().stop();
         entity.getBrain().setActiveActivityToFirstValid(CollectionsKt.listOf(CobblemonActivities.POKEMON_SLEEPING_ACTIVITY));
         entity.getBrain().setMemory(CobblemonMemories.POKEMON_SLEEPING, true);
         return true;
      } else {
         return false;
      }
   }
}
