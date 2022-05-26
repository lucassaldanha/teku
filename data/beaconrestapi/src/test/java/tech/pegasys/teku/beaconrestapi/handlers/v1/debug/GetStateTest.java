/*
 * Copyright 2022 ConsenSys AG.
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

package tech.pegasys.teku.beaconrestapi.handlers.v1.debug;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_INTERNAL_SERVER_ERROR;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_NOT_FOUND;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.restapi.MetadataTestUtil.getResponseStringFromMetadata;
import static tech.pegasys.teku.infrastructure.restapi.MetadataTestUtil.verifyMetadataErrorResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.io.Resources;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.beaconrestapi.AbstractMigratedBeaconHandlerWithChainDataProviderTest;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.datastructures.metadata.StateAndMetaData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

class GetStateTest extends AbstractMigratedBeaconHandlerWithChainDataProviderTest {
  private GetState handler;

  @BeforeEach
  void setup() {
    initialise(SpecMilestone.PHASE0);
    genesis();

    request.setPathParameter("state_id", "head");
    handler = new GetState(chainDataProvider, spec, schemaDefinitionCache);
  }

  @Test
  void shouldReturnStateInformation()
      throws JsonProcessingException, ExecutionException, InterruptedException {
    final StateAndMetaData stateAndMetaData =
        chainDataProvider.getBeaconStateAndMetadata("head").get().orElseThrow();

    handler.handleRequest(request);

    assertThat(request.getResponseCode()).isEqualTo(SC_OK);
    assertThat(request.getResponseBody()).isEqualTo(stateAndMetaData.getData());
  }

  @Test
  void metadata_shouldHandle400() throws JsonProcessingException {
    verifyMetadataErrorResponse(handler, SC_BAD_REQUEST);
  }

  @Test
  void metadata_shouldHandle404() throws JsonProcessingException {
    verifyMetadataErrorResponse(handler, SC_NOT_FOUND);
  }

  @Test
  void metadata_shouldHandle500() throws JsonProcessingException {
    verifyMetadataErrorResponse(handler, SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void metadata_shouldHandle200() throws IOException {
    BeaconState responseData = dataStructureUtil.randomBeaconState();

    final String data = getResponseStringFromMetadata(handler, SC_OK, responseData);
    final String expected =
        Resources.toString(Resources.getResource(GetStateTest.class, "getState.json"), UTF_8);
    assertThat(data).isEqualTo(String.format(expected, responseData.getGenesisTime()));
  }
}