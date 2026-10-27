package de.baluserver.erzmarken;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import de.baluserver.erzmarken.mixin.MinecraftServerPlayerDataAccessor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Minecraft-facing bridge for durable shop inventory snapshots.
 *
 * Does not itself activate any session. Must only be called on the server thread
 * after closing any open menus. This saves main inventory, hotbar, armor, offhand
 * and the menu-carried stack. Experience and gamemode are intentionally left
 * unchanged by an inventory-only switch.
 * Persistence is verified against the actual compressed player file after a
 * targeted vanilla save. Activation still requires Fabric/Mixin and server tests.
 */
public final class MinecraftShopInventoryAdapter implements ShopInventorySwitchController.InventoryAdapter {
    private static final int VERSION = ShopInventorySnapshotFormat.VERSION;
    private final ServerPlayer player;

    public MinecraftShopInventoryAdapter(ServerPlayer player) {
        this.player = player;
    }

    private RegistryOps<JsonElement> ops() {
        RegistryAccess access = player.registryAccess();
        return RegistryOps.create(JsonOps.INSTANCE, access);
    }

    @Override public byte[] snapshot() throws IOException {
        ensureMenuClosed();
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        int size = player.getInventory().getContainerSize();
        root.addProperty("slots", size);
        root.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
        JsonArray contents = new JsonArray();
        for (int i = 0; i < size; i++) {
            contents.add(encode(player.getInventory().getItem(i)));
        }
        root.add("items", contents);
        root.add("cursor", encode(player.containerMenu.getCarried()));
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override public void apply(byte[] completeSnapshot) throws IOException {
        ensureMenuClosed();
        if (completeSnapshot == null) throw new IOException("Inventardaten fehlen");
        try {
            JsonObject obj = ShopInventorySnapshotFormat.read(completeSnapshot, player.getInventory().getContainerSize());
            int n = obj.get("slots").getAsInt();
            if (n != player.getInventory().getContainerSize()) throw new IOException("Inventargroesse hat sich geaendert");
            JsonArray contents = obj.getAsJsonArray("items");
            if (contents.size() != n) throw new IOException("Unvollstaendige Inventarliste");
            ItemStack[] decoded = new ItemStack[n];
            for (int i = 0; i < n; i++) decoded[i] = decode(contents.get(i));
            ItemStack cursor = decode(obj.get("cursor"));
            // No live slot changes until the entire snapshot has been decoded.
            for (int i = 0; i < n; i++) player.getInventory().setItem(i, decoded[i]);
            player.containerMenu.setCarried(cursor);
            player.getInventory().setSelectedSlot(obj.get("selectedSlot").getAsInt());
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Inventardaten konnten nicht geladen werden", e); }
    }

    @Override public void clear() throws IOException {
        ensureMenuClosed();
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    @Override public boolean isEmpty() throws IOException {
        ensureMenuClosed();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (!player.getInventory().getItem(i).isEmpty()) return false;
        }
        return player.containerMenu.getCarried().isEmpty();
    }

    private JsonElement encode(ItemStack stack) throws IOException {
        if (stack.isEmpty()) return com.google.gson.JsonNull.INSTANCE;
        return ItemStack.CODEC.encodeStart(ops(), stack)
            .result().orElseThrow(() -> new IOException("ItemStack konnte nicht serialisiert werden"));
    }

    private ItemStack decode(JsonElement encoded) throws IOException {
        if (encoded == null || encoded.isJsonNull()) return ItemStack.EMPTY;
        return ItemStack.CODEC.parse(ops(), encoded)
            .result().orElseThrow(() -> new IOException("ItemStack konnte nicht wiederhergestellt werden"));
    }

    private void ensureMenuClosed() throws IOException {
        if (!player.level().getServer().isSameThread()) throw new IOException("Inventarzugriff nur auf dem Serverthread");
        if (player.containerMenu != player.inventoryMenu)
            throw new IOException("Bitte alle Container und Shopmenues vor dem Inventarwechsel schliessen");
        if (!player.containerMenu.getCarried().isEmpty() || !player.inventoryMenu.getCraftSlots().isEmpty())
            throw new IOException("Cursor und persoenliches Craftingfeld muessen vor dem Wechsel leer sein");
    }

    @Override public void requirePersistenceSupport() throws IOException {
        ensureMenuClosed();
        var server = player.level().getServer();
        if (!(server instanceof MinecraftServerPlayerDataAccessor))
            throw new IOException("Spielerspeicher-Mixin nicht aktiv");
        if (((MinecraftServerPlayerDataAccessor) server).dulliehausen$getPlayerDataStorage() == null)
            throw new IOException("Spielerspeicher fehlt");
        MinecraftSavedInventory.requireSafePath(playerFile());
    }

    @Override public void persistAndVerify(byte[] expected) throws IOException {
        requirePersistenceSupport();
        ShopInventorySnapshotFormat.read(expected, player.getInventory().getContainerSize());
        var storage = ((MinecraftServerPlayerDataAccessor) player.level().getServer()).dulliehausen$getPlayerDataStorage();
        if (storage == null) throw new IOException("Spielerspeicher fehlt");
        storage.save(player);
        MinecraftSavedInventory.verifyFile(playerFile(), player.getUUID(), player.registryAccess(), expected);
    }

    private java.nio.file.Path playerFile() {
        return player.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat");
    }
}
