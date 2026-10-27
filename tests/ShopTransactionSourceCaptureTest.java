import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.util.*;
public class ShopTransactionSourceCaptureTest {
  static int count;
  static void ok(boolean value) { if (!value) throw new AssertionError(); count++; }
  static void fails(RunnableWithIo fn) throws Exception { try { fn.run(); throw new AssertionError("should fail"); } catch (java.io.IOException expected) {count++;} }
  interface RunnableWithIo { void run() throws Exception; }
  public static void main(String[] a) throws Exception {
    Path w=Files.createTempDirectory("phase2-sources-"); UUID player=UUID.randomUUID(); UUID txn=UUID.randomUUID();
    Files.createDirectories(w.resolve("data/erzmarken-shop-journal")); Files.createDirectories(w.resolve("playerdata"));
    Files.writeString(w.resolve("data/erzmarken-shop-journal/"+txn+".pending.json"),"pending");
    Files.writeString(w.resolve("data/erzmarken.json"),"tokens");
    Files.writeString(w.resolve("data/erzmarken-shop.json"),"shop");
    Path playerFile=w.resolve("playerdata/"+player+".dat"); Files.writeString(playerFile,"player bytes");
    var set=ShopTransactionSourceCapture.resolveOffline(w,player,false);
    ok(set.paths().size()==4); ok(set.registerAndWarehouseShareFile());
    ok(set.paths().get(ShopTransactionEvidenceStore.Source.SHOP_REGISTER).equals(
       set.paths().get(ShopTransactionEvidenceStore.Source.SHOP_WAREHOUSE)));
    fails(()->ShopTransactionSourceCapture.resolveOffline(w,player,true));
    var report=ShopTransactionSourceCapture.captureOffline(w,player,false,txn.toString(),ShopTransactionEvidenceStore.Moment.BEFORE);
    ok(report.files().size()==4);
    ok(ShopTransactionEvidenceStore.verify(w,txn.toString(),ShopTransactionEvidenceStore.Moment.BEFORE));
    fails(()->ShopTransactionSourceCapture.captureOffline(w,player,false,txn.toString(),ShopTransactionEvidenceStore.Moment.BEFORE));
    fails(()->ShopTransactionSourceCapture.captureOffline(w,player,false,UUID.randomUUID().toString(),ShopTransactionEvidenceStore.Moment.AFTER));
    Files.delete(playerFile); fails(()->ShopTransactionSourceCapture.resolveOffline(w,player,false));
    boolean linked = false;
    try {
      Files.createSymbolicLink(playerFile,w.resolve("data/erzmarken.json"));
      linked = true;
    } catch (UnsupportedOperationException | FileSystemException ex) {
      System.out.println("SKIP: source-capture symlink creation unavailable: " + ex);
    }
    if (linked) fails(()->ShopTransactionSourceCapture.resolveOffline(w,player,false));
    System.out.println(count+" source capture checks passed");
  }
}
