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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * A minimal JSON-RPC client for the acceptance-test eth1 node, covering only the four methods the
 * acceptance test DSL exercises. Not a general-purpose Ethereum JSON-RPC client.
 */
public class Eth1JsonRpcClient {

  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

  private final String eth1NodeUrl;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicInteger nextRequestId = new AtomicInteger(0);

  public Eth1JsonRpcClient(final String eth1NodeUrl, final OkHttpClient httpClient) {
    this.eth1NodeUrl = eth1NodeUrl;
    this.httpClient = httpClient;
  }

  /**
   * Performs an {@code eth_call} with the sender fixed to {@link Eth1Address#ZERO}, matching the
   * read-only usage of this call throughout the acceptance test DSL.
   */
  public SafeFuture<Bytes> ethCall(
      final Eth1Address to, final Bytes data, final String blockParameter) {
    final Map<String, Object> callObject = new LinkedHashMap<>();
    callObject.put("from", Eth1Address.ZERO.toHexString());
    callObject.put("to", to.toHexString());
    callObject.put("data", data.toHexString());
    return sendRequest("eth_call", List.of(callObject, blockParameter))
        .thenApply(result -> Bytes.fromHexString(requireResult("eth_call", result).asText()));
  }

  public SafeFuture<UInt64> ethGetTransactionCount(
      final Eth1Address address, final String blockParameter) {
    return sendRequest("eth_getTransactionCount", List.of(address.toHexString(), blockParameter))
        .thenApply(result -> decodeQuantity("eth_getTransactionCount", result));
  }

  /** Submits a signed raw transaction, returning the resulting transaction hash. */
  public SafeFuture<String> ethSendRawTransaction(final Bytes signedTx) {
    return sendRequest("eth_sendRawTransaction", List.of(signedTx.toHexString()))
        .thenApply(result -> requireResult("eth_sendRawTransaction", result).asText());
  }

  public SafeFuture<Optional<TransactionReceipt>> ethGetTransactionReceipt(final String txHash) {
    return sendRequest("eth_getTransactionReceipt", List.of(txHash))
        .thenApply(Eth1JsonRpcClient::decodeReceipt);
  }

  private static Optional<TransactionReceipt> decodeReceipt(final JsonNode result) {
    if (result == null || result.isNull()) {
      return Optional.empty();
    }
    return Optional.of(
        new TransactionReceipt(
            result.path("transactionHash").asText(null),
            result.path("status").asText(null),
            result.path("blockNumber").asText(null)));
  }

  private static UInt64 decodeQuantity(final String method, final JsonNode result) {
    final String hex = requireResult(method, result).asText();
    final String unprefixed = hex.startsWith("0x") ? hex.substring(2) : hex;
    return UInt64.valueOf(new BigInteger(unprefixed.isEmpty() ? "0" : unprefixed, 16));
  }

  /**
   * Guards against a malformed JSON-RPC response that has neither {@code error} nor {@code result},
   * which would otherwise surface as a bare {@link NullPointerException}. Not used by {@link
   * #decodeReceipt}, for which a null result is a legitimate "pending transaction" response.
   */
  private static JsonNode requireResult(final String method, final JsonNode result) {
    if (result == null || result.isNull()) {
      throw new RuntimeException(
          "JSON-RPC call to " + method + " returned a response with neither error nor result");
    }
    return result;
  }

  private SafeFuture<JsonNode> sendRequest(final String method, final List<Object> params) {
    final int id = nextRequestId.incrementAndGet();
    final byte[] requestBodyBytes;
    try {
      requestBodyBytes =
          objectMapper.writeValueAsBytes(new JsonRpcRequest("2.0", method, params, id));
    } catch (final IOException e) {
      return SafeFuture.failedFuture(e);
    }

    final Request httpRequest =
        new Request.Builder()
            .url(eth1NodeUrl)
            .post(RequestBody.create(requestBodyBytes, JSON_MEDIA_TYPE))
            .build();

    final SafeFuture<JsonNode> future = new SafeFuture<>();
    httpClient
        .newCall(httpRequest)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(final Call call, final IOException e) {
                future.completeExceptionally(e);
              }

              @Override
              public void onResponse(final Call call, final Response response) {
                try (response) {
                  future.complete(readResult(method, response));
                } catch (final Exception e) {
                  future.completeExceptionally(e);
                }
              }
            });
    return future;
  }

  private JsonNode readResult(final String method, final Response response) throws IOException {
    if (!response.isSuccessful()) {
      throw new IOException(
          "JSON-RPC call to "
              + method
              + " failed with HTTP status "
              + response.code()
              + ": "
              + response.message());
    }
    final ResponseBody body = response.body();
    if (body == null) {
      throw new IOException("JSON-RPC call to " + method + " returned an empty response body");
    }
    final JsonNode root = objectMapper.readTree(body.byteStream());
    final JsonNode errorNode = root.get("error");
    if (errorNode != null && !errorNode.isNull()) {
      final int code = errorNode.path("code").asInt();
      final String message = errorNode.path("message").asText();
      throw new RuntimeException(
          "JSON-RPC error calling " + method + ": code=" + code + ", message=" + message);
    }
    return root.get("result");
  }

  private record JsonRpcRequest(String jsonrpc, String method, List<Object> params, int id) {}
}
