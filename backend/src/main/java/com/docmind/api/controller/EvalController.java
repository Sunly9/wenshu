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

    public EvalController(EvalQuestionService evalQuestionService) {
        this.evalQuestionService = evalQuestionService;
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
