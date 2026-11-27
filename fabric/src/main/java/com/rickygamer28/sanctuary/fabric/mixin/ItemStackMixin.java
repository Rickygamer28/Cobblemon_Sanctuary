package com.rickygamer28.sanctuary.fabric.mixin;

import com.rickygamer28.sanctuary.NoBuilding;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Using an item on a block in a sanctuary: no fluids, fire or bone meal, and no tools changing blocks. */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void sanctuary$useOn(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack self = (ItemStack) (Object) this;
        boolean ok = NoBuilding.INSTANCE.isModifyingTool(self)
            ? NoBuilding.INSTANCE.allowToolUse(ctx.getLevel(), ctx.getPlayer())
            : NoBuilding.INSTANCE.allowItemUse(ctx.getLevel(), ctx.getPlayer(), self);
        if (!ok) cir.setReturnValue(InteractionResult.FAIL);
    }
}
