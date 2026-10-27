import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Public outcomes + full serialized RAM checkpoint: catches hidden claim/progress changes. */
public class TokenRewardRollbackTest {
    static int checks;
    static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    static JsonElement memory(TokenStore store) throws Exception {
        var field = TokenStore.class.getDeclaredField("state"); field.setAccessible(true);
        return new Gson().toJsonTree(field.get(store));
    }
    static long reward(TokenStore store, UUID player, int kind, TokenConfig.ActivityEconomy economy) {
        return switch (kind) {
            case 0 -> store.rewardDailyLogin(player, 3, economy);
            case 1 -> store.recordActivity(player, "fishing", economy);
            default -> store.rewardMining(player, 3, economy);
        };
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("reward-rollback-");
        try {
            var economy = new TokenConfig.ActivityEconomy();
            economy.activities.put("fishing", new TokenConfig.ActivityRule(1, 3));
            for (int kind = 0; kind < 3; kind++) for (boolean existing : new boolean[]{false, true}) {
                Path file = root.resolve("case-" + kind + "-" + existing + ".json");
                Path backup = root.resolve(file.getFileName() + ".preserved");
                UUID player = UUID.randomUUID(); var store = new TokenStore(file);
                var caseEconomy = new TokenConfig.ActivityEconomy();
                caseEconomy.activities.put("fishing", new TokenConfig.ActivityRule(kind == 1 && existing ? 2 : 1, 3));
                if (existing) {
                    check(store.add(player, 50));
                    if (kind == 1) check(store.recordActivity(player, "fishing", caseEconomy) == 0);
                    Files.move(file, backup);
                }
                JsonElement before = memory(store);
                byte[] diskBefore = existing ? Files.readAllBytes(backup) : null;
                Files.createDirectory(file); // Certain failure before atomic publication.
                check(reward(store, player, kind, caseEconomy) == (kind == 2 ? -1 : 0));
                check(store.balance(player) == (existing ? 50 : 0));
                check(before.equals(memory(store))); // Includes counters/periods/claim date; no phantom account.
                check(Files.isDirectory(file));
                Files.delete(file);
                if (existing) Files.move(backup, file);
                try { store.requireStorageAvailable(); throw new AssertionError("must remain fenced"); }
                catch (IOException expected) { checks++; }
                check(!store.add(player, 1));
                check(reward(store, player, kind, caseEconomy) == (kind == 2 ? -1 : 0));
                store.flush();
                check(before.equals(memory(store)));
                if (existing) check(Arrays.equals(diskBefore, Files.readAllBytes(file)));
                else check(!Files.exists(file));
                check(new TokenStore(file).balance(player) == (existing ? 50 : 0));
            }
            // Success and restart still retain real claims/counters.
            Path good = root.resolve("success.json"); UUID player = UUID.randomUUID();
            var store = new TokenStore(good);
            check(store.rewardDailyLogin(player, 3, economy) == 3);
            store = new TokenStore(good);
            check(store.rewardDailyLogin(player, 3, economy) == 0);
            check(store.recordActivity(player, "fishing", economy) == 3);
            check(store.rewardMining(player, 3, economy) >= 0);
            check(new TokenStore(good).balance(player) == 9);
            System.out.println("Reward account rollback: " + checks + " checks passed; no crash/power-loss guarantee");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
