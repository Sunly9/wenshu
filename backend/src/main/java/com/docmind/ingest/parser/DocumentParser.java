package com.docmind.ingest.parser;

import java.nio.file.Path;

public interface DocumentParser {

    /** @param fileType document 表里的规范类型：pdf / docx / md */
    boolean supports(String fileType);

    ParsedDocument parse(Path file) throws Exception;
}
