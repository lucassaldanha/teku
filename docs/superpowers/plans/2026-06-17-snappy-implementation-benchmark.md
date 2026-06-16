# Snappy Implementation Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained JMH benchmark comparing snappy-java (native) vs Netty's Snappy (`io.netty.handler.codec.compression.Snappy`) block compression across realistic gossip payloads, measuring throughput/latency and allocation, plus an executable wire-format cross-compatibility check.

**Architecture:** A single JMH benchmark class in the existing `eth-benchmark-tests` module. It generates SSZ-serialized gossip messages once via `DataStructureUtil`, then benchmarks `compress`/`decompress` for both implementations selected by `@Param`. Both implementations are invoked directly (raw library APIs, not Teku's wrapper classes) so the comparison isolates the compression engines, with byte-array marshalling included on both sides. No production code is modified.

**Tech Stack:** Java 25, JMH 1.37, snappy-java 1.1.10.8 (`org.xerial.snappy.Snappy`), Netty 4.2.10 (`io.netty.handler.codec.compression.Snappy`, `io.netty.buffer.ByteBuf`), Tuweni Bytes, Teku `DataStructureUtil` / `TestSpecFactory`.

**Reference spec:** `docs/superpowers/specs/2026-06-17-snappy-implementation-benchmark-design.md`

---

### Task 1: Add JMH dependencies for both Snappy implementations

The benchmark calls `org.xerial.snappy.Snappy` and `io.netty.handler.codec.compression.Snappy` directly. snappy-java and netty-codec must be on the `jmh` source-set classpath. `netty-codec` (which contains `io.netty.handler.codec.compression.Snappy`) is pulled transitively by the managed `io.netty:netty-handler` artifact (version managed in `gradle/versions.gradle`). `org.xerial.snappy:snappy-java` is managed at `1.1.10.8`.

**Files:**
- Modify: `eth-benchmark-tests/build.gradle` (the `dependencies { ... }` block, after the existing `jmhImplementation` lines around line 27)

- [ ] **Step 1: Add the two dependencies**

In `eth-benchmark-tests/build.gradle`, locate the existing line:

```gradle
	jmhImplementation testFixtures(project('::networking:eth2'))
```

Add immediately after it:

```gradle
	jmhImplementation 'io.netty:netty-handler'
	jmhImplementation 'org.xerial.snappy:snappy-java'
```

- [ ] **Step 2: Verify dependency resolution**

Run: `./gradlew :eth-benchmark-tests:jmhClasses --dry-run`
Expected: configures without "Could not resolve" errors. (Full compile is verified in Task 3 once the class exists.)

- [ ] **Step 3: Commit**

```bash
git add eth-benchmark-tests/build.gradle
git commit -m "Add netty-handler and snappy-java to eth-benchmark-tests jmh classpath"
```

---

### Task 2: Create the SnappyCompressionBenchmark class

Single class containing: payload + implementation `@Param`s, a `@Setup(Level.Trial)` that generates SSZ payloads and pre-compresses them, an executable bidirectional cross-compatibility check (feasibility item 1 from the spec), the two `@Benchmark` methods, and static engine helper methods for each implementation.

Design decisions encoded here:
- Call raw library APIs directly (not `SnappyBlockCompressor`/`SnappyFramedCompressor`) so the benchmark measures the engines, not Teku's gossip-specific bounds-checking.
- Both implementations marshal to/from `byte[]` (snappy-java is byte[]-native; Netty includes `byte[]`→`ByteBuf`→`byte[]`), reflecting the real cost a migration would carry since gossip code speaks `Bytes`/`byte[]`.
- A new Netty `Snappy` instance is created per call (it is stateful / not thread-safe — a spec finding, made visible here).
- Fixed `DataStructureUtil` seed (`1`) for reproducibility.
- Mainnet Fulu spec so `DataColumnSidecar` is available and sizes are realistic.

**Files:**
- Create: `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/networking/SnappyCompressionBenchmark.java`

- [ ] **Step 1: Write the benchmark class**

Create `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/networking/SnappyCompressionBenchmark.java` with exactly this content:

```java
/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.benchmarks.networking;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.Snappy;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.util.DataStructureUtil;

/**
 * Compares snappy-java (native, used today for gossip block compression) against Netty's pure-Java
 * Snappy block codec (used today for RPC framing), to evaluate consolidating on a single Snappy
 * implementation. Both implementations are invoked via their raw library APIs so the comparison
 * isolates the compression engine; the {@code byte[]} marshalling Netty requires is included
 * because gossip code speaks byte arrays.
 *
 * <p>Run a specific payload/impl with GC profiling:
 *
 * <pre>
 *   ./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -prof gc"
 * </pre>
 */
@Fork(1)
@State(Scope.Thread)
public class SnappyCompressionBenchmark {

  public enum Impl {
    SNAPPY_JAVA,
    NETTY
  }

  public enum Payload {
    ATTESTATION,
    AGGREGATE,
    SYNC_COMMITTEE_MESSAGE,
    BEACON_BLOCK,
    DATA_COLUMN_SIDECAR
  }

  @State(Scope.Benchmark)
  public static class Data {

    @Param({
      "ATTESTATION",
      "AGGREGATE",
      "SYNC_COMMITTEE_MESSAGE",
      "BEACON_BLOCK",
      "DATA_COLUMN_SIDECAR"
    })
    public Payload payload;

    @Param({"SNAPPY_JAVA", "NETTY"})
    public Impl impl;

    // Uncompressed SSZ bytes (input for the compress benchmark).
    public byte[] raw;
    // Pre-compressed bytes produced by the impl under test (input for the decompress benchmark).
    public byte[] compressed;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      final Spec spec = TestSpecFactory.createMainnetFulu();
      final DataStructureUtil data = new DataStructureUtil(1, spec);
      final Bytes ssz =
          switch (payload) {
            case ATTESTATION -> data.randomAttestation().sszSerialize();
            case AGGREGATE -> data.randomSignedAggregateAndProof().sszSerialize();
            case SYNC_COMMITTEE_MESSAGE -> data.randomSyncCommitteeMessage().sszSerialize();
            case BEACON_BLOCK -> data.randomSignedBeaconBlock().sszSerialize();
            case DATA_COLUMN_SIDECAR -> data.randomDataColumnSidecar().sszSerialize();
          };
      raw = ssz.toArrayUnsafe();

      compressed =
          switch (impl) {
            case SNAPPY_JAVA -> snappyJavaCompress(raw);
            case NETTY -> nettyCompress(raw);
          };

      // Feasibility check (spec item 1): the two implementations must produce/consume the same
      // Snappy block wire format in both directions, or a migration would break gossip
      // interoperability. Fail fast and loudly if not.
      verifyCrossCompatibility(raw);
    }
  }

  @Benchmark
  @Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
  public byte[] compress(final Data data) throws IOException {
    return switch (data.impl) {
      case SNAPPY_JAVA -> snappyJavaCompress(data.raw);
      case NETTY -> nettyCompress(data.raw);
    };
  }

  @Benchmark
  @Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
  public byte[] decompress(final Data data) throws IOException {
    return switch (data.impl) {
      case SNAPPY_JAVA -> snappyJavaUncompress(data.compressed);
      case NETTY -> nettyUncompress(data.compressed);
    };
  }

  static byte[] snappyJavaCompress(final byte[] in) throws IOException {
    return org.xerial.snappy.Snappy.compress(in);
  }

  static byte[] snappyJavaUncompress(final byte[] in) throws IOException {
    return org.xerial.snappy.Snappy.uncompress(in);
  }

  static byte[] nettyCompress(final byte[] in) {
    final ByteBuf inBuf = Unpooled.wrappedBuffer(in);
    final ByteBuf outBuf = Unpooled.buffer(32 + in.length / 2);
    try {
      new Snappy().encode(inBuf, outBuf, inBuf.readableBytes());
      final byte[] out = new byte[outBuf.readableBytes()];
      outBuf.readBytes(out);
      return out;
    } finally {
      inBuf.release();
      outBuf.release();
    }
  }

  static byte[] nettyUncompress(final byte[] in) {
    final ByteBuf inBuf = Unpooled.wrappedBuffer(in);
    final ByteBuf outBuf = Unpooled.buffer(in.length * 4);
    try {
      new Snappy().decode(inBuf, outBuf);
      final byte[] out = new byte[outBuf.readableBytes()];
      outBuf.readBytes(out);
      return out;
    } finally {
      inBuf.release();
      outBuf.release();
    }
  }

  static void verifyCrossCompatibility(final byte[] raw) throws IOException {
    final byte[] viaNetty = nettyCompress(raw);
    if (!Arrays.equals(raw, snappyJavaUncompress(viaNetty))) {
      throw new IllegalStateException(
          "snappy-java failed to round-trip Netty-compressed output (wire format mismatch)");
    }
    final byte[] viaJava = snappyJavaCompress(raw);
    if (!Arrays.equals(raw, nettyUncompress(viaJava))) {
      throw new IllegalStateException(
          "Netty failed to round-trip snappy-java-compressed output (wire format mismatch)");
    }
  }
}
```

- [ ] **Step 2: Apply formatting**

Run: `./gradlew :eth-benchmark-tests:spotlessApply`
Expected: BUILD SUCCESSFUL; the new file is reformatted to Google Java style if needed.

- [ ] **Step 3: Commit**

```bash
git add eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/networking/SnappyCompressionBenchmark.java
git commit -m "Add SnappyCompressionBenchmark comparing snappy-java and Netty Snappy"
```

---

### Task 3: Compile and smoke-run the benchmark (verifies cross-compatibility)

The cross-compatibility check runs inside `@Setup(Level.Trial)`, so any wire-format incompatibility fails the JMH run immediately. A short smoke run (1 iteration each) both compiles the benchmark and proves the feasibility claim.

**Files:** none (verification only)

- [ ] **Step 1: Compile the jmh source set**

Run: `./gradlew :eth-benchmark-tests:jmhClasses`
Expected: BUILD SUCCESSFUL. If it fails on a missing `randomDataColumnSidecar()` / `randomSignedAggregateAndProof()` / `randomSyncCommitteeMessage()` symbol, confirm the method names against `ethereum/spec/src/testFixtures/java/tech/pegasys/teku/spec/util/DataStructureUtil.java` and adjust.

- [ ] **Step 2: Smoke-run a single payload to confirm cross-compatibility passes and numbers are produced**

Run:
```bash
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -f 1 -wi 1 -i 1 -p payload=BEACON_BLOCK"
```
Expected: completes without throwing `IllegalStateException` (cross-compat passed for both impls), and prints a results table with `compress` and `decompress` rows for `SNAPPY_JAVA` and `NETTY`.

- [ ] **Step 3: Smoke-run the largest payload (data column sidecar)**

Run:
```bash
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -f 1 -wi 1 -i 1 -p payload=DATA_COLUMN_SIDECAR"
```
Expected: completes without error; confirms the large-payload path (where native snappy-java is most likely to win) works for both implementations.

- [ ] **Step 4: No commit** (verification step only — nothing changed on disk)

---

### Task 4: Add run instructions to the module README and record the result-capture command

Document how to run the benchmark with GC profiling so the throughput/latency and allocation metrics from the spec are reproducible.

**Files:**
- Modify: `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/README.md`

- [ ] **Step 1: Append a section to the README**

Add to the end of `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/README.md`:

```markdown

## Snappy implementation benchmark

`SnappyCompressionBenchmark` compares snappy-java (native, current gossip path) against Netty's
Snappy block codec (current RPC path) across realistic gossip payloads. It also asserts, on
startup, that the two implementations produce a mutually-decodable Snappy block wire format.

Run all payload/implementation combinations with allocation profiling:

```bash
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -prof gc"
```

Run a single payload (e.g. the large data-column-sidecar case):

```bash
./gradlew :eth-benchmark-tests:jmh --args="SnappyCompressionBenchmark -prof gc -p payload=DATA_COLUMN_SIDECAR"
```

Throughput/latency comes from the standard JMH score columns; allocation per op comes from the
`gc.alloc.rate.norm` row reported by `-prof gc`.
```

- [ ] **Step 2: Commit**

```bash
git add eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/README.md
git commit -m "Document how to run SnappyCompressionBenchmark with GC profiling"
```

---

## Post-implementation: record findings into the spec

After a full benchmark run, append the measured numbers (throughput, latency, `gc.alloc.rate.norm`
per impl per payload) and the cross-compatibility result to the **Decision output** section of
`docs/superpowers/specs/2026-06-17-snappy-implementation-benchmark-design.md`, then commit. This
closes the loop on the feasibility evaluation and turns the spec into the migrate / don't-migrate
recommendation.

## Notes on remaining feasibility items (no code, captured for the recommendation)

These were identified in the spec and do not require benchmark code, but must appear in the final
recommendation:
- **Bounds pre-check:** gossip's `SnappyBlockCompressor.uncompress` uses `Snappy.uncompressedLength`
  to reject oversized payloads before allocating. A Netty migration needs a small helper that reads
  the varint preamble to preserve this DoS guard.
- **Thread-safety:** Netty `Snappy` is stateful (the benchmark uses a fresh instance per call); a
  migration must use a per-use instance / `ThreadLocal` / `reset()` for concurrent gossip.
```
