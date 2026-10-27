package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit sandbox admission; ordinary releases and ordinary worlds never enable experimental actions. */
public final class Phase2TestPolicy {
    public enum Feature { INVENTORY, BUYBACK, RECOVERY, RETAIL }
    public record ServerScope(boolean dedicated, String bindAddress, int port,
                              boolean authenticated, boolean whitelist, int maxPlayers, Set<UUID> online) {
        public ServerScope { online = Set.copyOf(online); }
    }
    public record Admission(boolean allowed, String reason, Set<UUID> testers, Set<Feature> features) {
        public Admission { testers = Set.copyOf(testers); features = Set.copyOf(features); }
        public boolean permits(UUID player, Feature feature) {
            return allowed && feature != null && features.contains(feature) && (player == null || testers.contains(player));
        }
    }
    private Phase2TestPolicy() { }
    public static Admission inspect(boolean testBuild, boolean launchFlag, Path world, ServerScope scope) {
        if (!testBuild || !launchFlag) return denied("Passendes Test-JAR und ausdruecklicher Teststart erforderlich");
        if (scope == null || !scope.dedicated() || !("127.0.0.1".equals(scope.bindAddress()) || "::1".equals(scope.bindAddress())) || scope.port() != 25566
                || !scope.authenticated() || !scope.whitelist() || scope.maxPlayers() < 2 || scope.maxPlayers() > 8)
            return denied("Testserver muss lokal auf Port 25566 mit Anmeldung und Whitelist laufen (2 bis 8 Plaetze)");
        try {
            Path root = world.toAbsolutePath().normalize();
            var marker = StrictJsonFiles.readObject(root.resolve(".dulliehausen-phase2-test.json"), 16 * 1024);
            if (marker.get("schema").getAsBigDecimal().intValueExact() != 1
                    || !"isolated-phase2-test".equals(marker.get("purpose").getAsString())
                    || !root.equals(Path.of(marker.get("worldPath").getAsString()).toAbsolutePath().normalize()))
                return denied("Weltkennzeichen passt nicht zu dieser Testwelt");
            ShopBuybackReceiptLink.requireValidTransactionId(marker.get("testId").getAsString());
            Set<UUID> testers = new HashSet<>();
            for (var entry : marker.getAsJsonArray("testers")) {
                String id = entry.getAsString(); ShopBuybackReceiptLink.requireValidTransactionId(id);
                if (!testers.add(UUID.fromString(id))) return denied("Doppelte Testkonto-ID");
            }
            if (testers.size() < 2 || testers.size() > 8 || !testers.containsAll(scope.online()))
                return denied("Nur die eingetragenen Testkonten duerfen am Test teilnehmen");
            Set<Feature> features = EnumSet.noneOf(Feature.class);
            for (var entry : marker.getAsJsonArray("features"))
                if (!features.add(Feature.valueOf(entry.getAsString().toUpperCase(Locale.ROOT)))) return denied("Doppelte Testfunktion");
            if (features.isEmpty()) return denied("Keine Testfunktion eingetragen");
            return new Admission(true, "Isolierter Phase-2-Testbetrieb", testers, features);
        } catch (IOException | RuntimeException invalid) { return denied("Testweltkennzeichen fehlt oder ist ungueltig"); }
    }
    private static Admission denied(String reason) { return new Admission(false, reason, Set.of(), Set.of()); }
}
