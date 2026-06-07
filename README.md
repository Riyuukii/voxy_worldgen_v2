### Note: Certain parts of this fork were written with the help of AI and were made purely for private use. However, you are more than welcome to see how the code works or to merge the changes in this fork into your project. (all of these are also WIP and is unstable yet)


- **Changes:** implemented an optimized LOD network pipeline and helpers (`CompressionUtil`, `DeltaFilter`, `ChunkSendQueue`, `OptimizedLODData`) and wired them into `NetworkHandler` and the client receiver (`NetworkClientHandler`).

- **Compact binary layout:** `OptimizedLODData` uses a column-oriented header (dimension id, `ChunkPos`, `minY`) + a compact section bitmask followed by only-present sections. Each section is: `y`, `states`, `biomes`, optional `blockLight`, optional `skyLight`.

- **Adaptive per-array compression:** per-array "compressed-flagged" framing — boolean flag, then either raw bytes or LZ4-compressed bytes with original length. Implemented high-speed compression via `CompressionUtil` (LZ4) and only applied when it reduces payload size.

- **Delta filtering (redundant-send elimination):** `DeltaFilter` computes a CRC32 fingerprint over raw section arrays per player+chunk and suppresses sends when the CRC matches the last sent value. This avoids re-sending identical structural LOD data.

- **Section bitmasking:** a byte-array bitmask indicates which chunk sections are present, avoiding empty-section overhead entirely (no more sending full empty arrays).

- **Throttled send queue:** `ChunkSendQueue` enqueues outgoing payloads and drains a bounded number per server tick (configurable cap), preventing huge burst floods on teleports or multi-chunk syncs and smoothing Netty/OS load.

- **Backward-safe registration:** new payload `OptimizedLODData` is registered alongside existing payloads in `NetworkHandler.init()`; code paths in `NetworkHandler` were updated to use `sendOptimizedLODData(...)` so the switch is internal and can be rolled back easily.

- **Client support:** `NetworkClientHandler` now receives `OptimizedLODData`, reconstructs `PalettedContainer`/`LevelChunkSection` data using `RegistryFriendlyByteBuf`, and calls `VoxyIntegration.rawIngest(...)` — same ingestion path as before with significantly smaller payloads.

- **Why this reduces bandwidth & CPU:**
  - Only-present sections + section mask reduces transmitted metadata.
  - LZ4 targets palette/byte-array redundancy (fast compression, low CPU).
  - Delta filtering prevents repeated transmission of unchanged chunks.
  - Batching + throttling reduces transient burst traffic and overload.
  - Overall effect: large reductions in bytes/sec for LOD syncs and fewer redundant CPU cycles spent serializing/deserializing unchanged data.

- **Files added/modified (quick refs):**
  - Added: `src/main/java/.../network/CompressionUtil.java`, `DeltaFilter.java`, `ChunkSendQueue.java`, OptimizedLODData.java
  - Modified: `src/main/java/.../network/NetworkHandler.java` (register + `sendOptimizedLODData`), `src/client/java/.../network/NetworkClientHandler.java`
  - Build: build.gradle — added `org.lz4:lz4-java` dependency
