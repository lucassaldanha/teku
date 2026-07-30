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

package tech.pegasys.teku.test.acceptance.dsl.executionrequests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.rlp.RLP;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class LegacyTransactionSignerTest {

  private static final String GOLDEN_VECTORS_RESOURCE =
      "/executionrequests/legacy-tx-golden-vectors.json";

  // Same account as the golden vectors (BesuNode.getRichBenefactorKey()/Address()).
  private static final Eth1Credentials CREDENTIALS =
      Eth1Credentials.fromHexKey(
          "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63");
  private static final BigInteger GAS_PRICE = BigInteger.valueOf(1_000);
  private static final BigInteger GAS_LIMIT = BigInteger.valueOf(1_000_000);
  private static final Eth1Address TO =
      Eth1Address.fromHexString("0x00000961Ef480Eb55e80D19ad83579A64c007002");
  private static final Bytes DATA = Bytes.fromHexString("0xabcd");

  @TestFactory
  Stream<DynamicTest> reproducesGoldenVectors() throws IOException {
    final ObjectMapper objectMapper = new ObjectMapper();
    final JsonNode vectors;
    try (InputStream in =
        LegacyTransactionSignerTest.class.getResourceAsStream(GOLDEN_VECTORS_RESOURCE)) {
      assertThat(in).as("golden vectors fixture on the test classpath").isNotNull();
      vectors = objectMapper.readTree(in);
    }
    // This @TestFactory is the oracle for the whole signer: an empty (or emptied) fixture would
    // otherwise decode to zero elements and pass vacuously as a container with no children.
    assertThat(vectors).hasSize(3);

    final Iterator<JsonNode> iterator = vectors.elements();
    final Stream.Builder<JsonNode> streamBuilder = Stream.builder();
    iterator.forEachRemaining(streamBuilder::add);

    return streamBuilder.build().map(vector -> dynamicTestFor(vector));
  }

  private static DynamicTest dynamicTestFor(final JsonNode vector) {
    final String name = vector.get("name").asText();
    return DynamicTest.dynamicTest(
        name,
        () -> {
          final Eth1Credentials credentials =
              Eth1Credentials.fromHexKey(vector.get("privateKey").asText());
          final UInt64 nonce = UInt64.valueOf(vector.get("nonce").asLong());
          final BigInteger gasPrice = BigInteger.valueOf(vector.get("gasPrice").asLong());
          final BigInteger gasLimit = BigInteger.valueOf(vector.get("gasLimit").asLong());
          final Eth1Address to = Eth1Address.fromHexString(vector.get("to").asText());
          final BigInteger value = BigInteger.valueOf(vector.get("value").asLong());
          final Bytes data = Bytes.fromHexString(vector.get("data").asText());

          final Bytes rawTx =
              LegacyTransactionSigner.sign(credentials, nonce, gasPrice, gasLimit, to, value, data);

          assertThat(rawTx).isEqualTo(Bytes.fromHexString(vector.get("expectedRawTx").asText()));
          assertThat(credentials.address())
              .isEqualTo(Eth1Address.fromHexString(vector.get("expectedSender").asText()));
        });
  }

  @Test
  void nonceZero_encodesAsRlpEmptyString() {
    final DecodedTx decoded =
        DecodedTx.decode(
            LegacyTransactionSigner.sign(
                CREDENTIALS, UInt64.ZERO, GAS_PRICE, GAS_LIMIT, TO, BigInteger.valueOf(2), DATA));

    // RLP represents the integer zero as the empty byte string, never as a single 0x00 byte.
    assertThat(decoded.nonce).isEqualTo(Bytes.EMPTY);
  }

  @Test
  void valueZero_encodesAsRlpEmptyString() {
    final DecodedTx decoded =
        DecodedTx.decode(
            LegacyTransactionSigner.sign(
                CREDENTIALS, UInt64.valueOf(3), GAS_PRICE, GAS_LIMIT, TO, BigInteger.ZERO, DATA));

    assertThat(decoded.value).isEqualTo(Bytes.EMPTY);
  }

  @Test
  void valueBelow0x80_encodesAsSingleRawByteWithNoLengthPrefix() {
    // Values in [0x00, 0x7f] are their own RLP encoding: no length prefix byte at all.
    final BigInteger value = BigInteger.valueOf(0x7f);

    final DecodedTx decoded =
        DecodedTx.decode(
            LegacyTransactionSigner.sign(
                CREDENTIALS, UInt64.valueOf(3), GAS_PRICE, GAS_LIMIT, TO, value, DATA));

    assertThat(decoded.value).isEqualTo(Bytes.of(0x7f));
  }

  @Test
  void valueEquals0x80_encodesWithSingleByteLengthPrefix() {
    // 0x80 can no longer be self-encoded (it collides with the empty-string marker), so RLP
    // requires the short-string length prefix 0x81 followed by the single content byte 0x80.
    final BigInteger value = BigInteger.valueOf(0x80);

    final DecodedTx decoded =
        DecodedTx.decode(
            LegacyTransactionSigner.sign(
                CREDENTIALS, UInt64.valueOf(3), GAS_PRICE, GAS_LIMIT, TO, value, DATA));

    assertThat(decoded.value).isEqualTo(Bytes.of(0x80));
  }

  @Test
  void value32Bytes_encodesFullBytesWithNoPaddingOrTruncation() {
    final byte[] thirtyTwoBytes = new byte[32];
    for (int i = 0; i < thirtyTwoBytes.length; i++) {
      thirtyTwoBytes[i] = (byte) (i + 1);
    }
    final BigInteger value = new BigInteger(1, thirtyTwoBytes);

    final DecodedTx decoded =
        DecodedTx.decode(
            LegacyTransactionSigner.sign(
                CREDENTIALS, UInt64.valueOf(3), GAS_PRICE, GAS_LIMIT, TO, value, DATA));

    assertThat(decoded.value).isEqualTo(Bytes.wrap(thirtyTwoBytes));
  }

  /**
   * Decodes a signed legacy transaction back into its 9 RLP fields using tuweni's own {@link RLP}
   * reader (a separate code path from the {@link LegacyTransactionSigner} writer under test), in
   * strict/non-lenient mode so that any non-canonical encoding produced by the signer would surface
   * as a decoding failure or a byte mismatch.
   */
  private record DecodedTx(
      Bytes nonce,
      Bytes gasPrice,
      Bytes gasLimit,
      Bytes to,
      Bytes value,
      Bytes data,
      Bytes v,
      Bytes r,
      Bytes s) {

    static DecodedTx decode(final Bytes rawTx) {
      return RLP.decodeList(
          rawTx,
          /* lenient= */ false,
          reader ->
              new DecodedTx(
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false),
                  reader.readValue(false)));
    }
  }
}
