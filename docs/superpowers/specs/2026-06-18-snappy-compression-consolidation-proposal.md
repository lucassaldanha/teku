# Snappy Compression Consolidation Proposal

**Status:** Proposal / for team review
**Date:** 2026-06-18
**Author:** Lucas Saldanha
**Scope:** Teku consensus client — gossip and RPC Snappy compression

---

## TL;DR

Teku currently uses **two** different Snappy implementations: `snappy-java` (JNI/native C++) for
gossip messages, and **Netty's** Snappy codec (pure-Java) for RPC. Each has a problem — snappy-java
ships a native library whose custom loader has caused production startup failures, and Netty's
pure-Java Snappy is the slowest of the options we measured.

We evaluated alternatives, benchmarked four implementations across realistic gossip payloads, and
recommend consolidating **both** paths onto a single library: **aircompressor-v3**. Its native
implementation uses the modern Foreign Function & Memory API (FFM, `java.lang.foreign`) instead of
JNI — matching or beating snappy-java's speed while removing the native-loader fragility — and it
ships a competitive pure-Java fallback in the same dependency. We propose rolling it out behind
per-node feature flags, monitoring on canaries, then making it the default.

---

## 1. Background & Motivation

### 1.1 Current state — two Snappy libraries

| Path | Library | Implementation | Snappy format |
|---|---|---|---|
| Gossip | `org.xerial.snappy:snappy-java` | JNI → native C++ | block |
| RPC | Netty (`io.netty.handler.codec.compression.Snappy`) | pure Java | framed/streaming |

Carrying two implementations of the same algorithm is a maintenance burden: two dependencies to
track, two sets of behaviours to reason about, and two upgrade/security surfaces.

### 1.2 Problem with snappy-java: native-loader fragility

snappy-java is a JNI binding around a bundled native C++ library. It uses a custom loader that
unpacks and links a platform-specific `.so`/`.dylib`/`.dll` at startup. That loader has been a
source of operational pain:

- **musl-libc startup failures.** On musl-based environments the loader's platform detection
  misfires, producing `java.lang.NoClassDefFoundError: Could not initialize class
  org.xerial.snappy.Snappy` and preventing the node from running.
- **Behaviour churn across patch releases.** The error-handling behaviour around native
  allocation failures changed between adjacent patch versions (e.g. an `OutOfMemoryError` that was
  previously masked as `INVALID_CHUNK_SIZE` became visible), which changed how compression failures
  surfaced in Teku without any code change on our side.

These are properties of the JNI/native-loading approach, and they represent real operational risk
for operators on non-mainstream platforms.

### 1.3 Problem with Netty's Snappy: performance

Netty's Snappy is pure Java (no native dependency, so no loader fragility), but in our benchmarks it
was the **slowest and highest-allocating** of every implementation tested, on every realistic gossip
payload except the smallest. It is fine for RPC's relatively low volume today, but it is not a
candidate we would want to standardize gossip on, and it leaves performance on the table.

### 1.4 Goal

Land on a **single, well-maintained Snappy dependency** across the project that:

1. removes the native-loader class of failure,
2. performs at least as well as today on the gossip hot paths, and
3. reduces maintenance surface by consolidating two libraries into one.

---

## 2. Methodology

### 2.1 Selection criteria for an alternative

We looked for a library that is:

- **Well-maintained** and in active production use elsewhere.
- **Snappy-capable**, specifically the *block* format gossip requires (the Ethereum gossip spec
  mandates Snappy block; this immediately rules out Zstd-only libraries such as `zstd-jni`).
- **Robust to deploy** — ideally avoiding the JNI native-loader pattern that caused our incidents,
  while still able to reach native-level performance.
- **Performant** — competitive with the native C++ baseline (snappy-java), especially on the
  large payloads (beacon blocks, data-column sidecars) that matter most under load.

### 2.2 Candidate review

