package com.docmind.ingest.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserTest {

    @TempDir
    Path tmp;

    private ParsedDocument parse(String content) throws Exception {
        Path file = Files.writeString(tmp.resolve("test.md"), content, UTF_8);
        return new MarkdownParser().parse(file);
    }

    @Test
    void 标题段落列表表格代码块_全部识别() throws Exception {
        ParsedDocument doc = parse("""
                # 操作系统期末

                进程是资源分配的基本单位。
                线程是调度的基本单位。

                ## 第三章 调度

                - 先来先服务
                - 短作业优先

                | 算法 | 复杂度 |
                |---|---|
                | 快排 | O(nlogn) |

                ```
                int x = 1;
                ```
                """);

        List<ParsedElement> es = doc.elements();
        assertEquals(6, es.size());

        assertEquals(ParsedElement.ElementType.HEADING, es.get(0).type());
        assertEquals("操作系统期末", es.get(0).text());
        assertEquals(1, es.get(0).level());

        assertEquals(ParsedElement.ElementType.PARAGRAPH, es.get(1).type());
        assertTrue(es.get(1).text().contains("进程是资源分配的基本单位"));

        assertEquals(ParsedElement.ElementType.HEADING, es.get(2).type());
        assertEquals(2, es.get(2).level());

        assertEquals(ParsedElement.ElementType.LIST, es.get(3).type());
        assertTrue(es.get(3).text().contains("先来先服务"));

        assertEquals(ParsedElement.ElementType.TABLE, es.get(4).type());
        assertTrue(es.get(4).text().contains("O(nlogn)"));

        assertEquals(ParsedElement.ElementType.CODE, es.get(5).type());
        assertTrue(es.get(5).text().contains("int x = 1;"));
    }
}
