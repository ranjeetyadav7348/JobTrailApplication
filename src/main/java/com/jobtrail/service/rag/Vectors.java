package com.jobtrail.service.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encoding and comparison for embedding vectors.
 *
 * <p><strong>Vectors are stored L2-normalised.</strong> Cosine similarity is the
 * dot product divided by both magnitudes; if every stored vector already has
 * magnitude 1, that division disappears and similarity is a plain dot product.
 * Normalising once at write time therefore removes a square root and a division
 * from every comparison at read time, and read happens far more often than
 * write. It also means a stored vector can be scored without being unpacked
 * into a {@code float[]} first — see {@link #dot(byte[], float[])}.
 *
 * <p>Encoding is little-endian IEEE-754 float32, chosen to be explicit rather
 * than to inherit whatever byte order the JVM happens to use, so a database
 * written on one machine reads correctly on another.
 */
public final class Vectors {

    private static final int BYTES_PER_FLOAT = Float.BYTES;

    private Vectors() {
    }

    /** Normalises a copy and encodes it. A zero vector is returned as-is. */
    public static byte[] encodeNormalised(float[] values) {
        if (values == null || values.length == 0) {
            return new byte[0];
        }
        float[] unit = normalise(values);
        ByteBuffer buffer = ByteBuffer.allocate(unit.length * BYTES_PER_FLOAT)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : unit) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length < BYTES_PER_FLOAT) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / BYTES_PER_FLOAT];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    /**
     * Returns a unit-length copy. A vector of all zeros has no direction to
     * preserve, so it is returned unchanged rather than producing NaNs.
     */
    public static float[] normalise(float[] values) {
        double sumOfSquares = 0d;
        for (float value : values) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares <= 0d) {
            return values.clone();
        }
        float magnitude = (float) Math.sqrt(sumOfSquares);
        float[] unit = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            unit[i] = values[i] / magnitude;
        }
        return unit;
    }

    /**
     * Similarity between a stored (already normalised) vector and a normalised
     * query, read straight out of the bytes so scoring a whole corpus allocates
     * nothing per candidate.
     *
     * <p>Length mismatch scores 0 rather than throwing. That happens when the
     * embedding model is swapped and the stored dimensions no longer match:
     * those rows should drop out of the ranking and be re-indexed, not take the
     * whole search down.
     */
    public static double dot(byte[] stored, float[] normalisedQuery) {
        if (stored == null || normalisedQuery == null) {
            return 0d;
        }
        if (stored.length != normalisedQuery.length * BYTES_PER_FLOAT) {
            return 0d;
        }
        ByteBuffer buffer = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN);
        double total = 0d;
        for (float queryComponent : normalisedQuery) {
            total += buffer.getFloat() * (double) queryComponent;
        }
        return total;
    }
}
