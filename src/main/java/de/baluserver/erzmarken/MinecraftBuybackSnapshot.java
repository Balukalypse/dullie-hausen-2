package de.baluserver.erzmarken;

import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Pure plan calculation with real item codecs; does not touch a player's live inventory. */
public final class MinecraftBuybackSnapshot {
    private MinecraftBuybackSnapshot() { }
    public static byte[] afterRemoval(byte[] before, RegistryAccess access, String itemId, int quantity) throws IOException {
        var root = ShopInventorySnapshotFormat.read(before, Inventory.INVENTORY_SIZE + Inventory.EQUIPMENT_SLOT_MAPPING.size());
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        int selected = root.get("selectedSlot").getAsInt();
        var encoded = root.getAsJsonArray("items").get(selected);
        ItemStack held = encoded.isJsonNull() ? ItemStack.EMPTY : ItemStack.CODEC.parse(ops, encoded).result()
            .orElseThrow(() -> new IOException("Gehaltenes Item im Plan unlesbar"));
        if (held.isEmpty() || quantity < 1 || quantity > 64 || held.getCount() < quantity
                || !itemId.equals(BuiltInRegistries.ITEM.getKey(held.getItem()).toString())
                || !ItemStack.isSameItemSameComponents(held, new ItemStack(held.getItem())))
            throw new IOException("Geplanter Verkauf passt nicht zum gehaltenen unveraenderten Item");
        held = held.copy(); held.shrink(quantity);
        root.getAsJsonArray("items").set(selected, held.isEmpty() ? JsonNull.INSTANCE
            : ItemStack.CODEC.encodeStart(ops, held).result().orElseThrow(() -> new IOException("Zielitem nicht serialisierbar")));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
