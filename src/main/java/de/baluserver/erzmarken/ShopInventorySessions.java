package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Shop inventory life-cycle. Disabled by default until a complete Fabric build and integration audit. */
public final class ShopInventorySessions {
    // This guard must not be removed before end-to-end recovery and multiplayer tests.
    public static final boolean ENABLED = false;
    private ShopInventorySessions() { }

    private static ShopInventorySwitchController controller(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault");
        return new ShopInventorySwitchController(new ShopInventoryVault(root));
    }

    public static void open(ServerPlayer player) throws IOException {
        if (!ENABLED && !Phase2TestAccess.allowed(player.level().getServer(), player.getUUID(), Phase2TestPolicy.Feature.INVENTORY))
            throw new IOException("Inventar-Arbeitsmodus wartet auf technische Freigabe");
        ShopState shop = ErzmarkenMod.shopState;
        // The combined opening command loads inventory BEFORE publishing shop availability.
        if (shop == null || !shop.isOwner(player) || !shop.contains(player))
            throw new IOException("Shopbesitzer muss sich im Shopbereich befinden");
        if (player.isCreative() || player.isSpectator())
            throw new IOException("Inventarwechsel nur im Ueberlebensmodus");
        try {
            controller(player.level().getServer()).enter(player.getUUID(), new MinecraftShopInventoryAdapter(player));
        } catch (IOException | RuntimeException failure) {
            quarantineFailedSwitch(player);
            throw failure;
        }
    }

