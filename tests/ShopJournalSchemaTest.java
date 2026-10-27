import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class ShopJournalSchemaTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO action) throws IOException {
        try { action.run(); throw new AssertionError("accepted invalid journal"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("journal-schema-");
        try {
            ShopTransactionJournal.initialize(world); UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            Path folder = world.resolve("data/erzmarken-shop-journal");
            denied(() -> ShopTransactionJournal.begin(null, "minecraft:stone", 2, 14));
            denied(() -> ShopTransactionJournal.begin(owner, "stone", 2, 14));
            denied(() -> ShopTransactionJournal.begin(owner, "minecraft:stone", 0, 14));
            denied(() -> ShopTransactionJournal.begin(owner, "minecraft:stone", 65, 65));
            denied(() -> ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 0));
            denied(() -> ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 15));
            denied(() -> ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 2_000_002));
            try (var files = Files.list(folder)) { check(files.findAny().isEmpty()); }
            var entry = ShopTransactionJournal.begin(owner, "minecraft:stone", 2, 14);
            Path file = folder.resolve(entry.id() + ".pending.json"); byte[] original = Files.readAllBytes(file);
            var tokens = new TokenStore(world.resolve("data/erzmarken.json"));
            tokens.creditShopPurchaseChecked(owner, 14, entry.id());
            byte[] originalTokens = Files.readAllBytes(world.resolve("data/erzmarken.json"));
            check(ShopTransactionJournal.readPendingForReview(world, entry.id()).equals(entry));
            check(!ShopPendingTradeGate.mayJoin(world, owner));
            check(ShopPendingTradeGate.mayJoin(world, other));
            List<Consumer<JsonObject>> invalid = List.of(
                j -> j.addProperty("quantity", 2.5), j -> j.addProperty("quantity", "2"),
                j -> j.addProperty("quantity", 65), j -> j.addProperty("total", 15),
                j -> j.addProperty("total", 0), j -> j.addProperty("total", "14"),
                j -> j.addProperty("createdAt", -1), j -> j.addProperty("createdAt", "1000"),
                j -> j.addProperty("stage", "UNKNOWN"), j -> j.addProperty("stage", true),
                j -> j.addProperty("itemId", "stone"), j -> j.addProperty("playerId", "1-1-1-1-1"),
                j -> j.addProperty("id", UUID.randomUUID().toString()), j -> j.remove("quantity"));
            for (var mutate : invalid) {
                JsonObject raw = JsonParser.parseString(new String(original, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                mutate.accept(raw); Files.writeString(file, raw.toString()); byte[] corrupt = Files.readAllBytes(file);
                denied(() -> ShopTransactionJournal.readPendingForReview(world, entry.id()));
                denied(() -> ShopTransactionJournal.advance(entry, "SHOP_DEBITED"));
                check(!ShopPendingTradeGate.mayJoin(world, other)); // Cannot trust corrupt attribution.
                check(ShopTokenReceiptAudit.inspect(world, ShopJournalAudit.inspect(world), 4).unreadable() == 1);
                check(Arrays.equals(corrupt, Files.readAllBytes(file)));
                check(Arrays.equals(originalTokens, Files.readAllBytes(world.resolve("data/erzmarken.json"))));
            }
            Files.write(file, original);
            check(ShopPendingTradeGate.mayJoin(world, other));
            check(ShopTokenReceiptAudit.inspect(world, ShopJournalAudit.inspect(world), 4).matched() == 1);
            System.out.println("Strict journal schema: " + checks + " checks passed; real journal/token files");
        } finally {
            try (var files = Files.walk(world)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
