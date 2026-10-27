import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bound runtime store, real journal files and unchanged bytes after denied mutations. */
public class TokenStorePendingJournalGuardTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError("credit must fail"); } catch (IOException expected) { checks++; }
    }
    static void frozen(TokenStore store, Path file, UUID owner, UUID other) throws Exception {
        byte[] before = Files.readAllBytes(file);
        TokenConfig config = new TokenConfig();
        config.activityEconomy.activities.put("fishing", new TokenConfig.ActivityRule(1, 2));
        check(!store.add(owner, 1)); check(!store.take(owner, 1)); check(!store.set(owner, 1));
        check(!store.transfer(owner, other, 1)); check(!store.transfer(other, owner, 1));
        check(!store.canRewardMining(owner, 1, config.activityEconomy));
        check(store.rewardMining(owner, 1, config.activityEconomy) == -1);
        check(!store.countMinedOre(owner, "diamond", new TokenConfig.RewardRange(1, 1, 1, 1)));
        check(store.recordActivity(owner, "fishing", config.activityEconomy) == 0);
        check(store.rewardDailyLogin(owner, 1, config.activityEconomy) == 0);
        store.flush();
        check(Arrays.equals(before, Files.readAllBytes(file)));
        store.requirePersistedState(); checks++; // No hidden reward/progress/period changes in RAM.
    }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("token-pending-guard-");
        try {
            ShopTransactionJournal.initialize(world);
            Path file = world.resolve("data/erzmarken.json"); UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            TokenStore store = TokenStore.forWorld(world);
            check(store.add(owner, 100)); check(store.add(other, 100));
            var prepared = ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 14);
            Path pending = world.resolve("data/erzmarken-shop-journal/" + prepared.id() + ".pending.json");
            byte[] journalBytes = Files.readAllBytes(pending);
            frozen(store, file, owner, other);
            check(store.add(other, 1)); // Valid unrelated account remains usable.
            check(Arrays.equals(journalBytes, Files.readAllBytes(pending)));
            denied(() -> store.creditShopPurchaseChecked(owner, 14, prepared.id())); // No shop debit yet.
            ShopTransactionJournal.cancel(prepared); // PREPARED has no side effects in this fixture.
            check(store.add(owner, 1)); // A reviewed safe cancellation releases ordinary admission.

            var second = ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 14);
            var debited = ShopTransactionJournal.advance(second, "SHOP_DEBITED");
            frozen(store, file, owner, other);
            byte[] beforeWrongCredit = Files.readAllBytes(file);
            denied(() -> store.creditShopPurchaseChecked(other, 14, second.id()));
            denied(() -> store.creditShopPurchaseChecked(owner, 15, second.id()));
            denied(() -> store.creditShopPurchaseChecked(owner, 14, UUID.randomUUID().toString()));
            check(Arrays.equals(beforeWrongCredit, Files.readAllBytes(file)));
            store.creditShopPurchaseChecked(owner, 14, second.id());
            check(store.balance(owner) == 115);
            TokenStore restarted = TokenStore.forWorld(world);
            frozen(restarted, file, owner, other);
            byte[] credited = Files.readAllBytes(file);
            restarted.creditShopPurchaseChecked(owner, 14, second.id());
            check(Arrays.equals(credited, Files.readAllBytes(file))); // Exact retry is read-only.
            Path secondFile = pending.resolveSibling(second.id() + ".pending.json");
            byte[] originalJournal = Files.readAllBytes(secondFile);
            Files.writeString(secondFile, "CORRUPT");
            check(!restarted.add(other, 1)); check(!restarted.take(other, 1));
            check(Arrays.equals(credited, Files.readAllBytes(file)));
            check(Files.readString(secondFile).equals("CORRUPT"));
            Files.write(secondFile, originalJournal);
            check(restarted.add(other, 1)); // Admission denial did not set the separate write-error fence.
            check(ShopTransactionJournal.readPendingForReview(world, second.id()).equals(debited));
            System.out.println("Runtime pending-journal account guard: " + checks + " checks passed; no replay/server run");
        } finally {
            try (var files = Files.walk(world)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
