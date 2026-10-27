package de.baluserver.erzmarken;
import java.io.IOException;
import java.nio.file.*;
public final class CentralDataPathsTest {
    private static int checks;
    private static void ok(boolean b) { if(!b) throw new AssertionError("Test #" + (++checks)); checks++; }
    private static void rejects(Checked action) throws Exception { boolean rejected=false; try { action.run(); } catch(IOException e) { rejected=true; } ok(rejected); }
    private interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("dullie-layout-");
        var paths=CentralDataPaths.prepare(home);
        ok(Files.isDirectory(paths.root().resolve("config")));
        ok(Files.isDirectory(paths.root().resolve("bugreports/exports")));
        ok(Files.isDirectory(paths.root().resolve("bugreports/ai-inbox")));
        ok(Files.isDirectory(paths.root().resolve("bugreports/ai-outbox")));
        ok(paths.config("bugs.json").equals(paths.root().resolve("config/bugs.json")));
        ok(paths.data("bugs.json").equals(paths.root().resolve("data/bugs.json")));
        rejects(() -> paths.config("../secret"));
        rejects(() -> paths.data("evil/name"));
        rejects(() -> paths.data(".."));
        Files.writeString(paths.config("bugs.json"), "keep-me");
        CentralDataPaths.prepare(home);
        ok(Files.readString(paths.config("bugs.json")).equals("keep-me"));
        Path adversary=Files.createTempDirectory("dullie-link-");
        try { Files.createSymbolicLink(adversary.resolve("dulliehausen"),home.resolve("dulliehausen"));
              rejects(() -> CentralDataPaths.prepare(adversary)); }
        catch (UnsupportedOperationException | FileSystemException ex) { /* filesystem lacks symlinks */ }
        System.out.println("CentralDataPaths: " + checks + " checks passed");
    }
}
