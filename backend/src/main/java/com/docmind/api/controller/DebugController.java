package com.docmind.api.controller;

import com.docmind.api.dto.DebugResponse;
import com.docmind.observability.DebugService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Validated
public class DebugController {

    private final DebugService debugService;
    private final com.docmind.service.LocateService locateService;

    public DebugController(DebugService debugService, com.docmind.service.LocateService locateService) {
        this.debugService = debugService;
        this.locateService = locateService;
    }

    /** 即时执行检索链（不进 LLM），返回完整打分画像；strategy 可选（默认库策略） */
    @PostMapping("/api/kb/{kbId}/debug-query")
    public DebugResponse debugQuery(@PathVariable Long kbId,
                                    @RequestHeader("X-Visitor-Id")
                                    @NotBlank(message = "缺少访客标识")
                                    @Size(max = 64) String visitorId,
                                    @RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "").trim();
        if (question.isEmpty()) {
            throw new com.docmind.common.exception.ApiException("问题不能为空");
        }
        String strategy = body.get("strategy");
        if (strategy != null && strategy.isBlank()) strategy = null;
        return debugService.debugQuery(kbId, question, visitorId, strategy);
    }

    /** 查模式（原文定位）：丢半句话/关键词，返回排序段落（不进 LLM） */
    @PostMapping("/api/kb/{kbId}/locate")
    public java.util.List<java.util.Map<String, Object>> locate(
            @PathVariable Long kbId,
            @RequestHeader("X-Visitor-Id")
            @NotBlank(message = "缺少访客标识")
            @Size(max = 64) String visitorId,
            @RequestBody Map<String, String> body) {
        return locateService.locate(kbId, body.get("query"), visitorId);
    }

    /** 读取历史查询的检索画像（00 号文档 §7 冻结接口） */
    @GetMapping("/api/debug/{queryId}")
    public DebugResponse byQueryId(@PathVariable Long queryId,
                                   @RequestHeader("X-Visitor-Id")
                                   @NotBlank(message = "缺少访客标识")
                                   @Size(max = 64) String visitorId) {
        return debugService.byQueryId(queryId, visitorId);
    }

    /** 最近查询列表（调试台首页） */
    @GetMapping("/api/kb/{kbId}/debug/recent")
    public List<Map<String, Object>> recent(@PathVariable Long kbId,
                                            @RequestHeader("X-Visitor-Id")
                                            @NotBlank(message = "缺少访客标识")
                                            @Size(max = 64) String visitorId,
                                            @RequestParam(defaultValue = "20") int limit) {
        return debugService.recent(kbId, visitorId, Math.min(limit, 50));
    }
}
