package dev.llmgateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 用 JDK 自带的 HttpServer 起一个“假上游”，行为完全由测试控制：
 * 请求体里出现 "slow" 就卡住，出现 "rate" 就返回 429，否则把收到的内容回显。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatForwardingTests {

    private static final HttpServer UPSTREAM = startUpstream();
    private static volatile String lastAuthorization;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1");
        registry.add("gateway.upstream.api-key", () -> "upstream-secret");
        registry.add("gateway.upstream.read-timeout", () -> "500ms");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Autowired
    TestRestTemplate client;

    @Test
    void 正常请求原样转发并原样返回() {
        ResponseEntity<String> resp = post("{\"model\":\"m\",\"extra_field\":42}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 网关不认识的字段 extra_field 也被原样转发了
        assertThat(resp.getBody()).contains("\"extra_field\":42");
    }

    @Test
    void 上游的错误状态码和错误信息原样透传() {
        ResponseEntity<String> resp = post("{\"model\":\"rate\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getBody()).contains("rate_limit_exceeded");
    }

    @Test
    void 上游太慢返回504() {
        ResponseEntity<String> resp = post("{\"model\":\"slow\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resp.getBody()).contains("upstream_timeout");
    }

    @Test
    void 客户端的密钥不转发_上游收到的是网关自己的密钥() {
        post("{\"model\":\"m\"}");

        assertThat(lastAuthorization).isEqualTo("Bearer upstream-secret");
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("client-key-should-not-leak");
        return client.postForEntity("/v1/chat/completions", new HttpEntity<>(json, headers), String.class);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", ChatForwardingTests::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = 200;
        String reply = "{\"echo\":" + body + "}";
        if (body.contains("slow")) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (body.contains("rate")) {
            status = 429;
            reply = "{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
