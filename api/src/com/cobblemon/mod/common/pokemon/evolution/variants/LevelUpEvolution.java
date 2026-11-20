package com.cobblemon.mod.common.pokemon.evolution.variants

import com.cobblemon.mod.common.api.drop.DropTable
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.evolution.PassiveEvolution
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement
import java.util.LinkedHashSet

open class LevelUpEvolution(id: String,
      result: PokemonProperties,
      shedder: PokemonProperties?,
      optional: Boolean,
      consumeHeldItem: Boolean,
      requirements: MutableSet<Requirement>,
      learnableMoves: MutableSet<MoveTemplate>,
      drops: DropTable,
      permanent: Boolean
   ) :
   PassiveEvolution {
   @JvmStatic
   public LevelUpEvolution.Companion Companion = new LevelUpEvolution.Companion(null);
   @JvmStatic
   public java.lang.String ADAPTER_VARIANT = "level_up";
   @JvmStatic
   public java.lang.String ALTERNATIVE_ADAPTER_VARIANT = "passive";

   public open val id: String
   public open val result: PokemonProperties
   public open val shedder: PokemonProperties?
   public open var optional: Boolean
   public open var consumeHeldItem: Boolean
   public open val requirements: MutableSet<Requirement>
   public open val learnableMoves: MutableSet<MoveTemplate>
   public open val drops: DropTable
   public open val permanent: Boolean

   init {
      this.id = id;
      this.result = result;
      this.shedder = shedder;
      this.optional = optional;
      this.consumeHeldItem = consumeHeldItem;
      this.requirements = requirements;
      this.learnableMoves = learnableMoves;
      this.drops = drops;
      this.permanent = permanent;
   }

   public constructor() : this("id", new PokemonProperties(), null, true, true, new LinkedHashSet<>(), new LinkedHashSet<>(), new DropTable(), false)
   public override operator fun equals(other: Any?): Boolean {
      return other is LevelUpEvolution && StringsKt.equals((other as LevelUpEvolution).getId(), this.getId(), true);
   }

   public override fun hashCode(): Int {
      return 31 * this.getId().hashCode() + "level_up".hashCode();
   }

   companion object Companion private constructor() {
      public const val ADAPTER_VARIANT: String
      public const val ALTERNATIVE_ADAPTER_VARIANT: String

   }
}
