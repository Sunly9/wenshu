package com.docmind.api.controller;

import com.docmind.service.ConversationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Validated
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/api/kb/{kbId}/conversations")
    public Map<String, Object> create(@PathVariable Long kbId,
                                      @RequestHeader("X-Visitor-Id") @NotBlank @Size(max = 64) String visitorId) {
        return conversationService.create(kbId, visitorId);
    }

    @GetMapping("/api/kb/{kbId}/conversations")
    public List<Map<String, Object>> list(@PathVariable Long kbId,
                                          @RequestHeader("X-Visitor-Id") @NotBlank @Size(max = 64) String visitorId) {
        return conversationService.list(kbId, visitorId);
    }

    @GetMapping("/api/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable Long id,
                                              @RequestHeader("X-Visitor-Id") @NotBlank @Size(max = 64) String visitorId) {
        return conversationService.messages(id, visitorId);
    }

    @DeleteMapping("/api/conversations/{id}")
    public Map<String, Object> delete(@PathVariable Long id,
                                      @RequestHeader("X-Visitor-Id") @NotBlank @Size(max = 64) String visitorId) {
        conversationService.delete(id, visitorId);
        return Map.of("deleted", true);
    }
}
