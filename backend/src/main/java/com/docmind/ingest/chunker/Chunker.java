package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;

public interface Chunker {

    ChunkResult chunk(ParsedDocument doc);
}
