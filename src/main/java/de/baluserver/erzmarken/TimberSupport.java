package de.baluserver.erzmarken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;

/**
 * Observes connected natural logs BEFORE the player breaks the bottom log.
 * Datapack Timber often deletes the other logs through commands, bypassing
 * PlayerBlockBreakEvents. Credit only observed removals from this small tree.
 * Never rewards placed logs, Creative or Spectator breaks; limit each snapshot.
 */
final class TimberSupport {
    private TimberSupport() { }
    private static final int MAX_LOGS = 192;
    private static final int MAX_RADIUS = 12;
    private static final int OBSERVATION_TICKS = 40;
    private record Key(UUID player, String dimension, long root) { }
    private static final class Observation {
        final ServerLevel level;
        final UUID player;
        final Map<BlockPos, String> logs;
        int remaining = OBSERVATION_TICKS;
        boolean confirmed;
        Observation(ServerLevel level, UUID player, Map<BlockPos, String> logs) {
            this.level = level; this.player = player; this.logs = logs;
        }
    }
    private static final Map<Key, Observation> pending = new HashMap<>();
    // Block positions also seen in ordinary break callbacks must never be double-paid.
    private static final Map<String, Long> credited = new HashMap<>();
    private static long ticks;

    static boolean isLog(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.endsWith("_log") || id.endsWith("_stem") || id.endsWith("_hyphae") || id.endsWith("_wood");
    }

    static void before(ServerLevel level, ServerPlayer player, BlockPos root, BlockState state) {
        if (!isLog(state) || player.isCreative() || player.isSpectator() || ErzmarkenMod.tokens == null) return;
        String origin = key(level, root);
        if (ErzmarkenMod.tokens.wasPlacedLog(origin)) return;
        Map<BlockPos, String> logs = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(root.immutable());
        while (!queue.isEmpty() && logs.size() < MAX_LOGS && visited.size() < MAX_LOGS * 16) {
            BlockPos current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (Math.abs(current.getX() - root.getX()) > MAX_RADIUS ||
                Math.abs(current.getY() - root.getY()) > MAX_RADIUS ||
                Math.abs(current.getZ() - root.getZ()) > MAX_RADIUS) continue;
            BlockState candidate = level.getBlockState(current);
            if (!isLog(candidate)) continue;
            String positionKey = key(level, current);
            if (ErzmarkenMod.tokens.wasPlacedLog(positionKey)) continue;
            logs.put(current.immutable(), BuiltInRegistries.BLOCK.getKey(candidate.getBlock()).toString());
            // Directly adjoining blocks only: avoid connecting forests over leaf blocks.
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                queue.add(current.offset(dx, dy, dz).immutable());
            }
        }
        if (logs.size() < 2) return;
        pending.put(new Key(player.getUUID(), level.dimension().identifier().toString(), root.asLong()),
            new Observation(level, player.getUUID(), logs));
    }

    static void after(ServerLevel level, ServerPlayer player, BlockPos root) {
        Observation observation = pending.get(new Key(player.getUUID(), level.dimension().identifier().toString(), root.asLong()));
        if (observation != null) observation.confirmed = true;
        creditPosition(level, root); // ordinary callback itself credits this log
    }

    static void ordinaryBreak(ServerLevel level, BlockPos pos) {
        creditPosition(level, pos);
    }

    private static void creditPosition(ServerLevel level, BlockPos pos) {
        credited.put(key(level, pos), ticks);
    }

    static void tick(MinecraftServer server) {
        ticks++;
        Iterator<Map.Entry<Key, Observation>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Observation ob = it.next().getValue();
            if (!ob.confirmed) {
                if (--ob.remaining <= 0) it.remove();
                continue;
            }
            // Check repeatedly: some Timber datapacks schedule a few subsequent ticks.
            for (Iterator<Map.Entry<BlockPos, String>> blocks = ob.logs.entrySet().iterator(); blocks.hasNext();) {
                Map.Entry<BlockPos, String> e = blocks.next();
                BlockPos pos = e.getKey();
                String coord = key(ob.level, pos);
                if (credited.containsKey(coord)) { blocks.remove(); continue; }
                if (ErzmarkenMod.tokens == null || ErzmarkenMod.tokens.wasPlacedLog(coord)) {
                    blocks.remove(); continue;
                }
                String now = BuiltInRegistries.BLOCK.getKey(ob.level.getBlockState(pos).getBlock()).toString();
                if (now.equals(e.getValue())) continue;
                // Require actual removal, not swapping to another solid block.
                if (!ob.level.getBlockState(pos).isAir()) { blocks.remove(); continue; }
                credited.put(coord, ticks);
                ServerPlayer owner = server.getPlayerList().getPlayer(ob.player);
                if (owner != null && !owner.isCreative() && !owner.isSpectator()) {
                    long paid = ErzmarkenMod.tokens.recordActivity(ob.player, "woodcutting", ErzmarkenMod.config.activityEconomy);
                    if (paid > 0 && ErzmarkenMod.config.announceRewards)
                        owner.sendSystemMessage(Component.literal("§6[Dullie Hausen] §a+" + paid + " Token §7für Holzfällen (Timber)"));
                }
                blocks.remove();
            }
            if (--ob.remaining <= 0 || ob.logs.isEmpty()) it.remove();
        }
        if (ticks % 100 == 0) credited.entrySet().removeIf(e -> ticks - e.getValue() > 50);
    }

    static void clear() { pending.clear(); credited.clear(); ticks = 0; }
    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
