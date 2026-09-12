package com.docmind.api.controller;

import com.docmind.eval.EvalQuestionRequest;
import com.docmind.eval.EvalQuestionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Validated
public class EvalController {

    private final EvalQuestionService evalQuestionService;
    private final com.docmind.eval.EvalRunner evalRunner;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public EvalController(EvalQuestionService evalQuestionService,
                          com.docmind.eval.EvalRunner evalRunner,
                          org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.evalQuestionService = evalQuestionService;
        this.evalRunner = evalRunner;
        this.jdbc = jdbc;
    }

    /** 跑消融实验（6 配置异步执行，结果逐条落 eval_run，面板轮询 runs 接口） */
    @PostMapping("/api/eval/run")
    public Map<String, Object> run(@RequestHeader("X-Visitor-Id")
                                   @NotBlank(message = "缺少访客标识")
                                   @Size(max = 64) String visitorId,
                                   @RequestBody Map<String, Object> body) {
        long kbId = Long.parseLong(String.valueOf(body.get("kbId")));
        evalQuestionService.stats(kbId, visitorId);  // 复用权限校验
        evalRunner.runAsync(kbId, visitorId);
        return Map.of("started", true, "configs", 6);
    }

    /** 跑批结果列表（评测面板数据源） */
    @GetMapping("/api/kb/{kbId}/eval/runs")
    public List<Map<String, Object>> runs(@PathVariable Long kbId,
                                          @RequestHeader("X-Visitor-Id")
                                          @NotBlank(message = "缺少访客标识")
                                          @Size(max = 64) String visitorId) {
        evalQuestionService.stats(kbId, visitorId);
        return jdbc.queryForList(
                "SELECT id, config::text AS config, hit_rate_at5, mrr, detail::text AS detail, created_at "
                        + "FROM eval_run WHERE kb_id = ? ORDER BY id DESC LIMIT 30",
                kbId);
    }

    @PostMapping("/api/kb/{kbId}/eval/questions")
    public Map<String, Object> save(@PathVariable Long kbId,
                                    @RequestHeader("X-Visitor-Id")
                                    @NotBlank(message = "缺少访客标识")
                                    @Size(max = 64) String visitorId,
                                    @RequestBody EvalQuestionRequest req) {
        return evalQuestionService.save(kbId, req, visitorId);
    }

    @GetMapping("/api/kb/{kbId}/eval/questions")
    public List<Map<String, Object>> list(@PathVariable Long kbId,
                                          @RequestHeader("X-Visitor-Id")
                                          @NotBlank(message = "缺少访客标识")
                                          @Size(max = 64) String visitorId) {
        return evalQuestionService.list(kbId, visitorId);
    }

    @GetMapping("/api/kb/{kbId}/eval/stats")
    public Map<String, Object> stats(@PathVariable Long kbId,
                                     @RequestHeader("X-Visitor-Id")
                                     @NotBlank(message = "缺少访客标识")
                                     @Size(max = 64) String visitorId) {
        return evalQuestionService.stats(kbId, visitorId);
    }

    @DeleteMapping("/api/kb/{kbId}/eval/questions/{id}")
    public Map<String, Object> delete(@PathVariable Long kbId,
                                      @PathVariable Long id,
                                      @RequestHeader("X-Visitor-Id")
                                      @NotBlank(message = "缺少访客标识")
                                      @Size(max = 64) String visitorId) {
        evalQuestionService.delete(kbId, id, visitorId);
        return evalQuestionService.stats(kbId, visitorId);
    }
}
