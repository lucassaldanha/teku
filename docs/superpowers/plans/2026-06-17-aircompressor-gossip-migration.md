# aircompressor-v3 Gossip Snappy Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the gossip Snappy block codec from snappy-java (JNI native) to aircompressor-v3 (FFM-native with automatic pure-Java fallback), eliminating the JNI native-loader class of failure while preserving wire-format compatibility, the DoS bounds pre-check, and the public API.

**Architecture:** The gossip codec is encapsulated in a single class, `SnappyBlockCompressor` (networking:eth2), instantiated as a hardcoded singleton in `GossipEncoding.SSZ_SNAPPY`. The migration swaps that class's *internals* from `org.xerial.snappy.Snappy` to aircompressor's `SnappyCompressor`/`SnappyDecompressor` factories, keeping its public methods (`compress`, `uncompress`) and behaviour identical. No callers change. The benchmark in `eth-benchmark-tests` already proved the two produce a mutually-decodable Snappy block wire format and that aircompressor native matches/beats snappy-java.

**Tech Stack:** Java 25, aircompressor-v3 3.5 (`io.airlift.compress.v3.snappy`), Tuweni Bytes, JUnit 5 / AssertJ. snappy-java (`org.xerial.snappy`) is retained as a **test-only** dependency in networking:eth2 to guard network interoperability.

**Reference:** `docs/superpowers/specs/2026-06-17-snappy-implementation-benchmark-design.md` (benchmark results + feasibility findings).

## Key API facts (verified against airlift/aircompressor source)

- `io.airlift.compress.v3.snappy.SnappyCompressor.create()` → returns `SnappyNativeCompressor` if `SnappyNativeCompressor.isEnabled()`, else `SnappyJavaCompressor`. Implements `Compressor` (`int maxCompressedLength(int)`, `int compress(byte[] in, int inOff, int inLen, byte[] out, int outOff, int maxOut)`).
- `io.airlift.compress.v3.snappy.SnappyDecompressor.create()` → native-or-java the same way. Adds `int getUncompressedLength(byte[] compressed, int offset)` (drop-in for `Snappy.uncompressedLength`) and `int decompress(byte[] in, int inOff, int inLen, byte[] out, int outOff, int maxOut)`.
- Decompression of malformed input throws `io.airlift.compress.v3.MalformedInputException` (a `RuntimeException`), NOT `IOException`. The new `uncompress` must catch it and wrap as `DecodingException`.
- aircompressor's `SnappyCompressor`/`SnappyDecompressor` implementations are stateless (all working state is in caller-provided buffers), so a single shared instance is safe to use concurrently. Task 4 verifies this.

## Scope

- **In scope:** gossip block compression only (`SnappyBlockCompressor`).
- **Out of scope (follow-ups):** RPC (`SnappyFramedCompressor`, already Netty); other snappy-java users (`teku` `PrettyPrintCommand`, `data/dataexchange` `EraFile`). The project still ships snappy-java for those, so this migration does not by itself remove the dependency project-wide — it removes it from the gossip hot path.

---

### Task 1: Swap the networking:eth2 dependency and allow the license

aircompressor-v3 3.5 is already managed in `gradle/versions.gradle` (added for the benchmark). This task makes it a production dependency of networking:eth2, demotes snappy-java to test-only there, and allow-lists the new license (it becomes a `testCompile`-reachable dependency, which is the configuration `checkLicenses` scans).

**Files:**
- Modify: `networking/eth2/build.gradle:24`
- Modify: `gradle/check-licenses.gradle` (the `acceptedDependencies`/license map, near the existing `(group('io.libp2p')): apache,` entry around line 193)

- [ ] **Step 1: Swap the dependency**

In `networking/eth2/build.gradle`, replace this line:

```gradle
	implementation 'org.xerial.snappy:snappy-java'
```

with:

```gradle
	implementation 'io.airlift:aircompressor-v3'
	testImplementation 'org.xerial.snappy:snappy-java'
```

- [ ] **Step 2: Allow-list the aircompressor license**

In `gradle/check-licenses.gradle`, find the line:

```gradle
		(group('io.libp2p')): apache,
```

Add immediately after it:

```gradle
		(group('io.airlift')): apache,
```

- [ ] **Step 3: Verify resolution and licenses**

