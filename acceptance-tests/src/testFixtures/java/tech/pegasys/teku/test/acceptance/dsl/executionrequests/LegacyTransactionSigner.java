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

import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.apache.tuweni.crypto.SECP256K1;
import org.apache.tuweni.rlp.RLP;
import org.apache.tuweni.rlp.RLPWriter;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Signs legacy (pre-EIP-155) Ethereum transactions for the acceptance test DSL, replacing web3j's
 * {@code TransactionEncoder}/{@code FastRawTransactionManager}.
 *
 * <p>The acceptance test besu node's eth1 credentials sign without a chain id (matching web3j's
 * behaviour when its transaction manager is constructed with {@code ChainId.NONE}), so {@code v =
 * 27 + recoveryId} and no chain id appears anywhere in the encoding, unsigned or signed.
 *
 * <p>{@code sign} always takes an {@link Eth1Credentials} argument, so that class's static
 * initializer (which registers the BouncyCastle security provider tuweni's {@code SECP256K1}
 * requires) is guaranteed to have already run by the time any signing happens here; this class does
 * not repeat that registration.
 */
public class LegacyTransactionSigner {

  private static final BigInteger V_OFFSET = BigInteger.valueOf(27);

  private LegacyTransactionSigner() {}

  /**
   * Builds and signs a legacy transaction.
   *
   * @param credentials the eth1 account signing the transaction
   * @param nonce the sender's transaction count
   * @param gasPrice the gas price, in wei
   * @param gasLimit the gas limit
   * @param to the recipient address
   * @param value the value to transfer, in wei
   * @param data the transaction call data
   * @return the RLP-encoded, signed raw transaction
   */
  public static Bytes sign(
      final Eth1Credentials credentials,
      final UInt64 nonce,
      final BigInteger gasPrice,
      final BigInteger gasLimit,
      final Eth1Address to,
      final BigInteger value,
      final Bytes data) {
    final Bytes unsignedRlp = encodeUnsignedFields(nonce, gasPrice, gasLimit, to, value, data);
    final Bytes32 hash = Hash.keccak256(unsignedRlp);
    final SECP256K1.Signature signature = SECP256K1.signHashed(hash, credentials.keyPair());
    final BigInteger v = V_OFFSET.add(BigInteger.valueOf(signature.v()));

    return RLP.encodeList(
        writer -> {
          writeUnsignedFields(writer, nonce, gasPrice, gasLimit, to, value, data);
          writer.writeBigInteger(v);
          writer.writeBigInteger(signature.r());
          writer.writeBigInteger(signature.s());
        });
  }

  private static Bytes encodeUnsignedFields(
      final UInt64 nonce,
      final BigInteger gasPrice,
      final BigInteger gasLimit,
      final Eth1Address to,
      final BigInteger value,
      final Bytes data) {
    return RLP.encodeList(
        writer -> writeUnsignedFields(writer, nonce, gasPrice, gasLimit, to, value, data));
  }

  private static void writeUnsignedFields(
      final RLPWriter writer,
      final UInt64 nonce,
      final BigInteger gasPrice,
      final BigInteger gasLimit,
      final Eth1Address to,
      final BigInteger value,
      final Bytes data) {
    // writeBigInteger, unlike writeValue, produces the canonical minimal-length RLP encoding of an
    // integer: no leading zero byte, and zero itself encodes as the empty string (0x80).
    writer.writeBigInteger(nonce.bigIntegerValue());
    writer.writeBigInteger(gasPrice);
    writer.writeBigInteger(gasLimit);
    writer.writeValue(to.getWrappedBytes());
    writer.writeBigInteger(value);
    writer.writeValue(data);
  }
}
