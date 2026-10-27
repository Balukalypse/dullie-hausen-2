import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real shop/token ledgers and retail journal; inventory saver is a test fixture. */
public final class ShopRetailExecutionIntegrationTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static final class Inventory implements ShopInventorySwitchController.InventoryAdapter {
        byte[] live = new byte[]{1};
        final Path file;
        String mode;
        int saves;
        Inventory(Path file, String mode) throws IOException { this.file=file; this.mode=mode; Files.write(file, live); }
        public byte[] snapshot() { return live.clone(); }
        public void apply(byte[] value) { live=value.clone(); }
        public void clear() { throw new AssertionError("no clear"); }
        public boolean isEmpty() { return false; }
        public void requirePersistenceSupport() { }
        public void persistAndVerify(byte[] expected) throws IOException {
            if (mode.equals("save-before")) throw new IOException("save failure");
            Files.write(file, live); saves++;
            if (mode.equals("save-after")) throw new IOException("ambiguous save");
            if (!Arrays.equals(expected, Files.readAllBytes(file))) throw new IOException("readback mismatch");
            if (mode.equals("live-drift")) live=new byte[]{3};
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("retail-execution-").toAbsolutePath().normalize();
        try {
            for (String mode : List.of("success", "token-write", "shop-unavailable", "delivery-before",
                    "wrong-delivery", "save-before", "save-after", "live-drift", "finance-mismatch", "archive-failure")) {
                Path world=root.resolve(mode); Files.createDirectories(world.resolve("data"));
                Path shopFile=world.resolve("data/erzmarken-shop.json"), tokenFile=world.resolve("data/erzmarken.json");
                ShopState shop=new ShopState(shopFile); shop.setOwner(UUID.randomUUID());
                shop.setRegion("minecraft:overworld", new int[]{0,0,0}, new int[]{10,100,10});
                shop.setWholesaleUnitPrice("minecraft:stone",3); shop.buyWholesale("minecraft:stone",10);
                shop.setRetailUnitPrice("minecraft:stone",7);
                UUID buyer=UUID.randomUUID(); TokenStore tokens=TokenStore.forWorld(world); check(tokens.add(buyer,30));
                var quote=shop.retailQuote("minecraft:stone",2);
                var plan=new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(),buyer,"minecraft:stone",2,7,
                    30,2470,10,new byte[]{1},new byte[]{2});
                Inventory inventory=new Inventory(world.resolve("inventory-fixture.dat"),mode);
                List<String> interrupted=new ArrayList<>();
                var actions=new ShopRetailExecution.Actions() {
                    public void debitPlayer(String id) throws IOException {
                        if (mode.equals("token-write")) { Files.move(tokenFile,tokenFile.resolveSibling("tokens.saved")); Files.createDirectory(tokenFile); }
                        tokens.debitShopSaleChecked(buyer,14,id);
                    }
                    public void creditShop(String id) throws IOException {
                        if (mode.equals("shop-unavailable")) { Files.move(shopFile,shopFile.resolveSibling("shop.saved")); Files.createDirectory(shopFile); }
                        shop.commitRetail(buyer,quote,id);
                    }
                    public void deliverItems(byte[] planned) throws Exception {
                        if (mode.equals("delivery-before")) throw new IOException("delivery failed");
                        inventory.apply(mode.equals("wrong-delivery") ? new byte[]{4} : planned);
                        if (mode.equals("finance-mismatch")) {
                            var json=com.google.gson.JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject();
                            json.getAsJsonObject("players").getAsJsonObject(buyer.toString()).addProperty("balance",17);
                            Files.writeString(tokenFile,json.toString());
                        }
                        if (mode.equals("archive-failure")) Files.createDirectory(world.resolve("data/erzmarken-retail-journal/"+plan.id()+".complete.json"));
                    }
                    public void interrupted(ShopRetailJournal.Entry entry, Exception failure) { interrupted.add(entry.id()); }
                };
                Path pending=world.resolve("data/erzmarken-retail-journal/"+plan.id()+".pending.json");
                if (mode.equals("success")) {
                    check(ShopRetailExecution.execute(world,plan,inventory,actions).equals(plan.id()));
                    check(interrupted.isEmpty() && inventory.saves==1);
                    check(new TokenStore(tokenFile).balance(buyer)==16);
                    ShopState reopened=new ShopState(shopFile);
                    check(reopened.shopWallet()==2484 && reopened.wholesaleStock("minecraft:stone")==8);
                    check(reopened.retailHistory().size()==1 && reopened.retailHistory().get(0).id().equals(plan.id()));
                    check(!Files.exists(pending));
                    check("COMPLETED_VERIFIED".equals(ShopRetailJournal.readCompleted(world,plan.id()).stage()));
                    check(ShopPendingTradeGate.mayJoin(world,buyer)); ShopRetailJournal.requireClear(world);
                    try { ShopRetailExecution.execute(world,plan,inventory,actions); throw new AssertionError("replay"); }
                    catch(IOException expected) { checks++; }
                    check(tokens.balance(buyer)==16 && shop.retailHistory().size()==1);
                    var nextQuote=shop.retailQuote("minecraft:stone",1);
                    var nextPlan=new ShopRetailPlanStore.Plan(UUID.randomUUID().toString(),buyer,"minecraft:stone",1,7,
                        16,2484,8,new byte[]{2},new byte[]{5});
                    ShopRetailExecution.execute(world,nextPlan,inventory,new ShopRetailExecution.Actions() {
                        public void debitPlayer(String id) throws IOException { tokens.debitShopSaleChecked(buyer,7,id); }
                        public void creditShop(String id) throws IOException { shop.commitRetail(buyer,nextQuote,id); }
                        public void deliverItems(byte[] planned) { inventory.apply(planned); }
                        public void interrupted(ShopRetailJournal.Entry entry,Exception failure) { throw new AssertionError(failure); }
                    });
                    check(tokens.balance(buyer)==9 && shop.shopWallet()==2491 && shop.wholesaleStock("minecraft:stone")==7);
                    check(ShopPendingTradeGate.mayJoin(world,buyer));
                    check(ShopRetailJournal.inspect(world).size()==2);
                } else {
                    try { ShopRetailExecution.execute(world,plan,inventory,actions); throw new AssertionError(mode); }
                    catch(IOException expected) { checks++; }
                    check(interrupted.size()==1 && interrupted.get(0).equals(plan.id()));
                    check(Files.isRegularFile(pending));
                    check(!ShopPendingTradeGate.mayJoin(world,buyer));
                    try { ShopRetailJournal.requireClear(world); throw new AssertionError("pending must block"); }
                    catch(IOException expected) { checks++; }
                    check(tokens.balance(buyer)==(mode.equals("token-write") ? 30 : 16));
                    check(shop.retailHistory().size()==(mode.equals("token-write") || mode.equals("shop-unavailable") ? 0 : 1));
                }
            }
            System.out.println("ShopRetailExecutionIntegrationTest: "+checks+" checks passed; real ledgers, synthetic inventory saver");
        } finally {
            try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) {
                if(!p.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Unsafe cleanup"); Files.delete(p);
            } }
        }
    }
}
