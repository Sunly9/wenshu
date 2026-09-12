package com.docmind.index;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** jieba 中文分词：应用层分词后写入 content_tsv（to_tsvector('simple', ...)），查询侧同口径 */
@Component
public class ChineseTokenizer {

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /** 空格连接的分词结果，供 to_tsvector / plainto_tsquery 使用 */
    public String segment(String text) {
        return String.join(" ", segmentToList(text));
    }

    public List<String> segmentToList(String text) {
        if (text == null || text.isBlank()) return List.of();
        return segmenter.process(TokenCounter.sanitize(text), JiebaSegmenter.SegMode.INDEX).stream()
                .map(t -> t.word.trim())
                .filter(w -> !w.isEmpty())
                .collect(Collectors.toList());
    }
}
