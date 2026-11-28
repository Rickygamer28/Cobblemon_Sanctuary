package com.rickygamer28.sanctuary.compat.mixin;

import com.rickygamer28.sanctuary.life.Expeditions;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Expedition Board's Pokémon tooltip: a line saying where it lives, and its home-ground bonus. */
@Pseudo
@Mixin(targets = "com.cobblemonexpeditions.gui.ExpeditionBoardScreen", remap = false)
public abstract class ExpeditionBoardScreenMixin {
    /** The board works out the hovered Pokémon's power (for the selected expedition) just before its tooltip. */
    @Inject(method = "pokemonPower", at = @At("HEAD"), remap = false, require = 0)
    private void sanctuary$hovered(@Coerce Object pokemon, @Coerce Object expedition, CallbackInfoReturnable<Integer> cir) {
        Expeditions.rememberHovered(pokemon, expedition);
    }

    @SuppressWarnings("unchecked")
    @ModifyArg(method = "drawPokemonTooltip",
        at = @At(value = "INVOKE", target = "Lcom/cobblemonexpeditions/gui/ExpeditionBoardScreen;drawHoverCard"),
        index = 1, remap = false, require = 0)
    private List<?> sanctuary$homeLine(List<?> lines) {
        Expeditions.tooltipLines(this, (List<Object>) lines);
        return lines;
    }
}
