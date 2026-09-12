package com.docmind.service;

import com.docmind.api.dto.ChunkPreviewResponse;
import com.docmind.api.dto.ChunkPreviewResponse.PreviewBlock;
import com.docmind.common.exception.ApiException;
import com.docmind.ingest.chunker.ChunkDraft;
import com.docmind.ingest.chunker.ChunkResult;
import com.docmind.ingest.chunker.Chunker;
import com.docmind.ingest.chunker.FixedSizeChunker;
import com.docmind.ingest.chunker.RecursiveChunker;
import com.docmind.ingest.chunker.StructureAwareChunker;
import com.docmind.ingest.parser.DocumentParser;
import com.docmind.ingest.parser.ParsedDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 分片预览：临时文件解析 + 指定策略切分，不入库（权限校验在控制器层） */
@Service
public class ChunkPreviewService {

    static final int MAX_BLOCKS = 150;
    static final int CONTENT_PREVIEW_CHARS = 500;
    static final Set<String> STRATEGIES = Set.of("FIXED", "RECURSIVE", "STRUCTURE_AWARE");

    private final List<DocumentParser> parsers;
    private final List<Chunker> chunkers;

    public ChunkPreviewService(List<DocumentParser> parsers, List<Chunker> chunkers) {
        this.parsers = parsers;
        this.chunkers = chunkers;
    }

    public ChunkPreviewResponse preview(String strategy, MultipartFile file) {
        String normalized = strategy == null ? "STRUCTURE_AWARE" : strategy.toUpperCase(Locale.ROOT);
        if (!STRATEGIES.contains(normalized)) {
            throw new ApiException("不支持的分片策略：" + strategy + "（可选 FIXED / RECURSIVE / STRUCTURE_AWARE）");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("请选择要预览的文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        DocumentParser parser = parsers.stream().filter(p -> p.supports(ext)).findFirst()
                .orElseThrow(() -> new ApiException("仅支持 PDF / Word(.docx) / Markdown 文件"));

        Path temp = null;
        try {
            temp = Files.createTempFile("wenshu-preview-", "." + ext);
            file.transferTo(temp);
            ParsedDocument parsed = parser.parse(temp);
            Chunker chunker = pickChunker(normalized);
            ChunkResult result = chunker.chunk(parsed);

            List<PreviewBlock> blocks = new ArrayList<>();
            boolean truncated = false;
            for (int i = 0; i < result.parents().size() && blocks.size() < MAX_BLOCKS; i++) {
                blocks.add(toBlock("parent", i, result.parents().get(i)));
            }
            if (blocks.size() >= MAX_BLOCKS) truncated = true;
            for (int i = 0; i < result.children().size() && blocks.size() < MAX_BLOCKS; i++) {
                blocks.add(toBlock("child", i, result.children().get(i)));
            }
            if (blocks.size() >= MAX_BLOCKS
                    && (result.parents().size() + result.children().size()) > blocks.size()) {
                truncated = true;
            }

            return new ChunkPreviewResponse(
                    normalized,
                    parsed.pageCount(),
                    result.parents().size(),
                    result.children().size(),
                    result.children().stream().mapToInt(ChunkDraft::tokenCount).sum(),
                    truncated,
                    blocks);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("预览失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败无碍
                }
            }
        }
    }

    private PreviewBlock toBlock(String kind, int index, ChunkDraft draft) {
        return new PreviewBlock(kind, index, draft.parentIndex(), draft.tokenCount(),
                draft.sectionPath(), draft.pageNo(), draft.table(),
                truncate(draft.content(), CONTENT_PREVIEW_CHARS));
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Chunker pickChunker(String strategy) {
        return switch (strategy) {
            case "FIXED" -> chunkers.stream().filter(c -> c instanceof FixedSizeChunker).findFirst().orElseThrow();
            case "RECURSIVE" -> chunkers.stream().filter(c -> c instanceof RecursiveChunker).findFirst().orElseThrow();
            default -> chunkers.stream().filter(c -> c instanceof StructureAwareChunker).findFirst().orElseThrow();
        };
    }
}
