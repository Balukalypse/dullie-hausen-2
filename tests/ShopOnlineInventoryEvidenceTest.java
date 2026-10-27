import de.baluserver.erzmarken.ShopOnlineInventoryEvidence;
import java.nio.file.*;
import java.util.*;

public class ShopOnlineInventoryEvidenceTest {
  static int checks;
  static void ok(boolean v) { checks++; if(!v) throw new AssertionError("check " + checks); }
  static void fails(ShopOnlineInventoryEvidence.SnapshotSource s, Path w, UUID t, UUID p,
                    ShopOnlineInventoryEvidence.Moment m) throws Exception {
    try {ShopOnlineInventoryEvidence.capture(w,t,p,m,s);throw new AssertionError("expected failure");}
    catch(java.io.IOException expected){checks++;}
  }
  public static void main(String[] args) throws Exception {
    Path w=Files.createTempDirectory("live-evidence"); UUID t=UUID.randomUUID(), p=UUID.randomUUID();
    Path journal=w.resolve("data/erzmarken-shop-journal"); Files.createDirectories(journal);
    byte[] before="inventory-before".getBytes();
    fails(()->before,w,t,p,ShopOnlineInventoryEvidence.Moment.BEFORE);
    Files.writeString(journal.resolve(t+".pending.json"), "pending");
    var r=ShopOnlineInventoryEvidence.capture(w,t,p,ShopOnlineInventoryEvidence.Moment.BEFORE,()->before);
    ok(r.bytes()==before.length); ok(!r.durableInventoryCommitConfirmed());
    ok(ShopOnlineInventoryEvidence.verify(w,t,p,ShopOnlineInventoryEvidence.Moment.BEFORE));
    ok(!ShopOnlineInventoryEvidence.verify(w,t,UUID.randomUUID(),ShopOnlineInventoryEvidence.Moment.BEFORE));
    fails(()->before,w,t,p,ShopOnlineInventoryEvidence.Moment.BEFORE);
    fails(()->new byte[0],w,t,p,ShopOnlineInventoryEvidence.Moment.AFTER);
    var after=ShopOnlineInventoryEvidence.capture(w,t,p,ShopOnlineInventoryEvidence.Moment.AFTER,
        ()->"inventory-after".getBytes());
    ok(!after.durableInventoryCommitConfirmed());
    ok(ShopOnlineInventoryEvidence.verify(w,t,p,ShopOnlineInventoryEvidence.Moment.AFTER));
    Path a=w.resolve("data/erzmarken-shop-live-evidence").resolve(t.toString()).resolve("after.inventory.bin");
    Files.writeString(a,"tampered");
    ok(!ShopOnlineInventoryEvidence.verify(w,t,p,ShopOnlineInventoryEvidence.Moment.AFTER));
    System.out.println(checks+" live evidence checks passed");
  }
}
