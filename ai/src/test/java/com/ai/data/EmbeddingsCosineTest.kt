package com.ai.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [EmbeddingsStore.cosine] — both the FloatArray (RAG hot path) and
 *  List<Double> overloads. */
class EmbeddingsCosineTest {

    @Test fun identical_vectors_are_one() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)))
            .isWithin(1e-9).of(1.0)
    }

    @Test fun scaled_vectors_are_one() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)))
            .isWithin(1e-9).of(1.0)
    }

    @Test fun orthogonal_vectors_are_zero() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
            .isWithin(1e-9).of(0.0)
    }

    @Test fun opposite_vectors_are_minus_one() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)))
            .isWithin(1e-9).of(-1.0)
    }

    @Test fun empty_vector_returns_zero() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(), floatArrayOf(1f, 2f))).isEqualTo(0.0)
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 2f), floatArrayOf())).isEqualTo(0.0)
    }

    @Test fun dimension_mismatch_returns_zero() {
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f))).isEqualTo(0.0)
    }

    @Test fun zero_vector_returns_zero_not_nan() {
        val r = EmbeddingsStore.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
        assertThat(r).isEqualTo(0.0)
    }

    @Test fun list_overload_matches_float_overload() {
        assertThat(EmbeddingsStore.cosine(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0)))
            .isWithin(1e-9).of(1.0)
        assertThat(EmbeddingsStore.cosine(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0)))
            .isWithin(1e-9).of(1.0)
    }

    @Test fun list_overload_empty_returns_zero_mismatch_returns_nan() {
        // Empty → 0.0. Dimension mismatch → NaN by design: the List-overload
        // callers (SemanticSearchScreen, LocalSemanticSearchScreen,
        // SecondaryRunManager) check isNaN() to SKIP chunks embedded with a
        // swapped embedder. (The FloatArray hot-path overload instead returns
        // 0.0 because KnowledgeService pre-filters by dimension.)
        assertThat(EmbeddingsStore.cosine(emptyList(), listOf(1.0))).isEqualTo(0.0)
        assertThat(EmbeddingsStore.cosine(listOf(1.0, 2.0), listOf(1.0)).isNaN()).isTrue()
    }

    @Test fun known_angle_half() {
        // (1,0)·(1,1) / (1 * sqrt2) = 1/sqrt2 ≈ 0.7071
        assertThat(EmbeddingsStore.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f)))
            .isWithin(1e-6).of(0.70710678)
    }
}
