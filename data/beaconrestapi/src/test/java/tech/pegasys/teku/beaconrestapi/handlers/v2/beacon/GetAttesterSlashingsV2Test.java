/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.beaconrestapi.handlers.v2.beacon;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_BAD_REQUEST;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_INTERNAL_SERVER_ERROR;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_VERSION;
import static tech.pegasys.teku.infrastructure.restapi.MetadataTestUtil.getResponseStringFromMetadata;
import static tech.pegasys.teku.infrastructure.restapi.MetadataTestUtil.verifyMetadataErrorResponse;
import static tech.pegasys.teku.spec.SpecMilestone.ELECTRA;
import static tech.pegasys.teku.spec.SpecMilestone.PHASE0;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.io.Resources;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import tech.pegasys.teku.beaconrestapi.AbstractMigratedBeaconHandlerTest;
import tech.pegasys.teku.infrastructure.json.JsonTestUtil;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.TestSpecContext;
import tech.pegasys.teku.spec.TestSpecInvocationContextProvider;
import tech.pegasys.teku.spec.datastructures.metadata.ObjectAndMetaData;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionCache;
import tech.pegasys.teku.spec.util.DataStructureUtil;

@TestSpecContext(milestone = {PHASE0, ELECTRA})
class GetAttesterSlashingsV2Test extends AbstractMigratedBeaconHandlerTest {

  private SpecMilestone specMilestone;

  @BeforeEach
  void setup(final TestSpecInvocationContextProvider.SpecContext specContext) {
    spec = specContext.getSpec();
    dataStructureUtil = new DataStructureUtil(spec);
    specMilestone = specContext.getSpecMilestone();
    setHandler(new GetAttesterSlashingsV2(nodeDataProvider, new SchemaDefinitionCache(spec)));
  }

  @TestTemplate
  void should_returnAttesterSlashings() throws Exception {
    final List<AttesterSlashing> attesterSlashing =
        List.of(dataStructureUtil.randomAttesterSlashing());
    final ObjectAndMetaData<List<AttesterSlashing>> attesterSlashingWithMetaData =
        new ObjectAndMetaData<>(attesterSlashing, specMilestone, false, false, false);
    when(nodeDataProvider.getAttesterSlashingsAndMetaData())
        .thenReturn(attesterSlashingWithMetaData);

    handler.handleRequest(request);
    assertThat(request.getResponseCode()).isEqualTo(SC_OK);
    assertThat(request.getResponseBody()).isEqualTo(attesterSlashingWithMetaData);
    assertThat(request.getResponseHeaders(HEADER_CONSENSUS_VERSION))
        .isEqualTo(specMilestone.name());
  }

  @TestTemplate
  void should_returnEmptyListWhenNoAttesterSlashings() throws Exception {

    final ObjectAndMetaData<List<AttesterSlashing>> attesterSlashingWithMetaData =
        new ObjectAndMetaData<>(emptyList(), specMilestone, false, false, false);
    when(nodeDataProvider.getAttesterSlashingsAndMetaData())
        .thenReturn(attesterSlashingWithMetaData);

    handler.handleRequest(request);
    assertThat(request.getResponseCode()).isEqualTo(SC_OK);
    assertThat(request.getResponseBody()).isEqualTo(attesterSlashingWithMetaData);
    assertThat(request.getResponseHeaders(HEADER_CONSENSUS_VERSION))
        .isEqualTo(specMilestone.name());
  }

  @TestTemplate
  void metadata_shouldHandle200() throws Exception {
    final ObjectAndMetaData<List<AttesterSlashing>> responseData =
        withMetaData(
            List.of(
                dataStructureUtil.randomAttesterSlashing(),
                dataStructureUtil.randomAttesterSlashing()));

    final JsonNode data =
        JsonTestUtil.parseAsJsonNode(getResponseStringFromMetadata(handler, SC_OK, responseData));
    final JsonNode expected =
        JsonTestUtil.parseAsJsonNode(getExpectedResponseAsJson(specMilestone));
    assertThat(data).isEqualTo(expected);
  }

  @TestTemplate
  void metadata_shouldHandle400() throws JsonProcessingException {
    verifyMetadataErrorResponse(handler, SC_BAD_REQUEST);
  }

  @TestTemplate
  void metadata_shouldHandle500() throws JsonProcessingException {
    verifyMetadataErrorResponse(handler, SC_INTERNAL_SERVER_ERROR);
  }

  private String getExpectedResponseAsJson(final SpecMilestone specMilestone) throws IOException {
    final String fileName = String.format("getAttesterSlashings%s.json", specMilestone.name());
    return Resources.toString(
        Resources.getResource(GetAttesterSlashingsV2Test.class, fileName), UTF_8);
  }
}
