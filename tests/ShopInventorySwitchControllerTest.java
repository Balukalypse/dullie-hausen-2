import de.baluserver.erzmarken.*;
import java.nio.file.*;
import java.util.*;

public class ShopInventorySwitchControllerTest {
  static class Mock implements ShopInventorySwitchController.InventoryAdapter {
    byte[] item = "PRIVATE".getBytes();
    boolean failApply,failSnapshot,partialApply,partialClear;
    public byte[] snapshot() throws Exception { if (failSnapshot) throw new Exception("snapshot failed"); return item.clone(); }
    public void apply(byte[] data) throws Exception { if(failApply) throw new Exception("apply failed"); item=partialApply ? "PARTIAL".getBytes() : data.clone(); }
    public void clear() throws Exception { if(failApply) throw new Exception("clear failed"); item=(partialClear ? "STILL_PRIVATE" : "EMPTY").getBytes(); }
    public boolean isEmpty(){ return Arrays.equals(item, "EMPTY".getBytes()); }
    public void requirePersistenceSupport() { }
    public void persistAndVerify(byte[] expected) { check(Arrays.equals(item, expected), "persistence snapshot"); }
    String state(){ return new String(item); }
  }
  static void check(boolean result,String msg){if(!result)throw new AssertionError(msg);}
  public static void main(String[] args) throws Exception {
    Path temp = Files.createTempDirectory("dullie-switch-test");
    try {
      ShopInventoryVault vault=new ShopInventoryVault(temp);
      ShopInventorySwitchController switcher=new ShopInventorySwitchController(vault);
      UUID id=UUID.randomUUID(); Mock m=new Mock();
      switcher.enter(id,m);
      check(m.state().equals("EMPTY"),"new shop starts empty");
      check(vault.read(id).phase()==ShopInventoryVault.Phase.SHOP_ACTIVE,"shop active");
      m.item="SHOP_STOCK".getBytes(); switcher.exit(id,m);
      check(m.state().equals("PRIVATE"),"private restored");
      check(vault.read(id).phase()==ShopInventoryVault.Phase.PRIVATE_ACTIVE,"private active");
      switcher.enter(id,m); check(m.state().equals("SHOP_STOCK"),"shop persisted");
      switcher.exit(id,m); check(m.state().equals("PRIVATE"),"private persisted");
      Mock failing=new Mock(); failing.failSnapshot=true;
      UUID id2=UUID.randomUUID();
      try {switcher.enter(id2,failing);throw new AssertionError("must fail");}catch(java.io.IOException expected){}
      check(vault.read(id2).phase()==ShopInventoryVault.Phase.PRIVATE_ACTIVE,"snapshot failure cannot change vault");
      UUID id3=UUID.randomUUID(); Mock broken=new Mock(); broken.failApply=true;
      try {switcher.enter(id3,broken);throw new AssertionError("must fail");}catch(java.io.IOException expected){}
      check(vault.read(id3).phase()==ShopInventoryVault.Phase.RECOVERY_REQUIRED,"failed entry requires recovery");
      try {switcher.enter(id3,broken);throw new AssertionError("must block");}catch(java.io.IOException expected){}
      check(vault.inspectUnfinished().stream().anyMatch(i->i.playerId().equals(id3)),"scanner finds issue");
      UUID id4=UUID.randomUUID(); Mock brokenExit=new Mock(); switcher.enter(id4,brokenExit);
      brokenExit.failApply=true;
      try {switcher.exit(id4,brokenExit);throw new AssertionError("must fail");}catch(java.io.IOException expected){}
      check(vault.read(id4).phase()==ShopInventoryVault.Phase.RECOVERY_REQUIRED,"failed exit requires recovery");
      UUID id5=UUID.randomUUID(); Mock truncatedEntry=new Mock();
      switcher.enter(id5,truncatedEntry);truncatedEntry.item="SHOP_STOCK".getBytes();switcher.exit(id5,truncatedEntry);
      truncatedEntry.partialApply=true;
      try {switcher.enter(id5,truncatedEntry);throw new AssertionError("partial entry must fail");}catch(java.io.IOException expected){}
      check(vault.read(id5).phase()==ShopInventoryVault.Phase.RECOVERY_REQUIRED,"partial entry flagged");
      UUID id6=UUID.randomUUID(); Mock truncatedExit=new Mock();switcher.enter(id6,truncatedExit);
      truncatedExit.partialApply=true;
      try {switcher.exit(id6,truncatedExit);throw new AssertionError("partial exit must fail");}catch(java.io.IOException expected){}
      check(vault.read(id6).phase()==ShopInventoryVault.Phase.RECOVERY_REQUIRED,"partial exit flagged");
      UUID id7=UUID.randomUUID(); Mock incompleteClear=new Mock(); incompleteClear.partialClear=true;
      try {switcher.enter(id7,incompleteClear);throw new AssertionError("partial clear must fail");}
      catch(java.io.IOException expected){}
      check(vault.read(id7).phase()==ShopInventoryVault.Phase.RECOVERY_REQUIRED,
          "partial clear cannot confirm a shop inventory");
      System.out.println("10 Controller-Pruefungen bestanden");
    } finally {try (var walk=Files.walk(temp)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
  }
}
