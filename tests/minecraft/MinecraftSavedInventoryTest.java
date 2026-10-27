import de.baluserver.erzmarken.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;

/** Uses the real cached 26.3 codecs and compressed NBT; no running server or mock Minecraft. */
public class MinecraftSavedInventoryTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError("must reject"); } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // No data-pack server is running: bind explicit fixture defaults for codec tests.
        // This tests full custom component roundtrips, not vanilla data-pack loading.
        BuiltInRegistries.ITEM.listElements().forEach(holder -> {
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        });
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var nbtOps = RegistryOps.create(NbtOps.INSTANCE, access);
        var jsonOps = RegistryOps.create(JsonOps.INSTANCE, access);
        UUID player = UUID.randomUUID();
        int slots = Inventory.INVENTORY_SIZE + Inventory.EQUIPMENT_SLOT_MAPPING.size();
        ItemStack stone = new ItemStack(Items.STONE, 7);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        helmet.set(DataComponents.CUSTOM_NAME, Component.literal("Privater Helm"));
        ItemStack offhand = new ItemStack(Items.TORCH, 13);
        EntityEquipment equipment = new EntityEquipment();
        equipment.set(EquipmentSlot.HEAD, helmet); equipment.set(EquipmentSlot.OFFHAND, offhand);
        CompoundTag disk = new CompoundTag();
        disk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        disk.store("UUID", UUIDUtil.CODEC, player);
        disk.putInt("SelectedItemSlot", 4);
        disk.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps, List.of(new ItemStackWithSlot(4, stone)));
        disk.store("equipment", EntityEquipment.CODEC, nbtOps, equipment);
        JsonObject snapshot = new JsonObject(); snapshot.addProperty("version", 2);
        snapshot.addProperty("slots", slots); snapshot.addProperty("selectedSlot", 4);
        JsonArray contents = new JsonArray();
        for (int i = 0; i < slots; i++) contents.add(JsonNull.INSTANCE);
        contents.set(4, ItemStack.CODEC.encodeStart(jsonOps, stone).getOrThrow());
        for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
            ItemStack stack = equipment.get(entry.getValue());
            if (!stack.isEmpty()) contents.set(entry.getIntKey(), ItemStack.CODEC.encodeStart(jsonOps, stack).getOrThrow());
        }
        snapshot.add("items", contents); snapshot.add("cursor", JsonNull.INSTANCE);
        byte[] expected = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        MinecraftSavedInventory.verify(disk, player, access, expected); checks++;
        byte[] originalExpected = expected.clone();
        byte[] delivered = MinecraftRetailSnapshot.afterDelivery(expected, access, "minecraft:stone", 60);
        check(Arrays.equals(expected, originalExpected));
        CompoundTag deliveredDisk = disk.copy();
        deliveredDisk.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps,
            List.of(new ItemStackWithSlot(0, new ItemStack(Items.STONE, 3)), new ItemStackWithSlot(4, new ItemStack(Items.STONE, 64))));
        MinecraftSavedInventory.verify(deliveredDisk, player, access, delivered); checks++;
        denied(() -> MinecraftRetailSnapshot.afterDelivery(expected, access, "minecraft:air", 1));
        denied(() -> MinecraftRetailSnapshot.afterDelivery(expected, access, "minecraft:no_such_item", 1));
        denied(() -> MinecraftRetailSnapshot.afterDelivery(expected, access, "minecraft:stone", 0));
        JsonObject fullInventory = snapshot.deepCopy();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
            fullInventory.getAsJsonArray("items").set(slot,
                ItemStack.CODEC.encodeStart(jsonOps, new ItemStack(Items.STONE, 64)).getOrThrow());
        denied(() -> MinecraftRetailSnapshot.afterDelivery(fullInventory.toString().getBytes(StandardCharsets.UTF_8), access, "minecraft:stone", 1));
        byte[] planned = MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:stone", 2);
        check(Arrays.equals(expected, originalExpected));
        CompoundTag plannedDisk = disk.copy();
        plannedDisk.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps,
            List.of(new ItemStackWithSlot(4, new ItemStack(Items.STONE, 5))));
        MinecraftSavedInventory.verify(plannedDisk, player, access, planned); checks++;
        denied(() -> MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:dirt", 2));
        denied(() -> MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:stone", 8));
        denied(() -> MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:stone", 0));
        JsonObject namedHeld = snapshot.deepCopy();
        ItemStack namedStone = stone.copy(); namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("Privat"));
        namedHeld.getAsJsonArray("items").set(4, ItemStack.CODEC.encodeStart(jsonOps, namedStone).getOrThrow());
        denied(() -> MinecraftBuybackSnapshot.afterRemoval(namedHeld.toString().getBytes(StandardCharsets.UTF_8), access, "minecraft:stone", 2));
        byte[] emptyPlan = MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:stone", 7);
        CompoundTag emptyDisk = disk.copy();
        emptyDisk.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps, List.of());
        MinecraftSavedInventory.verify(emptyDisk, player, access, emptyPlan); checks++;
        byte[] singlePlan = MinecraftBuybackSnapshot.afterRemoval(expected, access, "minecraft:stone", 6);
        CompoundTag singleDisk = disk.copy();
        singleDisk.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps,
            List.of(new ItemStackWithSlot(4, new ItemStack(Items.STONE))));
        MinecraftSavedInventory.verify(singleDisk, player, access, singlePlan); checks++;
        denied(() -> MinecraftSavedInventory.verify(disk, UUID.randomUUID(), access, expected));
        CompoundTag wrongCount = disk.copy();
        wrongCount.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps, List.of(new ItemStackWithSlot(4, new ItemStack(Items.STONE, 6))));
        denied(() -> MinecraftSavedInventory.verify(wrongCount, player, access, expected));
        CompoundTag missingArmor = disk.copy(); missingArmor.remove("equipment");
        denied(() -> MinecraftSavedInventory.verify(missingArmor, player, access, expected));
        EntityEquipment renamed = new EntityEquipment(); renamed.setAll(equipment);
        renamed.set(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        CompoundTag lostName = disk.copy(); lostName.store("equipment", EntityEquipment.CODEC, nbtOps, renamed);
        denied(() -> MinecraftSavedInventory.verify(lostName, player, access, expected));
        CompoundTag wrongSelection = disk.copy(); wrongSelection.putInt("SelectedItemSlot", 0);
        denied(() -> MinecraftSavedInventory.verify(wrongSelection, player, access, expected));
        CompoundTag duplicate = disk.copy();
        duplicate.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps,
            List.of(new ItemStackWithSlot(4, stone), new ItemStackWithSlot(4, stone)));
        denied(() -> MinecraftSavedInventory.verify(duplicate, player, access, expected));
        CompoundTag oldFormat = disk.copy();
        oldFormat.store("Inventory", ItemStackWithSlot.CODEC.listOf(), nbtOps, List.of(new ItemStackWithSlot(100, helmet)));
        denied(() -> MinecraftSavedInventory.verify(oldFormat, player, access, expected));
        CompoundTag oldVersion = disk.copy(); oldVersion.putInt("DataVersion", 0);
        denied(() -> MinecraftSavedInventory.verify(oldVersion, player, access, expected));
        JsonObject cursor = snapshot.deepCopy(); cursor.add("cursor", contents.get(4));
        denied(() -> MinecraftSavedInventory.verify(disk, player, access, cursor.toString().getBytes(StandardCharsets.UTF_8)));
        Path dir = Files.createTempDirectory("real-playerdata-nbt-");
        try {
            Path file = dir.resolve(player + ".dat");
            NbtIo.writeCompressed(disk, file);
            byte[] bytes = Files.readAllBytes(file);
            MinecraftSavedInventory.verifyCompressed(bytes, player, access, expected); checks++;
            denied(() -> MinecraftSavedInventory.verifyCompressed(bytes, UUID.randomUUID(), access, expected));
            denied(() -> MinecraftSavedInventory.verifyCompressed("broken gzip".getBytes(StandardCharsets.UTF_8), player, access, expected));
            MinecraftSavedInventory.verifyFile(file, player, access, expected); checks++;
            check(Arrays.equals(bytes, Files.readAllBytes(file)));
            Files.writeString(file, "broken gzip");
            denied(() -> MinecraftSavedInventory.verifyFile(file, player, access, expected));
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Real Minecraft 26.3 saved inventory: " + checks + " checks passed");
    }
}
