package io.github.axiaoyuan.llmgateway.proxy;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.axiaoyuan.llmgateway.common.ErrorBodies;
import io.github.axiaoyuan.llmgateway.common.JsonPeek;
import io.github.axiaoyuan.llmgateway.observe.GatewayMetrics;
import io.github.axiaoyuan.llmgateway.observe.GatewayMetrics.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 网关的入口：客户端按 OpenAI 格式发请求，网关原样转给上游，再把响应原样带回。
 */
@RestController
class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final RestClient upstream;
    private final GatewayMetrics metrics;

    ChatCompletionsController(RestClient upstreamRestClient, GatewayMetrics metrics) {
        this.upstream = upstreamRestClient;
        this.metrics = metrics;
    }

    /** 上游返回的东西：状态码、正文、内容类型。 */
    private record UpstreamReply(int status, String body, MediaType contentType) {
    }

    /**
     * 请求体用 String 接收、原样转发，不解析成 Java 对象：
     * 客户端传什么字段都不会丢，厂商加了新参数网关也不用改。
     *
     * 注意：客户端带来的 Authorization 头不会被转发。上游用的是网关自己的密钥
     * （在 UpstreamClientConfig 里统一设置），客户端的 Key 以后由网关自己校验（功能 3）。
     */
    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> chat(@RequestBody String body) {
        long start = System.nanoTime();
        // 只瞄一眼 model 字段用来打指标标签，不解析整个请求体
        String model = JsonPeek.text(body, "model").orElse(null);

        long upstreamStart = System.nanoTime();
        try {
            UpstreamReply reply = callUpstream(body);
            Duration upstreamTime = Duration.ofNanos(System.nanoTime() - upstreamStart);

            boolean ok = reply.status() >= 200 && reply.status() < 300;
            if (ok) {
                recordUsage(model, reply.body());
            }
            ResponseEntity<String> response = ResponseEntity.status(reply.status())
                    .contentType(reply.contentType())
                    .body(reply.body());
            finish(model, reply.status(), ok ? Outcome.SUCCESS : Outcome.UPSTREAM_ERROR, start, upstreamTime);
            return response;
        } catch (ResourceAccessException e) {
            Duration upstreamTime = Duration.ofNanos(System.nanoTime() - upstreamStart);
            // 根本没拿到上游响应：要么超时，要么连不上
            boolean timeout = hasCause(e, HttpTimeoutException.class);
            HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            // 只记异常类型，不记请求头：请求头里有密钥
            log.warn("连接上游失败 原因={}", e.getMostSpecificCause().getClass().getSimpleName());
            ResponseEntity<String> response = ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(timeout
                            ? ErrorBodies.of("upstream_timeout", "上游响应超时")
                            : ErrorBodies.of("upstream_unavailable", "无法连接上游"));
            finish(model, status.value(),
                    timeout ? Outcome.UPSTREAM_TIMEOUT : Outcome.UPSTREAM_UNAVAILABLE, start, upstreamTime);
            return response;
        }
    }

    private UpstreamReply callUpstream(String body) {
        return upstream.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                // 用 exchange 而不是 retrieve：retrieve 遇到 4xx/5xx 会抛异常，
                // 我们要的是把上游的状态码和错误信息原样交给客户端
                .exchange((request, resp) -> {
                    String text = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    MediaType type = resp.getHeaders().getContentType();
                    return new UpstreamReply(resp.getStatusCode().value(), text,
                            type != null ? type : MediaType.APPLICATION_JSON);
                });
    }

    /** 上游在响应里报告了 usage 就记下 token 数；没有就跳过（有的上游不返回）。 */
    private void recordUsage(String model, String responseBody) {
        JsonPeek.object(responseBody, "usage").ifPresent(usage -> metrics.recordTokens(model,
                longField(usage, "prompt_tokens"), longField(usage, "completion_tokens")));
    }

    private void finish(String model, int status, Outcome outcome, long startNanos, Duration upstreamTime) {
        Duration total = Duration.ofNanos(System.nanoTime() - startNanos);
        metrics.recordRequest(model, status, outcome, total, upstreamTime);
        log.info("model={} status={} 结果={} 总耗时={}ms 上游={}ms",
                model, status, outcome.name().toLowerCase(), total.toMillis(), upstreamTime.toMillis());
    }

    private static long longField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : 0;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
