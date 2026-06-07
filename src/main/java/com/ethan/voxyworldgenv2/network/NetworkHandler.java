package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.ethan.voxyworldgenv2.network.OptimizedLODData;
import com.ethan.voxyworldgenv2.network.CompressionUtil;
import com.ethan.voxyworldgenv2.network.DeltaFilter;
import com.ethan.voxyworldgenv2.network.ChunkSendQueue;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class NetworkHandler {
    public static final Identifier HANDSHAKE_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":handshake");
    public static final Identifier LOD_DATA_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":lod_data");
    public static final Identifier LOD_DATA_V2_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":lod_data_v2");
    public static final Identifier CLIENT_FEATURES_ID = Identifier.parse(VoxyWorldGenV2.MOD_ID + ":client_features");

    // keep individual packets well under Netty's 2MB limit to prevent connection resets on public servers
    private static final int MAX_PACKET_BYTES = 32_768;
    // do not attempt compression on arrays smaller than this (saves CPU)
    private static final int COMPRESS_MIN_SIZE = 512;

    public record HandshakePayload(boolean serverHasMod) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(RegistryFriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }
        }

        public LODDataPayload(RegistryFriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b))
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.identifier().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // V2 payload: supports per-array compression to save bandwidth when both sides support it
    public record LODDataV2Payload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionDataV2> sections) implements CustomPacketPayload {
        public static final Type<LODDataV2Payload> TYPE = new Type<>(LOD_DATA_V2_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataV2Payload> CODEC = CustomPacketPayload.codec(LODDataV2Payload::write, LODDataV2Payload::new);

        public record SectionDataV2(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                writeCompressedFlaggedBytes(buf, states);
                writeCompressedFlaggedBytes(buf, biomes);
                buf.writeNullable(blockLight, (b, a) -> writeCompressedFlaggedBytes((RegistryFriendlyByteBuf) b, a));
                buf.writeNullable(skyLight, (b, a) -> writeCompressedFlaggedBytes((RegistryFriendlyByteBuf) b, a));
            }

            public static SectionDataV2 read(RegistryFriendlyByteBuf buf) {
                return new SectionDataV2(
                    buf.readInt(),
                    readCompressedFlaggedBytes(buf),
                    readCompressedFlaggedBytes(buf),
                    buf.readNullable(b -> readCompressedFlaggedBytes((RegistryFriendlyByteBuf) b)),
                    buf.readNullable(b -> readCompressedFlaggedBytes((RegistryFriendlyByteBuf) b))
                );
            }
        }

        public LODDataV2Payload(RegistryFriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionDataV2.read((RegistryFriendlyByteBuf) b))
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.identifier().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        
        PayloadTypeRegistry.clientboundPlay().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LODDataV2Payload.TYPE, LODDataV2Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OptimizedLODData.TYPE, OptimizedLODData.CODEC);

        // drain our throttled send queue on the server tick to avoid burst flooding
        ServerTickEvents.END_SERVER_TICK.register(server -> ChunkSendQueue.getInstance().tick());
        
        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    // New optimized sender: computes a CRC over raw section arrays and only enqueues
    // the optimized LZ4-backed payload when the per-player+chunk CRC differs.
    public static void sendOptimizedLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();

        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null && synced.contains(pos.pack())) return; // already synced

        List<LODDataPayload.SectionData> sectionsRaw = buildSections(chunk);
        if (sectionsRaw.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }

        int sectionCount = chunk.getSections().length;
        byte[] mask = new byte[(sectionCount + 7) / 8];

        List<OptimizedLODData.Section> optSections = new ArrayList<>();

        // build sections list (raw arrays) and populate mask
        for (LODDataPayload.SectionData sd : sectionsRaw) {
            int idx = sd.y() - minY;
            if (idx >= 0 && idx < sectionCount) {
                mask[idx / 8] |= (1 << (idx % 8));
            }
            optSections.add(new OptimizedLODData.Section(sd.y(), sd.states(), sd.biomes(), sd.blockLight(), sd.skyLight()));
        }

        // compute CRC across raw arrays to determine whether to skip sending
        int crc = 0;
        try {
            crc = DeltaFilter.computeCrc(optSections.stream().flatMap(s -> java.util.stream.Stream.of(s.states(), s.biomes(), s.blockLight(), s.skyLight())).filter(java.util.Objects::nonNull).toArray(byte[][]::new));
        } catch (Exception e) {
            crc = 0;
        }

        if (!DeltaFilter.shouldSend(player, pos, crc)) {
            // unchanged for this player
            return;
        }

        OptimizedLODData payload = new OptimizedLODData(chunk.getLevel().dimension(), pos, minY, mask, optSections);
        // enqueue for throttled send
        ChunkSendQueue.getInstance().enqueue(player, payload);
        setSyncedState(player, pos, true);
    }

    private static byte[] compressBytes(byte[] input) {
        if (input == null || input.length == 0) return input;
        if (input.length < COMPRESS_MIN_SIZE) return input;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.BEST_SPEED))) {
            dos.write(input);
            dos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            return input;
        }
    }

    private static byte[] decompressBytes(byte[] compressed, int expectedLength) throws IOException {
        if (compressed == null) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             InflaterInputStream iis = new InflaterInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(1024, expectedLength))) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = iis.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private static void writeCompressedFlaggedBytes(RegistryFriendlyByteBuf buf, byte[] data) {
        if (data == null) {
            buf.writeBoolean(false);
            return;
        }

        if (data.length < COMPRESS_MIN_SIZE) {
            buf.writeBoolean(false);
            buf.writeByteArray(data);
            return;
        }

        byte[] compressed = compressBytes(data);
        if (compressed != null && compressed.length < data.length) {
            buf.writeBoolean(true);
            buf.writeInt(data.length);
            buf.writeByteArray(compressed);
        } else {
            buf.writeBoolean(false);
            buf.writeByteArray(data);
        }
    }

    private static byte[] readCompressedFlaggedBytes(RegistryFriendlyByteBuf buf) {
        boolean compressed = buf.readBoolean();
        if (compressed) {
            int origLen = buf.readInt();
            byte[] comp = buf.readByteArray();
            try {
                return decompressBytes(comp, origLen);
            } catch (IOException e) {
                return comp;
            }
        } else {
            return buf.readByteArray();
        }
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.pack());
            } else {
                synced.remove(pos.pack());
            }
        }
    }

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        double maxDistSq = 4096.0 * 4096.0;

        List<ServerPlayer> recipients = new ArrayList<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, pos, false);
                continue;
            }

            var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
            if (synced != null && synced.contains(pos.pack())) {
                // player already has this chunk's LOD data
                continue;
            }

            recipients.add(player);
        }

        if (recipients.isEmpty()) return;

        List<LODDataPayload.SectionData> sections = buildSections(chunk);
        if (sections.isEmpty()) return;

        for (ServerPlayer player : recipients) {
            // use optimized sender (LZ4 + delta filtering + throttled queue)
            sendOptimizedLODData(player, chunk);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null && synced.contains(pos.pack())) return; // already synced

        // use optimized sender which will perform delta filtering and queueing
        sendOptimizedLODData(player, chunk);
    }

    private static List<LODDataPayload.SectionData> buildSections(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSectionY();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;

            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            byte[] states, biomes;
            try {
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }

            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            sections.add(new LODDataPayload.SectionData(
                minY + i,
                states,
                biomes,
                bl != null ? bl.getData().clone() : null,
                sl != null ? sl.getData().clone() : null
            ));
        }

        return sections;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections) {
        List<LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = 0;
        boolean sentAny = false;

        for (LODDataPayload.SectionData sd : sections) {
            int sectionBytes = sd.states().length + sd.biomes().length
                + (sd.blockLight() != null ? sd.blockLight().length : 0)
                + (sd.skyLight() != null ? sd.skyLight().length : 0);

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                ServerPlayNetworking.send(player, new LODDataPayload(dimension, pos, minY, batch));
                sentAny = true;
                batch = new ArrayList<>();
                batchBytes = 0;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, new LODDataPayload(dimension, pos, minY, batch));
            sentAny = true;
        }

        if (sentAny) {
            setSyncedState(player, pos, true);
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        ServerPlayNetworking.send(player, new HandshakePayload(true));
    }
}
