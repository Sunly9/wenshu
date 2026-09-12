package com.docmind.api.controller;

import com.docmind.api.dto.ChatRequest;
import com.docmind.common.ClientIp;
import com.docmind.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 提问，SSE 流式返回：citation → token* → done（00 号文档 §7） */
    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader("X-Visitor-Id")
                           @NotBlank(message = "缺少访客标识")
                           @Size(max = 64) String visitorId,
                           @Valid @RequestBody ChatRequest req,
                           HttpServletRequest request) {
        return chatService.ask(req, visitorId, ClientIp.of(request));
    }
}
