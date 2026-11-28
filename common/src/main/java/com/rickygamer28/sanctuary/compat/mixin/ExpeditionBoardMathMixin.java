package com.rickygamer28.sanctuary.compat.mixin;

import com.rickygamer28.sanctuary.life.Expeditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Expedition Board's own estimate of what a Pokémon adds (its Power, the team's Power and the success chance):
 * a resident on an expedition that suits its home habitat adds the same bonus the server gives it.
 */
@Pseudo
@Mixin(targets = "com.cobblemonexpeditions.gui.BoardPreviewMath", remap = false)
public abstract class ExpeditionBoardMathMixin {
    @Inject(method = "contribution", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void sanctuary$homeGround(@Coerce Object state, @Coerce Object pokemon, @Coerce Object expedition, CallbackInfoReturnable<Double> cir) {
        double bonus = Expeditions.boardBonus(pokemon, expedition);
        if (bonus != 1.0) cir.setReturnValue(cir.getReturnValue() * bonus);
    }
}
