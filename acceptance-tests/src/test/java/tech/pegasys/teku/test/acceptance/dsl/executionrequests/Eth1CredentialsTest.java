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

import org.junit.jupiter.api.Test;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;

class Eth1CredentialsTest {

  // BesuNode.getRichBenefactorKey()/getRichBenefactorAddress() are instance methods on a class
  // that otherwise requires a testcontainers Network and docker image to construct, so (matching
  // the same account used in the legacy-tx-golden-vectors.json fixture) the values are reproduced
  // here rather than instantiating a BesuNode in this plain unit test.
  private static final String RICH_BENEFACTOR_PRIVATE_KEY =
      "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63";
  private static final String RICH_BENEFACTOR_ADDRESS =
      "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73";

  @Test
  void fromHexKey_derivesRichBenefactorAddress() {
    final Eth1Credentials credentials = Eth1Credentials.fromHexKey(RICH_BENEFACTOR_PRIVATE_KEY);

    assertThat(credentials.address()).isEqualTo(Eth1Address.fromHexString(RICH_BENEFACTOR_ADDRESS));
  }

  @Test
  void fromHexKey_acceptsKeyWithoutHexPrefix() {
    final String unprefixed = RICH_BENEFACTOR_PRIVATE_KEY.substring(2);

    final Eth1Credentials credentials = Eth1Credentials.fromHexKey(unprefixed);

    assertThat(credentials.address()).isEqualTo(Eth1Address.fromHexString(RICH_BENEFACTOR_ADDRESS));
  }

  @Test
  void fromHexKey_withAndWithoutPrefixDeriveTheSameAddress() {
    final Eth1Credentials withPrefix = Eth1Credentials.fromHexKey(RICH_BENEFACTOR_PRIVATE_KEY);
    final Eth1Credentials withoutPrefix =
        Eth1Credentials.fromHexKey(RICH_BENEFACTOR_PRIVATE_KEY.substring(2));

    assertThat(withPrefix.address()).isEqualTo(withoutPrefix.address());
  }
}
