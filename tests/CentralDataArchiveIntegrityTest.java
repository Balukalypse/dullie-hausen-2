package de.baluserver.erzmarken;
import java.nio.file.*;
import java.io.IOException;

public final class CentralDataArchiveIntegrityTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    private static boolean fails(Path root, Path world) {
        try { CentralDataArchive.capture(root,world); return false; }
        catch(IOException expected) { return true; }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("archive-integrity-");
        Path world=root.resolve("world"); Files.createDirectories(world.resolve("data"));
        Path source=world.resolve("data/erzmarken.json"); Files.writeString(source,"original");
        CentralDataArchive.capture(root,world);
        Path archive=root.resolve("dulliehausen/legacy-archive/world-data/erzmarken.json");
        Path receipt=root.resolve("dulliehausen/legacy-archive/.integrity/world-data/erzmarken.json.sha256");
        check(Files.exists(receipt),"receipt exists for new archive");
        check(Files.readString(receipt).matches("[0-9a-f]{64}\\n"),"hash format");
        check(CentralDataArchive.capture(root,world).unchanged()==1,"intact receipt accepted");
        Files.writeString(source,"updated");
        check(CentralDataArchive.capture(root,world).changedSinceArchive()==1,"source update detected");
        Files.writeString(archive,"tampered");
        check(fails(root,world),"tampered archive rejected");
        Files.writeString(archive,"original");
        Files.writeString(receipt,"broken\n");
        check(fails(root,world),"tampered receipt rejected");
        Files.delete(receipt);
        var legacy=CentralDataArchive.capture(root,world);
        check(legacy.notices().stream().anyMatch(x->x.equals("ARCHIVE_UNVERIFIED_LEGACY world-data/erzmarken.json")),"legacy missing receipt explicitly reported");
        check(!Files.exists(receipt),"legacy receipt not retroactively minted");
        Files.delete(archive); Files.writeString(receipt,"deadbeef\n");
        check(fails(root,world),"orphan receipt rejected");
        System.out.println("CentralDataArchiveIntegrity: " + checks + " checks passed");
    }
}
