package com.ethan.voxyworldgenv2.network;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

public final class CompressionUtil {
    private CompressionUtil() {}

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();

    // Compress using LZ4 block compression. Returns a new byte[] with compressed data.
    public static byte[] compressLz4(byte[] src) {
        if (src == null || src.length == 0) return src;
        LZ4Compressor compressor = FACTORY.fastCompressor();
        int maxCompressedLength = compressor.maxCompressedLength(src.length);
        byte[] compressed = new byte[maxCompressedLength];
        int compressedLen = compressor.compress(src, 0, src.length, compressed, 0, maxCompressedLength);
        byte[] out = new byte[compressedLen];
        System.arraycopy(compressed, 0, out, 0, compressedLen);
        return out;
    }

    // Decompress LZ4 compressed bytes. Caller must supply the original (decompressed) length
    // when available; if originalLength is unknown, the caller should arrange a different
    // framing strategy (we use original length in the packet for this reason).
    public static byte[] decompressLz4(byte[] compressed, int originalLength) {
        if (compressed == null) return null;
        LZ4SafeDecompressor decompressor = FACTORY.safeDecompressor();
        byte[] restored = new byte[originalLength];
        int decompressedLen = decompressor.decompress(compressed, 0, compressed.length, restored, 0);
        if (decompressedLen != originalLength) {
            byte[] exact = new byte[decompressedLen];
            System.arraycopy(restored, 0, exact, 0, decompressedLen);
            return exact;
        }
        return restored;
    }
}
