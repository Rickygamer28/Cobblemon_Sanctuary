package com.rickygamer28.sanctuary.compat.mixin;

import com.rickygamer28.sanctuary.life.Expeditions;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cobblemon Expeditions works out what each Pokémon adds to an expedition here: a resident whose home habitat suits
 * the expedition adds a little more. (Only applied when Expeditions is installed.)
 */
@Pseudo
@Mixin(targets = "com.cobblemonexpeditions.expedition.TEPCalculator", remap = false)
public abstract class ExpeditionPowerMixin {
    @Inject(method = "computeContribution", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void sanctuary$homeGround(@Coerce Object pokemon, List<?> preferredTypes, @Coerce Object cfg, CallbackInfoReturnable<Double> cir) {
        double bonus = Expeditions.homeBonus(pokemon, preferredTypes);
        if (bonus != 1.0) cir.setReturnValue(cir.getReturnValue() * bonus);
    }
}
