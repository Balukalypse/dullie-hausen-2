import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public class TokenStorePersistenceTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("token-persistence");
        try {
            Path file = root.resolve("tokens.json"); UUID player = UUID.randomUUID();
            String id = UUID.randomUUID().toString();
            TokenStore store = new TokenStore(file);
            // Looking up a new UUID must not add a phantom account in memory.
            UUID unknown = UUID.randomUUID();
            check(store.balance(unknown) == 0);
            check(store.balance(null) == 0);
            check(!Files.exists(file));

            store.creditShopPurchaseChecked(player, 25, id);
            store.creditShopPurchaseChecked(player, 25, id);
            check(store.balance(player) == 25);
            store = new TokenStore(file);
            byte[] persistedBeforeRead = Files.readAllBytes(file);
            check(store.balance(unknown) == 0);
            store.requirePersistedState();
            store.flush();
            check(Arrays.equals(persistedBeforeRead, Files.readAllBytes(file)));

            store.creditShopPurchaseChecked(player, 25, id);
            check(store.balance(player) == 25);
            try { store.creditShopPurchaseChecked(player, 26, id); throw new AssertionError(); }
            catch (IOException expected) { }
            try { store.creditShopPurchaseChecked(UUID.randomUUID(), 25, id); throw new AssertionError(); }
            catch (IOException expected) { }
            check(new TokenStore(file).balance(player) == 25);
            // Destination turned into a directory: refuse and roll back in-memory receipt.
            Path blocked = root.resolve("blocked.json"); TokenStore failing = new TokenStore(blocked);
            Files.createDirectory(blocked);
            String retry = UUID.randomUUID().toString();
            try { failing.creditShopPurchaseChecked(player, 7, retry); throw new AssertionError(); }
            catch (IOException expected) { }
            check(failing.balance(player) == 0);
            Files.delete(blocked);
            try { failing.creditShopPurchaseChecked(player, 7, retry); throw new AssertionError("failed store must remain fenced"); }
            catch (IOException expected) { }
            check(!failing.add(player, 1)); failing.flush(); check(!Files.exists(blocked));
            new TokenStore(blocked).creditShopPurchaseChecked(player, 7, retry);
            check(new TokenStore(blocked).balance(player) == 7);
            Path regularFailure = root.resolve("regular-failure.json");
            TokenStore regular = new TokenStore(regularFailure); Files.createDirectory(regularFailure);
            check(!regular.add(player, 10)); check(!regular.take(player, 1));
            Files.delete(regularFailure); regular.flush(); check(!Files.exists(regularFailure));
            try { regular.requirePersistedState(); throw new AssertionError("ambiguous ordinary write must fence the store"); }
            catch (IOException expected) { }
            // A refused debit/transfer must never create phantom accounts.
            TokenStore empty = new TokenStore(root.resolve("empty.json"));
            UUID absent = UUID.randomUUID();
            check(!empty.take(absent, 1));
            check(!empty.transfer(absent, player, 1));
            check(!Files.exists(root.resolve("empty.json")));
            check(empty.balance(absent) == 0);

            // Force ordinary payment disk failures and verify rollback of live balances.
            Path ordinaryPath = root.resolve("ordinary-balance.json");
            TokenStore ordinary = new TokenStore(ordinaryPath);
            UUID receiver = UUID.randomUUID();
            check(ordinary.add(player, 50));
            check(ordinary.add(receiver, 8));
            long originalBytes = Files.size(ordinaryPath);
            Path preserved = root.resolve("ordinary-balance-preserved.json");
            Files.move(ordinaryPath, preserved);
            Files.createDirectory(ordinaryPath);
            check(!ordinary.take(player, 10));
            check(ordinary.balance(player) == 50);
            check(ordinary.balance(receiver) == 8);
            check(!ordinary.transfer(player, receiver, 5));
            Files.delete(ordinaryPath);
            Files.move(preserved, ordinaryPath);
            check(Files.size(ordinaryPath) == originalBytes);
            check(new TokenStore(ordinaryPath).balance(player) == 50);
            check(new TokenStore(ordinaryPath).balance(receiver) == 8);

            // Independent stores for each first failed operation (they are fenced thereafter).
            for (int op = 0; op < 3; op++) {
                Path path = root.resolve("ordinary-fail-" + op + ".json");
                TokenStore caseStore = new TokenStore(path);
                check(caseStore.add(player, 50));
                byte[] diskBefore = Files.readAllBytes(path);
                Path old = root.resolve("ordinary-fail-backup-" + op + ".json");
                Files.move(path, old); Files.createDirectory(path);
                boolean result = switch (op) {
                    case 0 -> caseStore.add(player, 7);
                    case 1 -> caseStore.set(player, 25);
                    default -> caseStore.transfer(player, receiver, 5);
                };
                check(!result);
                check(caseStore.balance(player) == 50);
                check(caseStore.balance(receiver) == 0);
                Files.delete(path); Files.move(old, path);
                check(Arrays.equals(Files.readAllBytes(path), diskBefore));
            }
            // Existing legacy JSON without receipts remains loadable.
            Path legacy = root.resolve("legacy.json");
            Files.writeString(legacy, "{\"players\":{},\"placedOres\":[]}");
            new TokenStore(legacy).creditShopPurchaseChecked(player, 3, UUID.randomUUID().toString());
            check(new TokenStore(legacy).balance(player) == 3);
            Path invalid = root.resolve("invalid.json");
            for (String broken : new String[]{
                    "{\"players\":{},\"placedOres\":[],\"shopCredits\":null}",
                    "{\"players\":{},\"placedOres\":[],\"shopCredits\":{},\"shopCredits\":{}}",
                    "{\"players\":{\"" + player + "\":{\"balance\":1.5}},\"placedOres\":[]}",
                    "{\"players\":{},\"placedOres\":[],\"shopCredits\":{\"" + id
                        + "\":{\"playerId\":\"" + player + "\",\"amount\":1.5}}}"}) {
                Files.writeString(invalid, broken);
                try { new TokenStore(invalid); throw new AssertionError("ambiguous token file must fail startup"); }
                catch (IOException expected) { }
                check(Files.readString(invalid).equals(broken));
            }
            try (var files = Files.list(root)) {
                check(files.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
            System.out.println("Token persistence: restart, exact retry, conflicts, write failure/retry, legacy migration passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
