package de.baluserver.erzmarken;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Independent readback of actual 26.3 playerdata, including separately stored equipment. */
public final class MinecraftSavedInventory {
    private MinecraftSavedInventory() { }
    public static void verifyCompressed(byte[] saved, UUID player, RegistryAccess access, byte[] expected) throws IOException {
        if (saved == null || saved.length == 0 || saved.length > 16 * 1024 * 1024)
            throw new IOException("Spielerdatei fehlt oder ist zu gross");
        try {
            verify(NbtIo.readCompressed(new ByteArrayInputStream(saved), NbtAccounter.create(16 * 1024 * 1024)), player, access, expected);
        } catch (RuntimeException failure) { throw new IOException("Gespeichertes NBT nicht verifizierbar", failure); }
    }
    public static void verifyFile(Path file, UUID player, RegistryAccess access, byte[] expected) throws IOException {
        requireSafePath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024 * 1024)
            throw new IOException("Spielerdatei fehlt oder ist zu gross");
        // Vanilla save() logs some failures instead of throwing. Only this readback is evidence.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
        try {
            verify(readFileReadOnly(file), player, access, expected);
        } catch (RuntimeException failure) { throw new IOException("Gespeichertes NBT nicht verifizierbar", failure); }
    }
    /** Admin diagnostics: no write channel, no save request, no force operation. */
    public static CompoundTag readFileReadOnly(Path file) throws IOException {
        requireSafePath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024 * 1024)
            throw new IOException("Spielerdatei fehlt oder ist zu gross");
        try { return NbtIo.readCompressed(file, NbtAccounter.create(16 * 1024 * 1024)); }
        catch (RuntimeException invalid) { throw new IOException("Spielerdatei nicht lesbar", invalid); }
    }
    public static void requireSafePath(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Spielerdateipfad enthaelt Verknuepfung");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Spielerdateiziel ist keine regulaere Datei");
    }
    public static void verify(CompoundTag disk, UUID player, RegistryAccess access, byte[] expected) throws IOException {
        try {
            int slots = Inventory.INVENTORY_SIZE + Inventory.EQUIPMENT_SLOT_MAPPING.size();
            var snapshot = ShopInventorySnapshotFormat.read(expected, slots);
            if (!(disk.get("DataVersion") instanceof IntTag)
                    || disk.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version())
                throw new IOException("Spielerdatei hat nicht die laufende Datenversion");
            if (!disk.read("UUID", UUIDUtil.CODEC).orElseThrow().equals(player))
                throw new IOException("Spielerdatei gehoert zu anderer UUID");
            if (!(disk.get("SelectedItemSlot") instanceof IntTag)
                    || disk.getIntOr("SelectedItemSlot", -1) != snapshot.get("selectedSlot").getAsInt())
                throw new IOException("Gespeicherter Hotbar-Slot weicht ab");
            var nbtOps = RegistryOps.create(NbtOps.INSTANCE, access);
            var jsonOps = RegistryOps.create(JsonOps.INSTANCE, access);
            if (!(disk.get("Inventory") instanceof ListTag)) throw new IOException("Inventory-Liste fehlt");
            List<ItemStackWithSlot> inventory = ItemStackWithSlot.CODEC.listOf().parse(nbtOps, disk.get("Inventory"))
                .result().orElseThrow(() -> new IOException("Inventory-NBT unlesbar"));
            ItemStack[] actual = new ItemStack[slots]; Arrays.fill(actual, ItemStack.EMPTY);
            Set<Integer> seen = new HashSet<>();
            for (var entry : inventory) {
                if (entry.slot() < 0 || entry.slot() >= Inventory.INVENTORY_SIZE || !seen.add(entry.slot()))
                    throw new IOException("Doppelter oder fremder Inventory-Slot");
                actual[entry.slot()] = entry.stack();
            }
            EntityEquipment equipment = disk.contains("equipment")
                ? EntityEquipment.CODEC.parse(nbtOps, disk.get("equipment")).result()
                    .orElseThrow(() -> new IOException("Equipment-NBT unlesbar"))
                : new EntityEquipment();
            if (!equipment.get(EquipmentSlot.MAINHAND).isEmpty())
                throw new IOException("Unerwartete separate MAINHAND-Ausstattung");
            for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet())
                actual[entry.getIntKey()] = equipment.get(entry.getValue());
            var items = snapshot.getAsJsonArray("items");
            for (int i = 0; i < slots; i++) {
                JsonElement encoded = items.get(i);
                ItemStack wanted = encoded.isJsonNull() ? ItemStack.EMPTY
                    : ItemStack.CODEC.parse(jsonOps, encoded).result()
                        .orElseThrow(() -> new IOException("Snapshot-Item unlesbar"));
                if (wanted.getCount() != actual[i].getCount() || !ItemStack.isSameItemSameComponents(wanted, actual[i]))
                    throw new IOException("Gespeichertes Inventar weicht in Slot " + i + " ab");
            }
        } catch (RuntimeException failure) { throw new IOException("Spielerdatei konnte nicht verglichen werden", failure); }
    }
}
