package com.ethan.voxyworldgenv2.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Optimized LOD payload with a compact section bitmask and LZ4-compressed section arrays.
 * Layout (high level):
 * - UTF dimension id
 * - ChunkPos
 * - VarInt minY
 * - byte[] sectionMask (compact bitmask: bit i set => section present)
 * - collection of sections present: for each section:
 *     - VarInt y
 *     - compressed-flagged bytes for `states`
 *     - compressed-flagged bytes for `biomes`
 *     - nullable compressed-flagged bytes for `blockLight`
 *     - nullable compressed-flagged bytes for `skyLight`
 *
 * Each compressed-flagged bytes entry is written as:
 * - boolean compressed
 * - if compressed: int originalLength, byte[] compressedData
 * - else: byte[] rawData
 */
public record OptimizedLODData(ResourceKey<Level> dimension, ChunkPos pos, int minY, byte[] sectionMask, List<Section> sections) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.parse("voxyworldgenv2:lod_data_opt");
    public static final Type<OptimizedLODData> TYPE = new Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, OptimizedLODData> CODEC = CustomPacketPayload.codec(OptimizedLODData::write, OptimizedLODData::new);

    private static final int MIN_COMPRESS = 128; // don't compress tiny arrays

    public record Section(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeInt(y);
            writeCompressedFlaggedBytes(buf, states);
            writeCompressedFlaggedBytes(buf, biomes);
            buf.writeNullable(blockLight, (b, a) -> writeCompressedFlaggedBytes((RegistryFriendlyByteBuf) b, a));
            buf.writeNullable(skyLight, (b, a) -> writeCompressedFlaggedBytes((RegistryFriendlyByteBuf) b, a));
        }

        public static Section read(RegistryFriendlyByteBuf buf) {
            int y = buf.readInt();
            byte[] states = readCompressedFlaggedBytes(buf);
            byte[] biomes = readCompressedFlaggedBytes(buf);
            byte[] bl = buf.readNullable(b -> readCompressedFlaggedBytes((RegistryFriendlyByteBuf) b));
            byte[] sl = buf.readNullable(b -> readCompressedFlaggedBytes((RegistryFriendlyByteBuf) b));
            return new Section(y, states, biomes, bl, sl);
        }
    }

    public OptimizedLODData(RegistryFriendlyByteBuf buf) {
        this(
            ResourceKey.create(Registries.DIMENSION, Identifier.parse(buf.readUtf())),
            buf.readChunkPos(),
            buf.readInt(),
            buf.readByteArray(),
            buf.readCollection(ArrayList::new, b -> Section.read((RegistryFriendlyByteBuf) b))
        );
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(dimension.identifier().toString());
        buf.writeChunkPos(pos);
        buf.writeInt(minY);
        buf.writeByteArray(sectionMask);
        buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void writeCompressedFlaggedBytes(RegistryFriendlyByteBuf buf, byte[] data) {
        if (data == null) {
            buf.writeBoolean(false);
            return;
        }

        if (data.length < MIN_COMPRESS) {
            buf.writeBoolean(false);
            buf.writeByteArray(data);
            return;
        }

        byte[] compressed = CompressionUtil.compressLz4(data);
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
                return CompressionUtil.decompressLz4(comp, origLen);
            } catch (Exception e) {
                // on error return compressed bytes to avoid crashing; caller may handle
                return comp;
            }
        } else {
            return buf.readByteArray();
        }
    }
}
