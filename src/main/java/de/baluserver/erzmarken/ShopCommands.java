package de.baluserver.erzmarken;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/** Only one shop exists. The owner may change listings/prices only INSIDE it. */
public final class ShopCommands {
    private ShopCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shop")
            .executes(ctx -> feedback(ctx.getSource(),
                "Shop: /shop info, /shop oeffnen, /shop schliessen | /token shop | Besitzer im Shop: /shop creative, /shop preis ID TOKEN, /shop add ID minecraft:item ANZAHL PREIS, /shop remove ID | Konsole: shop region DIM X1 Y1 Z1 X2 Y2 Z2, shop owner SPIELER, shop katalog ..."))
            .then(Commands.literal("test").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("status").executes(ctx -> feedback(ctx.getSource(),
                    Phase2TestAccess.admission(ctx.getSource().getServer()).reason()))))
            .then(Commands.literal("helfer")
                .then(Commands.literal("add").then(Commands.argument("spieler", EntityArgument.player()).executes(ctx -> helper(ctx, true))))
                .then(Commands.literal("remove").then(Commands.argument("spieler", EntityArgument.player()).executes(ctx -> helper(ctx, false))))
                .then(Commands.literal("list").executes(ctx -> feedback(ctx.getSource(), "Bauhelfer: " + (ErzmarkenMod.shopState == null ? "unbekannt" : ErzmarkenMod.shopState.helpers())))))
            .then(Commands.literal("schutz")
                .then(Commands.literal("an").executes(ctx -> protection(ctx, true)))
                .then(Commands.literal("aus").executes(ctx -> protection(ctx, false))))
            .then(Commands.literal("lager").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("list").executes(ctx -> feedback(ctx.getSource(), "Shop-Lagerpositionen: " + ErzmarkenMod.shopState.warehouse())))
                .then(Commands.literal("add").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> warehouse(ctx, true))))))
                .then(Commands.literal("remove").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> warehouse(ctx, false)))))))
            // Mode persistence is intentionally independent of the unfinished commercial engine.
            .then(Commands.literal("modus").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("anzeigen").executes(ctx -> {
                    ShopState st = ErzmarkenMod.shopState;
                    return st == null ? fail(ctx.getSource(), "Shopdaten fehlen") : feedback(ctx.getSource(),
                        "Betriebsart: " + st.shopMode() + ", Shopkasse: " + st.shopWallet() + " Token");
                }))
                .then(Commands.literal("grosshandel").executes(ctx -> setMode(ctx, "grosshandel")))
                .then(Commands.literal("kreativ").executes(ctx -> setMode(ctx, "kreativ"))))
            .then(Commands.literal("grosshandel").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("liste").executes(ShopCommands::wholesaleList))
                .then(Commands.literal("bestand").executes(ShopCommands::wholesaleStock))
                .then(Commands.literal("historie").executes(ShopCommands::wholesaleHistory))
                .then(Commands.literal("preis")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .then(Commands.argument("token", LongArgumentType.longArg(1, 1_000_000L))
                            .executes(ShopCommands::wholesaleSet))))
                .then(Commands.literal("entfernen")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ShopCommands::wholesaleRemove)))
                .then(Commands.literal("einkauf")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .then(Commands.argument("menge", IntegerArgumentType.integer(1, 2304))
                            .executes(ShopCommands::wholesaleBuy)))))
            // Retail prices are editable by owner within shop, or by the server console.
            // No item delivery or player debit takes place through these commands.
            .then(Commands.literal("angebot")
                .then(Commands.argument("item", IdentifierArgument.id())
                    .then(Commands.argument("menge", IntegerArgumentType.integer(1, 2304))
                        .executes(ShopCommands::retailQuote))))
            .then(Commands.literal("kaufen")
                .then(Commands.argument("item", IdentifierArgument.id())
                    .then(Commands.argument("menge", IntegerArgumentType.integer(1, 64))
                        .executes(ShopCommands::retailBuy))))
            .then(Commands.literal("verkaufspreis")
                .then(Commands.literal("liste").executes(ShopCommands::retailList))
                .then(Commands.literal("setzen")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .then(Commands.argument("token", LongArgumentType.longArg(1, 1_000_000L))
                            .executes(ShopCommands::retailSet))))
                .then(Commands.literal("entfernen")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ShopCommands::retailRemove))))
            .then(Commands.literal("ankauf")
                .then(Commands.literal("liste").executes(ShopCommands::buybackList))
                .then(Commands.literal("vorschlaege").executes(ShopCommands::buybackProposals))
                .then(Commands.literal("vorschlagen").requires(TokenCommands::consoleOnly)
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .then(Commands.argument("token", LongArgumentType.longArg(1, 1_000_000L))
                            .executes(ShopCommands::buybackPropose))))
                .then(Commands.literal("annehmen")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ctx -> buybackDecision(ctx, true))))
                .then(Commands.literal("ablehnen")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ctx -> buybackDecision(ctx, false))))
                .then(Commands.literal("verkaufen")
                    .then(Commands.argument("menge", IntegerArgumentType.integer(1, 64))
                        .executes(ShopCommands::buybackSell)))
                .then(Commands.literal("freigabe").requires(TokenCommands::consoleOnly)
                    .then(Commands.literal("an").executes(ctx -> buybackEnable(ctx, true)))
                    .then(Commands.literal("aus").executes(ctx -> buybackEnable(ctx, false))))
                .then(Commands.literal("deaktivieren")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ShopCommands::buybackDisable))))
            .then(Commands.literal("journal").requires(TokenCommands::consoleOnly).executes(ShopCommands::journalOverview)
                .then(Commands.literal("pruefen")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(ShopCommands::reviewBuyback)))
                .then(Commands.literal("abschliessen")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("bestaetigung", StringArgumentType.word()).executes(ShopCommands::finalizeBuyback)))))
            .then(Commands.literal("kaufjournal").requires(TokenCommands::consoleOnly).executes(ShopCommands::retailJournalOverview))
            .then(Commands.literal("status").requires(TokenCommands::consoleOnly).executes(ShopCommands::status))
            .then(Commands.literal("info").executes(ctx -> feedback(ctx.getSource(),
                "Shop: " + (ErzmarkenMod.shopState == null ? "Status unbekannt" : ErzmarkenMod.shopState.hudStatus().replaceAll("§[0-9a-fk-or]", "")))))
            .then(Commands.literal("oeffnen").executes(ctx -> changeOpen(ctx, true)))
            .then(Commands.literal("schliessen").executes(ctx -> changeOpen(ctx, false)))
            .then(Commands.literal("creative").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                String message = ShopCatalogueMenu.open(player);
                return feedback(ctx.getSource(), message);
            }))
            // Konfigurationsrechte sind ausschliesslich fuer die Serverkonsole.
            .then(Commands.literal("katalog").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("list").executes(ShopCommands::catalogueList))
                .then(Commands.literal("clear").executes(ShopCommands::catalogueClear))
                .then(Commands.literal("add")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ctx -> catalogueAdd(ctx, 1))
                        .then(Commands.argument("menge", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> catalogueAdd(ctx, IntegerArgumentType.getInteger(ctx, "menge"))))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .executes(ShopCommands::catalogueRemove))))
            .then(Commands.literal("preis")
                .then(Commands.argument("artikel", StringArgumentType.word())
                    .then(Commands.argument("token", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                        .executes(ShopCommands::price))))
            .then(Commands.literal("add")
                .then(Commands.argument("artikel", StringArgumentType.word())
                    .then(Commands.argument("item", IdentifierArgument.id())
                        .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 64))
                            .then(Commands.argument("token", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                                .executes(ShopCommands::add))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("artikel", StringArgumentType.word())
                    .executes(ShopCommands::remove)))
            .then(Commands.literal("owner").requires(TokenCommands::consoleOnly)
                .then(Commands.argument("spieler", EntityArgument.player())
                    .executes(ShopCommands::owner)))
            .then(Commands.literal("clearowner").requires(TokenCommands::consoleOnly)
                .executes(ctx -> {
                    try {
                        ErzmarkenMod.shopState.setOwner(null);
                        return feedback(ctx.getSource(), "Shopbesitzer entfernt.");
                    } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
                }))
            // Koordinaten direkt per Konsole setzen; kein OP-Spieler zum Markieren noetig.
            .then(Commands.literal("region").requires(TokenCommands::consoleOnly)
                .then(Commands.argument("dimension", IdentifierArgument.id())
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                            .then(Commands.argument("z1", IntegerArgumentType.integer())
                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                    .then(Commands.argument("y2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                            .executes(ShopCommands::region))))))))));
    }

    private static boolean ownerApproval(CommandSourceStack source) {
        // Console can create proposals but cannot impersonate the shop owner's confirmation.
        ServerPlayer player = source.getPlayer();
        ShopState st = ErzmarkenMod.shopState;
        return player != null && st != null && st.isOwner(player) && st.contains(player);
    }
    private static int buybackEnable(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        try {
            if (enabled) {
                ShopTransactionJournal.ensureClear();
                if (!Phase2TestAccess.allowed(ctx.getSource().getServer(), null, Phase2TestPolicy.Feature.BUYBACK))
                    return fail(ctx.getSource(), "Spielerankauf bleibt ausserhalb der vorbereiteten Testwelt gesperrt.");
            }
            ErzmarkenMod.shopState.setBuybackTradesEnabled(enabled);
            return feedback(ctx.getSource(), "Spielerankauf " + (enabled ? "aktiviert" : "gesperrt")
                + ". WARNUNG: Noch keine Absturz-Transaktionssicherheit fuer Token- und Spielerinventar!");
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
    }

    private static int buybackSell(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx.getSource(), "Nur Spieler koennen Waren abgeben");
        try {
            return feedback(ctx.getSource(), ShopBuybackService.sellMainHand(player,
                IntegerArgumentType.getInteger(ctx, "menge")));
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Ankauf nicht bestaetigt; Belege vor weiteren Versuchen pruefen", ex);
            return fail(ctx.getSource(), "Ankauf nicht abgeschlossen. Bereits gespeicherte Teilschritte sind moeglich. "
                + "Nicht erneut verkaufen; Serververwaltung muss die Buchungsbelege pruefen.");
        }
          catch (IllegalArgumentException | IllegalStateException ex) { return fail(ctx.getSource(), ex.getMessage()); }
    }

    private static int buybackList(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        feedback(ctx.getSource(), "Aktive Ankaufspreise (noch KEINE echten Spieler-Ankaeufe):");
        st.buybackPrices().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> feedback(ctx.getSource(), e.getKey() + " = " + e.getValue() + " Token/Stueck"));
        return 1;
    }
    private static int buybackProposals(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        feedback(ctx.getSource(), "Offene Ankaufspreis-Vorschlaege:");
        st.buybackProposals().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> feedback(ctx.getSource(), e.getKey() + " = " + e.getValue().suggestedUnitPrice()
                + " Token/Stueck (" + e.getValue().rationale() + ")"));
        return 1;
    }
    private static int buybackPropose(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        Identifier id = IdentifierArgument.getId(ctx, "item");
        if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) return fail(ctx.getSource(), "Unbekanntes Item");
        try {
            st.proposeBuyback(id.toString(), LongArgumentType.getLong(ctx, "token"), "Vorschlag durch Serverkonsole; KI-Anbindung folgt");
            return feedback(ctx.getSource(), "Ankaufsvorschlag gespeichert: " + id
                + ". Shopbesitzer muss /shop ankauf annehmen " + id + " ausfuehren.");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
          catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
    }
    private static int buybackDecision(CommandContext<CommandSourceStack> ctx, boolean approve) {
        if (!ownerApproval(ctx.getSource())) return fail(ctx.getSource(), "Nur der Shopbesitzer INNERHALB des Shopbereichs darf bestaetigen oder ablehnen");
        ShopState st = ErzmarkenMod.shopState;
        Identifier item = IdentifierArgument.getId(ctx, "item");
        try {
            if (approve) st.approveBuyback(item.toString()); else st.rejectBuyback(item.toString());
            return feedback(ctx.getSource(), approve ? "Ankaufspreis genehmigt (Ankauf noch gesperrt)" : "Vorschlag abgelehnt");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
          catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
    }
    private static int buybackDisable(CommandContext<CommandSourceStack> ctx) {
        if (!ownerApproval(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer innerhalb des Shops");
        try {
            ErzmarkenMod.shopState.disableBuyback(IdentifierArgument.getId(ctx, "item").toString());
            return feedback(ctx.getSource(), "Ankauf deaktiviert");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
    }

    private static int retailList(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur der Shopbesitzer im Shop oder die Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        feedback(ctx.getSource(), "Verkaufspreise (noch KEINE aktiven Spielerverkaeufe):");
        st.retailPrices().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> feedback(ctx.getSource(), e.getKey() + ": " + e.getValue()
                + " Token/Stueck, virtuell auf Lager: " + st.wholesaleStock(e.getKey())));
        return 1;
    }
    private static int retailSet(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur der Shopbesitzer im Shop oder die Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        Identifier item = IdentifierArgument.getId(ctx, "item");
        if (BuiltInRegistries.ITEM.getOptional(item).isEmpty()) return fail(ctx.getSource(), "Unbekanntes Item");
        try {
            long price = LongArgumentType.getLong(ctx, "token");
            st.setRetailUnitPrice(item.toString(), price);
            return feedback(ctx.getSource(), "Verkaufspreis gespeichert: " + item + " = " + price
                + " Token/Stueck (noch keine automatische Ausgabe)");
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
          catch (IllegalArgumentException ex) { return fail(ctx.getSource(), ex.getMessage()); }
    }
    private static int retailRemove(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur der Shopbesitzer im Shop oder die Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        try {
            Identifier item = IdentifierArgument.getId(ctx, "item");
            st.removeRetailUnitPrice(item.toString());
            return feedback(ctx.getSource(), "Verkaufspreis entfernt: " + item);
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
    }

    private static int wholesaleHistory(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        var history = st.purchaseHistory();
        feedback(ctx.getSource(), "Letzte Grosshandelseinkaeufe (" + history.size() + " Belege gespeichert):");
        for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) {
            var r = history.get(i);
            feedback(ctx.getSource(), "Beleg " + r.id().substring(0, Math.min(8, r.id().length())) + " | "
                + r.itemId() + " x" + r.quantity() + " / " + r.totalPrice()
                + " Token | Kasse danach: " + r.balanceAfter());
        }
        return 1;
    }

    private static int wholesaleStock(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        feedback(ctx.getSource(), "Virtueller Grosshandelsbestand (noch NICHT zur Entnahme freigegeben):");
        st.wholesaleStock().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> feedback(ctx.getSource(), e.getKey() + " x" + e.getValue()));
        return 1;
    }
    private static int wholesaleBuy(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Konsole");
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        if (!st.isOpen()) return fail(ctx.getSource(), "Nur bei geoeffnetem Shop");
        Identifier id = IdentifierArgument.getId(ctx, "item");
        if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) return fail(ctx.getSource(), "Unbekanntes Item");
        int count = IntegerArgumentType.getInteger(ctx, "menge");
        try {
            long amountPaid = st.buyWholesale(id.toString(), count);
            return feedback(ctx.getSource(), "Einkauf verbucht: " + count + "x " + id + ", -" + amountPaid
                + " Shop-Token. Virtuelles Lager: " + st.wholesaleStock(id.toString())
                + ". Warenentnahme ist bis zur sicheren Inventar-Anbindung GESPERRT.");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
          catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) { return fail(ctx.getSource(), e.getMessage()); }
    }

    private static int retailBuy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx.getSource(), "Nur Spieler koennen Waren kaufen");
        try {
            return feedback(ctx.getSource(), ShopRetailService.buy(player,
                IdentifierArgument.getId(ctx, "item").toString(), IntegerArgumentType.getInteger(ctx, "menge")));
        } catch (IOException ex) {
            ErzmarkenMod.LOGGER.error("Kauf nicht bestaetigt; Verkaufsbelege pruefen", ex);
            return fail(ctx.getSource(), "Kauf nicht abgeschlossen. Gespeicherte Teilschritte sind moeglich. "
                + "Serververwaltung muss vor einem erneuten Kauf die Verkaufsbelege pruefen.");
        } catch (IllegalArgumentException | IllegalStateException ex) { return fail(ctx.getSource(), ex.getMessage()); }
    }

    private static int retailJournalOverview(CommandContext<CommandSourceStack> ctx) {
        try {
            var world = ctx.getSource().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            var entries = ShopRetailJournal.inspect(world);
            feedback(ctx.getSource(), "Verkaufsjournal: " + entries.size() + " Belege; Anzeige der letzten 20. Keine automatische Wiederholung.");
            entries.stream().limit(20).forEach(entry -> feedback(ctx.getSource(), "Beleg " + entry.id()
                + " | " + entry.stage() + " | Spieler " + entry.playerId() + " | " + entry.quantity()
                + "x " + entry.itemId() + " | " + entry.total() + " Token"));
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            return fail(ctx.getSource(), "Verkaufsjournal nicht lesbar; Buchungsdateien pruefen: " + failure.getMessage());
        }
    }

    /** Price preview only: does NOT purchase, debit tokens or deliver items. */
    private static int retailQuote(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        String item = IdentifierArgument.getId(ctx, "item").toString();
        int amount = IntegerArgumentType.getInteger(ctx, "menge");
        try {
            ShopState.RetailQuote quote = st.retailQuote(item, amount);
            return feedback(ctx.getSource(), "Unverbindliches Angebot: " + item + " x" + amount
                + " zu " + quote.unitPrice() + " Token = " + quote.totalPrice()
                + " Token | Shopbestand: " + quote.availableStock()
                + " | aktuell lieferbar: " + (quote.available() ? "ja" : "nein")
                + " | KEIN Kauf, KEINE Tokenabbuchung");
        } catch (IllegalArgumentException | ArithmeticException ex) {
            return fail(ctx.getSource(), ex.getMessage());
        }
    }

    private static int wholesaleList(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        feedback(ctx.getSource(), "Shopkasse: " + st.shopWallet() + " Token | Grosshandelspreise: " + st.wholesalePrices().size());
        st.wholesalePrices().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> feedback(ctx.getSource(), e.getKey() + " = " + e.getValue() + " Token / Stueck"));
        return 1;
    }
    private static int wholesaleSet(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        Identifier item = IdentifierArgument.getId(ctx, "item");
        if (item == null || BuiltInRegistries.ITEM.getOptional(item).isEmpty())
            return fail(ctx.getSource(), "Unbekannte Minecraft-Item-ID");
        long value = LongArgumentType.getLong(ctx, "token");
        try {
            st.setWholesaleUnitPrice(item.toString(), value);
            return feedback(ctx.getSource(), "Grosshandelspreis gespeichert: " + item + " = " + value + " Token/Stueck. Noch KEIN aktiver Einkauf.");
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
          catch (IllegalArgumentException ex) { return fail(ctx.getSource(), ex.getMessage()); }
    }
    private static int wholesaleRemove(CommandContext<CommandSourceStack> ctx) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        try {
            Identifier item = IdentifierArgument.getId(ctx, "item");
            st.removeWholesaleUnitPrice(item.toString());
            return feedback(ctx.getSource(), "Grosshandelspreis entfernt: " + item);
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, String mode) {
        ShopState st = ErzmarkenMod.shopState;
        if (st == null) return fail(ctx.getSource(), "Shopdaten fehlen");
        try {
            st.setShopMode(mode);
            return feedback(ctx.getSource(), "Shop-Betriebsart gespeichert: " + mode
                + ". Achtung: Inventartrennung und Kassenhandel sind noch NICHT aktiv.");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
          catch (IllegalArgumentException | IllegalStateException e) { return fail(ctx.getSource(), e.getMessage()); }
    }

    private static int helper(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Serverkonsole.");
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten fehlen.");
        ServerPlayer target = EntityArgument.getPlayer(ctx, "spieler");
        try {
            if (add) ErzmarkenMod.shopState.addHelper(target.getUUID());
            else ErzmarkenMod.shopState.removeHelper(target.getUUID());
            return feedback(ctx.getSource(), target.getName().getString() + (add ? " als Bauhelfer eingesetzt." : " als Bauhelfer entfernt."));
        } catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
          catch (IOException e) { return ioError(ctx.getSource(), e); }
    }
    private static int protection(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Besitzer im Shop oder Konsole.");
        try {
            ErzmarkenMod.shopState.setProtectedRegion(enabled);
            return feedback(ctx.getSource(), "Shop-Schutz: " + (enabled ? "AKTIV" : "DEAKTIVIERT") + ".");
        } catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
          catch (IOException e) { return ioError(ctx.getSource(), e); }
    }
    private static int warehouse(CommandContext<CommandSourceStack> ctx, boolean add) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten fehlen.");
        var pos = new net.minecraft.core.BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
        try {
            ErzmarkenMod.shopState.setWarehouse(pos, add);
            return feedback(ctx.getSource(), "Shop-Lager: " + pos + (add ? " gesperrt fuer Bauhelfer." : " wieder normale Kiste."));
        } catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
          catch (IOException e) { return ioError(ctx.getSource(), e); }
    }

    private static int region(CommandContext<CommandSourceStack> ctx) {
        String dimName = IdentifierArgument.getId(ctx, "dimension").toString();
        boolean exists = false;
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimName)) {
                exists = true;
                break;
            }
        }
        if (!exists) return fail(ctx.getSource(), "Unbekannte Dimension: " + dimName + " (z.B. minecraft:overworld)");
        int[] corner1 = {
            IntegerArgumentType.getInteger(ctx, "x1"),
            IntegerArgumentType.getInteger(ctx, "y1"),
            IntegerArgumentType.getInteger(ctx, "z1")
        };
        int[] corner2 = {
            IntegerArgumentType.getInteger(ctx, "x2"),
            IntegerArgumentType.getInteger(ctx, "y2"),
            IntegerArgumentType.getInteger(ctx, "z2")
        };
        try {
            ErzmarkenMod.shopState.setRegion(dimName, corner1, corner2);
            return feedback(ctx.getSource(), "Shopbereich gespeichert in " + dimName + ": "
                + format(corner1) + " bis " + format(corner2) + ".");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
    }

    private static int catalogueList(CommandContext<CommandSourceStack> ctx) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten noch nicht geladen.");
        var catalog = ErzmarkenMod.shopState.catalogue();
        if (catalog.isEmpty()) return feedback(ctx.getSource(), "Katalog leer (0 von 54). /shop katalog add minecraft:beacon");
        feedback(ctx.getSource(), "Freigegebene Katalog-Items (" + catalog.size() + "/54):");
        for (ShopState.CatalogueItem e : catalog) feedback(ctx.getSource(), e.itemId() + " x" + e.amount());
        return 1;
    }
    private static int catalogueAdd(CommandContext<CommandSourceStack> ctx, int amount) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten noch nicht geladen.");
        String raw = IdentifierArgument.getId(ctx, "item").toString();
        Identifier id = Identifier.tryParse(raw);
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
            return fail(ctx.getSource(), "Ungueltige Item-ID: " + raw);
        }
        String normalized = id.toString();
        // Auch Nicht-Stapelbares darf nur mit der moeglichen Maximalmenge ausgegeben werden.
        int maxCount = BuiltInRegistries.ITEM.getOptional(id).orElseThrow().getDefaultMaxStackSize();
        if (amount > maxCount) return fail(ctx.getSource(), "Maximal " + maxCount + " Stueck fuer dieses Item.");
        try {
            ErzmarkenMod.shopState.catalogueAdd(normalized, amount);
            return feedback(ctx.getSource(), "Katalog: " + normalized + " x" + amount + " freigegeben.");
        } catch (IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
          catch (IOException e) { return ioError(ctx.getSource(), e); }
    }
    private static int catalogueRemove(CommandContext<CommandSourceStack> ctx) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten noch nicht geladen.");
        Identifier id = IdentifierArgument.getId(ctx, "item");
        if (id == null) return fail(ctx.getSource(), "Item-ID ungueltig.");
        try {
            return ErzmarkenMod.shopState.catalogueRemove(id.toString())
                ? feedback(ctx.getSource(), "Katalog: " + id + " entfernt.")
                : fail(ctx.getSource(), "Item ist nicht im Katalog.");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
    }
    private static int catalogueClear(CommandContext<CommandSourceStack> ctx) {
        if (ErzmarkenMod.shopState == null) return fail(ctx.getSource(), "Shopdaten noch nicht geladen.");
        try {
            ErzmarkenMod.shopState.catalogueClear();
            return feedback(ctx.getSource(), "Katalog vollstaendig geleert.");
        } catch (IOException e) { return ioError(ctx.getSource(), e); }
    }

    private static int changeOpen(CommandContext<CommandSourceStack> ctx, boolean open) {
        ServerPlayer actor = ctx.getSource().getPlayer();
        ErzmarkenMod.LOGGER.info("Shopstatus-Befehl: offen={} Quelle={}", open,
            actor == null ? "Konsole" : actor.getUUID());
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(),
            "Nur der Shopbesitzer im Shopgebaeude oder die Serverkonsole darf den Laden oeffnen/schliessen.");
        ShopState state = ErzmarkenMod.shopState;
        if (state == null) return fail(ctx.getSource(), "Shopdaten sind nicht geladen.");
        if (!state.isComplete() || state.owner() == null) return fail(ctx.getSource(),
            "Shop wird gebaut. Bitte zuerst den Bereich und den Besitzer festlegen.");
        try {
            final java.util.UUID owner;
            try { owner = java.util.UUID.fromString(state.owner()); }
            catch (IllegalArgumentException invalidOwner) { throw new IOException("Ungueltige Shopbesitzer-UUID", invalidOwner); }
            var server = ctx.getSource().getServer();
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
            boolean changed = ShopLifecycleController.change(open, new ShopLifecycleController.Port() {
                public ShopInventoryVault.Phase phase() throws IOException { return ShopInventorySessions.phase(server, owner); }
                public boolean isOpen() { return state.isOpen(); }
                public void preflight(boolean opening, ShopInventoryVault.Phase phase) throws IOException {
                    state.requirePersistedState();
                    if (opening || phase == ShopInventoryVault.Phase.SHOP_ACTIVE) {
                        if (ownerPlayer == null) throw new IOException("Inventarwechsel gesperrt: Shopbesitzer nicht online");
                    }
                    if (opening && !ShopInventorySessions.ENABLED
                            && !Phase2TestAccess.allowed(server, owner, Phase2TestPolicy.Feature.INVENTORY))
                        throw new IOException("Shop-Oeffnung mit Inventarwechsel wartet auf technische Freigabe");
                    if (opening && (!state.contains(ownerPlayer) || ownerPlayer.isCreative() || ownerPlayer.isSpectator()))
                        throw new IOException("Shopbesitzer muss im Shopbereich und im Ueberlebensmodus sein");
                    if (!opening) ShopInventorySessions.assertMayCloseOwner(server, owner);
                }
                public void enter() throws IOException { ShopInventorySessions.open(ownerPlayer); }
                public void exit() throws IOException { ShopInventorySessions.close(ownerPlayer); }
                public void setOpen(boolean value) throws IOException { state.setOpen(value); }
                public void quarantineOpeningFailure(IOException failure) { ShopInventorySessions.quarantineOpeningFailure(ownerPlayer, failure); }
            });
            ErzmarkenMod.LOGGER.info("Shopstatus synchronisiert: offen={} geaendert={}", state.isOpen(), changed);
            SidebarSupport.updateShopStatusNow(ctx.getSource().getServer(), ErzmarkenMod.tokens, state);
            if (!changed) return feedback(ctx.getSource(), open
                ? "Shop ist bereits geoeffnet; Inventar unveraendert."
                : "Shop ist bereits geschlossen; Inventar unveraendert.");
            var notification = Component.literal("§6[Dullie Hausen] §eDer Adminshop ist jetzt "
                + (open ? "§aGEÖFFNET" : "§cGESCHLOSSEN") + "§e.");
            for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                player.sendSystemMessage(notification);
            }
            return feedback(ctx.getSource(), open ? "Shop geoeffnet; Arbeitsinventar geladen."
                : "Shop geschlossen; privates Inventar aktiv.");
        } catch (IOException failure) {
            ErzmarkenMod.LOGGER.error("Shopstatus-/Inventarwechsel fehlgeschlagen", failure);
            SidebarSupport.updateShopStatusNow(ctx.getSource().getServer(), ErzmarkenMod.tokens, state);
            return fail(ctx.getSource(), "Wechsel nicht abgeschlossen: " + failure.getMessage()
                + ". Aktueller Shopstatus: " + state.hudStatus().replaceAll("§[0-9a-fk-or]", "")
                + ". Bei Inventarfehlern nicht erneut versuchen; Serververwaltung informieren.");
        }
    }

    private static int journalOverview(CommandContext<CommandSourceStack> ctx) {
        try {
            var server = ctx.getSource().getServer();
            var audit = ShopJournalAudit.inspect(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            java.util.Set<String> receiptIds = new java.util.HashSet<>();
            if (ErzmarkenMod.shopState != null)
                for (var receipt : ErzmarkenMod.shopState.buybackHistory()) receiptIds.add(receipt.id());
            var overview = ShopRecoveryAdminOverview.inspect(audit, receiptIds);
            for (String line : ShopRecoveryAdminOverview.adminLines(overview, 12))
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            var content = ShopRecoveryContentAudit.inspect(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), audit,
                ErzmarkenMod.shopState == null ? java.util.List.of() : ErzmarkenMod.shopState.buybackHistory(), 12);
            for (String line : content.lines())
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            var tokenReceipts = ShopTokenReceiptAudit.inspect(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), audit, 12);
            for (String line : tokenReceipts.lines())
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            var diskEvidence = ShopPersistedEvidenceAudit.inspect(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), audit, 12);
            for (String line : diskEvidence.lines())
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            return 1;
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
    }

    private static int reviewBuyback(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        try {
            ShopBuybackReceiptLink.requireValidTransactionId(id);
            var server = ctx.getSource().getServer();
            var world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            var plan = ShopBuybackIntentStore.read(world, id);
            java.util.UUID player = java.util.UUID.fromString(plan.expected().playerId());
            boolean online = server.getPlayerList().getPlayer(player) != null;
            net.minecraft.nbt.CompoundTag saved = null;
            if (!online) {
                try { saved = MinecraftSavedInventory.readFileReadOnly(ShopPlayerDataPaths.resolveOffline(world, player)); }
                catch (IOException unreadable) { /* Report UNAVAILABLE, still compare financial files. */ }
            }
            final var comparison = saved;
            ShopBuybackRecoveryReview.SnapshotVerifier verifier = comparison == null ? null
                : expected -> MinecraftSavedInventory.verify(comparison, player, server.registryAccess(), expected);
            var report = ShopBuybackRecoveryReview.inspect(world, id, online, verifier);
            for (String line : report.lines()) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            if (report.financesMatch() && report.inventory() == ShopBuybackRecoveryReview.InventoryMatch.AFTER_ONLY) {
                try {
                    var ticket = ShopBuybackRecoveryFinalizer.inspect(world, id, online,
                        (bytes, uuid, expected) -> MinecraftSavedInventory.verifyCompressed(bytes, uuid, server.registryAccess(), expected));
                    ctx.getSource().sendSuccess(() -> Component.literal("Pruefkennung fuer genau diese Dateien: " + ticket.confirmation()), false);
                    if (!ShopBuybackRecoveryFinalizer.COMMAND_ENABLED
                            && !Phase2TestAccess.allowed(server, null, Phase2TestPolicy.Feature.RECOVERY))
                        ctx.getSource().sendSuccess(() -> Component.literal("Der Wiederherstellungsabschluss ist noch nicht freigegeben."), false);
                    else ctx.getSource().sendSuccess(() -> Component.literal("Bewusst bestaetigen: /shop journal abschliessen "
                        + id + " " + ticket.confirmation()), false);
                } catch (IOException changed) {
                    ctx.getSource().sendSuccess(() -> Component.literal("Keine Abschlusskennung: Dateistand nicht eindeutig bestaetigt."), false);
                }
            }
            return 1;
        } catch (IllegalArgumentException invalid) { return fail(ctx.getSource(), "Ungueltige Buchungs-ID"); }
        catch (IOException invalid) { return fail(ctx.getSource(), "Buchungsplan fehlt oder ist nicht lesbar; alte Faelle nicht automatisch rekonstruieren."); }
    }

    private static int finalizeBuyback(CommandContext<CommandSourceStack> ctx) {
        if (!ShopBuybackRecoveryFinalizer.COMMAND_ENABLED
                && !Phase2TestAccess.allowed(ctx.getSource().getServer(), null, Phase2TestPolicy.Feature.RECOVERY))
            return fail(ctx.getSource(), "Wiederherstellungsabschluss ist bis zur Serverpruefung gesperrt.");
        String id = StringArgumentType.getString(ctx, "id");
        String confirmation = StringArgumentType.getString(ctx, "bestaetigung");
        try {
            ShopBuybackReceiptLink.requireValidTransactionId(id);
            var server = ctx.getSource().getServer();
            var world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            var plan = ShopBuybackIntentStore.read(world, id);
            java.util.UUID player = java.util.UUID.fromString(plan.expected().playerId());
            if (!ShopBuybackRecoveryFinalizer.COMMAND_ENABLED
                    && !Phase2TestAccess.allowed(server, player, Phase2TestPolicy.Feature.RECOVERY))
                return fail(ctx.getSource(), "Buchung gehoert nicht zu einem freigegebenen Testkonto.");
            ShopBuybackRecoveryFinalizer.finishConfirmed(world, id, confirmation,
                () -> server.getPlayerList().getPlayer(player) != null,
                (bytes, uuid, expected) -> MinecraftSavedInventory.verifyCompressed(bytes, uuid, server.registryAccess(), expected),
                () -> {
                    if (ErzmarkenMod.tokens == null || ErzmarkenMod.shopState == null) throw new IOException("Finanzdaten fehlen");
                    ErzmarkenMod.tokens.requirePersistedState(); ErzmarkenMod.shopState.requirePersistedState();
                });
            return feedback(ctx.getSource(), "Wiederherstellungsabschluss dokumentiert und Journal archiviert: " + id);
        } catch (IllegalArgumentException invalid) { return fail(ctx.getSource(), "Ungueltige Buchungs-ID"); }
        catch (IOException invalid) { return ioError(ctx.getSource(), invalid); }
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ShopState s = ErzmarkenMod.shopState;
        if (s == null) return feedback(ctx.getSource(), "Shop: Status unbekannt (Fehler beim Laden der Shopdaten).");
        String owner = s.owner() == null ? "nicht gesetzt" : s.owner();
        if (!s.isComplete()) return feedback(ctx.getSource(), "Shopbereich nicht vollstaendig. Besitzer-UUID: " + owner);
        return feedback(ctx.getSource(), "Shop " + (s.isOpen() ? "GEÖFFNET" : "GESCHLOSSEN") + " | " + s.dimension() + " | von " + format(s.first())
            + " bis " + format(s.second()) + " | Besitzer-UUID: " + owner);
    }
    private static String format(int[] p) { return p[0] + "," + p[1] + "," + p[2]; }
    private static int owner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer newOwner = EntityArgument.getPlayer(ctx, "spieler");
        try {
            ErzmarkenMod.shopState.setOwner(newOwner.getUUID());
            return feedback(ctx.getSource(), newOwner.getName().getString() + " ist nun der einzige Shopbesitzer (keine OP-Rechte vergeben).");
        } catch (IOException ex) { return ioError(ctx.getSource(), ex); }
    }
    private static boolean canEdit(CommandSourceStack source) {
        if (ErzmarkenMod.shopState == null) return false;
        if (TokenCommands.consoleOnly(source)) return true;
        try {
            ServerPlayer player = source.getPlayerOrException();
            return ErzmarkenMod.shopState.isOwner(player) && ErzmarkenMod.shopState.contains(player);
        } catch (CommandSyntaxException ignored) { return false; }
    }
    private static int price(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Serverkonsole darf Preise aendern.");
        String id = StringArgumentType.getString(ctx, "artikel");
        long value = LongArgumentType.getLong(ctx, "token");
        TokenConfig.ShopEntry entry = ErzmarkenMod.config.shopItem(id);
        if (entry == null) return fail(ctx.getSource(), "Artikel-ID unbekannt. /token shop");
        long old = entry.price;
        entry.price = value;
        try {
            ErzmarkenMod.saveConfig();
            return feedback(ctx.getSource(), "Preis fuer " + id + " geaendert: " + value + " Token.");
        } catch (IOException ex) { entry.price = old; return ioError(ctx.getSource(), ex); }
    }
    private static int add(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Serverkonsole darf Angebote anlegen.");
        String id = StringArgumentType.getString(ctx, "artikel");
        if (!id.matches("[a-z0-9_]+") || ErzmarkenMod.config.shopItem(id) != null)
            return fail(ctx.getSource(), "Ungueltige oder bereits vorhandene Artikel-ID (nur a-z, 0-9, _).");
        String rawItem = IdentifierArgument.getId(ctx, "item").toString();
        Identifier itemId = Identifier.tryParse(rawItem);
        if (itemId == null || BuiltInRegistries.ITEM.getOptional(itemId).isEmpty())
            return fail(ctx.getSource(), "Unbekannte Item-ID: " + rawItem);
        // Besitzer darf nur Artikel in den Verkauf aufnehmen, die in der Konsole freigegeben wurden.
        if (!TokenCommands.consoleOnly(ctx.getSource())
                && ErzmarkenMod.shopState.catalogue().stream().noneMatch(e -> e.itemId().equals(itemId.toString()))) {
            return fail(ctx.getSource(), "Dieser Artikel wurde von der Serverkonsole nicht freigegeben.");
        }
        int amount = IntegerArgumentType.getInteger(ctx, "anzahl");
        int maxStack = BuiltInRegistries.ITEM.getOptional(itemId).orElseThrow().getDefaultMaxStackSize();
        if (amount > maxStack) return fail(ctx.getSource(), "Dieses Item erlaubt hoechstens " + maxStack + " pro Angebot.");
        long tokens = LongArgumentType.getLong(ctx, "token");
        TokenConfig.ShopEntry entry = new TokenConfig.ShopEntry(id, id, rawItem, amount, tokens);
        ErzmarkenMod.config.shop.add(entry);
        try {
            ErzmarkenMod.saveConfig();
            return feedback(ctx.getSource(), "Artikel " + id + " (" + rawItem + " x" + amount + ") fuer " + tokens + " Token hinzugefuegt.");
        } catch (IOException ex) { ErzmarkenMod.config.shop.remove(entry); return ioError(ctx.getSource(), ex); }
    }
    private static int remove(CommandContext<CommandSourceStack> ctx) {
        if (!canEdit(ctx.getSource())) return fail(ctx.getSource(), "Nur Shopbesitzer im Shop oder Serverkonsole darf Angebote entfernen.");
        TokenConfig.ShopEntry entry = ErzmarkenMod.config.shopItem(StringArgumentType.getString(ctx, "artikel"));
        if (entry == null) return fail(ctx.getSource(), "Unbekannter Artikel.");
        int index = ErzmarkenMod.config.shop.indexOf(entry);
        ErzmarkenMod.config.shop.remove(index);
        try {
            ErzmarkenMod.saveConfig();
            return feedback(ctx.getSource(), "Artikel " + entry.id + " entfernt.");
        } catch (IOException ex) { ErzmarkenMod.config.shop.add(index, entry); return ioError(ctx.getSource(), ex); }
    }
    private static int feedback(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[Dullie Hausen] " + text), false);
        return 1;
    }
    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[Dullie Hausen] " + text));
        return 0;
    }
    private static int ioError(CommandSourceStack source, IOException ex) {
        ErzmarkenMod.LOGGER.error("Shopdaten konnten nicht gespeichert werden", ex);
        return fail(source, "Speicherfehler. Serverlog pruefen. Aenderung nicht uebernommen.");
    }
}
