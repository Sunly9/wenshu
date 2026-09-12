package com.docmind.ingest.chunker;

import com.docmind.ingest.parser.ParsedDocument;

import java.util.List;

public interface Chunker {

    List<ChunkDraft> chunk(ParsedDocument doc);
}
