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

import java.security.Security;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.apache.tuweni.crypto.SECP256K1;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;

/**
 * Holds a SECP256K1 key pair for an eth1 account used by the acceptance test DSL, replacing web3j's
 * {@code Credentials}.
 */
public class Eth1Credentials {

  static {
    // Tuweni's SECP256K1 looks up the "BC" provider by name (KeyPairGenerator.getInstance(alg,
    // "BC")), which requires BouncyCastle to be registered globally rather than merely present on
    // the classpath. Registration is idempotent, so it is safe even if something else already
    // registered it.
    Security.addProvider(new BouncyCastleProvider());
  }

  private final SECP256K1.KeyPair keyPair;
  private final Eth1Address address;

  private Eth1Credentials(final SECP256K1.KeyPair keyPair) {
    this.keyPair = keyPair;
    this.address = deriveAddress(keyPair.publicKey());
  }

  /**
   * Creates credentials from a hex-encoded private key, with or without a {@code 0x} prefix.
   *
   * @param hexPrivateKey the 32-byte private key, hex encoded
   * @return the derived credentials
   */
  public static Eth1Credentials fromHexKey(final String hexPrivateKey) {
    final SECP256K1.SecretKey secretKey =
        SECP256K1.SecretKey.fromBytes(Bytes32.fromHexString(hexPrivateKey));
    return new Eth1Credentials(SECP256K1.KeyPair.fromSecretKey(secretKey));
  }

  public SECP256K1.KeyPair keyPair() {
    return keyPair;
  }

  public Eth1Address address() {
    return address;
  }

  private static Eth1Address deriveAddress(final SECP256K1.PublicKey publicKey) {
    // Ethereum address = the low-order 20 bytes of keccak256 of the 64-byte uncompressed public
    // key (x || y, without the leading 0x04 prefix byte). Tuweni's PublicKey#bytes() is already
    // in that 64-byte, prefix-less form.
    final Bytes hash = Hash.keccak256(publicKey.bytes());
    return Eth1Address.fromBytes(hash.slice(12, 20));
  }
}
