package io.github.axiaoyuan.llmgateway.common;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 只“瞄一眼”JSON 顶层的某个字段，不把整段解析成对象。
 *
 * 为什么：网关转发的请求体可能很大（长对话有几 MB），而我们只关心 model、usage 这种小字段。
 * 用流式解析器按顺序扫描顶层字段，遇到不关心的对象或数组整块跳过，找到就停。
 * 不是合法 JSON 时返回空，不抛异常：观测失败不能影响转发。
 */
public final class JsonPeek {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    private JsonPeek() {
    }

    /** 顶层字符串字段，例如请求里的 "model"。 */
    public static Optional<String> text(String json, String field) {
        return find(json, field).filter(JsonNode::isTextual).map(JsonNode::asText);
    }

    /** 顶层对象字段，例如响应里的 "usage"。 */
    public static Optional<JsonNode> object(String json, String field) {
        return find(json, field).filter(JsonNode::isObject);
    }

    private static Optional<JsonNode> find(String json, String field) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (field.equals(name)) {
                    return Optional.ofNullable(MAPPER.readTree(parser));
                }
                // 不是要找的字段：如果是对象或数组，整块跳过，不逐个解析里面的内容
                parser.skipChildren();
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