| Candidate | Verdict |
|---|---|
| **snappy-java** (status quo) | Best-maintained *JNI* Snappy binding, but the JNI native loader is exactly the liability we want to remove. |
| **Netty Snappy** (status quo) | Pure-Java, no native dependency, but slowest/highest-allocation in our tests. |
| **zstd-jni** | Well-maintained native binding, but **Zstandard only** — cannot serve the Snappy wire format gossip requires. Ruled out. |
| **aircompressor-v3** | **Selected.** Apache-2.0, actively maintained, used in production by Trino. Provides Snappy **block** format in both a native implementation (via FFM, not JNI) and a pure-Java implementation in one dependency. |

**Why aircompressor-v3's native path is different:** it uses the Foreign Function & Memory API
(`java.lang.foreign`, finalized in Java 22) instead of JNI. There is no hand-rolled native loader
of the kind that failed on musl; the JVM's own linker is used, and if the native library cannot
load on a given platform the library transparently falls back to its pure-Java implementation. Teku
already runs on Java 25, so the Java 22+ requirement is satisfied.

### 2.3 Testing approach

We built a JMH micro-benchmark (`SnappyCompressionBenchmark` in `eth-benchmark-tests`) comparing
four implementations:

- `SNAPPY_JAVA` — snappy-java (current gossip)
- `NETTY` — Netty Snappy (current RPC)
- `AIRCOMPRESSOR_NATIVE` — aircompressor-v3 FFM-native
- `AIRCOMPRESSOR_JAVA` — aircompressor-v3 pure-Java

Design decisions:

- **Realistic payloads.** SSZ-serialized gossip message types across the real size spectrum:
  attestation, aggregate-and-proof, sync-committee message, a full beacon block, and a data-column
  sidecar — generated with a fixed seed for reproducibility.