Run: `./gradlew :networking:eth2:dependencies --configuration testRuntimeClasspath` and confirm `io.airlift:aircompressor-v3:3.5` resolves and `org.xerial.snappy:snappy-java` still appears (via testImplementation).
Then run: `./gradlew checkLicenses`
Expected: BUILD SUCCESSFUL (no "not white-listed" entry for io.airlift).

- [ ] **Step 4: Commit**

```bash
git add networking/eth2/build.gradle gradle/check-licenses.gradle
git commit -m "Make aircompressor-v3 a gossip dependency and snappy-java test-only in networking:eth2"
```

---

### Task 2: Reimplement SnappyBlockCompressor on aircompressor-v3

Preserve the public API (`compress(Bytes)`, `uncompress(Bytes, SszLengthBounds, long)`) and the exact DoS-guard behaviour and exception messages — the existing `SnappyBlockCompressorTest` is the regression spec and must stay green. Only the engine changes.

**Files:**
- Modify: `networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressor.java`
- Test (existing, must stay green): `networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressorTest.java`

- [ ] **Step 1: Run the existing tests to establish the green baseline**

Run: `./gradlew :networking:eth2:test --tests "*.SnappyBlockCompressorTest"`
Expected: PASS (currently using snappy-java).

- [ ] **Step 2: Replace the implementation**

Replace the entire body of `SnappyBlockCompressor.java` (keep the copyright header) with:

```java
package tech.pegasys.teku.networking.eth2.gossip.encoding;

import io.airlift.compress.v3.MalformedInputException;
import io.airlift.compress.v3.snappy.SnappyCompressor;
import io.airlift.compress.v3.snappy.SnappyDecompressor;
import java.util.Arrays;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;

/**
 * Implements snappy compression using the "block" format, backed by aircompressor-v3. Uses the
 * native (FFM) implementation when available and transparently falls back to pure Java otherwise.
 * See: https://github.com/google/snappy/blob/master/format_description.txt
 *
 * <p>The {@link SnappyCompressor}/{@link SnappyDecompressor} instances are stateless and safe to
 * share across threads.
 */
public class SnappyBlockCompressor {

  private final SnappyCompressor compressor = SnappyCompressor.create();
  private final SnappyDecompressor decompressor = SnappyDecompressor.create();

  public Bytes uncompress(
      final Bytes compressedData, final SszLengthBounds lengthBounds, final long maxBytesLength)
      throws DecodingException {
    final byte[] compressed = compressedData.toArrayUnsafe();

    final int uncompressedLength;
    try {
      uncompressedLength = decompressor.getUncompressedLength(compressed, 0);
    } catch (final MalformedInputException e) {
      throw new DecodingException("Failed to uncompress", e);
    }

    if (uncompressedLength > maxBytesLength) {
      throw new DecodingException(
          String.format(
              "Uncompressed length %d exceeds max length in bytes of %s",
              uncompressedLength, maxBytesLength));
    }

    if (!lengthBounds.isWithinBounds(uncompressedLength)) {
      throw new DecodingException(
          String.format(
              "Uncompressed length %d is not within expected bounds %s",
              uncompressedLength, lengthBounds));
    }

    try {
      final byte[] uncompressed = new byte[uncompressedLength];
      final int written =
          decompressor.decompress(compressed, 0, compressed.length, uncompressed, 0, uncompressed.length);
      return Bytes.wrap(written == uncompressed.length ? uncompressed : Arrays.copyOf(uncompressed, written));
    } catch (final MalformedInputException e) {
      throw new DecodingException("Failed to uncompress", e);
    }
  }

  public Bytes compress(final Bytes data) {
    final byte[] input = data.toArrayUnsafe();
    final byte[] output = new byte[compressor.maxCompressedLength(input.length)];
    final int written = compressor.compress(input, 0, input.length, output, 0, output.length);
    return Bytes.wrap(written == output.length ? output : Arrays.copyOf(output, written));
  }
}
```

- [ ] **Step 3: Format and re-run the existing tests**

Run: `./gradlew :networking:eth2:spotlessApply :networking:eth2:test --tests "*.SnappyBlockCompressorTest"`
Expected: PASS. All six existing tests (round-trip, bounds-too-long, bounds-too-short, max-length-exceeded, max-length-equal, random-data) must still pass with identical exception messages.

