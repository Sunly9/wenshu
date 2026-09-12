package com.docmind.service;

import com.docmind.api.dto.ChatRequest;
import com.docmind.common.exception.ApiException;
import com.docmind.generation.LlmClient;
import com.docmind.generation.PromptBuilder;
import com.docmind.observability.QueryLogService;
import com.docmind.retrieve.RetrievalService;
import com.docmind.retrieve.assembler.ContextAssembler;
import com.docmind.retrieve.fusion.FusedChunk;
import com.docmind.retrieve.recall.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 问答编排：限流 → 权限 → 向量召回 → 组装 → 先发引用 → 流式生成 → done（含耗时/计费）。
 * SSE 事件约定见 00 号文档 §7：citation / token / done。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int DAILY_QUESTION_LIMIT = 50;   // 00 号文档 §6：单 IP 每日 50 次

    private final KbService kbService;
    private final RetrievalService retrievalService;
    private final ContextAssembler assembler;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final QueryLogService queryLogService;
    private final com.docmind.common.ratelimit.RateLimiter rateLimiter;

    public ChatService(KbService kbService, RetrievalService retrievalService, ContextAssembler assembler,
                       PromptBuilder promptBuilder, LlmClient llmClient, QueryLogService queryLogService,
                       com.docmind.common.ratelimit.RateLimiter rateLimiter) {
        this.kbService = kbService;
        this.retrievalService = retrievalService;
        this.assembler = assembler;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.queryLogService = queryLogService;
        this.rateLimiter = rateLimiter;
    }

    public SseEmitter ask(ChatRequest req, String visitorId, String clientIp) {
        String key = "wenshu:q:" + clientIp + ":" + LocalDate.now();
        if (!rateLimiter.tryAcquire(key, DAILY_QUESTION_LIMIT, Duration.ofDays(1))) {
            throw new ApiException(429, "今日提问次数已达上限（每天 " + DAILY_QUESTION_LIMIT + " 次），明天再来吧");
        }
        kbService.requireAccessible(req.kbId(), visitorId);

        long start = System.currentTimeMillis();
        RetrievalService.RetrievalResult retrieval = retrievalService.recall(req.kbId(), req.question());
        List<FusedChunk> chosen = assembler.select(retrieval.fused());

        SseEmitter emitter = new SseEmitter(120_000L);
        // 客户端断开/超时后不再发送，但订阅继续跑完以便 query_log 落完整数据
        java.util.concurrent.atomic.AtomicBoolean dead = new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onTimeout(() -> dead.set(true));
        emitter.onCompletion(() -> dead.set(true));
        emitter.onError(e -> dead.set(true));
        java.util.function.BiConsumer<String, Object> safeSend = (event, data) -> {
            if (!dead.get()) {
                send(emitter, event, data);
            }
        };
        if (chosen.isEmpty()) {
            safeSend.accept("token", Map.of("text", "当前资料库还没有可检索的内容，请先上传文档并等待解析完成。"));
            safeSend.accept("done", Map.of("queryId", -1, "latencyMs", 0));
            emitter.complete();
            return emitter;
        }

        // 引用先于回答发送：前端可立即渲染"答案依据"列表
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            RetrievedChunk c = chosen.get(i).chunk();
            Map<String, Object> m = new HashMap<>();
            m.put("n", i + 1);
            m.put("chunkId", c.chunkId());
            m.put("file", c.fileName());
            m.put("page", c.pageNo());
            m.put("section", c.sectionPath());
            m.put("snippet", snippet(c.content(), 80));
            citations.add(m);
        }
        safeSend.accept("citation", Map.of("citations", citations));

        StringBuilder answer = new StringBuilder();
        AtomicInteger promptTokens = new AtomicInteger();
        AtomicInteger completionTokens = new AtomicInteger();
        String userPrompt = promptBuilder.buildUserPrompt(req.question(),
                chosen.stream().map(FusedChunk::chunk).toList());

        llmClient.stream(PromptBuilder.SYSTEM_PROMPT, userPrompt).subscribe(
                chunk -> {
                    if (!chunk.delta().isEmpty()) {
                        answer.append(chunk.delta());
                        safeSend.accept("token", Map.of("text", chunk.delta()));
                    }
                    if (chunk.promptTokens() != null) promptTokens.set(chunk.promptTokens());
                    if (chunk.completionTokens() != null) completionTokens.set(chunk.completionTokens());
                },
                error -> {
                    log.warn("生成失败：{}", error.getMessage());
                    safeSend.accept("done", Map.of("error", "生成服务暂时不可用，请稍后再试"));
                    emitter.complete();
                },
                () -> {
                    long latency = System.currentTimeMillis() - start;
                    long queryId = queryLogService.save(
                            req.kbId(), visitorId, req.question(),
                            retrievedDetail(retrieval),
                            chosen.stream().map(f -> f.chunk().chunkId()).toList(),
                            answer.toString(), latency,
                            promptTokens.get(), completionTokens.get());
                    safeSend.accept("done", Map.of(
                            "queryId", queryId, "latencyMs", latency,
                            "promptTokens", promptTokens.get(), "completionTokens", completionTokens.get()));
                    emitter.complete();
                    log.info("问答完成 kb={} 问题={} 引用={} 块 耗时={}ms", req.kbId(), req.question(), chosen.size(), latency);
                });
        return emitter;
    }

    /** retrieved JSONB 结构：[{chunkId, vectorScore, ftsScore, rrfScore, vectorRank, ftsRank}]，rerankScore D11 追加 */
    private List<Map<String, Object>> retrievedDetail(RetrievalService.RetrievalResult retrieval) {
        List<Map<String, Object>> detail = new ArrayList<>();
        for (FusedChunk f : retrieval.fused()) {
            Map<String, Object> m = new HashMap<>();
            m.put("chunkId", f.chunk().chunkId());
            if (f.vectorScore() != null) m.put("vectorScore", round4(f.vectorScore()));
            if (f.ftsScore() != null) m.put("ftsScore", round4(f.ftsScore()));
            m.put("rrfScore", round4(f.rrfScore()));
            if (f.vectorRank() != null) m.put("vectorRank", f.vectorRank());
            if (f.ftsRank() != null) m.put("ftsRank", f.ftsRank());
            detail.add(m);
        }
        return detail;
    }

    private double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private String snippet(String content, int max) {
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 发送失败多为客户端已断开：completeWithError 可能再次抛错，静默即可
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 已完成
            }
        }
    }
}
