/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.beaconrestapi.handlers.v1.config;

import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_INTERNAL_SERVER_ERROR;
import static tech.pegasys.teku.infrastructure.http.HttpStatusCodes.SC_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_INTERNAL_ERROR;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.RES_OK;
import static tech.pegasys.teku.infrastructure.http.RestApiConstants.TAG_CONFIG;
import static tech.pegasys.teku.infrastructure.json.types.CoreTypes.UINT64_TYPE;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.javalin.http.Context;
import io.javalin.plugin.openapi.annotations.HttpMethod;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import tech.pegasys.teku.api.ConfigProvider;
import tech.pegasys.teku.api.response.v1.config.GetDepositContractResponse;
import tech.pegasys.teku.beaconrestapi.MigratingEndpointAdapter;
import tech.pegasys.teku.infrastructure.json.types.SerializableTypeDefinition;
import tech.pegasys.teku.infrastructure.restapi.endpoints.EndpointMetadata;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiRequest;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.eth1.Eth1Address;

public class GetDepositContract extends MigratingEndpointAdapter {
  public static final String ROUTE = "/eth/v1/config/deposit_contract";
  private final Eth1Address depositContractAddress;
  private final ConfigProvider configProvider;

  private static final SerializableTypeDefinition<DepositContractData> DEPOSIT_CONTRACT_TYPE =
      SerializableTypeDefinition.object(DepositContractData.class)
          .withField(
              "chain_id",
              UINT64_TYPE.withDescription("Id of Eth1 chain on which contract is deployed."),
              DepositContractData::getChainId)
          .withField(
              "address", Eth1Address.getJsonTypeDefinition(), DepositContractData::getAddress)
          .build();

  private static final SerializableTypeDefinition<DepositContractData>
      DEPOSIT_CONTRACT_RESPONSE_TYPE =
          SerializableTypeDefinition.object(DepositContractData.class)
              .name("GetDepositContractResponse")
              .withField("data", DEPOSIT_CONTRACT_TYPE, Function.identity())
              .build();

  public GetDepositContract(
      final Eth1Address depositContractAddress, final ConfigProvider configProvider) {
    super(
        EndpointMetadata.get(ROUTE)
            .operationId("getDepositContractAddress")
            .summary("Get deposit contract address")
            .description("Retrieve deposit contract address and genesis fork version.")
            .tags(TAG_CONFIG)
            .response(SC_OK, "Request successful", DEPOSIT_CONTRACT_RESPONSE_TYPE)
            .response(SC_INTERNAL_SERVER_ERROR, "Beacon node internal error.")
            .build());
    this.configProvider = configProvider;
    this.depositContractAddress = depositContractAddress;
  }

  @OpenApi(
      path = ROUTE,
      method = HttpMethod.GET,
      summary = "Get deposit contract address",
      tags = {TAG_CONFIG},
      description = "Retrieve deposit contract address and genesis fork version.",
      responses = {
        @OpenApiResponse(
            status = RES_OK,
            content = @OpenApiContent(from = GetDepositContractResponse.class)),
        @OpenApiResponse(status = RES_INTERNAL_ERROR)
      })
  @Override
  public void handle(@NotNull final Context ctx) throws Exception {
    adapt(ctx);
  }

  @Override
  public void handleRequest(RestApiRequest request) throws JsonProcessingException {
    final int depositChainId = configProvider.getGenesisSpecConfig().getDepositChainId();
    DepositContractData data = new DepositContractData(depositChainId, depositContractAddress);
    request.respondOk(data);
  }

  private static class DepositContractData {
    final UInt64 chainId;
    final Eth1Address address;

    DepositContractData(int chainId, Eth1Address address) {
      this.chainId = UInt64.valueOf(chainId);
      this.address = address;
    }

    UInt64 getChainId() {
      return chainId;
    }

    Eth1Address getAddress() {
      return address;
    }
  }
}
