package com.docmind.retrieve.assembler;

import com.docmind.retrieve.fusion.FusedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 上下文组装：融合序装入预算 ≤3000 token（父块合并去重在 D11 接入） */
@Component
public class ContextAssembler {

    static final int CONTEXT_TOKEN_BUDGET = 3000;

    public List<FusedChunk> select(List<FusedChunk> candidates) {
        List<FusedChunk> chosen = new ArrayList<>();
        int tokens = 0;
        for (FusedChunk candidate : candidates) {
            if (tokens + candidate.chunk().tokenCount() > CONTEXT_TOKEN_BUDGET) continue;
            chosen.add(candidate);
            tokens += candidate.chunk().tokenCount();
        }
        return chosen;
    }
}
