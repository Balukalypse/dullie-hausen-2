package de.baluserver.erzmarken;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class TokenCommands {
    private TokenCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("token")
            .executes(ctx -> balance(ctx.getSource().getPlayerOrException()))
            .then(Commands.literal("balance")
                .executes(ctx -> balance(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("spieler", EntityArgument.player())
                    .requires(TokenCommands::consoleOnly)
                    .executes(ctx -> otherBalance(ctx, EntityArgument.getPlayer(ctx, "spieler")))))
            .then(Commands.literal("shop").executes(ctx -> shop(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("buy")
                .then(Commands.argument("artikel", StringArgumentType.word())
                    .executes(ctx -> buy(ctx, 1))
                    .then(Commands.argument("menge", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> buy(ctx, IntegerArgumentType.getInteger(ctx, "menge"))))))
            .then(Commands.literal("pay")
                .then(Commands.argument("spieler", EntityArgument.player())
                    .then(Commands.argument("betrag", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                        .executes(TokenCommands::pay))))
            .then(Commands.literal("trade")
                .then(Commands.argument("spieler", EntityArgument.player())
                    .then(Commands.argument("preis", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                        .executes(TokenCommands::trade))))
            .then(Commands.literal("accept")
                .then(Commands.argument("spieler", EntityArgument.player()).executes(TokenCommands::accept)))
            .then(Commands.literal("cancel").executes(ctx -> {
                ServerPlayer sender = ctx.getSource().getPlayerOrException();
                ErzmarkenMod.TRADES.cancel(sender.getUUID());
                msg(sender, "§7Handelsangebot abgebrochen.");
                return 1;
            }))
            .then(Commands.literal("admin").requires(TokenCommands::consoleOnly)
                .then(Commands.literal("reload").executes(ctx -> {
                    if (!ErzmarkenMod.reloadConfig()) return error(ctx.getSource(), "Konfiguration konnte nicht geladen werden. Serverlog pruefen.");
                    return success(ctx.getSource(), "Konfiguration neu geladen.");
                }))
                .then(Commands.literal("add").then(Commands.argument("spieler", EntityArgument.player())
                    .then(Commands.argument("betrag", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                        .executes(ctx -> modify(ctx, "add")))))
                .then(Commands.literal("take").then(Commands.argument("spieler", EntityArgument.player())
                    .then(Commands.argument("betrag", LongArgumentType.longArg(1, TokenStore.MAX_TOKENS))
                        .executes(ctx -> modify(ctx, "take")))))
                .then(Commands.literal("set").then(Commands.argument("spieler", EntityArgument.player())
                    .then(Commands.argument("betrag", LongArgumentType.longArg(0, TokenStore.MAX_TOKENS))
                        .executes(ctx -> modify(ctx, "set")))))));
    }

    /** Nur der echte Server-Konsolen-CommandSource; weder OP noch Commandblock noch RCON. */
    static boolean consoleOnly(CommandSourceStack src) {
        return src.getEntity() == null && "Server".equals(src.getTextName());
    }
    private static void msg(ServerPlayer player, String text) { player.sendSystemMessage(Component.literal("§6[Dullie Hausen] §r" + text)); }
    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[Dullie Hausen] " + message), false);
        return 1;
    }
    private static int error(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[Dullie Hausen] " + message));
        return 0;
    }
    private static int balance(ServerPlayer player) {
        msg(player, "§e" + ErzmarkenMod.tokens.balance(player.getUUID()) + " Token");
        return 1;
    }
    private static int otherBalance(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        return success(ctx.getSource(), "Kontostand von " + player.getName().getString() + ": " + ErzmarkenMod.tokens.balance(player.getUUID()) + " Token");
    }
    private static int shop(ServerPlayer player) {
        if (ErzmarkenMod.shopState == null || !ErzmarkenMod.shopState.contains(player)) {
            msg(player, "§cDen Adminshop kannst du nur im markierten Shopgebaeude besuchen.");
            return 0;
        }
        if (!ErzmarkenMod.shopState.isOpen()) {
            msg(player, "§cShop: " + ErzmarkenMod.shopState.hudStatus());
            return 0;
        }
        msg(player, "§eAdminshop §7- Kontostand: " + ErzmarkenMod.tokens.balance(player.getUUID()));
        for (TokenConfig.ShopEntry entry : ErzmarkenMod.config.shop) {
            msg(player, "§f" + entry.id + " §7- " + entry.name + ": §e" + entry.price + " Token §7(/token buy " + entry.id + ")");
        }
        return 1;
    }
    private static int buy(CommandContext<CommandSourceStack> ctx, int quantity) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer buyer = ctx.getSource().getPlayerOrException();
        if (ErzmarkenMod.shopState == null || !ErzmarkenMod.shopState.contains(buyer))
            return error(ctx.getSource(), "Kaufen ist nur im Shopgebaeude moeglich.");
        if (!ErzmarkenMod.shopState.isOpen()) return error(ctx.getSource(), "Shop: " + ErzmarkenMod.shopState.hudStatus());
        if (ErzmarkenMod.shopState.isTemporaryCreative(buyer.getUUID()))
            return error(ctx.getSource(), "Beende zuerst den Shop-Kreativmodus.");
        try {
            ShopPrivateTradeAdmission.require(buyer.level().getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT), buyer.getUUID());
        } catch (java.io.IOException failure) {
            return error(ctx.getSource(), "Kauf gesperrt: Shop-Arbeitsinventar zuerst beenden oder pruefen lassen.");
        }
        String id = StringArgumentType.getString(ctx, "artikel");
        TokenConfig.ShopEntry entry = ErzmarkenMod.config.shopItem(id);
        if (entry == null) return error(ctx.getSource(), "Unbekannter Artikel. Nutze /token shop");
        long total = entry.price * quantity;
        if (ErzmarkenMod.tokens.balance(buyer.getUUID()) < total) return error(ctx.getSource(), "Du hast nicht genug Token (benoetigt: " + total + ").");
        Identifier itemId = Identifier.tryParse(entry.item);
        if (itemId == null || BuiltInRegistries.ITEM.getOptional(itemId).isEmpty()) return error(ctx.getSource(), "Shop-Item in Konfiguration nicht gefunden: " + entry.item);
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == null) return error(ctx.getSource(), "Item nicht vorhanden.");
        ItemStack example = new ItemStack(item);
        int count = entry.amount * quantity;
        if (!InventorySupport.canFit(buyer, example, count)) return error(ctx.getSource(), "Nicht genug Platz im Inventar.");
        if (!ErzmarkenMod.tokens.take(buyer.getUUID(), total)) return error(ctx.getSource(), "Zahlung fehlgeschlagen.");
        InventorySupport.deliver(buyer, example, count);
        msg(buyer, "§aGekauft: " + quantity + "x " + entry.name + " fuer " + total + " Token. §7Rest: " + ErzmarkenMod.tokens.balance(buyer.getUUID()));
        return 1;
    }
    private static int pay(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer from = ctx.getSource().getPlayerOrException();
        ServerPlayer to = EntityArgument.getPlayer(ctx, "spieler");
        long amount = LongArgumentType.getLong(ctx, "betrag");
        if (from.getUUID().equals(to.getUUID())) return error(ctx.getSource(), "Nicht an dich selbst senden.");
        if (!ErzmarkenMod.tokens.transfer(from.getUUID(), to.getUUID(), amount)) return error(ctx.getSource(), "Ueberweisung fehlgeschlagen (Saldo/Limit pruefen).");
        msg(from, "§a" + amount + " Token an " + to.getName().getString() + " gesendet.");
        msg(to, "§a" + amount + " Token von " + from.getName().getString() + " erhalten.");
        return 1;
    }
    private static int trade(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer seller = ctx.getSource().getPlayerOrException();
        if (ErzmarkenMod.shopState != null && ErzmarkenMod.shopState.isTemporaryCreative(seller.getUUID()))
            return error(ctx.getSource(), "Handeln im Shop-Kreativmodus ist verboten.");
        ServerPlayer buyer = EntityArgument.getPlayer(ctx, "spieler");
        long price = LongArgumentType.getLong(ctx, "preis");
        String problem = ErzmarkenMod.TRADES.create(seller, buyer, price);
        if (problem != null) return error(ctx.getSource(), problem);
        msg(seller, "§aAngebot an " + buyer.getName().getString() + " gesendet. Preis: " + price + " Token (2 Minuten gueltig).");
        msg(buyer, "§e" + seller.getName().getString() + " bietet " + seller.getMainHandItem().getCount() + "x " + seller.getMainHandItem().getHoverName().getString() + " fuer " + price + " Token an. §aAnnehmen: /token accept " + seller.getName().getString());
        return 1;
    }
    private static int accept(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer buyer = ctx.getSource().getPlayerOrException();
        ServerPlayer seller = EntityArgument.getPlayer(ctx, "spieler");
        if (ErzmarkenMod.shopState != null && (ErzmarkenMod.shopState.isTemporaryCreative(buyer.getUUID())
                || ErzmarkenMod.shopState.isTemporaryCreative(seller.getUUID())))
            return error(ctx.getSource(), "Handeln im Shop-Kreativmodus ist verboten.");
        long price = ErzmarkenMod.TRADES.price(seller.getUUID());
        String problem = ErzmarkenMod.TRADES.accept(buyer, seller, ErzmarkenMod.tokens);
        if (problem != null) return error(ctx.getSource(), problem);
        msg(buyer, "§aHandel erfolgreich: " + price + " Token bezahlt.");
        msg(seller, "§aHandel erfolgreich: " + price + " Token erhalten.");
        return 1;
    }
    private static int modify(CommandContext<CommandSourceStack> ctx, String op) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "spieler");
        long amount = LongArgumentType.getLong(ctx, "betrag");
        boolean ok = switch (op) {
            case "add" -> ErzmarkenMod.tokens.add(target.getUUID(), amount);
            case "take" -> ErzmarkenMod.tokens.take(target.getUUID(), amount);
            case "set" -> ErzmarkenMod.tokens.set(target.getUUID(), amount);
            default -> false;
        };
        if (!ok) return error(ctx.getSource(), "Aenderung fehlgeschlagen (Kontostand/Limit).");
        return success(ctx.getSource(), "Kontostand von " + target.getName().getString() + ": " + ErzmarkenMod.tokens.balance(target.getUUID()));
    }
}
