package io.github.axiaoyuan.llmgateway.proxy;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import io.github.axiaoyuan.llmgateway.common.ErrorBodies;
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

    ChatCompletionsController(RestClient upstreamRestClient) {
        this.upstream = upstreamRestClient;
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
        try {
            ResponseEntity<String> response = upstream.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    // 用 exchange 而不是 retrieve：retrieve 遇到 4xx/5xx 会抛异常，
                    // 我们要的是把上游的状态码和错误信息原样交给客户端
                    .exchange((request, resp) -> {
                        String text = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        MediaType type = resp.getHeaders().getContentType();
                        return ResponseEntity.status(resp.getStatusCode())
                                .contentType(type != null ? type : MediaType.APPLICATION_JSON)
                                .body(text);
                    });
            log.info("转发完成 status={} 耗时={}ms", response.getStatusCode().value(), elapsedMs(start));
            return response;
        } catch (ResourceAccessException e) {
            // 根本没拿到上游响应：要么超时，要么连不上
            boolean timeout = hasCause(e, HttpTimeoutException.class);
            HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            String type = timeout ? "upstream_timeout" : "upstream_unavailable";
            String message = timeout ? "上游响应超时" : "无法连接上游";
            // 只记异常类型，不记请求头：请求头里有密钥
            log.warn("转发失败 status={} 原因={} 耗时={}ms",
                    status.value(), e.getMostSpecificCause().getClass().getSimpleName(), elapsedMs(start));
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorBodies.of(type, message));
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
