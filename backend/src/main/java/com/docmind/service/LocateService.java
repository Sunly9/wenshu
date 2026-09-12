package com.docmind.service;

import com.docmind.common.exception.ApiException;
import com.docmind.retrieve.RetrievalService;
import com.docmind.retrieve.fusion.FusedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 查模式（原文定位）：丢关键词/术语/半句话 → 检索链到精排为止，返回排序段落，不进 LLM（用户手册 §3.4） */
@Service
public class LocateService {

    private final KbService kbService;
    private final RetrievalService retrievalService;

    public LocateService(KbService kbService, RetrievalService retrievalService) {
        this.kbService = kbService;
        this.retrievalService = retrievalService;
    }

    public List<Map<String, Object>> locate(long kbId, String query, String visitorId) {
        if (query == null || query.isBlank()) {
            throw new ApiException("请输入要定位的内容");
        }
        kbService.requireAccessible(kbId, visitorId);
        RetrievalService.RetrievalResult retrieval = retrievalService.recall(kbId, query.trim());

        List<Map<String, Object>> items = new ArrayList<>();
        for (FusedChunk f : retrieval.reranked()) {
            Map<String, Object> m = new HashMap<>();
            m.put("chunkId", f.chunk().chunkId());
            m.put("documentId", f.chunk().documentId());
            m.put("file", f.chunk().fileName());
            m.put("section", f.chunk().sectionPath());
            m.put("page", f.chunk().pageNo());
            m.put("content", f.chunk().content());
            m.put("tokenCount", f.chunk().tokenCount());
            if (f.rerankScore() != null) m.put("score", Math.round(f.rerankScore() * 10000) / 10000.0);
            items.add(m);
        }
        return items;
    }
}
