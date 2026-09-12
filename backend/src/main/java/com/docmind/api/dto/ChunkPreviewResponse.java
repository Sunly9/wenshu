package com.docmind.api.dto;

import java.util.List;

/** 分片预览（00 号文档 §7）：不入库，直接返回切分结果 */
public record ChunkPreviewResponse(
        String strategy,
        int pageCount,
        int parentCount,
        int childCount,
        int totalChildTokens,
        boolean truncated,
        List<PreviewBlock> blocks
) {

    /** kind: parent=父块(返回单元,1024) / child=子块(检索单元,320)，child 的 parentIndex 指向父块列表 */
    public record PreviewBlock(String kind, int index, Integer parentIndex, int tokenCount,
                               String sectionPath, Integer pageNo, boolean table, String content) {}
}
