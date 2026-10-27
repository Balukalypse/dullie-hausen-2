package de.baluserver.erzmarken;
import java.nio.file.*;
import java.util.*;
public class ShopInventoryRecoveryExportTest {
  static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
  public static void main(String[] args) throws Exception {
    Path tmp=Files.createTempDirectory("dh-recovery-export-");
    Path root=tmp.resolve("vault"); Path out=tmp.resolve("recovery");
    ShopInventoryVault vault=new ShopInventoryVault(root);
    UUID good=UUID.randomUUID(), damaged=UUID.randomUUID();
    vault.prepareEnter(good,new byte[]{1,2,3});
    Path goodPath=root.resolve(good+".vault"); byte[] original=Files.readAllBytes(goodPath);
    Files.writeString(root.resolve(damaged+".vault"),"BAD");
    var result=ShopInventoryRecoveryExport.export(root,out);
    check(result.size()==2,"Must report open and corrupt sessions");
    check(result.stream().anyMatch(r->r.player().equals(good)&&r.phase().equals("ENTER_PREPARED")),"Missing pending enter");
    check(result.stream().anyMatch(r->r.player().equals(damaged)&&r.phase().equals("CORRUPT")),"Missing corrupt entry");
    check(Arrays.equals(original,Files.readAllBytes(goodPath)),"Must not mutate source");
    check(Arrays.equals(original,Files.readAllBytes(out.resolve("vault-evidence").resolve(good+".vault"))),"Evidence differs");
    check(Files.readString(out.resolve("RECOVERY-REPORT.txt")).contains("SHA256="),"Missing checksum");
    boolean rejected=false;
    try { ShopInventoryRecoveryExport.export(root,root.resolve("within")); }
    catch(java.io.IOException expected) {rejected=true;}
    check(rejected,"Must not put output inside vault root");
    rejected=false;
    try { ShopInventoryRecoveryExport.export(root,out); }
    catch(java.io.IOException expected) {rejected=true;}
    check(rejected,"Must not overwrite an existing export");
    System.out.println("Recovery export: 8 checks passed");
  }
}
