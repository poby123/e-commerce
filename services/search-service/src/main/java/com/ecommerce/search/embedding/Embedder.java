package com.ecommerce.search.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * multilingual-e5-small, in-process via ONNX Runtime (int8, ~120 MB, a few ms per short text on CPU).
 *
 * <p>The model was trained with the prefixes "query: " and "passage: ", and its embedding is the attention-masked
 * mean of the last hidden states, L2-normalised. Skipping either costs noticeable quality, so both are fixed here.
 */
@Component
public class Embedder implements DisposableBean {

    public static final int DIMENSIONS = 384;

    @ConfigurationProperties("ecommerce.search.embedding")
    public record Properties(@DefaultValue("models/multilingual-e5-small") Path modelDir,
                             @DefaultValue("128") int maxTokens) {
    }

    private static final Logger log = LoggerFactory.getLogger(Embedder.class);

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public Embedder(Properties properties) throws IOException, OrtException {
        Path dir = properties.modelDir().toAbsolutePath();
        this.tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(dir.resolve("tokenizer.json"))
                .optMaxLength(properties.maxTokens())
                .optTruncation(true)
                .optPadding(false)
                .build();
        this.session = environment.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
        log.info("Loaded embedding model from {} (inputs {})", dir, session.getInputNames());
    }

    public float[] embedQuery(String text) {
        return embed("query: " + text);
    }

    public float[] embedPassage(String text) {
        return embed("passage: " + text);
    }

    private float[] embed(String text) {
        Encoding encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] shape = {1, ids.length};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape));
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(encoding.getTypeIds()), shape));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                return normalize(meanPool(hidden[0], mask));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static float[] meanPool(float[][] tokens, long[] mask) {
        float[] sum = new float[tokens[0].length];
        int count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < sum.length; d++) {
                sum[d] += tokens[t][d];
            }
        }
        for (int d = 0; d < sum.length; d++) {
            sum[d] /= Math.max(count, 1);
        }
        return sum;
    }

    /** Unit length, so the index can use dot_product (equal to cosine, cheaper). */
    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= scale;
        }
        return v;
    }

    @Override
    public void destroy() throws OrtException {
        session.close();
        tokenizer.close();
    }
}
