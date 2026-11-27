package com.rickygamer28.sanctuary.fabric.mixin;

import com.rickygamer28.sanctuary.NoBuilding;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nobody builds in a sanctuary (the mod's own pieces still go down). */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void sanctuary$place(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        BlockItem self = (BlockItem) (Object) this;
        if (!NoBuilding.INSTANCE.allowPlace(ctx.getLevel(), ctx.getPlayer(), self.getBlock().defaultBlockState())) cir.setReturnValue(InteractionResult.FAIL);
    }
}
