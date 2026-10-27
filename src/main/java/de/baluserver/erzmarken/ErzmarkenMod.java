package de.baluserver.erzmarken;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side only. No custom packets, client-required menus, or custom items. */
public final class ErzmarkenMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Erzmarken");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("erzmarken.json");
    public static TokenConfig config;
    public static TokenStore tokens;
    public static ShopState shopState;
    public static final TradeService TRADES = new TradeService();
    private static final List<Placement> PENDING_PLACEMENTS = new ArrayList<>();
    private static int tickCounter;
    private static int activeTickCounter;
    private static final Map<UUID, Integer> LAST_FISH = new HashMap<>();
    private static final Map<UUID, Integer> LAST_MOB_KILLS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_BREEDING = new HashMap<>();
    private static final Map<UUID, PositionSample> LAST_ACTIVE_SAMPLE = new HashMap<>();
    private record PositionSample(String dimension, double x, double y, double z) { }

    // A position may only produce one harvesting/building progress event per rolling half hour.
    private static final long ACTIVITY_REUSE_DELAY_MS = 30 * 60 * 1000L;
    private static Map<String, TokenConfig.RewardRange> oreRanges;
    private static final Map<String, String> ORE_GROUPS = Map.ofEntries(
        Map.entry("minecraft:diamond_ore", "diamond"), Map.entry("minecraft:deepslate_diamond_ore", "diamond"),
        Map.entry("minecraft:emerald_ore", "emerald"), Map.entry("minecraft:deepslate_emerald_ore", "emerald"),
        Map.entry("minecraft:ancient_debris", "ancient_debris"),
        Map.entry("minecraft:redstone_ore", "redstone"), Map.entry("minecraft:deepslate_redstone_ore", "redstone"),
        Map.entry("minecraft:gold_ore", "gold"), Map.entry("minecraft:deepslate_gold_ore", "gold"), Map.entry("minecraft:nether_gold_ore", "gold"),
        Map.entry("minecraft:iron_ore", "iron"), Map.entry("minecraft:deepslate_iron_ore", "iron"),
        Map.entry("minecraft:lapis_ore", "lapis"), Map.entry("minecraft:deepslate_lapis_ore", "lapis"),
        Map.entry("minecraft:coal_ore", "coal"), Map.entry("minecraft:deepslate_coal_ore", "coal"),
        Map.entry("minecraft:copper_ore", "copper"), Map.entry("minecraft:deepslate_copper_ore", "copper"),
        Map.entry("minecraft:nether_quartz_ore", "quartz")
    );

    private record Placement(ServerLevel world, BlockPos pos, Block block, UUID player, boolean activityEligible) { }

    @Override
    public void onInitialize() {
        Phase2TestAccess.requireLaunchIdentity();
        try {
            config = TokenConfig.load(CONFIG_PATH);
            oreRanges = Map.copyOf(config.oreRanges);
        } catch (IOException e) {
            throw new IllegalStateException("Fehler in config/erzmarken.json", e);
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                Phase2TestAccess.requireStartupScope(server);
                // Archive known legacy files BEFORE loading persistent state. Originals remain authoritative.
                CentralDataArchive.Result archiveResult = CentralDataArchive.capture(
                    Path.of("."), server.getWorldPath(LevelResource.ROOT));
                LOGGER.info("Dullie Hausen Legacy-Archiv: {} kopiert, {} identisch, {} geaendert seit Archivierung",
                    archiveResult.copied(), archiveResult.unchanged(), archiveResult.changedSinceArchive());
                for (String notice : archiveResult.notices()) LOGGER.warn("Legacy-Archiv: {}", notice);
                ShopTransactionJournal.initialize(server.getWorldPath(LevelResource.ROOT));
                ShopJournalAudit.Report journalAudit = ShopJournalAudit.inspect(server.getWorldPath(LevelResource.ROOT));
                if (journalAudit.blocksTrading()) {
                    LOGGER.error("Shop-Ankauf gesperrt: {} offene Journalbuchungen, {} auffaellige Dateien. "
                        + "Erst Sicherungen und gespeicherte Minecraft-Spielerdaten manuell pruefen.",
                        journalAudit.pending(), journalAudit.suspicious());
                }

                tokens = TokenStore.forWorld(server.getWorldPath(LevelResource.ROOT));
                // Fresh worlds need no receipt audit until a token file exists.
                if (java.nio.file.Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/erzmarken.json"))) {
                    var tokenReceiptAudit = ShopTokenReceiptAudit.inspect(
                        server.getWorldPath(LevelResource.ROOT), journalAudit, 8);
                    if (tokenReceiptAudit.reviewRequired())
                        for (String line : tokenReceiptAudit.lines()) LOGGER.warn("Shop-Tokenaudit: {}", line);
                }
                try {
                    shopState = new ShopState(server.getWorldPath(LevelResource.ROOT).resolve("data/erzmarken-shop.json"));
                } catch (IOException shopError) {
                    shopState = null; // Fehlerzustand im HUD anzeigen; Kaeufe bleiben gesperrt.
                    LOGGER.error("Shopdaten fehlerhaft. Shop gesperrt; HUD zeigt Status unbekannt.", shopError);
                }
                // Read-only startup overview; missing receipts are hints, not proof of corruption.
                java.util.Set<String> receiptIds = new java.util.HashSet<>();
                if (shopState != null)
                    for (var receipt : shopState.buybackHistory()) receiptIds.add(receipt.id());
                var recoveryOverview = ShopRecoveryAdminOverview.inspect(journalAudit, receiptIds);
                if (recoveryOverview.tradingBlocked())
                    LOGGER.warn("Shop-Recovery-Audit: {} offene Buchungen mit Shopbeleg, {} ohne Beleg, {} Shopbelege ohne Journal. /shop journal fuer Einzelheiten. KEINE automatische Freigabe.",
                        recoveryOverview.pendingWithReceipt(), recoveryOverview.pendingWithoutReceipt(),
                        recoveryOverview.receiptsWithoutJournal());
                StreamSupport.load(server.getWorldPath(LevelResource.ROOT).resolve("data/erzmarken-stream.json"));
                BugReports.start(server);
                KiDirector.start(server);
                LOGGER.info("Erzmarken gestartet ({} Erztypen mit Einzelbelohnungen)", oreRanges.size());
            } catch (IOException e) {
                throw new IllegalStateException("Spieler-Tokenstand konnte nicht geladen werden! Daten bitte sichern.", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ShopInventorySessions.logShutdownAudit(server);
            if (tokens != null) tokens.flush();
            PENDING_PLACEMENTS.clear();
            TimberSupport.clear();
            BugReports.stop();
            KiDirector.stop();
            LAST_FISH.clear();
            LAST_MOB_KILLS.clear();
            LAST_BREEDING.clear();
            LAST_ACTIVE_SAMPLE.clear();
            TRADES.clear();
            StreamSupport.stop(server);
            SidebarSupport.reset();
            shopState = null;
            tokens = null;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (tokens == null || !(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) return;
            boolean placedLog = tokens.wasPlacedLog(positionKey(level, pos));
            TimberSupport.ordinaryBreak(level, pos);
            TimberSupport.after(level, serverPlayer, pos);
            if (!placedLog && !serverPlayer.isCreative() && !serverPlayer.isSpectator()) {
                String blockIdForActivity = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (TimberSupport.isLog(state)) {
                    long paid = tokens.recordActivity(serverPlayer.getUUID(), "woodcutting", config.activityEconomy);
                    if (paid > 0 && config.announceRewards) serverPlayer.sendSystemMessage(
                        Component.literal("§6[Dullie Hausen] §a+" + paid + " Token §7für Holzfällen"));
                }
            }
            if (!serverPlayer.isCreative() && !serverPlayer.isSpectator() && isMatureHarvest(state)
                    && firstActivityAt("harvest", level, pos)) {
                rewardActivity(serverPlayer, "harvesting", "Ernten");
            }
            // Auch bei geaenderten Bloecken/Creative die gesetzte Markierung bereinigen.
            boolean playerPlaced = tokens.wasPlaced(positionKey(level, pos));
            if (playerPlaced || serverPlayer.isCreative() || serverPlayer.isSpectator()) return;
            // Kein Token, wenn das verwendete Werkzeug das Erz nicht korrekt abbauen kann.
            if (!serverPlayer.hasCorrectToolForDrops(state)) return;
            if (!isOre(state)) return;
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String group = ORE_GROUPS.getOrDefault(blockId, blockId);
            TokenConfig.RewardRange range = oreRanges.get(group);
            if (range == null || !tokens.canRewardMining(serverPlayer.getUUID(), range.maxTokens, config.activityEconomy)
                    || !tokens.countMinedOre(serverPlayer.getUUID(), group, range)) return;
            long reward = java.util.concurrent.ThreadLocalRandom.current().nextLong(range.minTokens, range.maxTokens+1);
            long milestone = tokens.rewardMining(serverPlayer.getUUID(), reward, config.activityEconomy);
            if (milestone < 0) return;
            if (config.announceRewards) {
                serverPlayer.sendSystemMessage(Component.literal("§6[Dullie Hausen] §a+" + reward
                    + " Token §7für §f" + oreName(group) + "§7! Neuer Kontostand: §e"
                    + tokens.balance(serverPlayer.getUUID())));
            }
            if (milestone > 0) celebrateMilestone(serverPlayer, milestone);
        });

        // Detection of manually placed ore blocks. The actual block state is checked at tick end:
        // denied/failed placement will NOT be marked. Dispensers and external mods are not covered.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && shopState != null
                    && shopState.isTemporaryCreative(serverPlayer.getUUID())) {
                BlockPos clicked = hit.getBlockPos();
                BlockPos target = world.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
                if (!shopState.contains(serverPlayer, clicked) || !shopState.contains(serverPlayer, target)) {
                    return InteractionResult.FAIL;
                }
            }
            if (player instanceof ServerPlayer sp && shopState != null) {
                BlockPos clicked = hit.getBlockPos();
                BlockPos target = world.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
                if (shopState.contains(sp, clicked) && shopState.isWarehouse(clicked) && !shopState.isOwner(sp)) return InteractionResult.FAIL;
                if (shopState.protectedRegion() && shopState.contains(sp, target) && player.getItemInHand(hand).getItem() instanceof BlockItem
                        && !shopState.canBuild(sp)) return InteractionResult.FAIL;
                if (shopState.contains(sp, target) && shopState.isWarehouse(target) && player.getItemInHand(hand).getItem() instanceof BlockItem
                        && !shopState.isOwner(sp)) return InteractionResult.FAIL;
            }
            if (!(world instanceof ServerLevel level) || tokens == null) return InteractionResult.PASS;
            if (!(player.getItemInHand(hand).getItem() instanceof BlockItem held)) return InteractionResult.PASS;
            String placedId = BuiltInRegistries.BLOCK.getKey(held.getBlock()).toString();
            boolean log = TimberSupport.isLog(held.getBlock().defaultBlockState());
            // All blocks are observed for building, while ore/log provenance is retained.
            // Counts only real placements confirmed on the next tick.
            boolean building = !player.isCreative() && !player.isSpectator()
                    && !log && !isOre(held.getBlock());
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = world.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
            PENDING_PLACEMENTS.add(new Placement(level, target.immutable(), held.getBlock(), player.getUUID(), building));
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (tokens == null) return;
            for (Placement p : PENDING_PLACEMENTS) {
                if (p.world().getBlockState(p.pos()).is(p.block())) {
                    String id = BuiltInRegistries.BLOCK.getKey(p.block()).toString();
                    if (isOre(p.block())) tokens.markPlaced(positionKey(p.world(), p.pos()));
                    if (TimberSupport.isLog(p.block().defaultBlockState())) tokens.markPlacedLog(positionKey(p.world(), p.pos()));
                    if (p.activityEligible() && firstActivityAt("build", p.world(), p.pos())) {
                        ServerPlayer builder = server.getPlayerList().getPlayer(p.player());
                        if (builder != null && !builder.isCreative() && !builder.isSpectator())
                            rewardActivity(builder, "building", "Bauen");
                    }
                }
            }
            PENDING_PLACEMENTS.clear();
            ShopCreative.tick(server);
            TimberSupport.tick(server);
            if (tickCounter % 20 == 0) {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    int caught = sp.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT));
                    Integer previous = LAST_FISH.put(sp.getUUID(), caught);
                    if (previous != null && caught > previous && !sp.isCreative() && !sp.isSpectator()) {
                        for (int i = 0; i < Math.min(caught - previous, 32); i++)
                            rewardActivity(sp, "fishing", "Angeln");
                    }
                    // Vanilla player statistics, not entity death count, so only credited player kills.
                    int killed = sp.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
                    Integer prevKilled = LAST_MOB_KILLS.put(sp.getUUID(), killed);
                    if (prevKilled != null && killed > prevKilled && !sp.isCreative() && !sp.isSpectator()) {
                        for (int i = 0; i < Math.min(32, killed - prevKilled); i++)
                            rewardActivity(sp, "monster_hunting", "Monsterjagd");
                    }
                    int bred = sp.getStats().getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED));
                    Integer prevBred = LAST_BREEDING.put(sp.getUUID(), bred);
                    if (prevBred != null && bred > prevBred && !sp.isCreative() && !sp.isSpectator()) {
                        for (int i = 0; i < Math.min(32, bred - prevBred); i++)
                            rewardActivity(sp, "animal_breeding", "Tierzucht");
                    }
                }
            }
            // An active minute is earned only after real positional movement since last sample.
            // This is a conservative heuristic, not proof against all AFK mechanisms.
            if (++activeTickCounter >= 1200) {
                activeTickCounter = 0;
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    String dimension = sp.level().dimension().identifier().toString();
                    PositionSample next = new PositionSample(dimension, sp.getX(), sp.getY(), sp.getZ());
                    PositionSample old = LAST_ACTIVE_SAMPLE.put(sp.getUUID(), next);
                    if (old != null && old.dimension().equals(dimension)
                            && !sp.isCreative() && !sp.isSpectator()
                            && (Math.pow(sp.getX() - old.x(), 2) + Math.pow(sp.getZ() - old.z(), 2)) >= 16.0
                            && (shopState == null || !shopState.isOwner(sp) || !shopState.isOpen())) {
                        rewardActivity(sp, "active_minutes", "aktive Spielzeit");
                    }
                }
            }
            if (tickCounter % 600 == 0) {
                tokens.pruneActivityPositions(ACTIVITY_REUSE_DELAY_MS);
            }
            if (++tickCounter % 40 == 0) {
                SidebarSupport.update(server, tokens, shopState);
            }
            if (tickCounter % 600 == 0) {
                TabBranding.refreshAll(server);
            }
            if (tickCounter % 100 == 0) tokens.flush();
            if (tickCounter >= 600) tickCounter = 0;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!Phase2TestAccess.testerMayJoin(server, handler.player.getUUID())) {
                handler.disconnect(Component.literal("Dieser Testserver ist nur fuer eingetragene Testkonten freigegeben."));
                return;
            }
            // A rejected recovery session must not receive rewards or invoke legacy creative cleanup.
            if (!ShopInventorySessions.inspectLogin(handler.player)) return;
            try {
                if (!ShopPendingTradeGate.mayJoin(server.getWorldPath(LevelResource.ROOT), handler.player.getUUID())) {
                    handler.disconnect(Component.literal("Dullie Hausen: Offene Shopbuchung muss vor dem Login geprueft werden."));
                    return;
                }
            } catch (IOException failure) {
                LOGGER.error("Shopbuchungen beim Login nicht pruefbar: {}", handler.player.getUUID(), failure);
                handler.disconnect(Component.literal("Dullie Hausen: Shopbuchungspruefung fehlgeschlagen. Bitte Serververwaltung kontaktieren."));
                return;
            }
            LAST_FISH.put(handler.player.getUUID(), handler.player.getStats().getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT)));
            LAST_MOB_KILLS.put(handler.player.getUUID(), handler.player.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
            LAST_BREEDING.put(handler.player.getUUID(), handler.player.getStats().getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED)));
            LAST_ACTIVE_SAMPLE.put(handler.player.getUUID(), new PositionSample(
                handler.player.level().dimension().identifier().toString(),
                handler.player.getX(), handler.player.getY(), handler.player.getZ()));
            if (tokens != null) {
                long loginReward = tokens.rewardDailyLogin(handler.player.getUUID(), config.dailyLoginTokens, config.activityEconomy);
                if (loginReward > 0) handler.player.sendSystemMessage(Component.literal(
                    "§6[Dullie Hausen] §a+" + loginReward + " Token §7Tagesbonus für deinen ersten Login heute!"));
            }
            StreamSupport.joined(handler.player);
            BugReports.joined(handler.player);
            SidebarSupport.joined(handler.player);
            TabBranding.send(handler.player);
            if (shopState != null && shopState.isTemporaryCreative(handler.player.getUUID())) {
                // Crash/reconnect safety: remove temporary creative items immediately after login.
                ShopCreative.leave(handler.player);
            }
        });
        // Pre-death protection: prevents working-shop items from becoming vanilla death drops.
        // Fabric's ALLOW_DEATH cancellation alone is insufficient: restore health above zero.
        ServerLivingEntityEvents.ALLOW_DEATH.register((living, source, damageAmount) -> {
            if (!(living instanceof ServerPlayer player)) return true;
            if (!ShopInventorySessions.preventVanillaDeath(player)) return true;
            player.setHealth(Math.max(1.0F, player.getMaxHealth()));
            LOGGER.warn("Toedlichen Schaden bei Shop-Arbeitsinventar verhindert: UUID={}", player.getUUID());
            return false;
        });
        // Defensive respawn gate: never silently resume a shop inventory session after death.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ShopInventorySessions.inspectRespawn(newPlayer);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ShopInventorySessions.inspectDisconnect(handler.player);
            LAST_FISH.remove(handler.player.getUUID());
            LAST_MOB_KILLS.remove(handler.player.getUUID());
            LAST_BREEDING.remove(handler.player.getUUID());
            LAST_ACTIVE_SAMPLE.remove(handler.player.getUUID());
            StreamSupport.left(handler.player);
            SidebarSupport.left(handler.player);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer sp)) return true;
            if (shopState == null) {
                if (world instanceof ServerLevel level) TimberSupport.before(level, sp, pos, state);
                return true;
            }
            if (shopState.isTemporaryCreative(sp.getUUID()) && !shopState.contains(sp, pos)) return false;
            if (shopState.protectedRegion() && shopState.contains(sp, pos)) {
                if (!shopState.canBuild(sp) || (shopState.isWarehouse(pos) && !shopState.isOwner(sp))) return false;
            }
            if (world instanceof ServerLevel level) TimberSupport.before(level, sp, pos, state);
            return true;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer sp && shopState != null && shopState.protectedRegion()
                    && shopState.contains(sp, pos) && (!shopState.canBuild(sp)
                    || (shopState.isWarehouse(pos) && !shopState.isOwner(sp)))) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            TokenCommands.register(dispatcher);
            ShopCommands.register(dispatcher);
            StreamSupport.register(dispatcher);
            BugReports.register(dispatcher);
            KiDirector.register(dispatcher);
        });
    }

    private static void rewardActivity(ServerPlayer player, String key, String label) {
        if (tokens == null) return;
        long paid = tokens.recordActivity(player.getUUID(), key, config.activityEconomy);
        if (paid > 0 && config.announceRewards) player.sendSystemMessage(
            Component.literal("§6[Dullie Hausen] §a+" + paid + " Token §7für " + label));
    }

    private static boolean isMatureHarvest(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) return crop.isMaxAge(state);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.equals("minecraft:nether_wart") || id.equals("minecraft:cocoa")) {
            for (var property : state.getProperties()) {
                if (property instanceof IntegerProperty age && age.getName().equals("age"))
                    return state.getValue(age).intValue() == age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(-1);
            }
        }
        return false;
    }

    private static boolean firstActivityAt(String type, ServerLevel world, BlockPos pos) {
        return tokens != null && tokens.recordPositionActivity(
            type, positionKey(world, pos), ACTIVITY_REUSE_DELAY_MS);
    }

    private static String oreName(String group) {
        return switch (group) {
            case "diamond" -> "Diamanterz";
            case "emerald" -> "Smaragderz";
            case "ancient_debris" -> "Antiker Schrott";
            case "redstone" -> "Redstone-Erz";
            case "gold" -> "Golderz";
            case "iron" -> "Eisenerz";
            case "lapis" -> "Lapislazuli-Erz";
            case "coal" -> "Kohleerz";
            case "copper" -> "Kupfererz";
            case "quartz" -> "Netherquarzerz";
            default -> group;
        };
    }

    /** Server-seitiger Feuerwerkseffekt: Partikel und Raketen-Sounds oberhalb des Spielers. */
    private static void celebrateMilestone(ServerPlayer player, long milestone) {
        ServerLevel level = (ServerLevel) player.level();
        double x = player.getX(), y = player.getY() + 2.8, z = player.getZ();
        level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 70, 0.8, 0.7, 0.8, 0.18);
        level.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0f, 1.0f);
        level.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0f, 1.1f);
        player.sendSystemMessage(Component.literal("§6[Dullie Hausen] §eGlückwunsch! §f" + milestone
            + " Token insgesamt durch Erzabbau verdient!"));
    }

    /** Stable, safe accessor used by the piston mixin before mod initialization. */
    public static boolean isRewardOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return ORE_GROUPS.containsKey(id);
    }
    public static boolean isOre(BlockState state) { return isOre(state.getBlock()); }
    public static boolean isOre(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        String group = ORE_GROUPS.get(id);
        return group != null && oreRanges.containsKey(group);
    }
    private static String positionKey(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static void saveConfig() throws IOException {
        TokenConfig.write(CONFIG_PATH, config);
    }

    public static boolean reloadConfig() {
        try {
            TokenConfig fresh = TokenConfig.load(CONFIG_PATH);
            config = fresh;
            oreRanges = Map.copyOf(fresh.oreRanges);
            return true;
        } catch (IOException e) {
            LOGGER.error("Konfiguration nicht geladen (alte bleibt aktiv)", e);
            return false;
        }
    }
}
