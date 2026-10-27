package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

/** All state is changed on the server thread; write via atomic replacement. */
public final class TokenStore {
    public static final long MAX_TOKENS = 1_000_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Path shopWorld;
    private State state = new State();
    private boolean dirty;
    private IOException storageFailure;

    private static final class State {
        Map<String, Account> players = new HashMap<>();
        Map<String, ShopCredit> shopCredits = new HashMap<>();
        Map<String, ShopDebit> shopDebits = new HashMap<>();
        Set<String> placedOres = new HashSet<>();
        Set<String> placedLogs = new HashSet<>();
        Map<String, Long> activityPositionTimes = new HashMap<>();
    }
    private record ShopCredit(String playerId, long amount) { }
    private record ShopDebit(String playerId, long amount) { }
    private static final class Account {
        long balance;
        Map<String, Long> minedOreProgress = new HashMap<>();
        Map<String, Integer> nextOreTarget = new HashMap<>();
        Map<String, Integer> activityProgress = new HashMap<>();
        String dayKey, weekKey, monthKey;
        String lastLoginRewardDate;
        long earnedToday, earnedWeek, earnedMonth;
        Long earnedFromMining; // null: alter Spielstand; beim ersten Erzfund initialisieren
    }

    public TokenStore(Path file) throws IOException {
        this(file, null);
    }

    /** Runtime store: pending shop journals guard ordinary account mutations. */
    public static TokenStore forWorld(Path world) throws IOException {
        Path root = world.toAbsolutePath().normalize();
        return new TokenStore(root.resolve("data/erzmarken.json"), root);
    }

