package io.github.axiaoyuan.llmgateway.observe;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 网关的业务指标。Prometheus 里看到的名字是：
 * <ul>
 *   <li>{@code gateway_requests_total}：请求数，按模型、状态码、结果分组</li>
 *   <li>{@code gateway_request_duration_seconds}：总耗时（客户端感受到的）</li>
 *   <li>{@code gateway_upstream_duration_seconds}：等上游的耗时</li>
 *   <li>{@code gateway_overhead_seconds}：网关自身耗时 = 总耗时 - 等上游。压测最该看这个</li>
 *   <li>{@code gateway_tokens_total}：token 用量，按模型和类型（prompt / completion）分组</li>
 * </ul>
 * 标签数量受 {@link MetricsConfig} 限制，防止客户端乱填模型名把时间序列撑爆。
 */
@Component
public class GatewayMetrics {

    /** 结果分类：比 HTTP 状态码更贴近“网关这一侧发生了什么”。 */
    public enum Outcome {
        /** 上游正常返回（2xx） */
        SUCCESS,
        /** 上游返回了错误（4xx / 5xx），网关原样透传 */
        UPSTREAM_ERROR,
        /** 等上游超时，网关返回 504 */
        UPSTREAM_TIMEOUT,
        /** 连不上上游，网关返回 502 */
        UPSTREAM_UNAVAILABLE;

        String tag() {
            return name().toLowerCase();
        }
    }

    static final String UNKNOWN_MODEL = "unknown";

    /*
     * 直方图桶的范围。默认是 1ms ~ 30s，对这里两头都不合适：
     * 大模型一次生成可能超过 30 秒；网关自身耗时通常不到 1 毫秒，桶从 1ms 起就看不出差别。
     */
    private static final Duration CALL_MIN = Duration.ofMillis(5);
    private static final Duration CALL_MAX = Duration.ofMinutes(5);
    private static final Duration OVERHEAD_MIN = Duration.ofNanos(50_000);
    private static final Duration OVERHEAD_MAX = Duration.ofSeconds(1);

    private final MeterRegistry registry;

    GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一个请求。
     *
     * @param upstream 等上游花的时间；没连上上游时传 {@link Duration#ZERO}
     */
    public void recordRequest(String model, int status, Outcome outcome, Duration total, Duration upstream) {
        String m = modelTag(model);
        Counter.builder("gateway.requests")
                .description("网关处理的请求数")
                .tag("model", m)
                .tag("status", String.valueOf(status))
                .tag("outcome", outcome.tag())
                .register(registry)
                .increment();
        timer("gateway.request.duration", "客户端感受到的总耗时", m, CALL_MIN, CALL_MAX).record(total);
        if (!upstream.isZero()) {
            timer("gateway.upstream.duration", "等上游的耗时", m, CALL_MIN, CALL_MAX).record(upstream);
        }
        Duration overhead = total.minus(upstream);
        timer("gateway.overhead", "网关自身耗时（总耗时减去等上游）", m, OVERHEAD_MIN, OVERHEAD_MAX)
                .record(overhead.isNegative() ? Duration.ZERO : overhead);
    }

    /** 记录上游报告的 token 用量。 */
    public void recordTokens(String model, long promptTokens, long completionTokens) {
        String m = modelTag(model);
        tokens(m, "prompt").increment(promptTokens);
        tokens(m, "completion").increment(completionTokens);
    }

    private Counter tokens(String model, String type) {
        return Counter.builder("gateway.tokens")
                .description("上游报告的 token 用量")
                .tag("model", model)
                .tag("type", type)
                .register(registry);
    }

    private Timer timer(String name, String description, String model, Duration min, Duration max) {
        return Timer.builder(name)
                .description(description)
                .tag("model", model)
                // 生成直方图桶，Prometheus 才能算 P95 / P99，并且能跨多台网关合并
                .publishPercentileHistogram()
                .minimumExpectedValue(min)
                .maximumExpectedValue(max)
                .register(registry);
    }

    private static String modelTag(String model) {
        return model == null || model.isBlank() ? UNKNOWN_MODEL : model;
    }
}
