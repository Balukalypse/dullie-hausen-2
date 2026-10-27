package de.baluserver.erzmarken;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Plan a complete delivery to ordinary inventory slots. Never drops overflow items. */
public final class MinecraftRetailSnapshot {
    private MinecraftRetailSnapshot() { }
    public static final class NoSpaceException extends IOException {
        public NoSpaceException() { super("Nicht genug Platz im Hauptinventar fuer den ganzen Kauf"); }
    }
    public static byte[] afterDelivery(byte[] before, RegistryAccess access, String itemId, int quantity) throws IOException {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || quantity < 1 || quantity > 64) throw new IOException("Artikel oder Menge ungueltig");
        var item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new IOException("Unbekanntes Item"));
        ItemStack ordinary = new ItemStack(item);
        if (ordinary.isEmpty() || ordinary.getMaxStackSize() < 1) throw new IOException("Artikel ist nicht lieferbar");
        var root = ShopInventorySnapshotFormat.read(before, Inventory.INVENTORY_SIZE + Inventory.EQUIPMENT_SLOT_MAPPING.size());
        var contents = root.getAsJsonArray("items");
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        ItemStack[] stacks = new ItemStack[Inventory.INVENTORY_SIZE];
        for (int slot = 0; slot < stacks.length; slot++) {
            JsonElement encoded = contents.get(slot);
            stacks[slot] = encoded.isJsonNull() ? ItemStack.EMPTY : ItemStack.CODEC.parse(ops, encoded).result()
                .orElseThrow(() -> new IOException("Inventaritem nicht lesbar"));
        }
        int remaining = quantity;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < stacks.length && remaining > 0; slot++) {
                ItemStack stack = stacks[slot];
                if (pass == 0 && (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, ordinary))) continue;
                if (pass == 1 && !stack.isEmpty()) continue;
                int room = ordinary.getMaxStackSize() - (stack.isEmpty() ? 0 : stack.getCount());
                if (room <= 0) continue;
                int add = Math.min(remaining, room);
                ItemStack updated = stack.isEmpty() ? ordinary.copy() : stack.copy();
                updated.setCount((stack.isEmpty() ? 0 : stack.getCount()) + add);
                stacks[slot] = updated;
                contents.set(slot, ItemStack.CODEC.encodeStart(ops, updated).result()
                    .orElseThrow(() -> new IOException("Lieferung nicht serialisierbar")));
                remaining -= add;
            }
        }
        if (remaining != 0) throw new NoSpaceException();
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
