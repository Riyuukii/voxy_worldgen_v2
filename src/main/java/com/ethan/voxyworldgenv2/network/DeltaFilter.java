package com.ethan.voxyworldgenv2.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Lightweight delta filter that computes a CRC32 of payloads and remembers the last
 * value per-player+chunk to avoid re-sending identical LOD payloads.
 */
public final class DeltaFilter {
    private DeltaFilter() {}

    private static final Map<UUID, Map<Long, Integer>> LAST = new ConcurrentHashMap<>();

    public static int computeCrc(byte[]... parts) {
        CRC32 crc = new CRC32();
        if (parts != null) {
            for (byte[] p : parts) {
                if (p != null) crc.update(p, 0, p.length);
            }
        }
        return (int) crc.getValue();
    }

    public static boolean shouldSend(ServerPlayer player, ChunkPos pos, int newCrc) {
        UUID id = player.getUUID();
        Map<Long, Integer> map = LAST.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        long key = pos.pack();
        Integer prev = map.get(key);
        if (prev != null && prev == newCrc) return false;
        map.put(key, newCrc);
        return true;
    }

    public static void clearPlayer(ServerPlayer player) {
        LAST.remove(player.getUUID());
    }
}
