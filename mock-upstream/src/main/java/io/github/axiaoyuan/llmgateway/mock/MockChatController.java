package io.github.axiaoyuan.llmgateway.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用 model 字段触发故障：
 * <ul>
 *   <li>{@code mock-429}：返回 429 限流</li>
 *   <li>{@code mock-500}：返回 500 服务错误</li>
 *   <li>{@code mock-slow}：卡住 mock.slow-ms 毫秒（默认 300 秒），用来测超时</li>
 * </ul>
 * 其他任何 model 名都正常返回，延迟为 mock.latency-ms 毫秒。
 */
@RestController
class MockChatController {

    private final long latencyMs;
    private final long slowMs;

    MockChatController(@Value("${mock.latency-ms:200}") long latencyMs,
                       @Value("${mock.slow-ms:300000}") long slowMs) {
        this.latencyMs = latencyMs;
        this.slowMs = slowMs;
    }

    @PostMapping("/v1/chat/completions")
    ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) throws InterruptedException {
        String model = String.valueOf(request.getOrDefault("model", "mock"));
        switch (model) {
            case "mock-429" -> {
                return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "模拟上游：请求太频繁");
            }
            case "mock-500" -> {
                return error(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "模拟上游：内部错误");
            }
            case "mock-slow" -> Thread.sleep(slowMs);
            default -> Thread.sleep(latencyMs);
        }

        int promptTokens = estimateTokens(request.get("messages"));
        int completionTokens = 12;
        return ResponseEntity.ok(Map.of(
                "id", "chatcmpl-mock-" + UUID.randomUUID(),
                "object", "chat.completion",
                "created", System.currentTimeMillis() / 1000,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", "这是模拟上游的固定回答。"),
                        "finish_reason", "stop")),
                "usage", Map.of(
                        "prompt_tokens", promptTokens,
                        "completion_tokens", completionTokens,
                        "total_tokens", promptTokens + completionTokens)));
    }

    /** 粗略估算：约 4 个字符算 1 个 token。只为让 usage 有个随输入变化的数，后面计费要用。 */
    private static int estimateTokens(Object messages) {
        return messages == null ? 1 : messages.toString().length() / 4 + 1;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("type", type, "message", message)));
    }
}
