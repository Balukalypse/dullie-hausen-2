import de.baluserver.erzmarken.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real financial/journal files; byte comparison stands in for the separately tested Minecraft verifier. */
public class ShopBuybackRecoveryFinalizerTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    interface IO { void run() throws IOException; }
    static void denied(IO operation) throws IOException {
        try { operation.run(); throw new AssertionError("must refuse"); } catch (IOException expected) { checks++; }
    }
    static final class Env {
        final ShopBuybackPersistenceIntegrationTest.Fixture f;
        final Path playerFile, planFile;
        final TokenStore tokens;
        final ShopState shop;
        final ShopBuybackRecoveryFinalizer.InventoryVerifier verifier;
        final ShopBuybackRecoveryFinalizer.MemoryCheck memory;
        Env(Path root) throws IOException {
            f = new ShopBuybackPersistenceIntegrationTest.Fixture(root);
            playerFile = root.resolve("players/data/" + f.player + ".dat");
            Files.createDirectories(playerFile.getParent()); Files.writeString(playerFile, "AFTER_REMOVAL");
            planFile = root.resolve("data/erzmarken-shop-intents/" + f.entry.id() + ".intent");
            tokens = new TokenStore(f.tokenFile); shop = new ShopState(f.shopFile);
            verifier = (saved, uuid, expected) -> {
                if (!f.player.equals(uuid) || !Arrays.equals(saved, expected)) throw new IOException("fixture differs");
            };
            memory = () -> { tokens.requirePersistedState(); shop.requirePersistedState(); };
        }
        ShopBuybackRecoveryFinalizer.Ticket ticket() throws IOException {
            return ShopBuybackRecoveryFinalizer.inspect(f.world, f.entry.id(), false, verifier);
        }
        void finish(String digest) throws IOException {
            ShopBuybackRecoveryFinalizer.finishConfirmed(f.world, f.entry.id(), digest, () -> false, verifier, memory);
        }
        Path decision(String digest) {
            return f.world.resolve("data/erzmarken-shop-recovery-decisions/" + f.entry.id() + "-" + digest + ".json");
        }
        List<byte[]> protectedBytes() throws IOException {
            return List.of(Files.readAllBytes(f.tokenFile), Files.readAllBytes(f.shopFile),
                Files.readAllBytes(playerFile), Files.readAllBytes(planFile));
        }
        void unchanged(List<byte[]> before) throws IOException {
            var after = protectedBytes();
            for (int i = 0; i < before.size(); i++) check(Arrays.equals(before.get(i), after.get(i)));
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("confirmed-recovery-");
        try {
            Env good = new Env(root.resolve("success"));
            var before = good.protectedBytes(); byte[] pending = Files.readAllBytes(good.f.pending);
            var ticket = good.ticket();
            check(ticket.sources().size() == 5 && ticket.confirmation().matches("[0-9a-f]{64}"));
            good.unchanged(before); check(Arrays.equals(pending, Files.readAllBytes(good.f.pending)));
            check(!Files.exists(good.decision(ticket.confirmation()).getParent()));
            good.finish(ticket.confirmation()); good.unchanged(before);
            check(Files.exists(good.f.completed) && !Files.exists(good.f.pending));
            check(Files.readString(good.decision(ticket.confirmation())).contains("CONSOLE_COMPLETION_REQUEST"));
            check(ShopPendingTradeGate.mayJoin(good.f.world, good.f.player));
            ShopTransactionJournal.ensureClear();
            byte[] decision = Files.readAllBytes(good.decision(ticket.confirmation()));
            denied(() -> good.finish(ticket.confirmation()));
            check(Arrays.equals(decision, Files.readAllBytes(good.decision(ticket.confirmation()))));

            Env archivedPhase = new Env(root.resolve("verified-pending"));
            ShopTransactionJournal.advance(archivedPhase.f.entry, "COMPLETED_VERIFIED");
            byte[] verifiedPending = Files.readAllBytes(archivedPhase.f.pending);
            var verifiedTicket = archivedPhase.ticket(); archivedPhase.finish(verifiedTicket.confirmation());
            check(Arrays.equals(verifiedPending, Files.readAllBytes(archivedPhase.f.completed))); // archive only

            Env wrong = new Env(root.resolve("wrong-confirmation"));
            var correct = wrong.ticket(); var original = wrong.protectedBytes();
            denied(() -> wrong.finish("0".repeat(64))); denied(() -> wrong.finish("yes"));
            wrong.unchanged(original); check(Files.exists(wrong.f.pending));
            check(!Files.exists(wrong.decision(correct.confirmation()).getParent()));

            for (String role : new String[]{"plan", "journal", "tokens", "shop", "player"}) {
                Env stale = new Env(root.resolve("stale-" + role)); var old = stale.ticket();
                Path changed = switch (role) {
                    case "plan" -> stale.planFile; case "journal" -> stale.f.pending;
                    case "tokens" -> stale.f.tokenFile; case "shop" -> stale.f.shopFile;
                    default -> stale.playerFile;
                };
                Files.writeString(changed, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                var preserved = stale.protectedBytes(); byte[] currentPending = Files.readAllBytes(stale.f.pending);
                denied(() -> stale.finish(old.confirmation())); stale.unchanged(preserved);
                check(Arrays.equals(currentPending, Files.readAllBytes(stale.f.pending)));
                check(!Files.exists(stale.decision(old.confirmation()).getParent()));
            }

            Env beforeOnly = new Env(root.resolve("before-only")); Files.writeString(beforeOnly.playerFile, "BEFORE_REMOVAL");
            denied(beforeOnly::ticket);
            denied(() -> ShopBuybackRecoveryFinalizer.inspect(beforeOnly.f.world, beforeOnly.f.entry.id(), true, beforeOnly.verifier));
            denied(() -> ShopBuybackRecoveryFinalizer.inspect(beforeOnly.f.world, beforeOnly.f.entry.id(), false, null));
            Files.writeString(beforeOnly.playerFile, "AFTER_REMOVAL");
            denied(() -> ShopBuybackRecoveryFinalizer.inspect(beforeOnly.f.world, beforeOnly.f.entry.id(), false, (bytes, uuid, expected) -> { }));

            Env early = new Env(root.resolve("early-stage"));
            String earlyText = Files.readString(early.f.pending).replace("ITEM_REMOVED_UNSAVED", "PLAYER_CREDITED");
            Files.writeString(early.f.pending, earlyText); denied(early::ticket);

            Env dirtyTokens = new Env(root.resolve("dirty-memory")); var dirtyTicket = dirtyTokens.ticket();
            var untouched = dirtyTokens.protectedBytes(); dirtyTokens.tokens.markPlaced("unsaved-position");
            denied(() -> dirtyTokens.finish(dirtyTicket.confirmation())); dirtyTokens.unchanged(untouched);
            check(!Files.exists(dirtyTokens.decision(dirtyTicket.confirmation())));
            Env staleShop = new Env(root.resolve("stale-shop-memory"));
            new ShopState(staleShop.f.shopFile).setOpen(false); var freshFiles = staleShop.ticket();
            denied(() -> staleShop.finish(freshFiles.confirmation()));

            Env failedTokens = new Env(root.resolve("fenced-token-storage")); var failedTokenTicket = failedTokens.ticket();
            var failedTokenBefore = failedTokens.protectedBytes();
            Path tokenBackup = failedTokens.f.world.resolve("token-original.json");
            Files.move(failedTokens.f.tokenFile, tokenBackup); Files.createDirectory(failedTokens.f.tokenFile);
            denied(() -> failedTokens.tokens.creditShopPurchaseChecked(failedTokens.f.player, 1, UUID.randomUUID().toString()));
            Files.delete(failedTokens.f.tokenFile); Files.move(tokenBackup, failedTokens.f.tokenFile);
            denied(failedTokens.tokens::requirePersistedState);
            denied(failedTokens.tokens::requireStorageAvailable);
            check(!failedTokens.tokens.add(failedTokens.f.player, 1));
            check(!failedTokens.tokens.take(failedTokens.f.player, 1));
            check(!failedTokens.tokens.set(failedTokens.f.player, 1));
            check(!failedTokens.tokens.transfer(failedTokens.f.player, UUID.randomUUID(), 1));
            TokenConfig config = new TokenConfig();
            check(failedTokens.tokens.rewardDailyLogin(failedTokens.f.player, 1, config.activityEconomy) == 0);
            check(failedTokens.tokens.rewardMining(failedTokens.f.player, 1, config.activityEconomy) == -1);
            failedTokens.tokens.flush(); failedTokens.unchanged(failedTokenBefore);
            denied(() -> failedTokens.finish(failedTokenTicket.confirmation()));

            Env failedShop = new Env(root.resolve("fenced-shop-storage")); var failedShopTicket = failedShop.ticket();
            var failedShopBefore = failedShop.protectedBytes();
            Path shopBackup = failedShop.f.world.resolve("shop-original.json");
            Files.move(failedShop.f.shopFile, shopBackup); Files.createDirectory(failedShop.f.shopFile);
            denied(() -> failedShop.shop.setOpen(false));
            Files.delete(failedShop.f.shopFile); Files.move(shopBackup, failedShop.f.shopFile);
            denied(failedShop.shop::requirePersistedState);
            denied(failedShop.shop::requireStorageAvailable);
            try { failedShop.shop.quoteBuyback("minecraft:stone", 2); throw new AssertionError("fenced shop may not quote a new trade"); }
            catch (IllegalStateException expected) { checks++; }
            denied(() -> failedShop.shop.setOpen(false)); failedShop.unchanged(failedShopBefore);
            denied(() -> failedShop.finish(failedShopTicket.confirmation()));

            Env collision = new Env(root.resolve("decision-collision")); var collisionTicket = collision.ticket();
            Path bad = collision.decision(collisionTicket.confirmation()); Files.createDirectories(bad.getParent());
            Files.writeString(bad, "broken decision"); var collisionBefore = collision.protectedBytes();
            denied(() -> collision.finish(collisionTicket.confirmation())); collision.unchanged(collisionBefore);
            check(Files.readString(bad).equals("broken decision") && Files.exists(collision.f.pending));

            Env interrupted = new Env(root.resolve("interrupted-request")); var interruptedTicket = interrupted.ticket();
            AtomicInteger memoryChecks = new AtomicInteger();
            denied(() -> ShopBuybackRecoveryFinalizer.finishConfirmed(interrupted.f.world, interrupted.f.entry.id(),
                interruptedTicket.confirmation(), () -> false, interrupted.verifier, () -> {
                    interrupted.memory.verify();
                    if (memoryChecks.incrementAndGet() == 2) throw new IOException("simulated interruption after decision");
                }));
            check(Files.exists(interrupted.f.pending));
            byte[] request = Files.readAllBytes(interrupted.decision(interruptedTicket.confirmation()));
            var interruptedBefore = interrupted.protectedBytes();
            interrupted.finish(interruptedTicket.confirmation()); interrupted.unchanged(interruptedBefore);
            check(Arrays.equals(request, Files.readAllBytes(interrupted.decision(interruptedTicket.confirmation()))));

            Env onlineRace = new Env(root.resolve("online-between-checks")); var onlineTicket = onlineRace.ticket();
            AtomicInteger onlineChecks = new AtomicInteger();
            denied(() -> ShopBuybackRecoveryFinalizer.finishConfirmed(onlineRace.f.world, onlineRace.f.entry.id(),
                onlineTicket.confirmation(), () -> onlineChecks.incrementAndGet() > 1, onlineRace.verifier, onlineRace.memory));
            check(Files.exists(onlineRace.f.pending) && !Files.exists(onlineRace.f.completed));

            Env changedDuringRead = new Env(root.resolve("concurrent-change"));
            AtomicInteger inventoryChecks = new AtomicInteger();
            denied(() -> ShopBuybackRecoveryFinalizer.inspect(changedDuringRead.f.world, changedDuringRead.f.entry.id(), false,
                (bytes, uuid, expected) -> {
                    if (inventoryChecks.incrementAndGet() == 1)
                        Files.writeString(changedDuringRead.f.tokenFile, "\n", StandardOpenOption.APPEND);
                    changedDuringRead.verifier.verify(bytes, uuid, expected);
                }));
            check(!Files.exists(changedDuringRead.f.world.resolve("data/erzmarken-shop-recovery-decisions")));
            check(!ShopBuybackRecoveryFinalizer.COMMAND_ENABLED);
            System.out.println("Confirmed recovery: " + checks + " checks passed; financial/journal stores real, inventory verifier a test double");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