If `uncompress_randomData` (input `0x0102`) fails (i.e. aircompressor does not throw on that malformed input), STOP and report — it means the malformed-input handling differs and needs investigation before proceeding.

- [ ] **Step 4: Commit**

```bash
git add networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressor.java
git commit -m "Reimplement gossip SnappyBlockCompressor on aircompressor-v3"
```

---

### Task 3: Add a network-interoperability test against snappy-java

The migration is only safe if a node running aircompressor stays wire-compatible with the network (still running snappy-java). This test compresses with each engine and decompresses with the other, both directions, proving interoperability in-repo.

**Files:**
- Modify: `networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressorTest.java`

- [ ] **Step 1: Add the interop test**

Add these imports to the test (alongside the existing ones):

```java
import java.io.IOException;
import org.xerial.snappy.Snappy;
```

Add this test method to `SnappyBlockCompressorTest`:

```java
  @Test
  void interoperatesWithSnappyJavaWireFormat() throws DecodingException, IOException {
    final Bytes original = Bytes.fromHexString("0x00010203040506070809101112131415161718192021222324");

    // aircompressor output must be decodable by snappy-java (we send, the network receives).
    final Bytes ourCompressed = compressor.compress(original);
    assertThat(Bytes.wrap(Snappy.uncompress(ourCompressed.toArrayUnsafe()))).isEqualTo(original);

    // snappy-java output must be decodable by aircompressor (the network sends, we receive).
    final Bytes theirCompressed = Bytes.wrap(Snappy.compress(original.toArrayUnsafe()));
    final Bytes ourUncompressed =
        compressor.uncompress(theirCompressed, SszLengthBounds.ofBytes(0, 1000), MAX_PAYLOAD_SIZE);
    assertThat(ourUncompressed).isEqualTo(original);
  }
```

- [ ] **Step 2: Run it**

Run: `./gradlew :networking:eth2:test --tests "*.SnappyBlockCompressorTest"`
Expected: PASS, including the new `interoperatesWithSnappyJavaWireFormat`.

- [ ] **Step 3: Commit**

```bash
git add networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressorTest.java
git commit -m "Add gossip Snappy interoperability test against snappy-java wire format"
```

---

### Task 4: Add a concurrency test guarding the shared codec instances

