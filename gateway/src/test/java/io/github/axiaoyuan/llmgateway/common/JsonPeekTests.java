package io.github.axiaoyuan.llmgateway.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonPeekTests {

    @Test
    void 读顶层字符串字段() {
        assertThat(JsonPeek.text("{\"model\":\"qwen\",\"stream\":false}", "model")).contains("qwen");
    }

    @Test
    void 跳过前面的大数组_找到后面的字段() {
        String json = "{\"messages\":[{\"role\":\"user\",\"content\":\"{\\\"model\\\":\\\"fake\\\"}\"}],\"model\":\"real\"}";

        // messages 里藏着一个看起来像 model 的字符串，不能被误认
        assertThat(JsonPeek.text(json, "model")).contains("real");
    }

    @Test
    void 嵌套对象里的同名字段不算顶层() {
        assertThat(JsonPeek.text("{\"meta\":{\"model\":\"inner\"}}", "model")).isEmpty();
    }

    @Test
    void 读顶层对象字段() {
        assertThat(JsonPeek.object("{\"id\":\"x\",\"usage\":{\"prompt_tokens\":3}}", "usage"))
                .hasValueSatisfying(node -> assertThat(node.get("prompt_tokens").asInt()).isEqualTo(3));
    }

    @Test
    void 类型不对或不存在或不是JSON都返回空_不抛异常() {
        assertThat(JsonPeek.text("{\"model\":123}", "model")).isEmpty();
        assertThat(JsonPeek.object("{\"usage\":null}", "usage")).isEmpty();
        assertThat(JsonPeek.text("{}", "model")).isEmpty();
        assertThat(JsonPeek.text("不是 JSON", "model")).isEmpty();
        assertThat(JsonPeek.text("[1,2]", "model")).isEmpty();
        assertThat(JsonPeek.text(null, "model")).isEmpty();
    }
}
