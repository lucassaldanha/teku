# Snappy Implementation Benchmark & Feasibility Evaluation — Design

**Date:** 2026-06-17
**Status:** Approved (design)

## Goal

Decide whether Teku can drop the `org.xerial.snappy:snappy-java` dependency and use
Netty's Snappy implementation (`io.netty.handler.codec.compression.Snappy`) for **all**
Snappy usage, including gossip — landing on a single Snappy dependency across the project.

Today:

- **Gossip** (`networking/eth2/.../gossip/encoding/SnappyBlockCompressor`) uses snappy-java's
  `Snappy.compress` / `Snappy.uncompress` — Google Snappy **block** format, single-shot whole-message.
- **RPC** (`networking/eth2/.../rpc/core/encodings/compression/snappy/`) uses the Snappy
  **framed/streaming** format and already calls Netty's `Snappy.encode` / `Snappy.decode`
  (the raw block codec) underneath the framing.

This effort produces two deliverables that together inform the migrate / don't-migrate decision:

1. **A JMH benchmark** comparing snappy-java vs Netty block compression across realistic gossip
   payloads, measuring throughput/latency and allocation/GC pressure.
2. **A written feasibility evaluation** (this document) covering whether the migration is *safe*,
   not just whether it is *faster*.

## Motivation

snappy-java is a native (JNI/C++) library. It has already caused production failures on
musl-libc hosts (native library load/initialization failures surfacing as
`NoClassDefFoundError: Could not initialize class org.xerial.snappy.Snappy`). Removing it:

- Eliminates that entire class of native-library / platform-compatibility failure.
- Consolidates the project on a single Snappy implementation (the one already used by RPC).

The trade-off is that native C++ code *may* outperform Netty's pure-Java implementation,
particularly for large payloads. Quantifying that trade-off is the purpose of the benchmark.

## Non-goals

- Migrating production code. This effort measures and evaluates only; the benchmark is
  self-contained and changes no production code.
- Changing the RPC path (already on Netty).
- Benchmarking the Snappy *framed* format (RPC) — gossip is the only consumer of snappy-java.

## Location & structure

- New JMH benchmark:
  `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/networking/SnappyCompressionBenchmark.java`
  (mirrors the existing `networking/RateTrackerBenchmark.java` placement and conventions).
- No production code changes — the Netty block compress/decompress path is implemented inline
  in the benchmark class, calling `io.netty.handler.codec.compression.Snappy` directly,
  alongside the existing `SnappyBlockCompressor` (snappy-java).
- Dependencies: `eth-benchmark-tests` already has `testFixtures(project(':ethereum:spec'))`
  (for `DataStructureUtil`) and `testFixtures(project(':networking:eth2'))`; Netty is already on
  the classpath transitively via `networking:eth2`. Verify Netty is resolvable from the `jmh`
  source set during implementation and add an explicit `jmhImplementation` Netty dependency if not.

## Payloads

Realistic gossip message types, SSZ-serialized to Tuweni `Bytes`, generated via
`DataStructureUtil` (available through `ethereum:spec` testFixtures) with a **fixed seed** for
reproducibility. Covering the real gossip size spectrum:

- Attestation (~200 B), `AggregateAndProof`, `SyncCommitteeMessage` — small
- A full `BeaconBlock` — medium
- A `DataColumnSidecar` / `BlobSidecar` — large (~128 KB+)

Payloads are generated and SSZ-serialized once in `@Setup`.

**Caveat (documented in benchmark + here):** `DataStructureUtil` fills BLS signatures and many
fields with high-entropy random data, so absolute *compression ratios* will not match mainnet
traffic (real beacon data has more compressible zero-padding). This does not invalidate the
comparison: both implementations receive byte-identical input, so throughput, latency, and
allocation differences between them remain meaningful. Compression ratio is explicitly **not**
a reported metric for this reason.

## Benchmark shape

- `@State(Scope.Thread)` holding, per message type:
  - the raw SSZ `Bytes` (input for `compress`)
  - the pre-compressed `Bytes` (input for `decompress`)
- `@Param` over message type (attestation, aggregate, sync committee message, beacon block,
  data column / blob sidecar).
