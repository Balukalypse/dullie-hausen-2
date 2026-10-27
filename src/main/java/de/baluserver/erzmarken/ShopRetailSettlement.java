package de.baluserver.erzmarken;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Confirm a currently executing retail sale; never replay or infer compensation. */
public final class ShopRetailSettlement {
    private ShopRetailSettlement() { }

    public static void verifyBefore(Path world, ShopRetailPlanStore.Plan plan) throws IOException {
        try {
            JsonObject tokens = StrictJsonFiles.readObject(world.resolve("data/erzmarken.json"), 16 * 1024 * 1024);
            JsonObject shop = StrictJsonFiles.readObject(world.resolve("data/erzmarken-shop.json"), 16 * 1024 * 1024);
            if (number(tokens.getAsJsonObject("players").getAsJsonObject(plan.player().toString()), "balance") != plan.playerBalanceBefore()
                || number(shop, "shopWallet") != plan.walletBefore()
                || number(shop.getAsJsonObject("wholesaleStock"), plan.itemId()) != plan.stockBefore()
                || number(shop.getAsJsonObject("retailPrices"), plan.itemId()) != plan.unitPrice())
                throw new IOException("Gespeicherte Ausgangsfinanzen weichen vom Verkaufsplan ab");
            if (tokens.has("shopDebits") && tokens.getAsJsonObject("shopDebits").has(plan.id()))
                throw new IOException("Verkaufs-ID besitzt bereits eine Abbuchung");
            if (shop.has("retailHistory")) for (var value : shop.getAsJsonArray("retailHistory"))
                if (plan.id().equals(value.getAsJsonObject().get("id").getAsString()))
                    throw new IOException("Verkaufs-ID besitzt bereits einen Shopbeleg");
        } catch (RuntimeException failure) { throw new IOException("Verkaufs-Ausgangsfinanzen nicht lesbar", failure); }
    }

    public static void finishCurrent(Path world, ShopRetailJournal.Entry journal,
            ShopInventorySwitchController.InventoryAdapter inventory) throws IOException {
        if (journal == null || inventory == null || !"ITEM_DELIVERED_UNSAVED".equals(journal.stage())
            || !journal.equals(ShopRetailJournal.read(world, journal.id())))
            throw new IOException("Verkauf ist nicht zum Abschluss bereit");
        var plan = ShopRetailPlanStore.read(world, journal.id());
        byte[] expected = plan.inventoryAfter();
        try {
            inventory.requirePersistenceSupport();
            if (!Arrays.equals(expected, inventory.snapshot()))
                throw new IOException("Geliefertes Inventar weicht vom Verkaufsplan ab");
            inventory.persistAndVerify(expected.clone());
            if (!Arrays.equals(expected, inventory.snapshot()))
                throw new IOException("Inventar hat sich nach Verkaufsspeicherung geaendert");
            verifyFinances(world, plan);
            ShopRetailJournal.finishVerified(world, journal);
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Verkauf nicht abschliessend verifiziert", failure); }
    }

    public static void verifyFinances(Path world, ShopRetailPlanStore.Plan plan) throws IOException {
        try {
            JsonObject tokens = StrictJsonFiles.readObject(world.resolve("data/erzmarken.json"), 16 * 1024 * 1024);
            JsonObject shop = StrictJsonFiles.readObject(world.resolve("data/erzmarken-shop.json"), 16 * 1024 * 1024);
            JsonObject account = tokens.getAsJsonObject("players").getAsJsonObject(plan.player().toString());
            if (number(account, "balance") != plan.playerBalanceAfter()) throw new IOException("Spielerkonto weicht vom Verkaufsplan ab");
            JsonObject debit = tokens.getAsJsonObject("shopDebits").getAsJsonObject(plan.id());
            if (!plan.player().toString().equals(debit.get("playerId").getAsString())
                || number(debit, "amount") != plan.total()) throw new IOException("Verkaufs-Tokenbeleg fehlt oder weicht ab");
            if (number(shop, "shopWallet") != plan.walletAfter()
                || number(shop.getAsJsonObject("wholesaleStock"), plan.itemId()) != plan.stockAfter())
                throw new IOException("Shopkasse oder Lagerbestand weichen vom Verkaufsplan ab");
            int matches = 0;
            for (var value : shop.getAsJsonArray("retailHistory")) {
                JsonObject receipt = value.getAsJsonObject();
                if (!plan.id().equals(receipt.get("id").getAsString())) continue;
                matches++;
                if (!plan.player().toString().equals(receipt.get("playerUuid").getAsString())
                    || !plan.itemId().equals(receipt.get("itemId").getAsString())
                    || number(receipt, "quantity") != plan.quantity()
                    || number(receipt, "unitPrice") != plan.unitPrice()
                    || number(receipt, "paid") != plan.total()
                    || number(receipt, "walletAfter") != plan.walletAfter()
                    || number(receipt, "stockAfter") != plan.stockAfter()
                    || number(receipt, "timestampMillis") <= 0)
                    throw new IOException("Shop-Verkaufsbeleg weicht vom Plan ab");
            }
            if (matches != 1) throw new IOException("Shop-Verkaufsbeleg fehlt oder ist doppelt");
        } catch (RuntimeException failure) { throw new IOException("Verkaufsfinanzen nicht lesbar", failure); }
    }
    private static long number(JsonObject object, String field) {
        return object.get(field).getAsBigDecimal().longValueExact();
    }
}
