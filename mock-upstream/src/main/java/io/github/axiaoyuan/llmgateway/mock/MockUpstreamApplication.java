package io.github.axiaoyuan.llmgateway.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模拟上游：一个假的、OpenAI 格式的模型服务，和网关分开运行。
 *
 * 为什么要它：1. 压测不花钱；2. 延迟固定，测出来的数字能比较；3. 能故意制造故障，测故障切换。
 */
@SpringBootApplication
public class MockUpstreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockUpstreamApplication.class, args);
    }
}
