package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Single shop location/owner (saved with world, not with the global config). */
public final class ShopState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private Data data;
    private IOException storageFailure;

    static final class Data {
        String owner; // UUID of the single shop manager
        Boolean open = Boolean.TRUE; // alte Welten bleiben aus Kompatibilitaetsgruenden offen
        String dimension;
        int[] first;
        int[] second;
        Set<String> temporaryCreative = new HashSet<>(); // nur fuer Bereinigung alter v0.3-Sessions
        List<CatalogueItem> creativeCatalogue = new ArrayList<>();
        boolean protectedRegion = false;
        Set<String> helpers = new HashSet<>();
        Set<String> warehouse = new HashSet<>();
        String shopMode = "grosshandel"; // switching policy, not yet an operational stock engine
        boolean buybackTradesEnabled = false; // fail closed until admin enables after testing
        long shopWallet = 2500L;
        Map<String, Long> wholesalePrices = new HashMap<>(); // console-defined per-item wholesale unit costs
        Map<String, Long> wholesaleStock = new HashMap<>(); // paid virtual warehouse, NOT physical items
        Map<String, Long> buybackPrices = new HashMap<>(); // owner-approved player buyback prices; NO active trade execution
        Map<String, BuybackProposal> buybackProposals = new HashMap<>(); // proposals must be owner-approved
        Map<String, Long> retailPrices = new HashMap<>(); // owner-defined sale price, NOT yet connected to physical item sales
        List<PurchaseReceipt> purchaseHistory = new ArrayList<>(); // capped durable finance trail
        List<BuybackReceipt> buybackHistory = new ArrayList<>(); // capped player buyback audit trail
        List<RetailReceipt> retailHistory = new ArrayList<>(); // stock/cash booking only; no delivery by itself
    }

    public record BuybackProposal(String itemId, long suggestedUnitPrice, String rationale, long createdMillis) { }

    public record PurchaseReceipt(String id, long timestampMillis, String itemId, int quantity,
            long unitPrice, long totalPrice, long balanceAfter) { }

    public record BuybackReceipt(String id, long timestampMillis, String playerUuid,
            String itemId, int quantity, long unitPrice, long paid, long walletAfter) { }

    public record RetailReceipt(String id, long timestampMillis, String playerUuid,
            String itemId, int quantity, long unitPrice, long paid, long walletAfter,
            long stockAfter) { }

    /** Nur Items, die der Serveradmin explizit per Konsole freigegeben hat. */
    public record CatalogueItem(String itemId, int amount) {
    }

    public ShopState(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                var raw = StrictJsonFiles.readObject(file, 16 * 1024 * 1024);
                checkExactNumbers(raw, "shopWallet");
                for (String map : new String[]{"wholesalePrices", "wholesaleStock", "buybackPrices", "retailPrices"})
                    if (raw.has(map))
                        for (var value : raw.getAsJsonObject(map).entrySet()) value.getValue().getAsBigDecimal().longValueExact();
                for (String history : new String[]{"purchaseHistory", "buybackHistory", "retailHistory"})
                    if (raw.has(history))
                        for (var value : raw.getAsJsonArray(history)) checkExactNumbers(value.getAsJsonObject(),
                            "quantity", "unitPrice", "timestampMillis", "totalPrice", "balanceAfter", "paid", "walletAfter", "stockAfter");
                data = GSON.fromJson(raw, Data.class);
            } catch (RuntimeException ex) {
                throw new IOException("Defekte Shopdaten: " + file, ex);
            }
        } else data = new Data();
        if (data != null && data.creativeCatalogue == null) data.creativeCatalogue = new ArrayList<>();
        if (data != null && data.shopMode == null) data.shopMode = "grosshandel";
        if (data != null && data.wholesalePrices == null) data.wholesalePrices = new HashMap<>();
        if (data != null && data.wholesaleStock == null) data.wholesaleStock = new HashMap<>();
        if (data != null && data.buybackPrices == null) data.buybackPrices = new HashMap<>();
        if (data != null && data.buybackProposals == null) data.buybackProposals = new HashMap<>();
        if (data != null && data.retailPrices == null) data.retailPrices = new HashMap<>();
        if (data != null && data.purchaseHistory == null) data.purchaseHistory = new ArrayList<>();
        if (data != null && data.buybackHistory == null) data.buybackHistory = new ArrayList<>();
        if (data != null && data.retailHistory == null) data.retailHistory = new ArrayList<>();
        if (data != null && data.helpers == null) data.helpers = new HashSet<>();
        if (data != null && data.warehouse == null) data.warehouse = new HashSet<>();
        if (data == null || data.temporaryCreative == null
                || (data.first != null && data.first.length != 3)
                || (data.second != null && data.second.length != 3)) {
            throw new IOException("Ungueltige Shopdaten: " + file);
        }
        if (!"grosshandel".equals(data.shopMode) && !"kreativ".equals(data.shopMode)) throw new IOException("Ungueltiger Shopmodus");
        if (data.shopWallet < 0 || data.shopWallet > 1_000_000_000L) throw new IOException("Ungueltige Shopkasse");
        if (data.wholesalePrices.size() > 4096 || data.wholesaleStock.size() > 4096 || data.retailPrices.size() > 4096) throw new IOException("Zu viele Grosshandelsartikel");
        for (var entry : data.wholesaleStock.entrySet()) {
            if (!validItemId(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 1_000_000L)
                throw new IOException("Ungueltiger Grosshandelsbestand: " + entry.getKey());
        }
        for (var entry : data.wholesalePrices.entrySet()) {
            if (!validItemId(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() < 1 || entry.getValue() > 1_000_000L)
                throw new IOException("Ungueltiger Grosshandelspreis: " + entry.getKey());
        }
        for (var entry : data.retailPrices.entrySet()) {
            if (!validItemId(entry.getKey()) || entry.getValue() == null
                    || entry.getValue() < 1 || entry.getValue() > 1_000_000L)
                throw new IOException("Ungueltiger Verkaufspreis: " + entry.getKey());
        }
        if (data.buybackPrices.size() > 4096 || data.buybackProposals.size() > 4096) throw new IOException("Zu viele Ankaufseintraege");
        for (var entry : data.buybackPrices.entrySet()) {
            if (!validItemId(entry.getKey()) || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 1_000_000L)
                throw new IOException("Ungueltiger Ankaufspreis: " + entry.getKey());
        }
        for (var entry : data.buybackProposals.entrySet()) {
            BuybackProposal p = entry.getValue();
            if (p == null || !validItemId(entry.getKey()) || !entry.getKey().equals(p.itemId())
                || p.suggestedUnitPrice() < 1 || p.suggestedUnitPrice() > 1_000_000L
                || p.rationale() == null || p.rationale().length() > 300 || p.createdMillis() < 0)
                throw new IOException("Ungueltiger Ankaufs-Vorschlag: " + entry.getKey());
        }
        if (data.buybackHistory.size() > 500) throw new IOException("Ankaufshistorie ueberschreitet Limit");
        for (BuybackReceipt receipt : data.buybackHistory) {
            if (receipt == null || receipt.id() == null || receipt.id().isBlank()
                || receipt.playerUuid() == null || !validItemId(receipt.itemId())
                || receipt.quantity() < 1 || receipt.quantity() > 64 || receipt.unitPrice() < 1
                || receipt.paid() != receipt.unitPrice() * receipt.quantity()
                || receipt.walletAfter() < 0 || receipt.walletAfter() > 1_000_000_000L)
                throw new IOException("Ungueltiger Ankaufsbeleg");
        }
        if (data.retailHistory.size() > 500) throw new IOException("Verkaufshistorie ueberschreitet Limit");
        for (RetailReceipt receipt : data.retailHistory) {
            if (receipt == null || receipt.id() == null || receipt.id().isBlank()
                || receipt.playerUuid() == null || !validItemId(receipt.itemId())
                || receipt.quantity() < 1 || receipt.quantity() > 64
                || receipt.unitPrice() < 1 || receipt.unitPrice() > 1_000_000L
                || receipt.paid() != receipt.unitPrice() * receipt.quantity()
                || receipt.walletAfter() < 0 || receipt.walletAfter() > 1_000_000_000L
                || receipt.stockAfter() < 0 || receipt.stockAfter() > 1_000_000L)
                throw new IOException("Ungueltiger Verkaufsbeleg");
        }
        if (data.purchaseHistory.size() > 500) throw new IOException("Einkaufshistorie ueberschreitet Limit");
        for (PurchaseReceipt receipt : data.purchaseHistory) {
            if (receipt == null || receipt.id() == null || receipt.id().isBlank()
                    || !validItemId(receipt.itemId()) || receipt.quantity() < 1 || receipt.quantity() > 2304
                    || receipt.unitPrice() < 1 || receipt.totalPrice() != receipt.unitPrice() * receipt.quantity()
                    || receipt.balanceAfter() < 0 || receipt.balanceAfter() > 1_000_000_000L)
                throw new IOException("Ungueltiger Einkaufsbeleg");
        }
        if (data.creativeCatalogue.size() > 54) throw new IOException("Shopkatalog hat mehr als 54 Artikel.");
        Set<String> seenCatalogue = new HashSet<>();
        for (CatalogueItem entry : data.creativeCatalogue) {
            if (entry == null || entry.itemId() == null
                    || !entry.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || entry.amount() < 1 || entry.amount() > 64
                    || !seenCatalogue.add(entry.itemId())) {
                throw new IOException("Ungueltiger oder doppelter Eintrag im Shopkatalog.");
            }
        }
    }

    private static void checkExactNumbers(com.google.gson.JsonObject object, String... fields) {
        for (String field : fields) if (object.has(field)) object.get(field).getAsBigDecimal().longValueExact();
    }

    public synchronized boolean buybackTradesEnabled() { return data.buybackTradesEnabled; }
    public synchronized void requireStorageAvailable() throws IOException {
        if (storageFailure != null) throw new IOException("Shopspeicher nach Schreibfehler gesperrt", storageFailure);
    }
    public synchronized void requirePersistedState() throws IOException {
        requireStorageAvailable();
        var disk = StrictJsonFiles.readObject(file, 16 * 1024 * 1024);
        // Older shop files predate the retail receipt list. Missing means empty.
        if (!disk.has("retailHistory")) disk.add("retailHistory", new com.google.gson.JsonArray());
        if (!GSON.toJsonTree(data).equals(disk))
            throw new IOException("Shopdaten im Arbeitsspeicher weichen von der Datei ab; keine Recovery-Freigabe");
    }
    public synchronized void setBuybackTradesEnabled(boolean enabled) throws IOException {
        boolean previous = data.buybackTradesEnabled;
        data.buybackTradesEnabled = enabled;
        try { write(); } catch (IOException | RuntimeException ex) {
            data.buybackTradesEnabled = previous;
            throw ex;
        }
    }

    public synchronized Map<String, Long> buybackPrices() { return Map.copyOf(data.buybackPrices); }
    public synchronized Map<String, BuybackProposal> buybackProposals() { return Map.copyOf(data.buybackProposals); }

    /** AI output / admin suggestion is only a proposal, NEVER an active price. */
    public synchronized void proposeBuyback(String itemId, long suggested, String rationale) throws IOException {
        if (!validItemId(itemId) || suggested < 1 || suggested > 1_000_000L
                || rationale == null || rationale.length() > 300)
            throw new IllegalArgumentException("Ungueltiger Vorschlag");
        BuybackProposal prior = data.buybackProposals.put(itemId,
            new BuybackProposal(itemId, suggested, rationale, System.currentTimeMillis()));
        try { write(); } catch (IOException | RuntimeException e) {
            if (prior == null) data.buybackProposals.remove(itemId);
            else data.buybackProposals.put(itemId, prior);
            throw e;
        }
    }

    /** Must be invoked ONLY by verified shop owner within shop. */
    public synchronized void approveBuyback(String itemId) throws IOException {
        BuybackProposal proposal = data.buybackProposals.get(itemId);
        if (proposal == null) throw new IllegalArgumentException("Kein offener Ankaufsvorschlag");
        Long previous = data.buybackPrices.put(itemId, proposal.suggestedUnitPrice());
        data.buybackProposals.remove(itemId);
        try { write(); } catch (IOException | RuntimeException e) {
            if (previous == null) data.buybackPrices.remove(itemId);
            else data.buybackPrices.put(itemId, previous);
            data.buybackProposals.put(itemId, proposal);
            throw e;
        }
    }

    public synchronized void rejectBuyback(String itemId) throws IOException {
        BuybackProposal previous = data.buybackProposals.remove(itemId);
        if (previous != null) try { write(); } catch (IOException | RuntimeException e) {
            data.buybackProposals.put(itemId, previous);
            throw e;
        }
    }

    public synchronized void disableBuyback(String itemId) throws IOException {
        Long previous = data.buybackPrices.remove(itemId);
        if (previous != null) try { write(); } catch (IOException | RuntimeException e) {
            data.buybackPrices.put(itemId, previous);
            throw e;
        }
    }

    /** Prepare a bounded purchase of ordinary, unmodified vanilla stacks (no components). */
    public record BuybackQuote(String itemId, int quantity, long unitPrice, long total,
            long walletBalance, long stockBefore) { }

    public synchronized BuybackQuote quoteBuyback(String itemId, int quantity) {
        if (storageFailure != null) throw new IllegalStateException("Shopspeicher nach Schreibfehler gesperrt");
        if (!isOpen() || !"grosshandel".equals(data.shopMode))
            throw new IllegalStateException("Shop ist geschlossen oder nicht im Grosshandelsmodus");
        if (!validItemId(itemId) || quantity < 1 || quantity > 64)
            throw new IllegalArgumentException("Artikel oder Menge ungueltig (1 bis 64)");
        Long unit = data.buybackPrices.get(itemId);
        if (unit == null) throw new IllegalStateException("Shop kauft diesen Artikel nicht an");
        long total = Math.multiplyExact(unit, (long) quantity);
        if (total > data.shopWallet) throw new IllegalStateException("Shopkasse hat nicht genug Token");
        long stock = data.wholesaleStock.getOrDefault(itemId, 0L);
        if (stock > 1_000_000L - quantity) throw new IllegalStateException("Shoplager ist voll");
        return new BuybackQuote(itemId, quantity, unit, total, data.shopWallet, stock);
    }

    /** One atomic shop-file write: wallet, virtual stock and audit receipt together.
     *  Cross-file player-token/item transaction coordination lives in ShopBuybackService. */
    public synchronized BuybackReceipt commitBuyback(UUID player, BuybackQuote q, String transactionId) throws IOException {
        ShopBuybackReceiptLink.requireValidTransactionId(transactionId);
        if (data.buybackHistory.stream().anyMatch(r -> transactionId.equals(r.id())))
            throw new IllegalStateException("Transaktions-ID bereits im Shoparchiv vorhanden");
        BuybackQuote current = quoteBuyback(q.itemId(), q.quantity());
        if (!current.equals(q)) throw new IllegalStateException("Angebot ist inzwischen veraltet");
        data.shopWallet -= q.total();
        data.wholesaleStock.put(q.itemId(), q.stockBefore() + q.quantity());
        BuybackReceipt receipt = new BuybackReceipt(transactionId, System.currentTimeMillis(),
            player.toString(), q.itemId(), q.quantity(), q.unitPrice(), q.total(), data.shopWallet);
        data.buybackHistory.add(receipt);
        BuybackReceipt removed = data.buybackHistory.size() > 500 ? data.buybackHistory.remove(0) : null;
        try { write(); } catch (IOException | RuntimeException ex) {
            data.shopWallet = q.walletBalance();
            if (q.stockBefore() == 0) data.wholesaleStock.remove(q.itemId());
            else data.wholesaleStock.put(q.itemId(), q.stockBefore());
            data.buybackHistory.remove(receipt);
            if (removed != null) data.buybackHistory.add(0, removed);
            throw ex;
        }
        return receipt;
    }

    /** For retry/compensation after player-token write failed. Not an admin command. */
    public synchronized void rollbackBuyback(BuybackQuote q, String receiptId) throws IOException {
        if (data.buybackHistory.isEmpty()
                || !data.buybackHistory.get(data.buybackHistory.size() - 1).id().equals(receiptId))
            throw new IllegalStateException("Ruecknahme ohne letzten Beleg verweigert");
        if (data.shopWallet != q.walletBalance() - q.total()
                || data.wholesaleStock.getOrDefault(q.itemId(), 0L) != q.stockBefore() + q.quantity())
            throw new IllegalStateException("Shopdaten inzwischen geaendert");
        data.shopWallet = q.walletBalance();
        if (q.stockBefore() == 0) data.wholesaleStock.remove(q.itemId());
        else data.wholesaleStock.put(q.itemId(), q.stockBefore());
        BuybackReceipt receipt = data.buybackHistory.remove(data.buybackHistory.size() - 1);
        try { write(); } catch (IOException | RuntimeException ex) {
            data.shopWallet -= q.total();
            data.wholesaleStock.put(q.itemId(), q.stockBefore() + q.quantity());
            data.buybackHistory.add(receipt);
            throw ex;
        }
    }
    public synchronized List<BuybackReceipt> buybackHistory() { return List.copyOf(data.buybackHistory); }

    public List<PurchaseReceipt> purchaseHistory() { return List.copyOf(data.purchaseHistory); }
    public String shopMode() { return data.shopMode; }
    public long shopWallet() { return data.shopWallet; }
    public Map<String, Long> wholesalePrices() { return Map.copyOf(data.wholesalePrices); }
    public Map<String, Long> retailPrices() { return Map.copyOf(data.retailPrices); }
    public Long retailUnitPrice(String itemId) { return data.retailPrices.get(itemId); }
    /** Configure a retail price. This never transfers tokens or items. */
    public synchronized void setRetailUnitPrice(String itemId, long price) throws IOException {
        if (!validItemId(itemId) || price < 1 || price > 1_000_000L)
            throw new IllegalArgumentException("Artikel-ID oder Preis ungueltig");
        if (!data.wholesalePrices.containsKey(itemId) && !data.wholesaleStock.containsKey(itemId))
            throw new IllegalArgumentException("Artikel ist weder im Grosshandel gelistet noch bereits im Lager");
        Long old = data.retailPrices.put(itemId, price);
        try { write(); } catch (IOException | RuntimeException e) {
            if (old == null) data.retailPrices.remove(itemId);
            else data.retailPrices.put(itemId, old);
            throw e;
        }
    }
    public synchronized void removeRetailUnitPrice(String itemId) throws IOException {
        Long old = data.retailPrices.remove(itemId);
        if (old != null) try { write(); } catch (IOException | RuntimeException e) {
            data.retailPrices.put(itemId, old);
            throw e;
        }
    }
    /** Read-only retail quote. Never debits players, mints items, or changes stock. */
    public record RetailQuote(String itemId, int quantity, long unitPrice, long totalPrice,
            long availableStock, boolean available) { }
    public synchronized RetailQuote retailQuote(String itemId, int amount) {
        if (!validItemId(itemId) || amount < 1 || amount > 2304)
            throw new IllegalArgumentException("Artikel oder Menge ungueltig (1 bis 2304)");
        Long unit = data.retailPrices.get(itemId);
        if (unit == null) throw new IllegalArgumentException("Kein Verkaufspreis festgelegt");
        long total = Math.multiplyExact(unit, (long) amount);
        long stock = data.wholesaleStock.getOrDefault(itemId, 0L);
        return new RetailQuote(itemId, amount, unit, total, stock,
            "grosshandel".equals(data.shopMode) && isOpen() && stock >= amount);
    }
    /** Durable shop-side half of a sale. Caller must coordinate player debit and physical delivery. */
    public synchronized RetailReceipt commitRetail(UUID player, RetailQuote quote, String transactionId) throws IOException {
        ShopBuybackReceiptLink.requireValidTransactionId(transactionId);
        requirePersistedState();
        if (player == null || quote == null || quote.quantity() > 64)
            throw new IllegalArgumentException("Verkaufsdaten ungueltig");
        if (data.retailHistory.stream().anyMatch(r -> transactionId.equals(r.id())))
            throw new IllegalStateException("Transaktions-ID bereits im Verkaufsarchiv vorhanden");
        RetailQuote current = retailQuote(quote.itemId(), quote.quantity());
        if (!current.equals(quote) || !current.available())
            throw new IllegalStateException("Angebot ist veraltet oder Lagerbestand reicht nicht");
        if (data.shopWallet > 1_000_000_000L - quote.totalPrice())
            throw new IllegalStateException("Shopkasse erreicht ihre Obergrenze");
        long walletBefore = data.shopWallet;
        long stockBefore = current.availableStock();
        data.shopWallet += quote.totalPrice();
        data.wholesaleStock.put(quote.itemId(), stockBefore - quote.quantity());
        RetailReceipt receipt = new RetailReceipt(transactionId, System.currentTimeMillis(),
            player.toString(), quote.itemId(), quote.quantity(), quote.unitPrice(),
            quote.totalPrice(), data.shopWallet, stockBefore - quote.quantity());
        data.retailHistory.add(receipt);
        RetailReceipt removed = data.retailHistory.size() > 500 ? data.retailHistory.remove(0) : null;
        try { write(); } catch (IOException | RuntimeException ex) {
            data.shopWallet = walletBefore;
            data.wholesaleStock.put(quote.itemId(), stockBefore);
            data.retailHistory.remove(receipt);
            if (removed != null) data.retailHistory.add(0, removed);
            throw ex;
        }
        return receipt;
    }
    public synchronized List<RetailReceipt> retailHistory() { return List.copyOf(data.retailHistory); }
    public Map<String, Long> wholesaleStock() { return Map.copyOf(data.wholesaleStock); }
    public long wholesaleStock(String itemId) { return data.wholesaleStock.getOrDefault(itemId, 0L); }

    /** Reserve funds and virtual warehouse stock in a SINGLE durable JSON write.
     *  No items are minted or delivered. This stock is not yet exposed to player sales. */
    public synchronized long buyWholesale(String itemId, int amount) throws IOException {
        if (!"grosshandel".equals(data.shopMode)) throw new IllegalStateException("Nur im Grosshandelsmodus");
        if (amount < 1 || amount > 2304) throw new IllegalArgumentException("Menge muss zwischen 1 und 2304 liegen");
        Long unitPrice = data.wholesalePrices.get(itemId);
        if (unitPrice == null) throw new IllegalArgumentException("Kein Grosshandelspreis fuer diesen Artikel festgelegt");
        long total = Math.multiplyExact(unitPrice, (long) amount);
        if (data.shopWallet < total) throw new IllegalStateException("Shopkasse reicht nicht aus (benoetigt: " + total + ")");
        long previous = data.wholesaleStock.getOrDefault(itemId, 0L);
        long updated = Math.addExact(previous, amount);
        if (updated > 1_000_000L) throw new IllegalStateException("Lagergrenze erreicht");
        long previousWallet = data.shopWallet;
        data.shopWallet -= total;
        data.wholesaleStock.put(itemId, updated);
        PurchaseReceipt receipt = new PurchaseReceipt(UUID.randomUUID().toString(), System.currentTimeMillis(),
            itemId, amount, unitPrice, total, data.shopWallet);
        data.purchaseHistory.add(receipt);
        PurchaseReceipt removedOldest = null;
        if (data.buybackPrices.size() > 4096 || data.buybackProposals.size() > 4096) throw new IOException("Zu viele Ankaufseintraege");
        for (var entry : data.buybackPrices.entrySet()) {
            if (!validItemId(entry.getKey()) || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 1_000_000L)
                throw new IOException("Ungueltiger Ankaufspreis: " + entry.getKey());
        }
        for (var entry : data.buybackProposals.entrySet()) {
            BuybackProposal p = entry.getValue();
            if (p == null || !validItemId(entry.getKey()) || !entry.getKey().equals(p.itemId())
                || p.suggestedUnitPrice() < 1 || p.suggestedUnitPrice() > 1_000_000L
                || p.rationale() == null || p.rationale().length() > 300 || p.createdMillis() < 0)
                throw new IOException("Ungueltiger Ankaufs-Vorschlag: " + entry.getKey());
        }
        if (data.purchaseHistory.size() > 500) removedOldest = data.purchaseHistory.remove(0);
        try { write(); } catch (IOException | RuntimeException ex) {
            data.purchaseHistory.remove(receipt);
            if (removedOldest != null) data.purchaseHistory.add(0, removedOldest);
            data.shopWallet = previousWallet;
            if (previous == 0) data.wholesaleStock.remove(itemId);
            else data.wholesaleStock.put(itemId, previous);
            throw ex;
        }
        return total;
    }
    public Long wholesaleUnitPrice(String itemId) { return data.wholesalePrices.get(itemId); }
    private static boolean validItemId(String value) {
        return value != null && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
    /** Admin-only price configuration. Does not create stock or debit the shop wallet. */
    public void setWholesaleUnitPrice(String itemId, long price) throws IOException {
        if (!validItemId(itemId) || price < 1 || price > 1_000_000L)
            throw new IllegalArgumentException("Artikel-ID oder Preis ungueltig (1 bis 1000000 Token).");
        Long previous = data.wholesalePrices.put(itemId, price);
        try { write(); } catch (IOException ex) {
            if (previous == null) data.wholesalePrices.remove(itemId);
            else data.wholesalePrices.put(itemId, previous);
            throw ex;
        }
    }
    public void removeWholesaleUnitPrice(String itemId) throws IOException {
        Long previous = data.wholesalePrices.remove(itemId);
        if (previous != null) try { write(); } catch (IOException ex) {
            data.wholesalePrices.put(itemId, previous);
            throw ex;
        }
    }
    /** Only the finance engine may call this after checking physical stock & transaction invariants.
     *  Not exposed as a console command to avoid unaudited balance changes. */
    void setWalletFromVerifiedTransaction(long newBalance) throws IOException {
        if (newBalance < 0 || newBalance > 1_000_000_000L)
            throw new IllegalArgumentException("Shopkasse ausserhalb der Grenzen");
        long previous = data.shopWallet;
        data.shopWallet = newBalance;
        try { write(); } catch (IOException ex) { data.shopWallet = previous; throw ex; }
    }
    /** Safe policy change; operational commerce and dual inventory must be implemented separately. */
    public void setShopMode(String mode) throws IOException {
        if (!"grosshandel".equals(mode) && !"kreativ".equals(mode))
            throw new IllegalArgumentException("Shopmodus unbekannt");
        if (isOpen()) throw new IllegalStateException("Shop vor Moduswechsel schliessen");
        if (!data.temporaryCreative.isEmpty()) throw new IllegalStateException("Aktive Kreativ-Sitzungen beenden");
        String previous = data.shopMode;
        data.shopMode = mode;
        try { write(); } catch (IOException ex) { data.shopMode = previous; throw ex; }
    }
    public List<CatalogueItem> catalogue() { return List.copyOf(data.creativeCatalogue); }
    public void catalogueAdd(String itemId, int amount) throws IOException {
        if (data.creativeCatalogue.size() >= 54) throw new IllegalArgumentException("Maximal 54 Katalogartikel.");
        for (CatalogueItem item : data.creativeCatalogue) {
            if (item.itemId().equals(itemId)) throw new IllegalArgumentException("Gegenstand ist bereits vorhanden. Erst entfernen.");
        }
        data.creativeCatalogue.add(new CatalogueItem(itemId, amount));
        try { write(); } catch (IOException ex) {
            data.creativeCatalogue.remove(data.creativeCatalogue.size() - 1);
            throw ex;
        }
    }
    public boolean catalogueRemove(String itemId) throws IOException {
        int index = -1;
        for (int i = 0; i < data.creativeCatalogue.size(); i++) {
            if (data.creativeCatalogue.get(i).itemId().equals(itemId)) { index = i; break; }
        }
        if (index < 0) return false;
        CatalogueItem removed = data.creativeCatalogue.remove(index);
        try { write(); } catch (IOException ex) {
            data.creativeCatalogue.add(index, removed);
            throw ex;
        }
        return true;
    }
    public void catalogueClear() throws IOException {
        List<CatalogueItem> old = new ArrayList<>(data.creativeCatalogue);
        data.creativeCatalogue.clear();
        try { write(); } catch (IOException ex) {
            data.creativeCatalogue.addAll(old);
            throw ex;
        }
    }

    public boolean protectedRegion() { return data.protectedRegion && isComplete(); }
    public void setProtectedRegion(boolean value) throws IOException {
        if (value && (!isComplete() || data.owner == null)) throw new IllegalArgumentException("Shopregion und Besitzer fehlen.");
        boolean old = data.protectedRegion;
        data.protectedRegion = value;
        try { write(); } catch (IOException e) { data.protectedRegion = old; throw e; }
    }
    public boolean isHelper(ServerPlayer player) { return data.helpers.contains(player.getUUID().toString()); }
    public boolean canBuild(ServerPlayer player) { return isOwner(player) || isHelper(player); }
    public Set<String> helpers() { return Set.copyOf(data.helpers); }
    public void addHelper(UUID id) throws IOException {
        if (id.toString().equals(data.owner)) throw new IllegalArgumentException("Besitzer kann nicht Helfer sein.");
        if (!data.helpers.contains(id.toString()) && data.helpers.size() >= 3) throw new IllegalArgumentException("Maximal drei Bauhelfer erlaubt.");
        if (data.helpers.add(id.toString())) try { write(); } catch (IOException ex) { data.helpers.remove(id.toString()); throw ex; }
    }
    public void removeHelper(UUID id) throws IOException {
        if (data.helpers.remove(id.toString())) try { write(); } catch (IOException ex) { data.helpers.add(id.toString()); throw ex; }
    }
    private String chestKey(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    public boolean isWarehouse(BlockPos pos) { return data.warehouse.contains(chestKey(pos)); }
    public Set<String> warehouse() { return Set.copyOf(data.warehouse); }
    public void setWarehouse(BlockPos pos, boolean enabled) throws IOException {
        if (enabled && !within(pos)) throw new IllegalArgumentException("Position ausserhalb des Shops.");
        String key = chestKey(pos);
        boolean old = data.warehouse.contains(key);
        if (enabled) data.warehouse.add(key); else data.warehouse.remove(key);
        try { write(); } catch (IOException ex) {
            if (old) data.warehouse.add(key); else data.warehouse.remove(key);
            throw ex;
        }
    }
    public boolean within(BlockPos pos) {
        return isComplete() && between(pos.getX(), data.first[0], data.second[0])
            && between(pos.getY(), data.first[1], data.second[1])
            && between(pos.getZ(), data.first[2], data.second[2]);
    }
    public boolean isOwner(ServerPlayer player) {
        return data.owner != null && data.owner.equals(player.getUUID().toString());
    }
    public String owner() { return data.owner; }
    /** Status fuer HUD und Commands; unvollstaendig eingerichtete Shops sind nicht geoeffnet. */
    public String hudStatus() {
        if (data == null || data.open == null) return "§7Status unbekannt";
        if (!isComplete() || data.owner == null) return "§eWird gebaut. Bitte Geduld";
        return data.open ? "§aOffen" : "§cZu";
    }
    public boolean isOpen() {
        return data != null && isComplete() && data.owner != null && Boolean.TRUE.equals(data.open);
    }
    public void setOpen(boolean open) throws IOException {
        Boolean previous = data.open;
        data.open = open;
        try { write(); } catch (IOException ex) { data.open = previous; throw ex; }
    }
    public String dimension() { return data.dimension; }
    public int[] first() { return data.first == null ? null : data.first.clone(); }
    public int[] second() { return data.second == null ? null : data.second.clone(); }
    public boolean isComplete() { return data.first != null && data.second != null && data.dimension != null; }

    public boolean contains(ServerPlayer player) {
        return contains(player, player.blockPosition());
    }
    public boolean contains(ServerPlayer player, BlockPos pos) {
        if (!isComplete() || !player.level().dimension().identifier().toString().equals(data.dimension)) return false;
        return between(pos.getX(), data.first[0], data.second[0])
            && between(pos.getY(), data.first[1], data.second[1])
            && between(pos.getZ(), data.first[2], data.second[2]);
    }
    private static boolean between(int number, int a, int b) {
        return number >= Math.min(a, b) && number <= Math.max(a, b);
    }
    /** Ein einziger rechteckiger 3D-Shop, festgelegt per Serverkonsole. */
    public void setRegion(String dimension, int[] first, int[] second) throws IOException {
        if (dimension == null || first == null || first.length != 3 || second == null || second.length != 3)
            throw new IllegalArgumentException("Ungueltiger Shopbereich.");
        String oldDimension = data.dimension;
        int[] oldFirst = data.first, oldSecond = data.second;
        data.dimension = dimension;
        data.first = first.clone();
        data.second = second.clone();
        try { write(); } catch (IOException ex) {
            data.dimension = oldDimension; data.first = oldFirst; data.second = oldSecond;
            throw ex;
        }
    }

    public void setOwner(UUID id) throws IOException {
        String previous = data.owner;
        data.owner = id == null ? null : id.toString();
        Set<String> previousHelpers = new HashSet<>(data.helpers);
        data.helpers.clear(); // Bei Besitzerwechsel alle bisherigen Bauhelfer entziehen.
        try { write(); } catch (IOException ex) {
            data.owner = previous;
            data.helpers.addAll(previousHelpers);
            throw ex;
        }
    }
    public boolean isTemporaryCreative(UUID id) { return data.temporaryCreative.contains(id.toString()); }
    public Set<String> sessions() { return Set.copyOf(data.temporaryCreative); }
    public void setTemporaryCreative(UUID id, boolean value) throws IOException {
        boolean before = isTemporaryCreative(id);
        if (value) data.temporaryCreative.add(id.toString());
        else data.temporaryCreative.remove(id.toString());
        try { write(); } catch (IOException ex) {
            if (before) data.temporaryCreative.add(id.toString());
            else data.temporaryCreative.remove(id.toString());
            throw ex;
        }
    }

    private void write() throws IOException {
        if (storageFailure != null) throw new IOException("Shopspeicher nach Schreibfehler gesperrt", storageFailure);
        try { DurableFiles.replace(file, GSON.toJson(data).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException | RuntimeException failure) {
            storageFailure = new IOException("Shopspeicher nach Schreibfehler gesperrt; Dateien vor Neustart/Abgleich sichern", failure);
            throw storageFailure;
        }
    }
}
