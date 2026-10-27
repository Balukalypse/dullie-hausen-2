package de.baluserver.erzmarken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Editable JSON configuration under config/erzmarken.json. */
public final class TokenConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean announceRewards = true;
    public boolean trackPlacedOres = true;
    // Each ore family has a random block target and a random token reward.
    // Vanilla/deepslate/nether gold share a family.
    public Map<String, RewardRange> oreRanges = defaultOreRanges();
    public static final class RewardRange {
        public int minBlocks, maxBlocks;
        public long minTokens, maxTokens;
        public RewardRange(int minBlocks, int maxBlocks, long minTokens, long maxTokens) {
            this.minBlocks=minBlocks; this.maxBlocks=maxBlocks;
            this.minTokens=minTokens; this.maxTokens=maxTokens;
        }
    }
    private static Map<String, RewardRange> defaultOreRanges() {
        Map<String, RewardRange> r = new LinkedHashMap<>();
        r.put("diamond", new RewardRange(2,4,2,3));
        r.put("emerald", new RewardRange(1,2,2,3));
        r.put("ancient_debris", new RewardRange(1,1,2,3));
        r.put("redstone", new RewardRange(3,8,1,2));
        r.put("gold", new RewardRange(3,6,1,2));
        r.put("iron", new RewardRange(3,7,1,2));
        r.put("lapis", new RewardRange(2,5,1,2));
        r.put("coal", new RewardRange(10,20,1,2));
        r.put("copper", new RewardRange(8,15,1,2));
        r.put("quartz", new RewardRange(10,20,1,2));
        return r;
    }
    // Dullie Hausen 2.0: serverseitige Obergrenzen fuer ALLTAG + ERZE zusammen.
    // Vorlaeufige Balancingwerte; bestehende 1.3.2 Konfiguration bleibt kompatibel.
    public ActivityEconomy activityEconomy = new ActivityEconomy();
    // Phase 2: login bonus once per Berlin calendar date, shared with overall caps.
    public int dailyLoginTokens = 3;
    public static final class ActivityEconomy {
        public boolean enabled = true;
        public long dailyEarnCap = 40;
        public long weeklyEarnCap = 220;
        public long monthlyEarnCap = 850;
        public Map<String, ActivityRule> activities = defaultActivities();
    }
    public static final class ActivityRule {
        public boolean enabled = true;
        public int target;
        public long reward;
        public ActivityRule(int target, long reward) { this.target = target; this.reward = reward; }
    }
    private static Map<String, ActivityRule> defaultActivities() {
        Map<String, ActivityRule> rules = new LinkedHashMap<>();
        rules.put("fishing", new ActivityRule(8, 2));
        rules.put("harvesting", new ActivityRule(64, 2));
        rules.put("building", new ActivityRule(128, 2));
        rules.put("woodcutting", new ActivityRule(64, 2));
        rules.put("monster_hunting", new ActivityRule(30, 3));
        rules.put("animal_breeding", new ActivityRule(20, 2));
        rules.put("active_minutes", new ActivityRule(60, 4));
        return rules;
    }
    public List<ShopEntry> shop = new ArrayList<>(List.of(
        new ShopEntry("spawner", "Spawner x1", "minecraft:spawner", 1, 600),
        new ShopEntry("beacon", "Beacon x1", "minecraft:beacon", 1, 300),
        new ShopEntry("elytra", "Elytra x1", "minecraft:elytra", 1, 200)
    ));

    public static TokenConfig load(Path file) throws IOException {
        if (Files.notExists(file)) {
            TokenConfig fresh = new TokenConfig();
            write(file, fresh);
            return fresh;
        }
        try {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            TokenConfig config = GSON.fromJson(root, TokenConfig.class);
            if (config == null) throw new IOException("Konfiguration leer.");
            // v1.1 migration: introduce the user's v1.2 reward ranges once.
            // Store a backup before writing; preserve existing shop offers and settings.
            if (!root.has("oreRanges") || config.oreRanges == null) {
                config.oreRanges = defaultOreRanges();
                Path backup = file.resolveSibling(file.getFileName() + ".vor-v1.2.bak");
                if (Files.notExists(backup)) Files.copy(file, backup);
                config.validate();
                write(file, config);
            } else {
                // Existing servers keep their edited thresholds. New Phase-2 rules are appended.
                boolean upgraded = false;
                if (config.activityEconomy != null && config.activityEconomy.activities != null) {
                    for (var entry : defaultActivities().entrySet()) {
                        if (!config.activityEconomy.activities.containsKey(entry.getKey())) {
                            config.activityEconomy.activities.put(entry.getKey(), entry.getValue());
                            upgraded = true;
                        }
                    }
                }
                config.validate();
                if (upgraded) {
                    Path backup = file.resolveSibling(file.getFileName() + ".vor-phase2.bak");
                    if (Files.notExists(backup)) Files.copy(file, backup);
                    write(file, config);
                }
            }
            return config;
        } catch (RuntimeException e) {
            throw new IOException("Fehlerhafte erzmarken.json: " + e.getMessage(), e);
        }
    }

    private void validate() throws IOException {
        if (dailyLoginTokens < 0 || dailyLoginTokens > 100)
            throw new IOException("dailyLoginTokens muss zwischen 0 und 100 liegen.");
        if (oreRanges == null || oreRanges.isEmpty()) throw new IOException("oreRanges darf nicht leer sein.");
        for (Map.Entry<String, RewardRange> e : oreRanges.entrySet()) {
            RewardRange r=e.getValue();
            if (e.getKey()==null || r==null || r.minBlocks<1 || r.maxBlocks<r.minBlocks || r.maxBlocks>1_000_000
                    || r.minTokens<1 || r.maxTokens<r.minTokens || r.maxTokens>1_000_000)
                throw new IOException("Ungueltige Zufallsgrenzen fuer Erz: " + e.getKey());
        }
        if (activityEconomy == null) activityEconomy = new ActivityEconomy();
        ActivityEconomy ec = activityEconomy;
        if (ec.dailyEarnCap < 1 || ec.weeklyEarnCap < ec.dailyEarnCap ||
            ec.monthlyEarnCap < ec.weeklyEarnCap || ec.activities == null)
            throw new IOException("Ungueltige Aktivitaetslimits.");
        for (Map.Entry<String, ActivityRule> e : ec.activities.entrySet()) {
            if (e.getKey() == null || !e.getKey().matches("[a-z_]+") || e.getValue() == null ||
                e.getValue().target < 1 || e.getValue().reward < 1)
                throw new IOException("Ungueltige Aktivitaetsregel: " + e.getKey());
        }
        if (shop == null) throw new IOException("shop fehlt.");
        Set<String> seen = new HashSet<>();
        for (ShopEntry entry : shop) {
            if (entry == null || entry.id == null || !entry.id.matches("[a-z0-9_]+") || !seen.add(entry.id)) {
                throw new IOException("Shop-ID fehlt, ungueltig oder doppelt.");
            }
            if (entry.name == null || entry.name.isBlank() || entry.item == null ||
                !entry.item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ||
                entry.amount < 1 || entry.amount > 64 || entry.price < 1 || entry.price > 1_000_000) {
                throw new IOException("Fehlerhafter Shop-Artikel: " + entry.id);
            }
        }
    }

    public ShopEntry shopItem(String id) {
        for (ShopEntry entry : shop) if (entry.id.equals(id)) return entry;
        return null;
    }

    public static void write(Path file, TokenConfig config) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class ShopEntry {
        public String id;
        public String name;
        public String item;
        public int amount;
        public long price;

        public ShopEntry(String id, String name, String item, int amount, long price) {
            this.id = id;
            this.name = name;
            this.item = item;
            this.amount = amount;
            this.price = price;
        }
    }
}
