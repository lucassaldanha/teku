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

### Feasibility findings (confirmed)

1. **Wire format is interoperable (✅).** The bidirectional cross-compatibility check in
   `@Setup` passed for every payload type during every benchmark run: snappy-java decodes
   Netty's block output and vice-versa. Both implementations speak the same Google Snappy
   block format. A migration would not break gossip interoperability with the network.
2. **Bounds pre-check:** confirmed Netty exposes no public `uncompressedLength` equivalent; a
   migration must read the varint preamble manually to preserve gossip's pre-allocation DoS guard.
3. **Thread-safety:** confirmed — Netty `Snappy` is a stateful instance (the benchmark creates a
   fresh one per call); a migration needs a per-use instance / `ThreadLocal` / `reset()`.

### Measured performance

Environment: local dev machine (darwin), JDK 25, JMH 1.37, `-f 1 -wi 2 -i 5`, payloads from
`DataStructureUtil` (seed 1) on mainnet Fulu. Numbers are average time (`us/op`, lower is better)
and allocation (`gc.alloc.rate.norm`, `B/op`, lower is better). Reproduce with the command in the
module README. **Caveat:** `DataStructureUtil` payloads are high-entropy (random BLS signatures),
so these are not mainnet-representative *absolute* numbers — but both implementations receive
byte-identical input, so the *relative* comparison is valid.

| Payload | Op | snappy-java us/op | Netty us/op | snappy-java B/op | Netty B/op |
|---|---|---|---|---|---|
| Attestation | compress | 2.318 | 4.259 | 36,080 | 90,720 |
| Attestation | decompress | 0.687 | 1.565 | 16,640 | 83,296 |
| AggregateAndProof | compress | 2.253 | 3.732 | 36,544 | 91,040 |
| AggregateAndProof | decompress | 0.700 | 1.551 | 16,848 | 84,360 |
| SyncCommitteeMessage | compress | 0.224 | **0.201** | 384 | 1,240 |
| SyncCommitteeMessage | decompress | 0.213 | **0.064** | 160 | 920 |
| BeaconBlock | compress | 3.775 | 21.578 | 124,896 | 183,928 |
| BeaconBlock | decompress | 1.267 | 5.613 | 57,656 | 284,032 |
| DataColumnSidecar | compress | 2.340 | 4.258 | 38,008 | 92,056 |
| DataColumnSidecar | decompress | 0.728 | 1.652 | 17,528 | 87,736 |

(Bold = the implementation that won that row.)

### Interpretation

- **snappy-java wins on every realistic gossip payload** — attestations, aggregates, beacon
  blocks, and data column sidecars — on both latency and allocation, often substantially
  (BeaconBlock compress ~5.7×; decompress ~4.4×). The native engine also allocates roughly 2×
  less for medium payloads and up to ~5× less for the large BeaconBlock decompress path.
- **Netty wins only on the smallest payload** (`SyncCommitteeMessage`, the tiny case), where the
  fixed JNI-crossing cost of snappy-java dominates the work — Netty's decompress is ~3.3× faster
  there. This is the predicted small-message crossover.
- **Part of Netty's allocation disadvantage is benchmark-tunable.** The naive growable `ByteBuf`
  sizing (`in.length * 4`, doubling) inflates large-payload allocation; a production migration
  using pooled/right-sized buffers would narrow — but not erase — the gap, and would not change
  the latency ordering.

### Recommendation

snappy-java is meaningfully faster and lower-allocation for the payloads that dominate gossip
volume (attestations/aggregates) and that matter most under load (blocks, data column sidecars).
The wire format is interoperable, so a migration is *safe*, but the measured performance cost is
real and one-directional for everything except the smallest message.

**Therefore: do not migrate gossip to Netty purely for dependency consolidation.** The native
dependency removal (eliminating the musl-libc class of failures) is the only argument for
switching, and it is outweighed here by a consistent 2–5× latency/allocation regression on the
hot paths. If the native-library operational risk is judged unacceptable on its own merits, this
data quantifies the price of removing it, and any such migration must still add the varint-based
bounds-check helper and per-use `Snappy` instancing from the feasibility section.
