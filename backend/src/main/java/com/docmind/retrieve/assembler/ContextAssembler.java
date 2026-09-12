package com.docmind.retrieve.assembler;

import com.docmind.retrieve.fusion.FusedChunk;
import com.docmind.retrieve.recall.RetrievedChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 上下文组装（Small-to-Big，03 号文档 §5.1）：
 * 精排后的子块按 parent_id 分组去重 → 取最高分子块所属的父块（返回给模型的单元），
 * 上限 4 个父块 / 3000 token；无父块的平铺策略退化为子块本身。
 */
@Component
public class ContextAssembler {

    static final int CONTEXT_TOKEN_BUDGET = 3000;
    static final int MAX_CONTEXT_BLOCKS = 4;

    private final JdbcTemplate jdbc;

    public ContextAssembler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RetrievedChunk> assembleContext(List<FusedChunk> reranked) {
        // 精排序遍历：父块按"最佳子块的名次"定序；无父块者自成一组
        LinkedHashMap<Long, List<FusedChunk>> groups = new LinkedHashMap<>();
        for (FusedChunk f : reranked) {
            Long key = f.chunk().parentChunkId() != null ? f.chunk().parentChunkId() : -f.chunk().chunkId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        if (groups.isEmpty()) return List.of();

        List<Long> parentIds = groups.keySet().stream().filter(id -> id > 0).toList();
        Map<Long, RetrievedChunk> parents = parentIds.isEmpty() ? Map.of() : fetchParents(parentIds);

        List<RetrievedChunk> context = new ArrayList<>();
        int tokens = 0;
        for (Map.Entry<Long, List<FusedChunk>> entry : groups.entrySet()) {
            if (context.size() >= MAX_CONTEXT_BLOCKS) break;
            RetrievedChunk unit;
            if (entry.getKey() > 0) {
                unit = parents.get(entry.getKey());
                if (unit == null) {
                    // 父块缺失（理论不该发生）：退化为最佳子块
                    unit = entry.getValue().get(0).chunk();
                }
            } else {
                unit = entry.getValue().get(0).chunk();
            }
            if (tokens + unit.tokenCount() > CONTEXT_TOKEN_BUDGET) continue;
            context.add(unit);
            tokens += unit.tokenCount();
        }
        return context;
    }

    private Map<Long, RetrievedChunk> fetchParents(List<Long> parentIds) {
        StringJoiner in = new StringJoiner(",", "(", ")");
        for (Long id : parentIds) in.add(String.valueOf(id));
        Map<Long, RetrievedChunk> map = new LinkedHashMap<>();
        jdbc.query("SELECT c.id, c.document_id, d.file_name, c.section_path, c.page_no, c.content, c.token_count FROM chunk c JOIN document d ON d.id = c.document_id WHERE c.id IN " + in,
                rs -> {
                    map.put(rs.getLong("id"), new RetrievedChunk(
                            rs.getLong("id"),
                            rs.getLong("document_id"),
                            null,
                            rs.getString("file_name"),
                            rs.getString("section_path"),
                            rs.getObject("page_no") == null ? null : rs.getInt("page_no"),
                            rs.getString("content"),
                            rs.getInt("token_count"),
                            0.0));
                });
        return map;
    }
}
