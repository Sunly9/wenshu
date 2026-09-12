package com.docmind.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** DeepSeek 流式客户端（chat/completions, stream=true, include_usage） */
@Component
public class LlmClient {

    /** delta 为增量文本；prompt/completionTokens 只在最后一个 chunk 带 usage 时非空 */
    public record LlmChunk(String delta, Integer promptTokens, Integer completionTokens) {}

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String model;

    public LlmClient(@Value("${wenshu.llm.base-url}") String baseUrl,
                     @Value("${wenshu.llm.api-key}") String apiKey,
                     @Value("${wenshu.llm.model}") String model) {
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    public Flux<LlmChunk> stream(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "stream", true,
                "temperature", 0.1,          // 00 号文档 §6：事实型问答求稳
                "max_tokens", 1024,
                "stream_options", Map.of("include_usage", true));
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !"[DONE]".equals(data))
                .mapNotNull(this::parse);
    }

    private LlmChunk parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String delta = root.path("choices").path(0).path("delta").path("content").asText("");
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.has("completion_tokens") ? usage.path("completion_tokens").asInt() : null;
            return new LlmChunk(delta, promptTokens, completionTokens);
        } catch (Exception e) {
            return new LlmChunk("", null, null);  // 容忍个别无法解析的 chunk
        }
    }
}
