package com.cobblemon.mod.common.entity.pokemon.ai.tasks

import com.cobblemon.mod.common.CobblemonMemories
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.api.storage.StoreCoordinates
import com.cobblemon.mod.common.api.storage.party.PartyStore
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer
import com.mojang.datafixers.kinds.App
import com.mojang.datafixers.kinds.Applicative
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.OneShot
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor
import net.minecraft.world.entity.ai.behavior.declarative.Trigger
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder.Instance

object WakeUpTask private constructor() {
   @JvmStatic
   public WakeUpTask INSTANCE = new WakeUpTask();


   public fun create(): OneShot<PokemonEntity> {
      var var10000: OneShot = BehaviorBuilder.create(WakeUpTask::create$lambda$0);
      return var10000;
   }

   @JvmStatic
   fun `create$lambda$0`(it: Instance): App {
      return it.group(it.absent(CobblemonMemories.POKEMON_DROWSY) as App).apply(it as Applicative, WakeUpTask::create$lambda$0$0);
   }

   @JvmStatic
   fun `create$lambda$0$0`(var0: MemoryAccessor): Trigger {
      return WakeUpTask::create$lambda$0$0$0;
   }

   @JvmStatic
   fun `create$lambda$0$0$0`(var0: ServerLevel, entity: PokemonEntity, var2: Long): Boolean {
      var var10000: PersistentStatusContainer = entity.getPokemon().getStatus();
      var hasSleepStatus: Boolean = (if (var10000 != null) var10000.getStatus() else null) == Statuses.SLEEP;
      if (hasSleepStatus) {
         var var5: StoreCoordinates = entity.getPokemon().getStoreCoordinates().get() as StoreCoordinates;
         if ((if (var5 != null) var5.getStore() else null) is PartyStore) {
            return false;
         }
      }

      if (hasSleepStatus) {
         entity.getPokemon().setStatus(null);
      }

      entity.getBrain().eraseMemory(CobblemonMemories.POKEMON_SLEEPING);
      entity.getBrain().useDefaultActivity();
      return true;
   }
}