`SnappyBlockCompressor` is a singleton shared across gossip threads, and it now holds shared `SnappyCompressor`/`SnappyDecompressor` instances. This test exercises them concurrently to confirm they are thread-safe (the plan's stated assumption).

**Files:**
- Modify: `networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressorTest.java`

- [ ] **Step 1: Add the concurrency test**

Add these imports:

```java
import java.util.List;
import java.util.stream.IntStream;
```

Add this test method:

```java
  @Test
  void concurrentRoundTripsAreCorrect() {
    final List<Bytes> originals =
        IntStream.range(0, 64)
            .mapToObj(i -> Bytes.wrap(("gossip-payload-" + i + "-").repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .toList();

    IntStream.range(0, 2_000)
        .parallel()
        .forEach(
            i -> {
              final Bytes original = originals.get(i % originals.size());
              try {
                final Bytes roundTripped =
                    compressor.uncompress(
                        compressor.compress(original),
                        SszLengthBounds.ofBytes(0, 1_000_000),
                        MAX_PAYLOAD_SIZE);
                assertThat(roundTripped).isEqualTo(original);
              } catch (final DecodingException e) {
                throw new AssertionError("Concurrent round-trip failed", e);
              }
            });
  }
```

- [ ] **Step 2: Run it**

Run: `./gradlew :networking:eth2:test --tests "*.SnappyBlockCompressorTest"`
Expected: PASS. If this is flaky/fails, the shared instances are not thread-safe — switch the two fields to `ThreadLocal<SnappyCompressor>`/`ThreadLocal<SnappyDecompressor>` (via `ThreadLocal.withInitial(SnappyCompressor::create)`) and re-run.

- [ ] **Step 3: Commit**

```bash
git add networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressorTest.java
git commit -m "Verify gossip Snappy codec is safe under concurrent use"
```

---

### Task 5: Full verification

Confirm the change is sound across the module and that the native path is the one actually selected on this build host.

**Files:** none (verification only)

- [ ] **Step 1: Confirm no remaining production use of snappy-java in networking:eth2**

Run: `grep -rl "org.xerial.snappy" networking/eth2/src/main`
Expected: no output (the only former user, `SnappyBlockCompressor`, no longer imports it).

- [ ] **Step 2: Confirm which implementation is active on this host**

Run:
```bash
./gradlew :networking:eth2:test --tests "*.SnappyBlockCompressorTest" --info 2>&1 | grep -i "native\|enable-native-access" | head
```
This is informational — it surfaces any FFM native-access warning, confirming the native path engaged. (Functionally the codec is correct either way thanks to the pure-Java fallback; this step is to confirm native loaded, which is the performance goal.)

- [ ] **Step 3: Run the full module test + spotless + dependency check**

Run: `./gradlew :networking:eth2:spotlessCheck :networking:eth2:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: No commit** (verification only)

---

## Operational notes (record in PR description)

- aircompressor unpacks its native library to a temp dir; set `-Daircompressor.tmpdir=<path>` if the default temp dir is mounted `noexec`.
- Set `-Dio.airlift.compress.v3.disable-native=true` to force the pure-Java path (e.g. on an unsupported platform) — gossip keeps working, just without the native speedup.
- Requires Java 22+ (Teku runs on 25 ✓).
- snappy-java remains in the project for `teku` (`PrettyPrintCommand`) and `data/dataexchange` (`EraFile`); migrating or removing those is a separate consolidation step before the dependency can be dropped project-wide.
- Pin aircompressor ≥ the version with the malformed-input OOB fix (3.5 is well past it); the decompressor here always writes into a freshly allocated, exactly-sized buffer, so the reused-buffer disclosure advisory does not apply.

## Self-review notes

- Spec coverage: feasibility item 1 (wire interop) → Task 3; item 2 (bounds pre-check) → Task 2 (`getUncompressedLength` + preserved checks); item 3 (thread-safety) → Task 4. Performance rationale → benchmark spec (already recorded).
- Behaviour preservation: exception messages ("exceeds max length in bytes", "not within expected bounds") and `DecodingException` type are copied verbatim so the existing tests remain the contract.
- Exception-type change (snappy-java `IOException` → aircompressor `MalformedInputException extends RuntimeException`) is handled explicitly in both `uncompress` catch sites.

---

# Part 2: Feature-flag gating and RPC consolidation

This part makes the new compression **selectable per-node behind hidden experimental flags** (default = current behaviour, opt-in = aircompressor) and extends consolidation to the RPC path. Both Snappy block and Snappy framed are standardized wire formats, so these flags are **operational rollout/rollback toggles, not protocol switches** — a flagged node stays fully interoperable with the network.

## Adjustments to Part 1 (apply if shipping behind a flag)

To gate behind a flag, both implementations must coexist at runtime, so two Part-1 steps change:

- **Task 1, Step 1:** Do **not** demote snappy-java to `testImplementation`. Keep `implementation 'org.xerial.snappy:snappy-java'` (it backs the default, flag-off path) and **add** `implementation 'io.airlift:aircompressor-v3'`. (The license allow-list step is unchanged.)
- **Task 2:** Instead of replacing `SnappyBlockCompressor`'s internals in place, `SnappyBlockCompressor` becomes an **interface** with two implementations (Task 6 below). The aircompressor logic from Task 2 moves verbatim into `AircompressorSnappyBlockCompressor`; the original snappy-java logic is preserved in `SnappyJavaBlockCompressor`. Tasks 3–5 (interop test, concurrency test, verification) then run against the aircompressor implementation.

---

### Task 6: Extract `SnappyBlockCompressor` into an interface with two implementations

**Files:**
- Modify: `networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyBlockCompressor.java` (becomes an interface)
- Create: `networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/SnappyJavaBlockCompressor.java`
- Create: `networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/AircompressorSnappyBlockCompressor.java`
- Test: convert `SnappyBlockCompressorTest` into an abstract base with two concrete subclasses.

- [ ] **Step 1: Replace `SnappyBlockCompressor` with an interface**

Replace the class body (keep copyright header) of `SnappyBlockCompressor.java` with:

```java
package tech.pegasys.teku.networking.eth2.gossip.encoding;

import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;

/** Snappy "block" format codec for gossip messages. */
public interface SnappyBlockCompressor {

  Bytes compress(Bytes data);

  Bytes uncompress(Bytes compressedData, SszLengthBounds lengthBounds, long maxBytesLength)
      throws DecodingException;
}
```

- [ ] **Step 2: Add the snappy-java implementation (preserves current default behaviour)**

Create `SnappyJavaBlockCompressor.java` with the original implementation (copyright header + this body):

```java
package tech.pegasys.teku.networking.eth2.gossip.encoding;

import java.io.IOException;
import org.apache.tuweni.bytes.Bytes;
import org.xerial.snappy.Snappy;
import tech.pegasys.teku.infrastructure.ssz.sos.SszLengthBounds;

/** snappy-java (JNI native) implementation of the gossip Snappy block codec. */
public class SnappyJavaBlockCompressor implements SnappyBlockCompressor {

  @Override
  public Bytes uncompress(
      final Bytes compressedData, final SszLengthBounds lengthBounds, final long maxBytesLength)
      throws DecodingException {
    try {
      final int uncompressedLength = Snappy.uncompressedLength(compressedData.toArrayUnsafe());
      if (uncompressedLength > maxBytesLength) {
        throw new DecodingException(
            String.format(
                "Uncompressed length %d exceeds max length in bytes of %s",
                uncompressedLength, maxBytesLength));
      }
      if (!lengthBounds.isWithinBounds(uncompressedLength)) {
        throw new DecodingException(
            String.format(
                "Uncompressed length %d is not within expected bounds %s",
                uncompressedLength, lengthBounds));
      }
      return Bytes.wrap(Snappy.uncompress(compressedData.toArrayUnsafe()));
    } catch (final IOException e) {
      throw new DecodingException("Failed to uncompress", e);
    }
  }

  @Override
  public Bytes compress(final Bytes data) {
    try {
      return Bytes.wrap(Snappy.compress(data.toArrayUnsafe()));
    } catch (final IOException e) {
      throw new RuntimeException("Unable to compress data", e);
    }
  }
}
```

- [ ] **Step 3: Add the aircompressor implementation**

Create `AircompressorSnappyBlockCompressor.java` containing the aircompressor logic from Part 1 Task 2 (the `compress`/`uncompress` methods, the `SnappyCompressor`/`SnappyDecompressor` fields, the `MalformedInputException` handling), with the class renamed and `implements SnappyBlockCompressor` added. The method bodies are identical to Part 1 Task 2 Step 2.

- [ ] **Step 4: Update the default singleton wiring**

In `GossipEncoding.java`, change line 28 and add a factory. Replace:

```java
  GossipEncoding SSZ_SNAPPY = new SszSnappyEncoding(new SnappyBlockCompressor());
```

with:

```java
  GossipEncoding SSZ_SNAPPY = new SszSnappyEncoding(new SnappyJavaBlockCompressor());

  static GossipEncoding createSszSnappy(final SnappyBlockCompressor compressor) {
    return new SszSnappyEncoding(compressor);
  }
```

(`SszSnappyEncoding` is package-private, so the static factory exposes construction to `P2PConfig` in another package. Default `SSZ_SNAPPY` stays snappy-java.)

- [ ] **Step 5: Convert the test to cover both implementations**

Rename `SnappyBlockCompressorTest` to an abstract base `AbstractSnappyBlockCompressorTest` with an abstract `protected abstract SnappyBlockCompressor createCompressor();` and change the field to `private final SnappyBlockCompressor compressor = createCompressor();`. Add two concrete subclasses in the same test package: `SnappyJavaBlockCompressorTest extends AbstractSnappyBlockCompressorTest` (`return new SnappyJavaBlockCompressor();`) and `AircompressorSnappyBlockCompressorTest extends AbstractSnappyBlockCompressorTest` (`return new AircompressorSnappyBlockCompressor();`). The interop test (Part 1 Task 3) lives in the aircompressor subclass.

- [ ] **Step 6: Format, compile, test, commit**

Run: `./gradlew :networking:eth2:spotlessApply :networking:eth2:test --tests "*SnappyBlockCompressor*"`
Expected: PASS for both subclasses.

```bash
git add networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/gossip/encoding/ networking/eth2/src/test/java/tech/pegasys/teku/networking/eth2/gossip/encoding/
git commit -m "Extract SnappyBlockCompressor interface with snappy-java and aircompressor impls"
```

---

### Task 7: Gate the gossip compressor behind `--Xp2p-gossip-snappy-aircompressor-enabled`

**Files:**
- Modify: `networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/P2PConfig.java`
- Modify: `teku/src/main/java/tech/pegasys/teku/cli/options/P2POptions.java`

- [ ] **Step 1: Add the config field, default, getter, and builder selection**

In `P2PConfig.java`:
- Add a default constant near line 45: `public static final boolean DEFAULT_GOSSIP_SNAPPY_USE_AIRCOMPRESSOR = false;`
- Add a getter (near `getGossipEncoding()`, line 188): no new getter needed — the encoding is resolved in `build()` (below) and the existing `getGossipEncoding()` returns it.
- In `Builder` (line 302), replace `private final GossipEncoding gossipEncoding = GossipEncoding.SSZ_SNAPPY;` with:

```java
    private boolean gossipSnappyUseAircompressor = DEFAULT_GOSSIP_SNAPPY_USE_AIRCOMPRESSOR;
```

- In `build()` (before the `return new P2PConfig(...)` at line 376), add:

```java
      final GossipEncoding gossipEncoding =
          gossipSnappyUseAircompressor
              ? GossipEncoding.createSszSnappy(new AircompressorSnappyBlockCompressor())
              : GossipEncoding.SSZ_SNAPPY;
```

  and add the import for `AircompressorSnappyBlockCompressor`. The existing `build()` already references `gossipEncoding` for the `Eth2Context` (line 347) and the constructor (line 381); both now use this resolved local.
- Add the builder setter (near line 428):

```java
    public Builder gossipSnappyUseAircompressor(final boolean enabled) {
      this.gossipSnappyUseAircompressor = enabled;
      return this;
    }
```

- [ ] **Step 2: Add the hidden CLI option**

In `P2POptions.java`, add a field following the `--Xp2p-gossip-scoring-enabled` pattern (around line 396):

```java
  @Option(
      names = {"--Xp2p-gossip-snappy-aircompressor-enabled"},
      paramLabel = "<BOOLEAN>",
      showDefaultValue = Visibility.ALWAYS,
      description = "Use the aircompressor library for gossip Snappy compression (experimental)",
      hidden = true,
      arity = "0..1",
      fallbackValue = "true")
  private boolean gossipSnappyUseAircompressor =
      P2PConfig.DEFAULT_GOSSIP_SNAPPY_USE_AIRCOMPRESSOR;
```

In the `configure()` method, in the existing `builder.p2p(b -> ...)` block (around line 694), add:

```java
              .gossipSnappyUseAircompressor(gossipSnappyUseAircompressor)
```

- [ ] **Step 3: Build, test, commit**

Run: `./gradlew :networking:eth2:compileJava :teku:compileJava`
Expected: BUILD SUCCESSFUL.

```bash
git add networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/P2PConfig.java teku/src/main/java/tech/pegasys/teku/cli/options/P2POptions.java
git commit -m "Add hidden flag to select aircompressor for gossip Snappy"
```

---

### Task 8: Make the RPC Snappy inner block codec pluggable

Keep Teku's framing (stream identifier, 32 KB chunking, CRC32C in `SnappyUtil`) untouched; abstract only the per-chunk block codec so it can be Netty (default) or aircompressor. Per-call/per-decompressor instances are preserved (Netty's `Snappy` is stateful), so the codec is supplied via a factory.

**Files:**
- Create: `networking/eth2/.../rpc/core/encodings/compression/snappy/SnappyBlockCodec.java`
- Create: `.../snappy/NettySnappyBlockCodec.java`
- Create: `.../snappy/AircompressorSnappyBlockCodec.java`
- Modify: `.../snappy/SnappyFrameEncoder.java`, `.../snappy/SnappyFrameDecoder.java`, `.../snappy/SnappyFramedCompressor.java`

- [ ] **Step 1: Define the strategy interface**

Create `SnappyBlockCodec.java` (copyright header +):

```java
package tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy;

import io.netty.buffer.ByteBuf;

/** Per-chunk Snappy block codec used inside the RPC framed format. */
public interface SnappyBlockCodec {
  /** Compresses {@code length} readable bytes from {@code in} into {@code out}. */
  void encode(ByteBuf in, ByteBuf out, int length);

  /** Decompresses all readable bytes of {@code in} (one Snappy block) into {@code out}. */
  void decode(ByteBuf in, ByteBuf out);
}
```

- [ ] **Step 2: Netty implementation (current behaviour)**

Create `NettySnappyBlockCodec.java`:

```java
package tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.compression.Snappy;

public class NettySnappyBlockCodec implements SnappyBlockCodec {
  private final Snappy snappy = new Snappy();

  @Override
  public void encode(final ByteBuf in, final ByteBuf out, final int length) {
    snappy.encode(in, out, length);
  }

  @Override
  public void decode(final ByteBuf in, final ByteBuf out) {
    snappy.decode(in, out);
    snappy.reset();
  }
}
```

(Folding `reset()` into `decode()` preserves the per-chunk reset previously done at `SnappyFrameDecoder.java:208`.)

- [ ] **Step 3: aircompressor implementation**

Create `AircompressorSnappyBlockCodec.java`:

```java
package tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy;

import io.airlift.compress.v3.snappy.SnappyCompressor;
import io.airlift.compress.v3.snappy.SnappyDecompressor;
import io.netty.buffer.ByteBuf;

public class AircompressorSnappyBlockCodec implements SnappyBlockCodec {
  private final SnappyCompressor compressor = SnappyCompressor.create();
  private final SnappyDecompressor decompressor = SnappyDecompressor.create();

  @Override
  public void encode(final ByteBuf in, final ByteBuf out, final int length) {
    final byte[] src = new byte[length];
    in.readBytes(src);
    final byte[] dst = new byte[compressor.maxCompressedLength(length)];
    final int written = compressor.compress(src, 0, length, dst, 0, dst.length);
    out.writeBytes(dst, 0, written);
  }

  @Override
  public void decode(final ByteBuf in, final ByteBuf out) {
    final int compressedLength = in.readableBytes();
    final byte[] src = new byte[compressedLength];
    in.readBytes(src);
    final byte[] dst = new byte[decompressor.getUncompressedLength(src, 0)];
    final int written = decompressor.decompress(src, 0, compressedLength, dst, 0, dst.length);
    out.writeBytes(dst, 0, written);
  }
}
```

- [ ] **Step 4: Thread the codec through the frame encoder/decoder**

- `SnappyFrameEncoder.java`: replace the field `private final Snappy snappy = new Snappy();` (line 47) with `private final SnappyBlockCodec codec;`, add a constructor `public SnappyFrameEncoder(final SnappyBlockCodec codec) { this.codec = codec; }`, remove the `io.netty...Snappy` import, and change the two `snappy.encode(slice, out, N)` calls (lines 88, 94) to `codec.encode(slice, out, N)`.
- `SnappyFrameDecoder.java`: replace the field `private final Snappy snappy = new Snappy();` (line 54) with `private final SnappyBlockCodec codec;`, add the codec to its constructors (`SnappyFrameDecoder(SnappyBlockCodec codec)` and the `(SnappyBlockCodec, boolean validateChecksums)` overload), remove the `io.netty...Snappy` import, change `snappy.decode(...)` (lines 193, 199) to `codec.decode(...)`, and delete `snappy.reset();` (line 208 — now handled inside the codec).
- `SnappyFramedCompressor.java`: add `private final Supplier<SnappyBlockCodec> codecFactory;`, a default constructor `public SnappyFramedCompressor() { this(NettySnappyBlockCodec::new); }` and `public SnappyFramedCompressor(final Supplier<SnappyBlockCodec> codecFactory) { this.codecFactory = codecFactory; }`; change `compress()` (line 128) to `new SnappyFrameEncoder(codecFactory.get()).encode(data)` and `SnappyFramedDecompressor`'s `new SnappyFrameDecoder()` (line 33) to `new SnappyFrameDecoder(codecFactory.get())`.

- [ ] **Step 5: Compile and run the RPC compression + integration tests**

Run: `./gradlew :networking:eth2:spotlessApply :networking:eth2:test --tests "*Snappy*" --tests "*Rpc*"`
Expected: PASS (default Netty path unchanged in behaviour).

- [ ] **Step 6: Commit**

```bash
git add networking/eth2/src/main/java/tech/pegasys/teku/networking/eth2/rpc/core/encodings/compression/snappy/
git commit -m "Make RPC Snappy inner block codec pluggable (Netty default)"
```

---

### Task 9: Gate the RPC codec behind `--Xp2p-rpc-snappy-aircompressor-enabled`

**Files:**
- Modify: `networking/eth2/.../rpc/core/encodings/RpcEncoding.java`
- Modify: `networking/eth2/.../Eth2P2PNetworkBuilder.java` (line 181–182)
- Modify: `networking/eth2/.../P2PConfig.java` + `teku/.../cli/options/P2POptions.java` (mirror Task 7)

- [ ] **Step 1: Add a codec-selecting factory overload to `RpcEncoding`**

In `RpcEncoding.java`, add:

```java
  static RpcEncoding createSszSnappyEncoding(
      final int maxChunkSize, final boolean useAircompressor) {
    final java.util.function.Supplier<
            tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy.SnappyBlockCodec>
        codecFactory =
            useAircompressor
                ? tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy
                    .AircompressorSnappyBlockCodec::new
                : tech.pegasys.teku.networking.eth2.rpc.core.encodings.compression.snappy
                    .NettySnappyBlockCodec::new;
    return new LengthPrefixedEncoding(
        "ssz_snappy",
        RpcPayloadEncoders.createSszEncoders(),
        new SnappyFramedCompressor(codecFactory),
        maxChunkSize);
  }
```

(Use proper imports rather than fully-qualified names when implementing; shown qualified here only to be unambiguous.) Keep the existing single-arg `createSszSnappyEncoding(maxChunkSize)` delegating to `createSszSnappyEncoding(maxChunkSize, false)`.

- [ ] **Step 2: Add the config flag (mirror Task 7) and read it at the seam**

- In `P2PConfig.java`: add `DEFAULT_RPC_SNAPPY_USE_AIRCOMPRESSOR = false`, a `boolean rpcSnappyUseAircompressor` builder field + setter + constructor plumbing + `isRpcSnappyUseAircompressor()` getter (RPC encoding is built in `Eth2P2PNetworkBuilder`, not `P2PConfig.build()`, so this one needs a real getter).
- In `P2POptions.java`: add hidden `--Xp2p-rpc-snappy-aircompressor-enabled` option mirroring Task 7 Step 2 and wire `.rpcSnappyUseAircompressor(...)` into the `builder.p2p(...)` block.
- In `Eth2P2PNetworkBuilder.java` line 181–182, change:

```java
    final RpcEncoding rpcEncoding =
        RpcEncoding.createSszSnappyEncoding(spec.getNetworkingConfig().getMaxPayloadSize());
```

  to:

```java
    final RpcEncoding rpcEncoding =
        RpcEncoding.createSszSnappyEncoding(
            spec.getNetworkingConfig().getMaxPayloadSize(),
            config.isRpcSnappyUseAircompressor());
```

- [ ] **Step 3: Build, test, commit**

Run: `./gradlew :networking:eth2:test :teku:compileJava`
Expected: BUILD SUCCESSFUL.

```bash
git add networking/eth2 teku/src/main/java/tech/pegasys/teku/cli/options/P2POptions.java
git commit -m "Add hidden flag to select aircompressor for RPC Snappy"
```

---

## Part 2 self-review notes

- Both flags default to `false` (current behaviour). Snappy block and framed are standardized formats, so flags are operational toggles, not protocol changes — a node can run either implementation and stay network-interoperable (proven by the benchmark cross-compat check; re-verified for gossip by Part 1 Task 3).
- Gossip seam: encoding is resolved in `P2PConfig.Builder.build()` and already plumbed to `Eth2P2PNetworkBuilder:218` and all gossip managers — no downstream changes.
- RPC seam: `SnappyFramedCompressor` is the single `Compressor` impl for `ssz_snappy`, constructed once in `RpcEncoding.createSszSnappyEncoding` (called from `Eth2P2PNetworkBuilder:182`); the `Compressor`/`Decompressor` interface contract is unchanged, so all RPC call sites are untouched.
- The aircompressor RPC codec adds one ByteBuf→byte[] copy per chunk; acceptable given the measured Netty→aircompressor speedup, and isolated to the codec class.
- Rollout: enable gossip flag on a canary, observe, then RPC flag; once proven, flip defaults to aircompressor and remove the snappy-java/Netty-Snappy paths in a later cleanup.
