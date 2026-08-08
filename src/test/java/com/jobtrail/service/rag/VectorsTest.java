package com.jobtrail.service.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Encoding round-trips and similarity.
 *
 * <p>Worth testing because both failure modes here are silent. A byte-order
 * mistake produces vectors that decode to plausible-looking garbage, and a
 * similarity bug just returns the wrong passages — neither throws, and both
 * would surface only as answers that are subtly and inexplicably worse.
 */
class VectorsTest {

    @Test
    void roundTripsAVectorThroughItsEncoding() {
        float[] original = {0.6f, 0.8f};

        float[] decoded = Vectors.decode(Vectors.encodeNormalised(original));

        // Already unit length, so normalising is a no-op and the values survive.
        assertThat(decoded).hasSize(2);
        assertThat(decoded[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(decoded[1]).isCloseTo(0.8f, within(1e-6f));
    }

    @Test
    void normalisesOnTheWayIn() {
        float[] decoded = Vectors.decode(Vectors.encodeNormalised(new float[]{3f, 4f}));

        assertThat(decoded[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(decoded[1]).isCloseTo(0.8f, within(1e-6f));
    }

    @Test
    void scoresIdenticalDirectionsAsOne() {
        byte[] stored = Vectors.encodeNormalised(new float[]{1f, 2f, 3f});
        float[] query = Vectors.normalise(new float[]{2f, 4f, 6f});

        assertThat(Vectors.dot(stored, query)).isCloseTo(1d, within(1e-6));
    }

    @Test
    void scoresOrthogonalVectorsAsZero() {
        byte[] stored = Vectors.encodeNormalised(new float[]{1f, 0f});
        float[] query = Vectors.normalise(new float[]{0f, 1f});

        assertThat(Vectors.dot(stored, query)).isCloseTo(0d, within(1e-6));
    }

    @Test
    void scoresOppositeVectorsAsMinusOne() {
        byte[] stored = Vectors.encodeNormalised(new float[]{1f, 1f});
        float[] query = Vectors.normalise(new float[]{-1f, -1f});

        assertThat(Vectors.dot(stored, query)).isCloseTo(-1d, within(1e-6));
    }

    @Test
    void scoresMismatchedDimensionsAsZeroRatherThanThrowing() {
        // Happens when the embedding model is swapped. Those rows should fall
        // out of the ranking and be re-indexed, not take the search down.
        byte[] stored = Vectors.encodeNormalised(new float[]{1f, 0f, 0f});
        float[] query = Vectors.normalise(new float[]{1f, 0f});

        assertThat(Vectors.dot(stored, query)).isZero();
    }

    @Test
    void handlesAZeroVectorWithoutProducingNaN() {
        byte[] stored = Vectors.encodeNormalised(new float[]{0f, 0f});

        assertThat(Vectors.decode(stored)).containsExactly(0f, 0f);
        assertThat(Vectors.dot(stored, new float[]{1f, 0f})).isZero();
    }

    @Test
    void treatsNullAndEmptyInputAsEmpty() {
        assertThat(Vectors.encodeNormalised(null)).isEmpty();
        assertThat(Vectors.decode(null)).isEmpty();
        assertThat(Vectors.decode(new byte[0])).isEmpty();
        assertThat(Vectors.dot(null, new float[]{1f})).isZero();
    }
}
