package de.baluserver.erzmarken.mixin;

import de.baluserver.erzmarken.ErzmarkenMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Security fallback: piston movement must never turn a player-placed ore
 * into apparently natural ore by moving it to an untracked location.
 * Applies to both natural and placed reward-bearing ores. In vanilla
 * the same predicate is used when a piston plans its push/pull chain.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonOreProtectionMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private static void erzmarken$denyOreMovement(BlockState state, Level level, BlockPos pos,
            Direction moveDirection, boolean allowDestroy, Direction pistonDirection,
            CallbackInfoReturnable<Boolean> result) {
        if (ErzmarkenMod.isRewardOre(state)) {
            result.setReturnValue(false);
        }
    }
}
