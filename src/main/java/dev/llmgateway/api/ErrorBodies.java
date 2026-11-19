package dev.llmgateway.api;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 网关自己产生的错误，用和 OpenAI 一样的格式返回：{"error": {"message": ..., "type": ...}}。
 * 客户端（各种 SDK）本来就会解析这个格式，换成网关后不用改代码。
 */
public final class ErrorBodies {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrorBodies() {
    }

    public static String of(String type, String message) {
        try {
            return JSON.writeValueAsString(Map.of("error", Map.of("type", type, "message", message)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
