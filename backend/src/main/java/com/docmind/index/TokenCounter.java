package com.docmind.index;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * token 计数：与向量化共用同一个 bge 分词器（纯 Java WordPiece），
 * 保证"子块 320 token"的预算口径一致（03 号文档 §3 约定）。懒加载。
 */
@Component
public class TokenCounter {

    private final String modelDir;
    private volatile BertTokenizer tokenizer;

    public TokenCounter(@Value("${wenshu.embedding.model-dir}") String modelDir) {
        this.modelDir = modelDir;
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return tokenizer().encode(text, Integer.MAX_VALUE / 2).length;
    }

    /**
     * 清洗进分词器的文本：去掉控制字符与未配对代理项（乱码 PDF 常见）。
     */
    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean valid = cp >= 0x20 && cp != 0x7F && (cp < 0xD800 || cp > 0xDFFF)
                    && !Character.isISOControl(cp);
            if (valid) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private BertTokenizer tokenizer() {
        if (tokenizer == null) {
            synchronized (this) {
                if (tokenizer == null) {
                    try {
                        tokenizer = BertTokenizer.fromTokenizerJson(Path.of(modelDir, "tokenizer.json"));
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "分词器加载失败，请确认模型文件已下载到 " + modelDir + "：" + e.getMessage(), e);
                    }
                }
            }
        }
        return tokenizer;
    }
}
