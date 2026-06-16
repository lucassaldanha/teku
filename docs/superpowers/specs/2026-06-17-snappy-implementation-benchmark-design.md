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

## Decision output

After running the benchmark, the evaluation concludes with a recommendation:

- If Netty is competitive (and given it removes the native dependency that already caused
  outages), recommend migrating gossip to Netty block compression — with the bounds-check helper
  and thread-safety handling from the feasibility section.
- If snappy-java is materially faster on the large-payload (sidecar) cases that matter most,
  record the measured gap so the native-dependency risk can be weighed against it explicitly.
