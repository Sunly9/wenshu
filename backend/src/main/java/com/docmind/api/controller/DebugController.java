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

    public DebugController(DebugService debugService) {
        this.debugService = debugService;
    }

    /** 即时执行检索链（不进 LLM），返回完整打分画像 */
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
        return debugService.debugQuery(kbId, question, visitorId);
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