- `@Param` over implementation: `SNAPPY_JAVA`, `NETTY`.
- Two benchmark methods: `compress()` and `decompress()`.
- **Fair-comparison rule:** both paths accept and return Tuweni `Bytes`, so the Netty path
  includes its `Bytes` ↔ `ByteBuf` marshalling cost. Gossip code speaks `Bytes`, so this
  marshalling is part of the real cost of a migration and must be measured. A fresh Netty
  `Snappy` instance is created per op (it is stateful / not thread-safe — see Feasibility).
- Modes: `Throughput` and `AverageTime`.
- Allocation / GC: measured via JMH's `-prof gc`, documented in a short README note next to the
  benchmark (or in the module `README.md`).

### Netty block path (inline in benchmark)

- **Compress:** wrap input `Bytes` in `Unpooled.wrappedBuffer(...)`, allocate an output
  `ByteBuf`, call `new Snappy().encode(in, out, in.readableBytes())`, copy out to `byte[]` /
  `Bytes`, release buffers.
- **Decompress:** `new Snappy().decode(in, out)` into an output `ByteBuf`, copy to `Bytes`,
  release buffers.

## Feasibility evaluation

These are the questions that determine whether a future migration is *safe*. Findings will be
confirmed during implementation and recorded back into this document.

1. **Wire-format compatibility (migration crux).** Both implementations use the Google Snappy
   *block* format (varint-length preamble + compressed block). Netty's `Snappy.encode` writes
   the varint length preamble then the block; `decode` reads it back — matching snappy-java's
   `compress`/`uncompress`. Verify round-trip **both directions**
   (snappy-java output → Netty decode, and Netty output → snappy-java decode) with a small
   throwaway check during implementation. If both directions succeed, the wire format is
   interoperable and a node running either implementation stays gossip-compatible with the network.

2. **Uncompressed-length bounds pre-check.** Gossip's `SnappyBlockCompressor.uncompress` calls
   `Snappy.uncompressedLength(...)` to reject oversized payloads *before* allocating the output
   buffer (DoS guard against malicious peers). Netty exposes no public equivalent; a migration
   would read the varint preamble manually (or decode into a max-bounded `ByteBuf`) to preserve
   this guard. Documented as a required small helper for any real migration.

3. **Thread-safety.** snappy-java exposes thread-safe static methods. Netty's `Snappy` is a
   stateful instance and is **not** thread-safe — a migration must use a per-use instance,
   a `ThreadLocal`, or `reset()` between uses. Relevant because gossip decompression is highly
   concurrent. Documented.

4. **API shape.** snappy-java works on `byte[]`; Netty works on `ByteBuf`. Gossip code speaks
   Tuweni `Bytes`, so either implementation requires marshalling. This cost is included in the
   benchmark (see fair-comparison rule).

## Testing

JMH benchmarks are not unit-tested, consistent with the existing `eth-benchmark-tests` module.
The one correctness check is the bidirectional cross-compatibility round-trip (feasibility item 1),
performed during implementation to confirm wire-format interoperability before any migration is
considered.

## Results & Decision output

Four implementations were benchmarked: **snappy-java** (JNI native, current gossip),
**Netty** (pure-Java, current RPC), **aircompressor-v3 native** (FFM native — `java.lang.foreign`,
not JNI), and **aircompressor-v3 java** (pure-Java).

### Feasibility findings (confirmed)

1. **Wire format is interoperable across all four (✅).** The cross-compatibility check in `@Setup`
   verifies, in both directions against snappy-java (the production codec), that every
   implementation produces and consumes the same Google Snappy block format. It passed for every
   payload on every run. Exercising the aircompressor-native path through this check also proves its
   FFM native library loaded and executed on this platform (the `*Native*` classes have no silent
   pure-Java fallback). A migration to any of these would not break gossip interoperability.
2. **Bounds pre-check:** Netty and aircompressor both lack snappy-java's `uncompressedLength`
   pre-check. aircompressor's block `Decompressor` additionally *requires* the caller to supply an
   adequately-sized output buffer. Either way, a migration must read the Snappy block's varint
   preamble to size output and preserve gossip's pre-allocation DoS guard.
3. **Thread-safety:** Netty `Snappy` and aircompressor `Compressor`/`Decompressor` are stateful
   instances (the benchmark creates a fresh one per call); a migration needs a per-use instance /
   `ThreadLocal`. snappy-java's static methods are thread-safe.

### Measured performance

