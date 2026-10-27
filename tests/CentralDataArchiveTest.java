package de.baluserver.erzmarken;
import java.nio.file.*;
public final class CentralDataArchiveTest {
  private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
  public static void main(String[] a) throws Exception {
    Path root=Files.createTempDirectory("dullie-archive-test-"); Path world=root.resolve("world");
    Files.createDirectories(root.resolve("config")); Files.createDirectories(world.resolve("data/erzmarken-shop-journal"));
    Path token=world.resolve("data/erzmarken.json"); Files.writeString(token,"old-players");
    Files.writeString(root.resolve("config/erzmarken.json"),"settings");
    Files.writeString(world.resolve("data/erzmarken-shop-journal/tx1.json"),"pending");
    var first=CentralDataArchive.capture(root,world);
    check(first.copied()==3,"initial sources copied");
    Path archived=root.resolve("dulliehausen/legacy-archive/world-data/erzmarken.json");
    check(Files.readString(archived).equals("old-players"),"archive content");
    check(Files.readString(token).equals("old-players"),"original untouched");
    var second=CentralDataArchive.capture(root,world);
    check(second.copied()==0 && second.unchanged()==3,"idempotent");
    Files.writeString(token,"updated-players");
    var changed=CentralDataArchive.capture(root,world);
    check(changed.changedSinceArchive()==1,"changed source detected");
    check(Files.readString(archived).equals("old-players"),"archived content never overwritten");
    Path malicious=world.resolve("data/erzmarken-shop-journal/link.json");
    try { Files.createSymbolicLink(malicious,token); boolean rejected=false;
      try { CentralDataArchive.capture(root,world); } catch(java.io.IOException ex) { rejected=true; }
      check(rejected,"symlink must fail closed");
    } catch(UnsupportedOperationException | java.nio.file.FileSystemException ex) { /* no symlink support */ }
    Path status=root.resolve("dulliehausen/legacy-archive/ARCHIV-STATUS.txt");
    check(Files.readString(status).contains("inzwischen geaendert: 1"),"updated report");
    Files.deleteIfExists(malicious);
    Files.delete(status);
    Path target=root.resolve("do-not-overwrite.txt"); Files.writeString(target,"protected");
    try { Files.createSymbolicLink(status,target); boolean rejected=false;
      try { CentralDataArchive.capture(root,world); } catch(java.io.IOException ex) { rejected=true; }
      check(rejected,"status symlink rejected");
      check(Files.readString(target).equals("protected"),"linked target untouched");
    } catch(UnsupportedOperationException | java.nio.file.FileSystemException ex) { /* symlink not supported */ }
    System.out.println("CentralDataArchive: 10 assertions passed");
  }
}
