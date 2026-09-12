package com.docmind.ingest.chunker;

import java.util.List;

/** 分块结果：父块（返回给模型的单元，1024 token）+ 子块（参与检索的单元，320 token） */
public record ChunkResult(List<ChunkDraft> parents, List<ChunkDraft> children) {

    /** 平铺分块器（无父子结构）用 */
    public static ChunkResult flat(List<ChunkDraft> chunks) {
        return new ChunkResult(List.of(), chunks);
    }

    public int totalSize() {
        return parents.size() + children.size();
    }
}
