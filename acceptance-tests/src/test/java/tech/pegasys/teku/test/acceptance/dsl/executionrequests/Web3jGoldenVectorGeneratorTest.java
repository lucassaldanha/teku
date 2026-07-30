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

// TODO(web3j-removal): delete together with the web3j dependency (Task 5)

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * Temporary generator for the golden raw-signed legacy-transaction vectors committed at {@code
 * src/test/resources/executionrequests/legacy-tx-golden-vectors.json}. It uses web3j directly
 * ({@link Credentials}, {@link RawTransaction#createTransaction}, {@link
 * TransactionEncoder#signMessage}) to produce the oracle that the replacement tuweni-based legacy
 * transaction signer must reproduce byte-for-byte, before web3j is removed from the project. The
 * real product of this test is the committed fixture file, not the assertions below - those only
 * guard against the generator silently producing empty or unstable output.
 */
public class Web3jGoldenVectorGeneratorTest {

  // Value returned by BesuNode.getRichBenefactorKey().
  private static final String RICH_BENEFACTOR_PRIVATE_KEY =
      "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63";

  // Value returned by BesuNode.getRichBenefactorAddress(), used to sanity-check the derived
  // sender address.
  private static final String RICH_BENEFACTOR_ADDRESS =
      "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73";

  // Value returned by BesuNode.getWithdrawalRequestContractAddress() (EIP-7002
  // WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS).
  private static final String WITHDRAWAL_REQUEST_CONTRACT_ADDRESS =
      "0x00000961Ef480Eb55e80D19ad83579A64c007002";

  // Value returned by BesuNode.getConsolidationRequestContractAddress() (EIP-7251
  // CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS).
  private static final String CONSOLIDATION_REQUEST_CONTRACT_ADDRESS =
      "0x0000BBdDc7CE488642fb579F8B00f3a590007251";

  // Matches WithdrawalRequestContract/ConsolidationRequestContract#create*Request.
  private static final BigInteger GAS_PRICE = BigInteger.valueOf(1_000);
  private static final BigInteger GAS_LIMIT = BigInteger.valueOf(1_000_000);
  private static final BigInteger VALUE = BigInteger.valueOf(2);

  // Fixed, hard-coded 48-byte (BLS-pubkey-sized) placeholders - never a randomly generated key -
  // so the vectors stay stable across runs and across the eventual signer replacement.
  private static final String WITHDRAWAL_PUBKEY =
      "0xa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
  private static final String CONSOLIDATION_SOURCE_PUBKEY =
      "0xb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf";
  private static final String CONSOLIDATION_TARGET_PUBKEY =
      "0xc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef";

  // 8-byte big-endian gwei amount of 0, as encoded by
  // WithdrawalRequestContract#createWithdrawalRequest.
  private static final String ZERO_AMOUNT = "0x0000000000000000";

  private static final Path FIXTURE_PATH =
      Path.of("src/test/resources/executionrequests/legacy-tx-golden-vectors.json");

  @Test
  void generateGoldenVectors() throws IOException {
    final List<GoldenVector> vectors =
        List.of(
            goldenVector(
                "withdrawal",
                BigInteger.ZERO,
                WITHDRAWAL_REQUEST_CONTRACT_ADDRESS,
                concatHex(WITHDRAWAL_PUBKEY, ZERO_AMOUNT)),
            goldenVector(
                "consolidation",
                BigInteger.ZERO,
                CONSOLIDATION_REQUEST_CONTRACT_ADDRESS,
                concatHex(CONSOLIDATION_SOURCE_PUBKEY, CONSOLIDATION_TARGET_PUBKEY)),
            goldenVector(
                "withdrawal-nonce-7",
                BigInteger.valueOf(7),
                WITHDRAWAL_REQUEST_CONTRACT_ADDRESS,
                concatHex(WITHDRAWAL_PUBKEY, ZERO_AMOUNT)));

    for (final GoldenVector vector : vectors) {
      assertThat(vector.expectedRawTx()).startsWith("0x");
      assertThat(vector.expectedSender()).isEqualToIgnoringCase(RICH_BENEFACTOR_ADDRESS);
    }

    Files.createDirectories(FIXTURE_PATH.getParent());
    Files.writeString(FIXTURE_PATH, toJson(vectors), StandardCharsets.UTF_8);

    assertThat(FIXTURE_PATH).exists();
    assertThat(Files.readString(FIXTURE_PATH)).isNotBlank();
  }

  private GoldenVector goldenVector(
      final String name, final BigInteger nonce, final String to, final String data) {
    final Credentials credentials = Credentials.create(RICH_BENEFACTOR_PRIVATE_KEY);
    final RawTransaction rawTransaction =
        RawTransaction.createTransaction(nonce, GAS_PRICE, GAS_LIMIT, to, VALUE, data);
    final byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
    return new GoldenVector(
        name,
        RICH_BENEFACTOR_PRIVATE_KEY,
        nonce,
        GAS_PRICE,
        GAS_LIMIT,
        to,
        VALUE,
        data,
        Numeric.toHexString(signedMessage),
        credentials.getAddress());
  }

  private static String concatHex(final String a, final String b) {
    return "0x" + Numeric.cleanHexPrefix(a) + Numeric.cleanHexPrefix(b);
  }

  private static String toJson(final List<GoldenVector> vectors) {
    final StringBuilder json = new StringBuilder("[\n");
    for (int i = 0; i < vectors.size(); i++) {
      json.append(vectors.get(i).toJson());
      json.append(i < vectors.size() - 1 ? ",\n" : "\n");
    }
    json.append("]\n");
    return json.toString();
  }

  private record GoldenVector(
      String name,
      String privateKey,
      BigInteger nonce,
      BigInteger gasPrice,
      BigInteger gasLimit,
      String to,
      BigInteger value,
      String data,
      String expectedRawTx,
      String expectedSender) {

    String toJson() {
      return "  {\n"
          + "    \"name\": \""
          + name
          + "\",\n"
          + "    \"privateKey\": \""
          + privateKey
          + "\",\n"
          + "    \"nonce\": "
          + nonce
          + ",\n"
          + "    \"gasPrice\": "
          + gasPrice
          + ",\n"
          + "    \"gasLimit\": "
          + gasLimit
          + ",\n"
          + "    \"to\": \""
          + to
          + "\",\n"
          + "    \"value\": "
          + value
          + ",\n"
          + "    \"data\": \""
          + data
          + "\",\n"
          + "    \"expectedRawTx\": \""
          + expectedRawTx
          + "\",\n"
          + "    \"expectedSender\": \""
          + expectedSender
          + "\"\n"
          + "  }";
    }
  }
}
