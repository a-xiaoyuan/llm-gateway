package io.github.axiaoyuan.llmgateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

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

/** 上游地址上根本没有服务在听：网关应该返回 502，而不是抛出一个笼统的 500。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpstreamDownTests {

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        int closedPort = freePort();
        registry.add("gateway.upstream.base-url", () -> "http://127.0.0.1:" + closedPort + "/v1");
    }

    @Autowired
    TestRestTemplate client;

    @Test
    void 连不上上游返回502() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = client.postForEntity(
                "/v1/chat/completions", new HttpEntity<>("{\"model\":\"m\"}", headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).contains("upstream_unavailable");
    }

    /** 拿一个空闲端口后立刻关掉，这个端口上就没有服务了。 */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
