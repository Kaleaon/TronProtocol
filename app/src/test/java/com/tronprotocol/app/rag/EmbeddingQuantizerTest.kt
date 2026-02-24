package com.tronprotocol.app.rag

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class EmbeddingQuantizerTest {

    // --- quantize reduces size ---

    @Test
    fun quantize_reducesSize() {
        val embedding = FloatArray(128) { it * 0.01f }
        val quantized = EmbeddingQuantizer.quantize(embedding)

        val originalBytes = embedding.size * 4 // 4 bytes per float
        val quantizedBytes = quantized.data.size // 1 byte per dimension

        assertTrue(
            "Quantized size ($quantizedBytes) should be ~75% smaller than original ($originalBytes)",
            quantizedBytes < originalBytes
        )
        // 128 bytes vs 512 bytes => 75% reduction
        assertEquals(128, quantizedBytes)
        assertEquals(512, originalBytes)
    }

    // --- dequantize preserves values approximately ---

    @Test
    fun dequantize_afterQuantize_preservesValuesApproximately() {
        val original = FloatArray(64) { it * 0.015f + 0.1f }
        val quantized = EmbeddingQuantizer.quantize(original)
        val restored = EmbeddingQuantizer.dequantize(quantized)

        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(
                "Value at index $i should be approximately preserved",
                original[i], restored[i], 0.02f
            )
        }
    }

    // --- round-trip on uniform values ---

    @Test
    fun quantize_dequantize_roundTripUniformValues() {
        val original = FloatArray(32) { 0.5f }
        val quantized = EmbeddingQuantizer.quantize(original)
        val restored = EmbeddingQuantizer.dequantize(quantized)

        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(
                "Uniform value should be preserved at index $i",
                original[i], restored[i], 0.02f
            )
        }
    }

    // --- round-trip on varied values ---

    @Test
    fun quantize_dequantize_roundTripVariedValues() {
        val original = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        val quantized = EmbeddingQuantizer.quantize(original)
        val restored = EmbeddingQuantizer.dequantize(quantized)

        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(
                "Varied value at index $i should be approximately preserved",
                original[i], restored[i], 0.02f
            )
        }
    }

    // --- cosineSimilarityQuantized of identical vectors is close to 1.0 ---

    @Test
    fun cosineSimilarityQuantized_identicalVectors_closeToOne() {
        val embedding = FloatArray(128) { (it % 10) * 0.1f + 0.1f }
        val quantized = EmbeddingQuantizer.quantize(embedding)

        val similarity = EmbeddingQuantizer.cosineSimilarityQuantized(quantized, quantized)
        assertTrue(
            "Cosine similarity of identical quantized vectors should be close to 1.0, was $similarity",
            similarity > 0.99f
        )
    }

    // --- cosineSimilarityQuantized of orthogonal vectors is close to 0.0 ---

    @Test
    fun cosineSimilarityQuantized_orthogonalVectors_closeToZero() {
        // Create two vectors that are approximately orthogonal
        // Vector A: non-zero in first half, zero in second half
        // Vector B: zero in first half, non-zero in second half
        val size = 128
        val embeddingA = FloatArray(size) { if (it < size / 2) 1.0f else 0.0f }
        val embeddingB = FloatArray(size) { if (it >= size / 2) 1.0f else 0.0f }

        val quantizedA = EmbeddingQuantizer.quantize(embeddingA)
        val quantizedB = EmbeddingQuantizer.quantize(embeddingB)

        val similarity = EmbeddingQuantizer.cosineSimilarityQuantized(quantizedA, quantizedB)
        // After quantization, min-max scaling maps 0.0->0 and 1.0->255, but both
        // vectors have the same min/max so the quantized zeros become 0 bytes.
        // The dot product of (255,255,...,0,0,...) . (0,0,...,255,255,...) = 0
        // but norms are non-zero, so similarity should be close to 0.
        assertTrue(
            "Cosine similarity of orthogonal quantized vectors should be close to 0.0, was $similarity",
            abs(similarity) < 0.15f
        )
    }

    // --- estimateMemorySavings returns non-empty map ---

    @Test
    fun estimateMemorySavings_returnsNonEmptyMap() {
        val savings = EmbeddingQuantizer.estimateMemorySavings(1000, 128)
        assertTrue(savings.isNotEmpty())
        assertTrue(savings.containsKey("full_precision_bytes"))
        assertTrue(savings.containsKey("quantized_bytes"))
        assertTrue(savings.containsKey("savings_bytes"))
        assertTrue(savings.containsKey("savings_percent"))
        assertTrue("Savings should be positive", (savings["savings_bytes"] ?: 0L) > 0L)
    }

    // --- quantize handles single-element array ---

    @Test
    fun quantize_handlesSingleElementArray() {
        val original = floatArrayOf(0.42f)
        val quantized = EmbeddingQuantizer.quantize(original)
        val restored = EmbeddingQuantizer.dequantize(quantized)

        assertEquals(1, quantized.data.size)
        assertEquals(1, restored.size)
        // Single element: min=max=0.42, range=0, so all values map to 128
        // Dequantized: 0.42 + (128/255) * 0 = 0.42
        assertEquals(original[0], restored[0], 0.02f)
    }

    // --- quantize handles all-zero array ---

    @Test
    fun quantize_handlesAllZeroArray() {
        val original = FloatArray(16) { 0.0f }
        val quantized = EmbeddingQuantizer.quantize(original)
        val restored = EmbeddingQuantizer.dequantize(quantized)

        assertEquals(original.size, quantized.data.size)
        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(
                "All-zero array should round-trip to approximately 0",
                0.0f, restored[i], 0.02f
            )
        }
    }
}
