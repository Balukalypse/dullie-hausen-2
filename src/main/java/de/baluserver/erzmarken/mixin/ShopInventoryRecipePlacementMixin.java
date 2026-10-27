package de.baluserver.erzmarken.mixin;

import de.baluserver.erzmarken.ShopInventoryTransferGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recipe-book autofill does not pass through ordinary menu clicks. */
@Mixin(AbstractCraftingMenu.class)
public abstract class ShopInventoryRecipePlacementMixin {
    @Inject(method = "handlePlacement", at = @At("HEAD"), cancellable = true)
    private void dulliehausen$recipe(boolean all, boolean creative, RecipeHolder<?> recipe,
            ServerLevel level, Inventory inventory, CallbackInfoReturnable<RecipeBookMenu.PostPlaceAction> cir) {
        if (inventory.player instanceof ServerPlayer player && !ShopInventoryTransferGuard.mayTransfer(player)) {
            player.containerMenu.broadcastFullState();
            cir.setReturnValue(RecipeBookMenu.PostPlaceAction.NOTHING);
        }
    }
}
