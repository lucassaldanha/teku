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
