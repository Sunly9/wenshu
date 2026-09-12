package com.docmind.ingest.parser;

import java.util.List;

public record ParsedDocument(String fileName, int pageCount, List<ParsedElement> elements) {

    public long countByType(ParsedElement.ElementType type) {
        return elements().stream().filter(e -> e.type() == type).count();
    }
}
