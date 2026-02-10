package com.tronprotocol.app.rag

/**
 * Embedding Quantizer for memory-efficient vector storage
 *
 * Inspired by MiniRAG's quantize_embedding/dequantize_embedding functions.
 * Compresses 32-bit float embeddings to 8-bit integers using min-max scaling.
 *
 * Benefits on mobile:
 * - 75% memory reduction (4 bytes -> 1 byte per dimension)
 * - Faster cosine similarity computation
 * - Reduced storage footprint in SecureStorage
 *
 * For a 128-dim embedding:
 * - Full precision: 512 bytes
 * - Quantized: 128 bytes + 8 bytes overhead = 136 bytes (73% reduction)
 */
object EmbeddingQuantizer {

    /**
     * Quantized embedding with min/max metadata for dequantization
     */
    data class QuantizedEmbedding(
        val data: ByteArray,
        val minVal: Float,
        val maxVal: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QuantizedEmbedding) return false
            return data.contentEquals(other.data) && minVal == other.minVal && maxVal == other.maxVal
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + minVal.hashCode()
            result = 31 * result + maxVal.hashCode()
            return result
        }
    }

    /**
     * Quantize a float embedding to 8-bit integers.
     * Uses min-max scaling to map float range to [0, 255].
     */
    fun quantize(embedding: FloatArray): QuantizedEmbedding {
        if (embedding.isEmpty()) {
            return QuantizedEmbedding(ByteArray(0), 0f, 0f)
        }

        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        for (v in embedding) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = maxVal - minVal
        val data = ByteArray(embedding.size)

        if (range == 0f) {
            // All values are the same
            data.fill(128.toByte())
        } else {
            for (i in embedding.indices) {
                val normalized = (embedding[i] - minVal) / range
                data[i] = (normalized * 255).toInt().coerceIn(0, 255).toByte()
            }
        }

        return QuantizedEmbedding(data, minVal, maxVal)
    }

    /**
     * Dequantize back to float embedding.
     * Reconstructs approximate float values from 8-bit data.
     */
    fun dequantize(quantized: QuantizedEmbedding): FloatArray {
        if (quantized.data.isEmpty()) return FloatArray(0)

        val range = quantized.maxVal - quantized.minVal
        val result = FloatArray(quantized.data.size)

        for (i in quantized.data.indices) {
            val normalized = (quantized.data[i].toInt() and 0xFF) / 255.0f
            result[i] = quantized.minVal + normalized * range
        }

        return result
    }

    /**
     * Compute approximate cosine similarity directly on quantized embeddings.
     * Avoids full dequantization for faster computation.
     */
    fun cosineSimilarityQuantized(a: QuantizedEmbedding, b: QuantizedEmbedding): Float {
        if (a.data.size != b.data.size || a.data.isEmpty()) return 0.0f

        var dotProduct = 0L
        var normA = 0L
        var normB = 0L

        for (i in a.data.indices) {
            val va = (a.data[i].toInt() and 0xFF).toLong()
            val vb = (b.data[i].toInt() and 0xFF).toLong()
            dotProduct += va * vb
            normA += va * va
            normB += vb * vb
        }

        val denominator = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denominator > 0) (dotProduct / denominator).toFloat() else 0.0f
    }

    /**
     * Estimate memory savings for a given embedding count.
     */
    fun estimateMemorySavings(embeddingCount: Int, dimensions: Int): Map<String, Long> {
        val fullPrecisionBytes = embeddingCount.toLong() * dimensions * 4  // 4 bytes per float
        val quantizedBytes = embeddingCount.toLong() * (dimensions + 8)    // 1 byte per dim + 8 bytes min/max

        return mapOf(
            "full_precision_bytes" to fullPrecisionBytes,
            "quantized_bytes" to quantizedBytes,
            "savings_bytes" to (fullPrecisionBytes - quantizedBytes),
            "savings_percent" to ((fullPrecisionBytes - quantizedBytes) * 100 / fullPrecisionBytes)
        )
    }
}
