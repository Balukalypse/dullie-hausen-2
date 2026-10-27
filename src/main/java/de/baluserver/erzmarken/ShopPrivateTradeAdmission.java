package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Read-only prerequisite for private item purchases and player trades. */
public final class ShopPrivateTradeAdmission {
    private ShopPrivateTradeAdmission() { }
    public static void require(Path world, UUID... participants) throws IOException {
        if (world == null || participants == null || participants.length < 1 || participants.length > 2)
            throw new IOException("Ungueltige Teilnehmer der Inventarpruefung");
        // Validate all arguments before accessing any file.
        for (UUID player : participants)
            if (player == null) throw new IOException("Spieler der Inventarpruefung fehlt");
        var vault = new ShopInventoryVault(world.resolve("data/dulliehausen-inventory-vault"));
        for (UUID player : participants) vault.requirePrivateActive(player);
    }
}