    private TokenStore(Path file, Path shopWorld) throws IOException {
        this.file = file;
        this.shopWorld = shopWorld;
        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                com.google.gson.JsonObject raw = StrictJsonFiles.readObject(file, 16 * 1024 * 1024);
                if (raw.has("players") && raw.get("players").isJsonObject())
                    for (var account : raw.getAsJsonObject("players").entrySet()) {
                        var value = account.getValue().getAsJsonObject().get("balance");
                        if (value != null) value.getAsBigDecimal().longValueExact();
                    }
                if (raw.has("shopCredits") && raw.get("shopCredits").isJsonNull())
                    throw new IOException("Shop-Kontobelege duerfen nicht null sein");
                if (raw.has("shopCredits"))
                    for (var credit : raw.getAsJsonObject("shopCredits").entrySet())
                        credit.getValue().getAsJsonObject().get("amount").getAsBigDecimal().longValueExact();
                if (raw.has("shopDebits") && !raw.get("shopDebits").isJsonObject())
                    throw new IOException("Shop-Verkaufsbelege sind ungueltig");
                if (raw.has("shopDebits"))
                    for (var debit : raw.getAsJsonObject("shopDebits").entrySet())
                        debit.getValue().getAsJsonObject().get("amount").getAsBigDecimal().longValueExact();
                State loaded = GSON.fromJson(raw, State.class);
                if (loaded == null || loaded.players == null || loaded.placedOres == null) {
                    throw new IOException("Spielerdaten unvollstaendig");
                }
                for (Account entry : loaded.players.values()) {
                    if (entry == null || entry.balance < 0 || entry.balance > MAX_TOKENS)
                        throw new IOException("Ungueltiger Kontostand in Spielerdaten");
                }
                for (Account entry : loaded.players.values()) {
                    if (entry.activityProgress == null) entry.activityProgress = new HashMap<>();
                    if (entry.nextOreTarget == null) entry.nextOreTarget = new HashMap<>();
                    if (entry.minedOreProgress == null) entry.minedOreProgress = new HashMap<>();
                    for (Long count : entry.minedOreProgress.values()) {
                        if (count == null || count < 0 || count >= Integer.MAX_VALUE)
                            throw new IOException("Ungueltiger Erzfortschritt in Spielerdaten");
                    }
                }
                if (loaded.placedLogs == null) loaded.placedLogs = new HashSet<>();
                if (loaded.shopCredits == null) loaded.shopCredits = new HashMap<>();
                for (var credit : loaded.shopCredits.entrySet()) {
                    try { ShopBuybackReceiptLink.requireValidTransactionId(credit.getKey());
                        ShopBuybackReceiptLink.requireValidTransactionId(credit.getValue().playerId()); }
                    catch (RuntimeException ex) { throw new IOException("Ungueltiger Shop-Kontobeleg", ex); }
                    if (credit.getValue().amount() <= 0 || credit.getValue().amount() > MAX_TOKENS)
                        throw new IOException("Ungueltiger Shop-Kontobetrag");
                }
                if (loaded.shopDebits == null) loaded.shopDebits = new HashMap<>();
                for (var debit : loaded.shopDebits.entrySet()) {
                    try { ShopBuybackReceiptLink.requireValidTransactionId(debit.getKey());
                        ShopBuybackReceiptLink.requireValidTransactionId(debit.getValue().playerId()); }
                    catch (RuntimeException ex) { throw new IOException("Ungueltiger Shop-Verkaufsbeleg", ex); }
                    if (debit.getValue().amount() <= 0 || debit.getValue().amount() > MAX_TOKENS)
                        throw new IOException("Ungueltiger Shop-Verkaufsbetrag");
                }
                if (loaded.activityPositionTimes == null) loaded.activityPositionTimes = new HashMap<>();
                state = loaded;
            } catch (RuntimeException ex) {
                throw new IOException("Kaputte Spielerdaten - Start aus Sicherheitsgruenden verweigert", ex);
            }
        }
    }

    private Account account(UUID uuid) {
        return state.players.computeIfAbsent(uuid.toString(), key -> new Account());
    }
    /** Read-only balance lookup: querying an unknown player must not create a
     * phantom account and invalidate persisted-state recovery comparisons. */
    public long balance(UUID uuid) {
        if (uuid == null) return 0L;
        Account existing = state.players.get(uuid.toString());
        return existing == null ? 0L : existing.balance;
    }
    /** Failed or unreadable admission is a transient refusal, not a disk-write fence. */
    private boolean ordinaryMutationAllowed(UUID uuid) {
        if (storageFailure != null || uuid == null) return false;
        if (shopWorld == null) return true; // Standalone library/test store, not server runtime wiring.
        try { return ShopPendingTradeGate.mayJoin(shopWorld, uuid); }
        catch (IOException | RuntimeException failure) { return false; }
    }
    public void requireStorageAvailable() throws IOException { requireWritable(); }
    /** Recovery must never finalize over a live state that could overwrite different disk data. */
    public void requirePersistedState() throws IOException {
        requireWritable();
        if (!GSON.toJsonTree(state).equals(StrictJsonFiles.readObject(file, 16 * 1024 * 1024)))
            throw new IOException("Kontodaten im Arbeitsspeicher weichen von der Datei ab; keine Recovery-Freigabe");
    }
    /** Gemeinsame Token-Obergrenzen fuer natuerliche Erze und Aktivitaeten. */
    private void rollPeriods(Account a) {
        LocalDate now = LocalDate.now(ZoneId.of("Europe/Berlin"));
        String day = now.toString();
        String week = now.get(WeekFields.ISO.weekBasedYear()) + "-W" + now.get(WeekFields.ISO.weekOfWeekBasedYear());
        String month = now.getYear() + "-" + now.getMonthValue();
        if (!day.equals(a.dayKey)) { a.dayKey = day; a.earnedToday = 0; dirty = true; }
        if (!week.equals(a.weekKey)) { a.weekKey = week; a.earnedWeek = 0; dirty = true; }
        if (!month.equals(a.monthKey)) { a.monthKey = month; a.earnedMonth = 0; dirty = true; }
    }
    private boolean canEarn(Account a, long amount, TokenConfig.ActivityEconomy ec) {
        if (storageFailure != null) return false;
        rollPeriods(a);
        return amount > 0 && a.balance <= MAX_TOKENS - amount
            && (!ec.enabled || (a.earnedToday <= ec.dailyEarnCap - amount
                && a.earnedWeek <= ec.weeklyEarnCap - amount
                && a.earnedMonth <= ec.monthlyEarnCap - amount));
    }
    private boolean creditEarned(Account a, long amount) {
        a.balance += amount;
        a.earnedToday += amount;
        a.earnedWeek += amount;
        a.earnedMonth += amount;
        dirty = true;
        flush();
        return storageFailure == null;
    }
    private record AccountCheckpoint(String key, Account previous) { }
    /** Retain the whole account, including period keys, claim dates and progress.
     * This is only an in-memory checkpoint; disk outcomes remain fenced/ambiguous.
     */
    private AccountCheckpoint checkpoint(UUID uuid) {
        String key = uuid.toString();
        Account existing = state.players.get(key);
        return new AccountCheckpoint(key, existing == null ? null
            : GSON.fromJson(GSON.toJsonTree(existing), Account.class));
    }
    private void restoreAccount(AccountCheckpoint checkpoint) {
        if (checkpoint.previous() == null) state.players.remove(checkpoint.key());
        else state.players.put(checkpoint.key(), checkpoint.previous());
        // Keep dirty and storageFailure: never retry an uncertain disk write.
    }
    public boolean canRewardMining(UUID uuid, long maxReward, TokenConfig.ActivityEconomy economy) {
        if (!ordinaryMutationAllowed(uuid)) return false;
        return canEarn(account(uuid), maxReward, economy);
    }
    /** -1 = Limits erreicht; >=0 = verbucht, ggf. Meilenstein. */
    public long rewardMining(UUID uuid, long amount, TokenConfig.ActivityEconomy economy) {
        if (!ordinaryMutationAllowed(uuid)) return -1;
        AccountCheckpoint beforeAccount = checkpoint(uuid);
        Account a = account(uuid);
        if (!canEarn(a, amount, economy)) return -1;
        if (a.earnedFromMining == null) a.earnedFromMining = a.balance;
        long before = a.earnedFromMining;
        a.earnedFromMining += amount;
        if (!creditEarned(a, amount)) { restoreAccount(beforeAccount); return -1; }
        long milestone = nextMilestone(before);
        return a.earnedFromMining >= milestone ? milestone : 0;
    }
    private static long nextMilestone(long previous) {
        if (previous < 500) return (previous / 50 + 1) * 50;
        return (previous / 100 + 1) * 100;
    }
    /** Increments ore progress, draws a new target only after a successful reward. */
    public boolean countMinedOre(UUID uuid, String group, TokenConfig.RewardRange range) {
        if (!ordinaryMutationAllowed(uuid)) return false;
        Account a = account(uuid);
        int target = a.nextOreTarget.computeIfAbsent(group, key ->
            java.util.concurrent.ThreadLocalRandom.current().nextInt(range.minBlocks, range.maxBlocks+1));
        long count = a.minedOreProgress.getOrDefault(group,0L)+1;
        if (count >= target) {
            a.minedOreProgress.put(group,0L);
            a.nextOreTarget.put(group,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(range.minBlocks,range.maxBlocks+1));
            dirty=true;
            return true;
        }
        a.minedOreProgress.put(group,count);
        dirty=true;
        return false;
    }
    /**
     * Counts one VALIDATED activity event (caller must check natural fishing,
     * non-player-generated crops, meaningful building, etc.).
     * Returns granted tokens, or zero when below target / capped.
     * Day/week/month are evaluated in Europe/Berlin, independent of host timezone.
     */
    public long recordActivity(UUID uuid, String activity, TokenConfig.ActivityEconomy economy) {
        if (!ordinaryMutationAllowed(uuid)) return 0;
        if (economy == null || !economy.enabled) return 0;
        TokenConfig.ActivityRule rule = economy.activities.get(activity);
        if (rule == null || !rule.enabled) return 0;
        AccountCheckpoint beforeAccount = checkpoint(uuid);
        Account a = account(uuid);
        rollPeriods(a);
        int count = Math.min(rule.target, a.activityProgress.getOrDefault(activity, 0) + 1);
        if (count < rule.target) {
            a.activityProgress.put(activity, count);
            dirty = true;
            return 0;
        }
        if (!canEarn(a, rule.reward, economy)) {
            a.activityProgress.put(activity, rule.target);
            dirty = true;
            return 0;
        }
        a.activityProgress.put(activity, 0);
        if (!creditEarned(a, rule.reward)) { restoreAccount(beforeAccount); return 0; }
        return rule.reward;
    }

    /** Login reward: exactly once per Europe/Berlin calendar day, persisted atomically.
     *  Failure from caps does not mark the day as claimed, allowing later retries.
     *  Returns credited tokens, zero if already received/disabled/capped.
     */
    public long rewardDailyLogin(UUID uuid, int reward, TokenConfig.ActivityEconomy economy) {
        if (!ordinaryMutationAllowed(uuid)) return 0;
        if (reward <= 0 || economy == null) return 0;
        AccountCheckpoint beforeAccount = checkpoint(uuid);
        Account a = account(uuid);
        String today = LocalDate.now(ZoneId.of("Europe/Berlin")).toString();
        if (today.equals(a.lastLoginRewardDate) || !canEarn(a, reward, economy)) return 0;
        a.lastLoginRewardDate = today;
        if (!creditEarned(a, reward)) { restoreAccount(beforeAccount); return 0; }
        return reward;
    }

    /** Save-checked credit for shop purchases: never pretend an unsaved credit succeeded. */
    public void creditShopPurchaseChecked(UUID uuid, long amount) throws IOException {
        creditShopPurchaseChecked(uuid, amount, UUID.randomUUID().toString());
    }

    /** Balance and immutable transaction receipt share one atomic file replacement.
     * An exact retry is a no-op, including after restarting the server.
     */
    public void creditShopPurchaseChecked(UUID uuid, long amount, String transactionId) throws IOException {
        requireWritable();
        ShopBuybackReceiptLink.requireValidTransactionId(transactionId);
        if (amount > MAX_TOKENS) throw new IllegalArgumentException("Ungueltiger Betrag");
        if (amount <= 0) throw new IllegalArgumentException("Ungueltiger Betrag");
        ShopCredit credit = new ShopCredit(uuid.toString(), amount);
        ShopCredit existing = state.shopCredits.get(transactionId);
        if (existing != null) {
            if (!existing.equals(credit)) throw new IOException("Widerspruechlicher Shop-Kontobeleg");
            return;
        }
        if (shopWorld != null) ShopPendingTradeGate.requireCredit(shopWorld, uuid, transactionId, amount);
        Account a = account(uuid);
        if (a.balance > MAX_TOKENS - amount) throw new IllegalStateException("Spieler-Tokenlimit erreicht");
        a.balance += amount;
        state.shopCredits.put(transactionId, credit);
        dirty = true;
        try { flushChecked(); }
        catch (IOException | RuntimeException ex) {
            a.balance -= amount;
            state.shopCredits.remove(transactionId);
            dirty = true;
            throw ex;
        }
    }
    /** Durable, idempotent player debit for a future journalled retail sale.
     * This does not credit the shop or deliver items. Never expose it alone as a command. */
    public void debitShopSaleChecked(UUID uuid, long amount, String transactionId) throws IOException {
        requireWritable();
        if (uuid == null || amount <= 0 || amount > MAX_TOKENS)
            throw new IllegalArgumentException("Ungueltiger Verkaufsbetrag oder Spieler");
        ShopBuybackReceiptLink.requireValidTransactionId(transactionId);
        ShopDebit debit = new ShopDebit(uuid.toString(), amount);
        ShopDebit existing = state.shopDebits.get(transactionId);
        if (existing != null) {
            if (!existing.equals(debit)) throw new IOException("Widerspruechlicher Shop-Verkaufsbeleg");
            return;
        }
        if (shopWorld != null) {
            if (ShopJournalAudit.inspectForAdmission(shopWorld).blocksTrading())
                throw new IOException("Ankaufsjournal offen oder auffaellig");
            ShopRetailJournal.requireDebit(shopWorld, uuid, transactionId, amount);
        } else if (!ordinaryMutationAllowed(uuid))
            throw new IOException("Spielerkonto wegen offener Buchung gesperrt");
        Account a = state.players.get(uuid.toString());
        if (a == null || a.balance < amount) throw new IllegalStateException("Spieler hat nicht genug Token");
        a.balance -= amount;
        state.shopDebits.put(transactionId, debit);
        dirty = true;
        try { flushChecked(); }
        catch (IOException | RuntimeException ex) {
            a.balance += amount;
            state.shopDebits.remove(transactionId);
            dirty = true;
            throw ex;
        }
    }
    /** Fails closed on disk write errors; caller must handle compensation. */
    private void flushChecked() throws IOException {
        requireWritable();
        try {
            DurableFiles.replace(file, GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException | RuntimeException failure) {
            storageFailure = new IOException("Kontospeicher nach Schreibfehler gesperrt; Dateien vor Neustart/Abgleich sichern", failure);
            throw storageFailure;
        }
    }
    private void requireWritable() throws IOException {
        if (storageFailure != null) throw new IOException("Kontospeicher nach Schreibfehler gesperrt", storageFailure);
    }

    /** Ordinary payment failures must not leave an apparently credited/debited
     * in-memory balance. A write failure still permanently fences this store. */
    public boolean add(UUID uuid, long amount) {
        if (!ordinaryMutationAllowed(uuid) || amount <= 0 || amount > MAX_TOKENS) return false;
        String key = uuid.toString();
        Account a = state.players.get(key);
        long before = a == null ? 0L : a.balance;
        if (before > MAX_TOKENS - amount) return false;
        boolean created = a == null;
        if (created) a = account(uuid);
        a.balance = before + amount;
        dirty = true;
        flush();
        if (storageFailure == null) return true;
        if (created) state.players.remove(key); else a.balance = before;
        return false;
    }
    public boolean take(UUID uuid, long amount) {
        if (!ordinaryMutationAllowed(uuid) || amount <= 0 || amount > MAX_TOKENS) return false;
        Account a = state.players.get(uuid.toString());
        if (a == null || a.balance < amount) return false;
        long before = a.balance;
        a.balance -= amount;
        dirty = true;
        flush();
        if (storageFailure == null) return true;
        a.balance = before;
        return false;
    }
    public boolean set(UUID uuid, long amount) {
        if (!ordinaryMutationAllowed(uuid) || amount < 0 || amount > MAX_TOKENS) return false;
        String key = uuid.toString();
        Account a = state.players.get(key);
        boolean created = a == null;
        if (created) a = account(uuid);
        long before = a.balance;
        a.balance = amount;
        dirty = true;
        flush();
        if (storageFailure == null) return true;
        if (created) state.players.remove(key); else a.balance = before;
        return false;
    }
    public boolean transfer(UUID from, UUID to, long amount) {
        if (!ordinaryMutationAllowed(from) || !ordinaryMutationAllowed(to)) return false;
        if (from.equals(to) || amount <= 0 || amount > MAX_TOKENS) return false;
        Account sender = state.players.get(from.toString());
        if (sender == null || sender.balance < amount) return false;
        String recipientKey = to.toString();
        Account receiver = state.players.get(recipientKey);
        boolean created = receiver == null;
        long receiverBefore = created ? 0L : receiver.balance;
        if (receiverBefore > MAX_TOKENS - amount) return false;
        if (created) receiver = account(to);
        long senderBefore = sender.balance;
        sender.balance -= amount;
        receiver.balance = receiverBefore + amount;
        dirty = true;
        flush();
        if (storageFailure == null) return true;
        sender.balance = senderBefore;
        if (created) state.players.remove(recipientKey); else receiver.balance = receiverBefore;
        return false;
    }
    /** Persistierte Sperre gegen wiederholtes Bauen/Ernten an derselben Stelle.
     * Wird erst nach erfolgreicher Aktion aufgerufen, auf dem Serverthread.
     */
    public boolean recordPositionActivity(String type, String position, long cooldownMillis) {
        if (storageFailure != null) return false;
        String key = type + ":" + position;
        long now = System.currentTimeMillis();
        Long last = state.activityPositionTimes.get(key);
        if (last != null && now - last >= 0 && now - last < cooldownMillis) return false;
        state.activityPositionTimes.put(key, now);
        dirty = true;
        return true;
    }
    public void pruneActivityPositions(long cooldownMillis) {
        long now = System.currentTimeMillis();
        if (state.activityPositionTimes.entrySet().removeIf(e ->
                e.getValue() == null || now - e.getValue() > cooldownMillis)) dirty = true;
    }
    public void markPlacedLog(String position) {
        if (state.placedLogs.add(position)) dirty = true;
    }
    public boolean wasPlacedLog(String position) {
        if (state.placedLogs.remove(position)) { dirty = true; return true; }
        return false;
    }
    public void markPlaced(String position) {
        if (state.placedOres.add(position)) dirty = true;
    }
    public boolean wasPlaced(String position) {
        if (state.placedOres.remove(position)) {
            dirty = true;
            return true;
        }
        return false;
    }
    public void flush() {
        if (!dirty || storageFailure != null) return;
        try {
            flushChecked();
        } catch (IOException e) {
            ErzmarkenMod.LOGGER.error("Token-Daten konnten NICHT gespeichert werden: {}", file, e);
        }
    }
}
