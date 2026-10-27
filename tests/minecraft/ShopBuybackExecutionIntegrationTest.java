import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Actual ShopState + TokenStore + journal/intent/settlement; only inventory saver is synthetic. */
public final class ShopBuybackExecutionIntegrationTest {
    static int checks;
    static void check(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("buyback-real-ledgers-").toAbsolutePath().normalize();
        try {
            for(String mode:List.of("success","shop-write-failure","token-write-failure")) {
                Path world=root.resolve(mode); ShopTransactionJournal.initialize(world);
                Path shopFile=world.resolve("data/erzmarken-shop.json"),tokenFile=world.resolve("data/erzmarken.json");
                UUID player=UUID.randomUUID();
                ShopState shop=new ShopState(shopFile); shop.setOwner(UUID.randomUUID());
                shop.setRegion("minecraft:overworld",new int[]{0,0,0},new int[]{10,100,10});
                shop.proposeBuyback("minecraft:stone",7,"local integration"); shop.approveBuyback("minecraft:stone");
                TokenStore tokens=TokenStore.forWorld(world); check(tokens.add(player,100));
                var quote=shop.quoteBuyback("minecraft:stone",2);
                byte[] before="BEFORE_REMOVAL".getBytes(StandardCharsets.UTF_8),after="AFTER_REMOVAL".getBytes(StandardCharsets.UTF_8);
                var inventory=new ShopBuybackPersistenceIntegrationTest.Inventory(world.resolve("player-fixture.dat")); inventory.live=before.clone();
                var terms=new ShopBuybackExecution.Terms(player,quote.itemId(),quote.quantity(),quote.unitPrice(),100,quote.walletBalance(),quote.stockBefore());
                List<String> interrupted=new ArrayList<>();
                var actions=new ShopBuybackExecution.Actions() {
                    public void captureBefore(ShopTransactionJournal.Entry e) throws IOException { ShopBuybackIntentStore.read(world,e.id()); }
                    public void debitShop(String id) throws IOException {
                        if(mode.equals("shop-write-failure")) { Files.move(shopFile,shopFile.resolveSibling("shop.saved")); Files.createDirectory(shopFile); }
                        shop.commitBuyback(player,quote,id);
                    }
                    public void creditPlayer(String id) throws IOException {
                        if(mode.equals("token-write-failure")) { Files.move(tokenFile,tokenFile.resolveSibling("tokens.saved")); Files.createDirectory(tokenFile); }
                        tokens.creditShopPurchaseChecked(player,quote.total(),id);
                    }
                    public void removeItems() { inventory.live=after.clone(); }
                    public void captureAfter(ShopTransactionJournal.Entry e) { }
                    public void interrupted(ShopTransactionJournal.Entry e,Exception failure) { interrupted.add(e.id()); }
                };
                if(mode.equals("success")) {
                    String id=ShopBuybackExecution.execute(world,terms,inventory,before,after,actions);
                    check(interrupted.isEmpty()); check(inventory.saves==1);
                    check(new ShopState(shopFile).shopWallet()==2486 && new ShopState(shopFile).wholesaleStock("minecraft:stone")==2);
                    check(new TokenStore(tokenFile).balance(player)==114);
                    check(new ShopState(shopFile).buybackHistory().size()==1 && new ShopState(shopFile).buybackHistory().get(0).id().equals(id));
                    ShopTransactionJournal.ensureClear(); check(ShopPendingTradeGate.mayJoin(world,player));
                } else {
                    try { ShopBuybackExecution.execute(world,terms,inventory,before,after,actions); throw new AssertionError("write should fail"); }
                    catch(IOException expected) { checks++; }
                    check(interrupted.size()==1 && inventory.saves==0 && Arrays.equals(inventory.live,before));
                    check(tokens.balance(player)==100); check(!ShopPendingTradeGate.mayJoin(world,player));
                    check(Files.exists(world.resolve("data/erzmarken-shop-journal/"+interrupted.get(0)+".pending.json")));
                    if(mode.equals("shop-write-failure")) check(shop.shopWallet()==2500 && shop.buybackHistory().isEmpty());
                    else check(new ShopState(shopFile).shopWallet()==2486 && shop.buybackHistory().size()==1);
                }
            }
            System.out.println("ShopBuybackExecutionIntegrationTest: "+checks+" checks passed; real shop/token files, synthetic inventory save");
        } finally {
            try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(!p.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Unsafe cleanup"); Files.delete(p);
            } }
        }
    }
}
