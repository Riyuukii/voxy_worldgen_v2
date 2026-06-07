package com.ethan.voxyworldgenv2.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Simple throttled send queue to prevent huge network bursts. Drained from server tick.
 */
public final class ChunkSendQueue {
    private ChunkSendQueue() {}

    private static final ChunkSendQueue INSTANCE = new ChunkSendQueue();
    private static final int MAX_SENDS_PER_TICK = 24;

    private static final class Entry {
        final ServerPlayer player;
        final CustomPacketPayload payload;

        Entry(ServerPlayer p, CustomPacketPayload payload) {
            this.player = p;
            this.payload = payload;
        }
    }

    private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();

    public static ChunkSendQueue getInstance() {
        return INSTANCE;
    }

    public void enqueue(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null) return;
        queue.add(new Entry(player, payload));
    }

    // Called from server tick thread to send a bounded number of packets per tick
    public void tick() {
        int sent = 0;
        while (sent < MAX_SENDS_PER_TICK) {
            Entry e = queue.poll();
            if (e == null) break;
            try {
                // direct send via Fabric API using the CustomPacketPayload instance
                ServerPlayNetworking.send(e.player, e.payload);
            } catch (Exception ex) {
                // swallow to avoid tick crashes; log where appropriate elsewhere
                ex.printStackTrace();
            }
            sent++;
        }
    }
}