    public static void close(ServerPlayer player) throws IOException {
        try {
            // Existing live sessions can restore private items after test admission is revoked.
            var existing = new ShopInventoryVault(player.level().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/dulliehausen-inventory-vault")).read(player.getUUID());
            if (!ENABLED && existing.phase() != ShopInventoryVault.Phase.SHOP_ACTIVE
                    && !Phase2TestAccess.allowed(player.level().getServer(), player.getUUID(), Phase2TestPolicy.Feature.INVENTORY))
                throw new IOException("Inventar-Arbeitsmodus wartet auf technische Freigabe");
            controller(player.level().getServer()).exit(player.getUUID(), new MinecraftShopInventoryAdapter(player));
        } catch (IOException | RuntimeException failure) {
            quarantineFailedSwitch(player);
            throw failure;
        }
    }

    public static ShopInventoryVault.Phase phase(MinecraftServer server, java.util.UUID owner) throws IOException {
        return new ShopInventoryVault(server.getWorldPath(LevelResource.ROOT)
            .resolve("data/dulliehausen-inventory-vault")).read(owner).phase();
    }

    /** A confirmed entry whose shop-status write failed must never be replayed blindly. */
    public static void quarantineOpeningFailure(ServerPlayer player, IOException original) {
        try {
            new ShopInventoryVault(player.level().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/dulliehausen-inventory-vault")).flagRecovery(player.getUUID());
        } catch (IOException | RuntimeException markingFailure) { original.addSuppressed(markingFailure); }
        player.connection.disconnect(Component.literal(
            "Dullie Hausen: Shop-Oeffnung unterbrochen. Inventar gesichert; Serververwaltung muss den Zustand pruefen."));
    }

    /** Only this owner's confirmed working inventory may remain for the close operation. */
    public static void assertMayCloseOwner(MinecraftServer server, java.util.UUID owner) throws IOException {
        var vault = new ShopInventoryVault(server.getWorldPath(LevelResource.ROOT)
            .resolve("data/dulliehausen-inventory-vault"));
        for (var entry : vault.inspectUnfinished()) {
            if (owner.equals(entry.playerId()) && entry.phase() == ShopInventoryVault.Phase.SHOP_ACTIVE) continue;
            throw new IOException("Shop-Schliessung gesperrt: ungepruefte Inventarsitzung " + entry.playerId());
        }
    }

    /** Do not leave a connected player playing with a partially switched inventory. */
    private static void quarantineFailedSwitch(ServerPlayer player) {
        try {
            var vault = new ShopInventoryVault(player.level().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/dulliehausen-inventory-vault"));
            var phase = vault.read(player.getUUID()).phase();
            if (phase == ShopInventoryVault.Phase.PRIVATE_ACTIVE || phase == ShopInventoryVault.Phase.SHOP_ACTIVE) return;
            vault.flagRecovery(player.getUUID());
        } catch (IOException | RuntimeException failure) {
            ErzmarkenMod.LOGGER.error("Shop-Wechsel nicht sicher abschliessbar: UUID={}", player.getUUID(), failure);
        }
        player.connection.disconnect(Component.literal(
            "Dullie Hausen: Inventarwechsel unterbrochen. Bitte Serververwaltung zur Pruefung kontaktieren."));
    }

    /** Block normal play when a previous inventory switch is unresolved.
     * Never restore, clear or overwrite live player items on login here.
     */
    public static boolean inspectLogin(ServerPlayer player) {
        try {
            ShopInventoryVault vault = new ShopInventoryVault(player.level().getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault"));
            ShopInventoryVault.Record record = vault.read(player.getUUID());
            if (record.phase() != ShopInventoryVault.Phase.PRIVATE_ACTIVE) {
                ErzmarkenMod.LOGGER.error("Inventar-Recovery erforderlich fuer UUID={} ({}): {}",
                    player.getUUID(), player.getUUID(), record.phase());
                player.connection.disconnect(Component.literal(
                    "Dullie Hausen: Shop-Inventar muss vor dem Login manuell wiederhergestellt werden. Bitte Serververwaltung kontaktieren."));
                return false;
            }
            return true;
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Inventardaten nicht pruefbar fuer {}: {}", player.getUUID(), ex.getMessage(), ex);
            player.connection.disconnect(Component.literal(
                "Dullie Hausen: Shop-Inventarpruefung fehlgeschlagen. Bitte Serververwaltung kontaktieren."));
            return false;
        }
    }

    /**
     * Legacy creative cleanup must never clear an inventory while a durable shop
     * inventory session is active, unfinished, or unreadable. Fail closed.
     * Does not modify player inventory or vault data.
     */
    public static boolean mayRunLegacyCleanup(ServerPlayer player) {
        try {
            ShopInventoryVault vault = new ShopInventoryVault(player.level().getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault"));
            boolean safe = LegacyShopCleanupPolicy.allowed(vault.read(player.getUUID()));
            if (!safe) ErzmarkenMod.LOGGER.error(
                "Legacy-Shop-Aufraeumen blockiert: offene Inventarsitzung UUID={}", player.getUUID());
            return safe;
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Legacy-Shop-Aufraeumen blockiert: Vault unlesbar UUID={}",
                player.getUUID(), ex);
            return false;
        }
    }

    /** Called before vanilla applies death drops. Does not change inventories or vault files.
     * Existing vault sessions remain protected even after test admission is revoked.
     * An unreadable vault of the shop owner is treated conservatively (fail-closed).
     */
    public static boolean preventVanillaDeath(ServerPlayer player) {
        // A persisted session remains protected after owner changes or test admission revocation.
        try {
            ShopInventoryVault vault = new ShopInventoryVault(player.level().getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault"));
            var record = vault.read(player.getUUID());
            return ShopPreDeathPolicy.mustPreventDeath(true, record.phase(), false);
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Shop-Vault vor toedlichem Schaden unlesbar: UUID={}",
                player.getUUID(), ex);
            return ShopPreDeathPolicy.mustPreventDeath(true, null, true);
        }
    }

    /**
     * A death may cause Vanilla to drop/copy the live player inventory independently
     * of the vault. This callback MUST NOT restore it automatically. Mark the
     * session for administrator review and disconnect the respawned player.
     * Normal respawns without a shop session are unaffected.
     */
    public static void inspectRespawn(ServerPlayer player) {
        try {
            ShopInventoryVault vault = new ShopInventoryVault(player.level().getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault"));
            ShopInventoryVault.Record record = vault.read(player.getUUID());
            if (!ShopRespawnRecoveryPolicy.requiresQuarantine(record)) return;
            if (record.phase() != ShopInventoryVault.Phase.RECOVERY_REQUIRED)
                vault.flagRecovery(player.getUUID());
            ErzmarkenMod.LOGGER.error("Shop-Inventar nach Respawn gesperrt: UUID={} alte Phase={}. "
                + "Spielerdatei, Drops und Vault vor Wiederherstellung sichern!",
                player.getUUID(), record.phase());
            player.connection.disconnect(Component.literal(
                "Dullie Hausen: Shop-Inventar nach Tod gesperrt. Bitte Serververwaltung kontaktieren."));
        } catch (IOException | IllegalStateException ex) {
            ErzmarkenMod.LOGGER.error("Shop-Inventar Respawn-Kontrolle fehlgeschlagen: UUID={}",
                player.getUUID(), ex);
            player.connection.disconnect(Component.literal(
                "Dullie Hausen: Inventarpruefung nach Tod fehlgeschlagen. Bitte Serververwaltung kontaktieren."));
        }
    }

    /**
     * Disconnect may happen after vanilla has already serialized player data.
     * Never swap or clear live items here. Persist a quarantine marker so the
     * next login cannot misinterpret a live shop inventory as private items.
     * An administrator must compare vault, playerdata and any drops first.
     */
    public static void inspectDisconnect(ServerPlayer player) {
        try {
            ShopInventoryVault vault = new ShopInventoryVault(player.level().getServer()
                .getWorldPath(LevelResource.ROOT).resolve("data/dulliehausen-inventory-vault"));
            var record = vault.read(player.getUUID());
            if (record.phase() != ShopInventoryVault.Phase.PRIVATE_ACTIVE) {
                vault.flagRecovery(player.getUUID());
                ErzmarkenMod.LOGGER.error("Shop-Logout quarantined: UUID={} formerPhase={}. "
                    + "Keine Inventaraenderung; Spielerdatei und Vault vor Recovery sichern!",
                    player.getUUID(), record.phase());
            }
        } catch (IOException | IllegalStateException ex) {
            ErzmarkenMod.LOGGER.error("Shop-Logout Recovery-Marker konnte nicht gespeichert werden: UUID={}",
                player.getUUID(), ex);
        }
    }

    /**
     * SERVER_STOPPING audit persists a recovery marker for outstanding sessions.
     * A server crash bypasses this callback, but the login gate already rejects
     * every non-PRIVATE_ACTIVE record. Corrupt files remain untouched.
     */
    public static void logShutdownAudit(MinecraftServer server) {
        try {
            ShopInventoryVault vault = new ShopInventoryVault(server.getWorldPath(LevelResource.ROOT)
                .resolve("data/dulliehausen-inventory-vault"));
            for (var entry : vault.inspectUnfinished()) {
                ErzmarkenMod.LOGGER.error("Shop-Vault beim Shutdown offen: {} Phase={} Fehler={}",
                    entry.playerId(), entry.phase(), entry.error());
                if (entry.phase() == null) continue; // Corrupt: never overwrite.
                try {
                    vault.flagRecovery(entry.playerId());
                } catch (IOException | IllegalStateException ex) {
                    ErzmarkenMod.LOGGER.error("Shutdown-Quarantaene fehlgeschlagen: UUID={}",
                        entry.playerId(), ex);
                }
            }
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Shop-Vault Shutdown-Audit fehlgeschlagen", ex);
        }
    }

    public static void assertMayClose(MinecraftServer server) throws IOException {
        ShopInventoryVault vault = new ShopInventoryVault(server.getWorldPath(LevelResource.ROOT)
            .resolve("data/dulliehausen-inventory-vault"));
        for (ShopInventoryVault.Inspection entry : vault.inspectUnfinished()) {
            if (entry.phase() == null || entry.phase() == ShopInventoryVault.Phase.RECOVERY_REQUIRED
                || entry.phase() == ShopInventoryVault.Phase.ENTER_PREPARED
                || entry.phase() == ShopInventoryVault.Phase.EXIT_PREPARED)
                throw new IOException("Inventar-Recovery bei " + entry.playerId() + ": " + entry.error());
            if (entry.phase() == ShopInventoryVault.Phase.SHOP_ACTIVE)
                throw new IOException("Shop-Arbeitsinventar noch aktiv: " + entry.playerId());
        }
    }
}
