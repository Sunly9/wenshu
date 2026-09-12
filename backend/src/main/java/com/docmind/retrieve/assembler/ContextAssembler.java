package com.docmind.retrieve.assembler;

import com.docmind.retrieve.recall.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 上下文组装：按召回顺序装入预算（M1 简化版：≤3000 token；父块合并/去重在 D8/D11） */
@Component
public class ContextAssembler {

    static final int CONTEXT_TOKEN_BUDGET = 3000;

    public List<RetrievedChunk> select(List<RetrievedChunk> recalled) {
        List<RetrievedChunk> chosen = new ArrayList<>();
        int tokens = 0;
        for (RetrievedChunk chunk : recalled) {
            if (tokens + chunk.tokenCount() > CONTEXT_TOKEN_BUDGET) continue;
            chosen.add(chunk);
            tokens += chunk.tokenCount();
        }
        return chosen;
    }
}
