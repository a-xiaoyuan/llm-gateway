package io.github.axiaoyuan.llmgateway.observe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 从 Prometheus 的视角验证指标：发请求，再读 /actuator/prometheus，看抓到的文本里有没有对应的数。
 * 假上游：model 是 "boom" 时返回 500，其余返回带 usage 的正常响应。
 *
 * Spring Boot 测试里默认关掉指标导出（/actuator/prometheus 会 404），@AutoConfigureObservability 把它打开。
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.metrics.max-models=5")
class MetricsEndpointTests {

    private static final HttpServer UPSTREAM = startUpstream();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstream.base-url",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Autowired
    TestRestTemplate client;

    @Test
    void 成功请求记下请求数_耗时直方图和token() {
        chat("m-ok");

        String scrape = scrape();
        assertThat(scrape).containsPattern(
                "gateway_requests_total\\{[^}]*model=\"m-ok\"[^}]*outcome=\"success\"[^}]*status=\"200\"[^}]*} 1\\.0");
        assertThat(scrape).containsPattern("gateway_tokens_total\\{[^}]*model=\"m-ok\"[^}]*type=\"prompt\"[^}]*} 7\\.0");
        assertThat(scrape).containsPattern("gateway_tokens_total\\{[^}]*model=\"m-ok\"[^}]*type=\"completion\"[^}]*} 5\\.0");
        // 有直方图桶，Prometheus 才能算 P99
        assertThat(scrape).contains("gateway_request_duration_seconds_bucket{");
        assertThat(scrape).contains("gateway_overhead_seconds_bucket{");
        assertThat(scrape).contains("gateway_upstream_duration_seconds_bucket{");
    }

    @Test
    void 上游报错记为upstream_error_且不记token() {
        chat("boom");

        String scrape = scrape();
        assertThat(scrape).containsPattern(
                "gateway_requests_total\\{[^}]*model=\"boom\"[^}]*outcome=\"upstream_error\"[^}]*status=\"500\"");
        assertThat(scrape).doesNotContainPattern("gateway_tokens_total\\{[^}]*model=\"boom\"");
    }

    @Test
    void 客户端乱填模型名_model标签数量有上限() {
        for (int i = 0; i < 30; i++) {
            chat("random-" + i);
        }

        Matcher m = Pattern.compile("gateway_requests_total\\{[^}]*model=\"([^\"]+)\"").matcher(scrape());
        var models = m.results().map(r -> r.group(1)).collect(Collectors.toSet());
        // 上限是 5（见类上的 properties），30 个随机名字不会生成 30 组时间序列
        assertThat(models).hasSizeLessThanOrEqualTo(5);
    }

    private void chat(String model) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        client.postForEntity("/v1/chat/completions",
                new HttpEntity<>("{\"model\":\"" + model + "\",\"messages\":[]}", headers), String.class);
    }

    private String scrape() {
        return client.getForObject("/actuator/prometheus", String.class);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", MetricsEndpointTests::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean boom = body.contains("\"boom\"");
        String reply = boom
                ? "{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}"
                : "{\"id\":\"x\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5,\"total_tokens\":12}}";
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(boom ? 500 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
