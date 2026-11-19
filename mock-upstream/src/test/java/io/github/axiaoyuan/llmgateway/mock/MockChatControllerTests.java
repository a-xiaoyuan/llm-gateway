package io.github.axiaoyuan.llmgateway.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mock.latency-ms=0")
class MockChatControllerTests {

    @Autowired
    TestRestTemplate client;

    @Test
    void 正常模型返回OpenAI格式和用量() {
        ResponseEntity<String> resp = post("{\"model\":\"any\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"choices\"").contains("\"usage\"").contains("\"total_tokens\"");
    }

    @Test
    void mock429触发限流错误() {
        assertThat(post("{\"model\":\"mock-429\"}").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void mock500触发服务错误() {
        assertThat(post("{\"model\":\"mock-500\"}").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client.postForEntity("/v1/chat/completions", new HttpEntity<>(json, headers), String.class);
    }
}
