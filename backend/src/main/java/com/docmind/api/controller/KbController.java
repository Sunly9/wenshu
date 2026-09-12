package com.docmind.api.controller;

import com.docmind.api.dto.JoinRequest;
import com.docmind.api.dto.KbCreateRequest;
import com.docmind.api.dto.KbResponse;
import com.docmind.common.ClientIp;
import com.docmind.service.KbService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kb")
@Validated
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    @PostMapping
    public KbResponse create(@RequestHeader("X-Visitor-Id")
                             @NotBlank(message = "缺少访客标识")
                             @Size(max = 64) String visitorId,
                             @Valid @RequestBody KbCreateRequest req) {
        return kbService.create(req, visitorId);
    }

    @GetMapping
    public List<KbResponse> list(@RequestHeader("X-Visitor-Id")
                                 @NotBlank(message = "缺少访客标识")
                                 @Size(max = 64) String visitorId) {
        return kbService.listByVisitor(visitorId);
    }

    @PostMapping("/join")
    public KbResponse join(@RequestHeader("X-Visitor-Id")
                           @NotBlank(message = "缺少访客标识")
                           @Size(max = 64) String visitorId,
                           @Valid @RequestBody JoinRequest req,
                           HttpServletRequest request) {
        return kbService.join(req.code(), visitorId, ClientIp.of(request));
    }

    @PostMapping("/{id}/code/reset")
    public KbResponse resetCode(@PathVariable Long id,
                                @RequestHeader("X-Visitor-Id")
                                @NotBlank(message = "缺少访客标识")
                                @Size(max = 64) String visitorId) {
        return kbService.resetCode(id, visitorId);
    }
}
