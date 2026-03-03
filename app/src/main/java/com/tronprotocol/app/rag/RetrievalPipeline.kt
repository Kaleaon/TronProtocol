package com.tronprotocol.app.rag

interface IngestionIndexer {
    fun ingest(chunk: TextChunk, allChunks: MutableList<TextChunk>, chunkIndex: MutableMap<String, TextChunk>)
}

interface CandidateRetriever {
    fun retrieveCandidates(query: String, strategy: RetrievalStrategy, topK: Int): List<RetrievalResult>
}

interface ResultReranker {
    fun rerank(
        query: String,
        strategy: RetrievalStrategy,
        candidates: List<RetrievalResult>,
        topK: Int
    ): List<RetrievalResult>
}

interface PostRetrievalValidator {
    fun validate(results: List<RetrievalResult>, topK: Int): List<RetrievalResult>
}