- **Engines compared directly.** Each implementation is driven through its raw library API (not
  through Teku's wrapper classes), with `byte[]`↔buffer marshalling included where the library
  requires it, because gossip code speaks byte arrays.
- **Metrics.** Throughput and average time per op (JMH) plus allocation per op (`-prof gc`).
- **Correctness / interoperability.** A startup check round-trips every implementation's output
  through snappy-java in both directions, verifying they all share the same Snappy block wire
  format. This is the safety gate for a migration — and, because the aircompressor `*Native*`
  classes have no silent pure-Java fallback, a passing run also proves the FFM native library
  actually loaded and executed.

**Benchmark environment:** local dev machine (darwin/aarch64), JDK 25, JMH 1.37, `-f 1 -wi 2 -i 5`.

**Caveat on the numbers:** the synthetic payloads are high-entropy (random BLS signatures), so the
*absolute* figures are not representative of mainnet compression ratios. But every implementation
receives byte-identical input, so the *relative* comparison between them is valid. Real-traffic
measurement is part of the rollout plan (Section 5).

---

## 3. Results

### 3.1 Interoperability (the migration safety gate)

✅ All four implementations produce and consume a mutually-decodable Snappy block wire format, in
both directions against snappy-java. A node running any of them stays interoperable with the
network. (Both Snappy block and Snappy framed are standardized formats — switching implementation is
not a protocol change.)

### 3.2 Performance — average time per operation (µs/op, lower is better)

Bold = fastest in that row.

| Payload | Op | snappy-java | Netty | aircompressor-native | aircompressor-java |
|---|---|---|---|---|---|
| Attestation | compress | 2.183 | 3.993 | **2.036** | 2.999 |
| Attestation | decompress | 0.648 | 1.516 | **0.478** | 0.513 |
| AggregateAndProof | compress | 2.073 | 3.470 | **1.946** | 3.147 |
| AggregateAndProof | decompress | 0.658 | 1.462 | **0.471** | 0.561 |
| SyncCommitteeMessage | compress | 0.205 | 0.188 | **0.089** | 0.528 |
| SyncCommitteeMessage | decompress | 0.201 | 0.062 | 0.027 | **0.010** |
| BeaconBlock | compress | 3.552 | 20.487 | **3.425** | 9.693 |
| BeaconBlock | decompress | **1.222** | 5.260 | ~1.0–1.4 † | 2.145 |
| DataColumnSidecar | compress | 2.152 | 4.014 | **2.006** | 2.992 |
| DataColumnSidecar | decompress | 0.681 | 1.581 | **0.489** | 0.567 |

† aircompressor-native beacon-block decompress measured 0.989 µs/op in the throughput pass (faster
than snappy-java) but was noisier in the average-time pass; treat it as a tie with snappy-java.

### 3.3 Allocation per operation (`gc.alloc.rate.norm`, bytes/op, lower is better)

- **aircompressor-native ≈ snappy-java** — within ~24 B/op (e.g. beacon-block decompress 57,680 vs
  57,656; compress 124,920 vs 124,896).
- **aircompressor-java** matches snappy-java exactly on decompress (e.g. 57,656 B/op for a block),
  and allocates ~2× on compress.
- **Netty is the worst allocator** — e.g. beacon-block decompress 284,032 B/op, roughly 5×
  snappy-java.

### 3.4 Interpretation

- **aircompressor-native matches or beats snappy-java on essentially every operation** — equal-or-
  faster compress, clearly faster decompress (the gossip-receive hot path, ~1.3–1.4× on
  attestations/aggregates/sidecars), and near-identical allocation — while using FFM instead of JNI.
- **aircompressor-java is a much better pure-Java option than Netty** — ~2× faster on blocks and
  snappy-java-level allocation on decompress, making it a strong native-free fallback.
- **Netty is the weakest** of the four for gossip block work and is not a consolidation target.

---

## 4. Proposed Changes

**Adopt `aircompressor-v3` as the single Snappy library for both gossip and RPC**, replacing both
snappy-java (gossip) and Netty's Snappy (RPC).

### 4.1 Gossip (replaces snappy-java)

Gossip block compression is encapsulated in a single class, `SnappyBlockCompressor`. We turn it into
an interface with two implementations — `SnappyJavaBlockCompressor` (the existing behaviour) and
`AircompressorSnappyBlockCompressor` (new) — selected at runtime. The new implementation:

- uses aircompressor's `SnappyCompressor.create()` / `SnappyDecompressor.create()` factories
  (native when available, automatic pure-Java fallback otherwise);
- preserves the DoS bounds pre-check via `SnappyDecompressor.getUncompressedLength(...)` (a direct
  replacement for snappy-java's `uncompressedLength`), so oversized payloads are still rejected
  before allocation;
- keeps the exact public API and exception semantics, so no callers change.

### 4.2 RPC (replaces Netty's Snappy)

RPC uses the Snappy *framed* format. Teku already implements the framing itself (stream identifier,
32 KB chunking, CRC32C checksums); Netty's `Snappy` is only used for the inner per-chunk block
codec. We abstract that inner codec behind a small strategy interface with a Netty implementation
(default) and an aircompressor implementation, leaving all framing logic untouched. This is a tight,
low-risk change (≈6 call sites) and inherits the same performance win shown above.

### 4.3 Consolidation outcome

After both changes, `networking:eth2` uses aircompressor for all Snappy. Netty remains a dependency
(used widely for non-Snappy networking) but its Snappy codec is no longer used; snappy-java is no
longer needed on the gossip path. Two non-gossip snappy-java users remain (`PrettyPrintCommand`,
era-file handling) and would be migrated in a follow-up before the dependency can be dropped
project-wide.

> Detailed, task-by-task implementation steps live in the companion plan:
> `docs/superpowers/plans/2026-06-17-aircompressor-gossip-migration.md`.

---

## 5. Rollout Plan

Because both Snappy formats are standardized, switching implementation is **not** a protocol change —
a node can run either implementation and stay interoperable. This lets us roll out gradually and
roll back instantly via configuration, with no network-compatibility risk.

### 5.1 Phase 1 — ship behind hidden feature flags (default OFF)

Add two hidden, experimental CLI flags (defaulting to current behaviour):

- `--Xp2p-gossip-snappy-aircompressor-enabled`
- `--Xp2p-rpc-snappy-aircompressor-enabled`

Both implementations coexist in the binary; the flags select which is used. Default-off means a
normal release changes nothing until an operator opts in.

### 5.2 Phase 2 — canary + monitoring

Enable the **gossip** flag on a small number of our own nodes (hoodi/mainnet canaries) and observe:

- **Stability:** no compression/decompression errors in logs; confirm via the existing loud
  failure paths (compression errors are explicit exceptions, not silent).
- **Native engagement:** confirm the FFM native path actually loaded (vs falling back to pure-Java)
  on the target platform/architecture.
- **Resource & health metrics:** CPU, allocation/GC pressure, gossip processing latency, missed/late
  attestations, peer scores — compared against control nodes on the default path.
- **Interoperability in the wild:** canary nodes continue to gossip successfully with the rest of
  the network.

Then repeat for the **RPC** flag.

### 5.3 Phase 3 — real-traffic benchmarking

Supplement the synthetic JMH results with measurements on representative mainnet data (real beacon
blocks and data-column sidecars, which have realistic, more-compressible content), to confirm the
relative performance and allocation advantages hold on production traffic, not just synthetic input.

### 5.4 Phase 4 — make it the default

Once the canary period is clean and the real-traffic numbers confirm the benefit:

1. Flip the flag defaults to aircompressor.
2. After a stabilization release, remove the legacy snappy-java (gossip) and Netty-Snappy (RPC) code
   paths and the flags.
3. Migrate the remaining non-gossip snappy-java users and drop the `snappy-java` dependency
   project-wide.

### 5.5 Rollback

At any point in Phases 1–3, set the relevant flag back to its default to revert to the previous
implementation immediately, with no redeploy of a different artifact required.

---

## 6. Risks & Caveats

- **Platform native support.** aircompressor's native path requires its bundled native library to
  load; if it can't on some platform, it falls back to pure-Java automatically (still correct, just
  without the native speedup). Phase 2 explicitly verifies native engagement on our targets.
- **`noexec` temp directories.** aircompressor unpacks its native library to a temp dir; operators
  on `noexec`-mounted temp dirs can set `-Daircompressor.tmpdir=<path>`, or force pure-Java with
  `-Dio.airlift.compress.v3.disable-native=true`.
- **Thread-safety.** aircompressor's compressor/decompressor instances are stateless and safe to
  share across the concurrent gossip threads; the implementation plan includes a concurrency test to
  verify this.
- **Java version.** Requires Java 22+ for FFM. Teku is on Java 25 — satisfied.
- **Security advisory.** A historical advisory affected aircompressor's *pure-Java* decompressors on
  malformed input with reused output buffers; it is fixed in the version we target, and our code
  always decompresses into a freshly allocated, exactly-sized buffer, so it does not apply.
- **RPC per-chunk copy.** The aircompressor RPC codec adds one ByteBuf→byte[] copy per chunk; small
  relative to the measured performance gain and isolated to the codec class.

---

## Appendix

### A. Reproducing the benchmark

```bash
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -prof gc"
# single payload / single implementation:
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -prof gc -p payload=DATA_COLUMN_SIDECAR -p impl=AIRCOMPRESSOR_NATIVE"
```

Benchmark source: `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/networking/SnappyCompressionBenchmark.java`

### B. Library details

- **aircompressor-v3** — `io.airlift:aircompressor-v3` (latest 3.5 at time of writing), Apache-2.0,
  used by Trino. Native via `java.lang.foreign`; classes `SnappyNativeCompressor`/`Decompressor`
  (FFM native) and `SnappyJavaCompressor`/`Decompressor` (pure-Java), selected by
  `SnappyCompressor.create()` / `SnappyDecompressor.create()`.

### C. Companion documents (internal)

- Design + benchmark spec: `docs/superpowers/specs/2026-06-17-snappy-implementation-benchmark-design.md`
- Implementation plan (migration + flags + RPC): `docs/superpowers/plans/2026-06-17-aircompressor-gossip-migration.md`
- Benchmark build plan: `docs/superpowers/plans/2026-06-17-snappy-implementation-benchmark.md`
