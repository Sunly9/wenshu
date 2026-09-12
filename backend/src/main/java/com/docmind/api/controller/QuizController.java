package com.docmind.api.controller;

import com.docmind.common.exception.ApiException;
import com.docmind.quiz.GradeItem;
import com.docmind.quiz.QuizQuestion;
import com.docmind.quiz.QuizService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Validated
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    /** 出题：范围可选（documentId / sectionPrefix，都空=整个库），生成 5 题（3 单选 + 2 简答） */
    @PostMapping("/api/kb/{kbId}/quiz/generate")
    public List<QuizQuestion> generate(@PathVariable Long kbId,
                                       @RequestHeader("X-Visitor-Id")
                                       @NotBlank(message = "缺少访客标识")
                                       @Size(max = 64) String visitorId,
                                       @RequestBody Map<String, String> body) {
        Long documentId = null;
        String raw = body.get("documentId");
        if (raw != null && !raw.isBlank()) {
            try {
                documentId = Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new ApiException("documentId 不合法");
            }
        }
        return quizService.generate(kbId, documentId, body.get("sectionPrefix"), visitorId);
    }

    /** 判分：题目（含答案与 sourceChunkIds，前端持有）+ 学生作答 */
    @PostMapping("/api/kb/{kbId}/quiz/grade")
    public List<GradeItem> grade(@PathVariable Long kbId,
                                 @RequestHeader("X-Visitor-Id")
                                 @NotBlank(message = "缺少访客标识")
                                 @Size(max = 64) String visitorId,
                                 @RequestBody GradeRequest req) {
        if (req.questions() == null || req.userAnswers() == null
                || req.questions().size() != req.userAnswers().size()) {
            throw new ApiException("题目与作答数量不一致");
        }
        return quizService.grade(kbId, req.questions(), req.userAnswers(), visitorId);
    }

    public record GradeRequest(List<QuizQuestion> questions, List<String> userAnswers) {}
}
