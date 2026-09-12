package com.docmind.service;

import com.docmind.api.dto.KbCreateRequest;
import com.docmind.api.dto.KbResponse;
import com.docmind.common.ShareCodeGenerator;
import com.docmind.common.exception.ApiException;
import com.docmind.common.exception.NotFoundException;
import com.docmind.common.ratelimit.RateLimiter;
import com.docmind.domain.KbVisitor;
import com.docmind.domain.KnowledgeBase;
import com.docmind.domain.repo.DocumentRepository;
import com.docmind.domain.repo.KbVisitorRepository;
import com.docmind.domain.repo.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class KbService {

    /** 口令验证限流：每 IP 每小时 10 次，防口令枚举（00 号文档 §5 索引策略说明） */
    static final int CODE_ATTEMPTS_PER_HOUR = 10;

    private final KnowledgeBaseRepository kbRepo;
    private final KbVisitorRepository kbVisitorRepo;
    private final DocumentRepository documentRepo;
    private final RateLimiter rateLimiter;

    public KbService(KnowledgeBaseRepository kbRepo,
                     KbVisitorRepository kbVisitorRepo,
                     DocumentRepository documentRepo,
                     RateLimiter rateLimiter) {
        this.kbRepo = kbRepo;
        this.kbVisitorRepo = kbVisitorRepo;
        this.documentRepo = documentRepo;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public KbResponse create(KbCreateRequest req, String visitorId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.name());
        kb.setDescription(req.description());
        kb.setShareCode(generateUniqueCode());
        kb.setCreatedBy(visitorId);
        kb = kbRepo.save(kb);
        kbVisitorRepo.save(new KbVisitor(kb.getId(), visitorId));
        return toResponse(kb, visitorId);
    }

    @Transactional(readOnly = true)
    public List<KbResponse> listByVisitor(String visitorId) {
        return kbRepo.findAllByVisitor(visitorId).stream()
                .map(kb -> toResponse(kb, visitorId))
                .toList();
    }

    /** 输口令加入：绑定当前浏览器（visitor_id）与库，幂等 */
    @Transactional
    public KbResponse join(String code, String visitorId, String clientIp) {
        if (!rateLimiter.tryAcquire("wenshu:code:" + clientIp, CODE_ATTEMPTS_PER_HOUR, Duration.ofHours(1))) {
            throw new ApiException(429, "口令尝试过于频繁，请 1 小时后再试");
        }
        KnowledgeBase kb = kbRepo.findByShareCode(code.toUpperCase().trim())
                .orElseThrow(() -> new NotFoundException("口令不存在或已失效"));
        kbVisitorRepo.findById(new KbVisitor.Pk(kb.getId(), visitorId))
                .orElseGet(() -> kbVisitorRepo.save(new KbVisitor(kb.getId(), visitorId)));
        return toResponse(kb, visitorId);
    }

    /** 重置口令：仅创建者可调，旧口令即刻作废，已绑定设备不受影响 */
    @Transactional
    public KbResponse resetCode(Long kbId, String visitorId) {
        KnowledgeBase kb = requireAccessible(kbId, visitorId);
        if (!kb.getCreatedBy().equals(visitorId)) {
            throw new ApiException(403, "只有资料库创建者可以重置口令");
        }
        kb.setShareCode(generateUniqueCode());
        return toResponse(kbRepo.save(kb), visitorId);
    }

    /** 越权校验：visitor 必须绑定该库，否则一律 404（不泄露库的存在性） */
    public KnowledgeBase requireAccessible(Long kbId, String visitorId) {
        KnowledgeBase kb = kbRepo.findById(kbId).orElseThrow(() -> new NotFoundException("资料库不存在"));
        if (!kbVisitorRepo.existsById(new KbVisitor.Pk(kbId, visitorId))) {
            throw new NotFoundException("资料库不存在");
        }
        return kb;
    }

    private String generateUniqueCode() {
        String code = ShareCodeGenerator.generate();
        // 32^6 ≈ 10 亿，碰撞概率极低，循环兜底即可
        while (kbRepo.existsByShareCode(code)) {
            code = ShareCodeGenerator.generate();
        }
        return code;
    }

    private KbResponse toResponse(KnowledgeBase kb, String visitorId) {
        return new KbResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getShareCode(),
                kb.getCreatedBy().equals(visitorId),
                documentRepo.countByKbId(kb.getId()),
                kb.getCreatedAt()
        );
    }
}
