package io.github.axiaoyuan.llmgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 网关入口。代码按功能分包，每个包只对外暴露必要的类：
 * <ul>
 *   <li>{@code proxy}：把请求转发给上游（功能 1）</li>
 *   <li>{@code observe}：指标，供 Prometheus 抓取（功能 2）</li>
 *   <li>{@code common}：各功能共用的少量代码，例如错误格式、JSON 字段速读</li>
 * </ul>
 * 后续功能各自新建包（auth、ratelimit、routing ……），见 docs/design.md 第 7 节。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
