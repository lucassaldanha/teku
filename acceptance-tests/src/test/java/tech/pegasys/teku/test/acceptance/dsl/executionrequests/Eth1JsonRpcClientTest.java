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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class Eth1JsonRpcClientTest {

  private static final Eth1Address CONTRACT_ADDRESS =
      Eth1Address.fromHexString("0x00000961Ef480Eb55e80D19ad83579A64c007002");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockWebServer mockWebServer;
  private Eth1JsonRpcClient client;

  @BeforeEach
  void setUp() throws IOException {
    mockWebServer = new MockWebServer();
    mockWebServer.start();
    client = new Eth1JsonRpcClient(mockWebServer.url("/").toString(), new OkHttpClient());
  }

  @AfterEach
  void tearDown() throws IOException {
    mockWebServer.shutdown();
  }

  @Test
  void ethCall_decodesSuccessfulResult() throws Exception {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"result\":\"0x0000000000000000000000000000000000000000000000000000000000000005\"}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<Bytes> future = client.ethCall(CONTRACT_ADDRESS, Bytes.EMPTY, "latest");

    assertThat(future.get(5, TimeUnit.SECONDS))
        .isEqualTo(
            Bytes.fromHexString(
                "0x0000000000000000000000000000000000000000000000000000000000000005"));

    final RecordedRequest recordedRequest = mockWebServer.takeRequest();
    final JsonNode requestJson = objectMapper.readTree(recordedRequest.getBody().readUtf8());
    assertThat(requestJson.get("method").asText()).isEqualTo("eth_call");
    final JsonNode callObject = requestJson.get("params").get(0);
    assertThat(callObject.get("from").asText()).isEqualTo(Eth1Address.ZERO.toHexString());
    assertThat(callObject.get("to").asText()).isEqualTo(CONTRACT_ADDRESS.toHexString());
    assertThat(callObject.get("data").asText()).isEqualTo("0x");
    assertThat(requestJson.get("params").get(1).asText()).isEqualTo("latest");
  }

  @Test
  void ethGetTransactionCount_decodesHexQuantityToUInt64() throws Exception {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x2a\"}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<UInt64> future = client.ethGetTransactionCount(CONTRACT_ADDRESS, "latest");

    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(UInt64.valueOf(42));
  }

  @Test
  void ethGetTransactionReceipt_returnsEmptyWhenResultIsNull() throws Exception {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<Optional<TransactionReceipt>> future =
        client.ethGetTransactionReceipt("0xabc123");

    assertThat(future.get(5, TimeUnit.SECONDS)).isEmpty();
  }

  @Test
  void ethGetTransactionReceipt_decodesNonNullResult() throws Exception {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                    + "{\"transactionHash\":\"0xabc123\",\"status\":\"0x1\",\"blockNumber\":\"0x10\"}}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<Optional<TransactionReceipt>> future =
        client.ethGetTransactionReceipt("0xabc123");

    assertThat(future.get(5, TimeUnit.SECONDS))
        .contains(new TransactionReceipt("0xabc123", "0x1", "0x10"));
  }

  @Test
  void sendRequest_failsFutureWithMethodNameOnJsonRpcError() {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"error\":{\"code\":-32000,\"message\":\"execution reverted\"}}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<Bytes> future = client.ethCall(CONTRACT_ADDRESS, Bytes.EMPTY, "latest");

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .hasMessageContaining("eth_call")
        .hasMessageContaining("-32000")
        .hasMessageContaining("execution reverted");
  }

  @Test
  void sendRequest_failsFutureOnNonSuccessfulHttpStatus() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("internal error"));

    final SafeFuture<String> future = client.ethSendRawTransaction(Bytes.fromHexString("0x1234"));

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .hasMessageContaining("eth_sendRawTransaction")
        .hasMessageContaining("500");
  }

  @Test
  void sendRequest_failsFutureWithMethodNameWhenResponseHasNeitherErrorNorResult() {
    mockWebServer.enqueue(
        new MockResponse()
            .setBody("{\"jsonrpc\":\"2.0\",\"id\":1}")
            .addHeader("Content-Type", "application/json"));

    final SafeFuture<String> future = client.ethSendRawTransaction(Bytes.fromHexString("0x1234"));

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .hasMessageContaining("eth_sendRawTransaction");
  }
}
