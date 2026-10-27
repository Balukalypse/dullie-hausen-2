import de.baluserver.erzmarken.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Real journal, immutable intent, token ledger and finance readback.
 * Shop debit and inventory persistence are fixtures, not a running Minecraft server.
 */
public class ShopBuybackExecutionTest {
    static int checks;
    static final byte[] BEFORE="BEFORE".getBytes(StandardCharsets.UTF_8), AFTER="AFTER".getBytes(StandardCharsets.UTF_8);
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO op) throws IOException {
        try { op.run(); throw new AssertionError("expected failure"); }
        catch (IOException expected) { checks++; }
    }
    static final class Fixture implements ShopBuybackExecution.Actions, ShopInventorySwitchController.InventoryAdapter {
        final Path world, shopFile, playerFile;
        final UUID player=UUID.randomUUID();
        final TokenStore tokens;
        final String fault;
        byte[] live=BEFORE.clone();
        ShopTransactionJournal.Entry interrupted;
        Exception failureContext;
        int debits, credits, removals, saves;
        Fixture(Path world,String fault) throws IOException {
            this.world=world; this.fault=fault;
            ShopTransactionJournal.initialize(world);
            shopFile=world.resolve("data/erzmarken-shop.json");
            playerFile=world.resolve("fixture-player.dat");
            Files.write(playerFile,BEFORE);
            Files.writeString(shopFile,"{\"shopWallet\":2500,\"wholesaleStock\":{},\"buybackHistory\":[]}");
            tokens=TokenStore.forWorld(world); check(tokens.add(player,100));
        }
        ShopBuybackExecution.Terms terms() { return new ShopBuybackExecution.Terms(player,"minecraft:stone",2,7,100,2500,0); }
        String run() throws IOException { return ShopBuybackExecution.execute(world,terms(),this,BEFORE,AFTER,this); }
        void inject(String point) throws IOException { if (fault.equals(point)) throw new IOException("injected " + point); }
        public void captureBefore(ShopTransactionJournal.Entry entry) throws IOException {
            // The immutable plan must already exist before debit can be reached.
            check(Arrays.equals(ShopBuybackIntentStore.read(world,entry.id()).before(),BEFORE));
            inject("before-capture");
        }
        public void debitShop(String id) throws IOException {
            inject("before-debit");
            if (fault.equals("debit-file-failure")) {
                Files.move(shopFile,shopFile.resolveSibling("shop-before-failure.saved"));
                Files.createDirectory(shopFile); // Force the real durable writer to reject the destination.
            }
            JsonObject receipt=new JsonObject();
            receipt.addProperty("id",id); receipt.addProperty("timestampMillis",System.currentTimeMillis());
            receipt.addProperty("playerUuid",player.toString()); receipt.addProperty("itemId","minecraft:stone");
            receipt.addProperty("quantity",2); receipt.addProperty("unitPrice",7); receipt.addProperty("paid",14); receipt.addProperty("walletAfter",2486);
            JsonObject shop=new JsonObject(); shop.addProperty("shopWallet",2486);
            JsonObject stock=new JsonObject(); stock.addProperty("minecraft:stone",2); shop.add("wholesaleStock",stock);
            JsonArray receipts=new JsonArray(); receipts.add(receipt); shop.add("buybackHistory",receipts);
            DurableFiles.replace(shopFile,shop.toString().getBytes(StandardCharsets.UTF_8)); debits++;
            inject("after-debit");
        }
        public void creditPlayer(String id) throws IOException {
            inject("before-credit");
            if (fault.equals("credit-file-failure")) {
                Path tokenFile=world.resolve("data/erzmarken.json");
                Files.move(tokenFile,tokenFile.resolveSibling("tokens-before-failure.saved"));
                Files.createDirectory(tokenFile); // Exercise actual TokenStore failure/rollback, not a mock exception.
            }
            tokens.creditShopPurchaseChecked(player,14,id); credits++;
            inject("after-credit");
        }
        public void removeItems() throws IOException {
            inject("before-removal"); live=AFTER.clone(); removals++;
            inject("after-removal");
            if (fault.equals("wrong-removal")) live=new byte[]{9};
        }
        public void captureAfter(ShopTransactionJournal.Entry entry) throws IOException {
            check(entry.stage().equals("ITEM_REMOVED_UNSAVED")); inject("after-capture");
            if (fault.equals("finance-mismatch")) {
                JsonObject shop=JsonParser.parseString(Files.readString(shopFile)).getAsJsonObject(); shop.addProperty("shopWallet",2400);
                DurableFiles.replace(shopFile,shop.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        public void interrupted(ShopTransactionJournal.Entry entry,Exception failure) {
            interrupted=entry; failureContext=failure;
            if (fault.equals("callback-failure")) throw new IllegalStateException("callback");
        }
        public byte[] snapshot() { return live.clone(); }
        public void apply(byte[] ignored) { throw new AssertionError("No automatic rollback"); }
        public void clear() { throw new AssertionError("No automatic clear"); }
        public boolean isEmpty() { return false; }
        public void requirePersistenceSupport() throws IOException { inject("unsupported-save"); }
        public void persistAndVerify(byte[] expected) throws IOException {
            inject("save-before");
            if (fault.equals("callback-failure")) throw new IOException("save failure before failing callback");
            DurableFiles.replace(playerFile,live); saves++;
            inject("save-after");
            if (!Arrays.equals(Files.readAllBytes(playerFile),expected)) throw new IOException("saved mismatch");
            if (fault.equals("live-drift")) live=new byte[]{8};
        }
        void unresolved() throws IOException {
            check(interrupted!=null);
            check(Files.exists(world.resolve("data/erzmarken-shop-journal/"+interrupted.id()+".pending.json")));
            check(!Files.exists(world.resolve("data/erzmarken-shop-journal/"+interrupted.id()+".complete.json")));
            check(!ShopPendingTradeGate.mayJoin(world,player));
            denied(ShopTransactionJournal::ensureClear);
            int oldDebits=debits,oldCredits=credits,oldRemovals=removals;
            denied(this::run); // Pending journal or changed live inventory refuses a second execution.
            check(debits==oldDebits && credits==oldCredits && removals==oldRemovals);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("buyback-execution-").toAbsolutePath().normalize();
        try {
            Fixture good=new Fixture(root.resolve("good"),"");
            String id=good.run();
            check(good.debits==1 && good.credits==1 && good.removals==1 && good.saves==1);
            check(good.interrupted==null);
            check(new TokenStore(good.world.resolve("data/erzmarken.json")).balance(good.player)==114);
            check(Arrays.equals(Files.readAllBytes(good.playerFile),AFTER));
            check(Files.exists(good.world.resolve("data/erzmarken-shop-journal/"+id+".complete.json")));
            ShopTransactionJournal.initialize(good.world); ShopTransactionJournal.ensureClear();
            check(ShopPendingTradeGate.mayJoin(good.world,good.player));
            denied(good::run); check(good.debits==1 && good.credits==1); // Cannot reuse the same before snapshot.

            for (String fault : List.of("before-capture","before-debit","after-debit","before-credit","after-credit",
                    "before-removal","after-removal","wrong-removal","after-capture","save-before","save-after","live-drift","finance-mismatch",
                    "debit-file-failure","credit-file-failure","callback-failure")) {
                Fixture f=new Fixture(root.resolve(fault),fault); denied(f::run); f.unresolved();
                check(f.tokens.balance(f.player)==(f.credits==0?100:114));
                if (f.removals==0) check(Arrays.equals(f.live,BEFORE));
                if (f.saves==0) check(Arrays.equals(Files.readAllBytes(f.playerFile),BEFORE));
                if (fault.equals("debit-file-failure")) check(Files.isRegularFile(f.world.resolve("data/shop-before-failure.saved")));
                if (fault.equals("credit-file-failure")) check(new TokenStore(f.world.resolve("data/tokens-before-failure.saved")).balance(f.player)==100);
                if (fault.equals("callback-failure")) check(f.failureContext.getSuppressed().length==1);
            }
            Fixture plan=new Fixture(root.resolve("plan-failure"),"");
            Files.writeString(plan.world.resolve("data/erzmarken-shop-intents"),"unsafe destination");
            denied(plan::run); plan.unresolved(); check(plan.debits==0 && plan.credits==0 && plan.removals==0);
            for (String mode : List.of("unsupported-save","changed-before")) {
                Fixture f=new Fixture(root.resolve(mode),mode);
                if (mode.equals("changed-before")) f.live=new byte[]{7};
                denied(f::run); check(f.interrupted==null && f.debits==0 && f.credits==0);
                try(var entries=Files.list(f.world.resolve("data/erzmarken-shop-journal"))) { check(entries.findAny().isEmpty()); }
            }
            System.out.println("ShopBuybackExecutionTest: "+checks+" checks passed; real journal/intent/token files, fixture shop/inventory");
        } finally {
            try(var files=Files.walk(root)) {
                for(Path p:files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Unsafe test cleanup");
                    Files.delete(p);
                }
            }
        }
    }
}
