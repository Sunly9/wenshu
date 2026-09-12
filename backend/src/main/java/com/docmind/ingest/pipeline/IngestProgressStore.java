package com.docmind.ingest.pipeline;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 解析/入库进度（内存态，仅用于进度条展示；状态真相以 document 表为准） */
@Component
public class IngestProgressStore {

    private final Map<Long, Integer> percent = new ConcurrentHashMap<>();

    public void update(Long documentId, int value) {
        percent.put(documentId, Math.max(0, Math.min(100, value)));
    }

    public void remove(Long documentId) {
        percent.remove(documentId);
    }

    public int get(Long documentId) {
        return percent.getOrDefault(documentId, 0);
    }
}
