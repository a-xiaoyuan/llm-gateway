package dev.llmgateway.upstream;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfig {

    /**
     * 调用上游用的 HTTP 客户端。整个应用共用一个：底层连接池可以复用 TCP 连接，
     * 不用每个请求都重新握手。
     */
    @Bean
    RestClient upstreamRestClient(UpstreamProperties props) {
        HttpClient http = HttpClient.newBuilder()
                // 两种超时分开设：连接超时在这里，读取超时在下面的 factory 上
                .connectTimeout(props.connectTimeout())
                // 固定 HTTP/1.1：对明文 http 地址，JDK 默认会尝试升级到 HTTP/2，行为不好预测
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .build();
    }
}
