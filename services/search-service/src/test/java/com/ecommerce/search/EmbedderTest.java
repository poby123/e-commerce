package com.ecommerce.search;

import com.ecommerce.search.embedding.Embedder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The model itself, without Spring or Elasticsearch: are similar meanings closer than unrelated ones? */
class EmbedderTest {

    static Embedder embedder;

    @BeforeAll
    static void load() throws Exception {
        embedder = new Embedder(new Embedder.Properties(Path.of(System.getProperty("ecommerce.search.embedding.model-dir")), 128));
    }

    @Test
    void vectorsAreUnitLength() {
        float[] v = embedder.embedPassage("무선 블루투스 이어폰");
        assertThat(v).hasSize(Embedder.DIMENSIONS);
        double norm = 0;
        for (float x : v) norm += x * x;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void anIntentIsCloserToTheRightProductThanToAnUnrelatedOne() {
        float[] query = embedder.embedQuery("방이 너무 건조해요");
        double humidifier = dot(query, embedder.embedPassage("초음파 가습기 대용량 4L"));
        double keyboard = dot(query, embedder.embedPassage("기계식 키보드 갈축 텐키리스"));
        System.out.printf("similarity: humidifier %.3f, keyboard %.3f%n", humidifier, keyboard);
        assertThat(humidifier).isGreaterThan(keyboard);
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
