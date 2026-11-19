package dev.llmgateway.upstream;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上游（真正的模型服务）的连接配置，对应 application.yml 里的 gateway.upstream。
 *
 * @param baseUrl        上游地址，到 /v1 为止，例如 http://localhost:9090/v1
 * @param apiKey         网关自己调用上游用的密钥，不是客户端传来的那个
 * @param connectTimeout 建立 TCP 连接最多等多久；连不上要尽快失败，所以设得短
 * @param readTimeout    连上之后等完整响应最多多久；大模型生成慢，所以设得长
 */
@ConfigurationProperties(prefix = "gateway.upstream")
public record UpstreamProperties(
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout) {
}
