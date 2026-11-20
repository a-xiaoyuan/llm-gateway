package io.github.axiaoyuan.llmgateway.observe;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 防止“高基数”：model 标签的值来自客户端，谁都可以乱填。如果每个不同的值都生成一组时间序列，
 * 有人发一万个随机模型名，Prometheus 就会被撑爆。
 *
 * 做法：model 标签最多保留 N 个不同的值，之后出现的新值不再生成时间序列（这些请求的指标被丢弃，转发不受影响）。
 * 功能 6 有了“已配置的模型列表”后，可以改成只认识配置里的模型、其余归为 other。
 */
@Configuration
class MetricsConfig {

    @Bean
    MeterFilter limitModelTags(@Value("${gateway.metrics.max-models:50}") int maxModels) {
        return MeterFilter.maximumAllowableTags("gateway", "model", maxModels, MeterFilter.deny());
    }
}