Environment: local dev machine (darwin/aarch64), JDK 25, JMH 1.37, `-f 1 -wi 2 -i 5`, payloads from
`DataStructureUtil` (seed 1) on mainnet Fulu. Average time (`us/op`, lower is better). **Caveat:**
`DataStructureUtil` payloads are high-entropy (random BLS signatures), so these are not
mainnet-representative *absolute* numbers — but all implementations receive byte-identical input, so
the *relative* comparison is valid. Bold = fastest in that row.

| Payload | Op | snappy-java | Netty | aircompressor-native | aircompressor-java |
|---|---|---|---|---|---|
| Attestation | compress | 2.183 | 3.993 | **2.036** | 2.999 |
| Attestation | decompress | 0.648 | 1.516 | **0.478** | 0.513 |
| AggregateAndProof | compress | 2.073 | 3.470 | **1.946** | 3.147 |
| AggregateAndProof | decompress | 0.658 | 1.462 | **0.471** | 0.561 |
| SyncCommitteeMessage | compress | 0.205 | 0.188 | **0.089** | 0.528 |
| SyncCommitteeMessage | decompress | 0.201 | 0.062 | 0.027 | **0.010** |
| BeaconBlock | compress | 3.552 | 20.487 | **3.425** | 9.693 |
| BeaconBlock | decompress | **1.222** | 5.260 | 1.389* | 2.145 |
| DataColumnSidecar | compress | 2.152 | 4.014 | **2.006** | 2.992 |
| DataColumnSidecar | decompress | 0.681 | 1.581 | **0.489** | 0.567 |

\* aircompressor-native BeaconBlock decompress measured 0.989 us/op (faster than snappy-java's
1.222) in the throughput pass; the avgt pass was noisier. Treat block decompress as a tie between
snappy-java and aircompressor-native.

Allocation (`gc.alloc.rate.norm`, `B/op`): **aircompressor-native ≈ snappy-java** (within ~24 B/op —
e.g. BeaconBlock decompress 57,680 vs 57,656; compress 124,920 vs 124,896). **aircompressor-java**
matches snappy-java exactly on *decompress* (16,640 B/op attestation; 57,656 block) but allocates ~2×
on compress. **Netty** is the worst allocator (BeaconBlock decompress 284,032 B/op — ~5× snappy-java).

### Interpretation

- **aircompressor-native matches or beats snappy-java on essentially every operation** — equal-or-
  faster compress, clearly faster decompress (the gossip receive hot path: ~1.3–1.4× faster on
  attestations/aggregates/sidecars), dramatically faster on the tiny sync-committee message, and
  near-identical allocation. Crucially it does this **via FFM, not JNI** — eliminating the custom
  native-loader path that caused the musl-libc failures.
- **aircompressor-java is a far better pure-Java option than Netty** — ~2× faster compress and
  decompress on blocks, and it allocates snappy-java-equivalent bytes on decompress (vs Netty's ~5×).
  It's a viable native-free fallback.
- **Netty is the weakest of the four** for gossip block work — slowest and highest-allocation on
  every realistic payload except the tiny sync-committee message. (Part of its allocation cost is the
  benchmark's naive `ByteBuf` sizing, but the latency ordering would not change.)

### Recommendation

The earlier "don't migrate" conclusion was framed against **Netty only**. With aircompressor-v3 in
the picture, the recommendation changes:

**Evaluate migrating gossip (and ideally RPC too) to `aircompressor-v3`, using its FFM-native Snappy.**
It delivers native-class performance equal to or better than snappy-java's JNI on every gossip
payload, with near-identical allocation, while replacing JNI with `java.lang.foreign` — which removes
the musl-libc / native-loader class of failure that motivated this work. It is a single,
well-maintained dependency (Apache-2.0, used by Trino) that also bundles a competitive pure-Java
Snappy as a fallback, so it could consolidate **both** of Teku's current Snappy dependencies.

**Do not migrate gossip to Netty** — it is slower and allocates more than the status quo.

Caveats for an aircompressor migration: requires Java 22+ (Teku is on 25 ✓); unpacks a native lib to
a temp dir (`aircompressor.tmpdir`; mind `noexec` mounts) with `io.airlift.compress.v3.disable-native`
to force pure-Java; must add the varint-preamble bounds-check helper and per-use instancing (feasibility
items 2–3); and there is a published advisory about the *pure-Java* Snappy/LZ4 decompressors disclosing
prior buffer contents on malformed input when output buffers are reused — pin the latest version and
don't reuse uncleared output buffers (the native path and non-reused buffers are unaffected).
