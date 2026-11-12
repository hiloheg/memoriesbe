package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.faces.FaceMath;

class FaceMathTests {

	@Test
	void identicalVectorsHaveCosineOne() {
		assertEquals(1.0, FaceMath.cosine(new float[] {1, 2, 3}, new float[] {1, 2, 3}), 1e-6);
		assertEquals(1.0, FaceMath.cosine(new float[] {1, 2, 3}, new float[] {2, 4, 6}), 1e-6);
	}

	@Test
	void orthogonalVectorsHaveCosineZero() {
		assertEquals(0.0, FaceMath.cosine(new float[] {1, 0}, new float[] {0, 1}), 1e-6);
	}

	@Test
	void aZeroVectorNeverMatches() {
		assertEquals(0.0, FaceMath.cosine(new float[] {0, 0, 0}, new float[] {1, 2, 3}), 0.0);
		assertEquals(0.0, FaceMath.cosine(new float[] {1, 2, 3}, new float[] {0, 0, 0}), 0.0);
	}

	@Test
	void bytesRoundTrip() {
		float[] v = {0.5f, -1.25f, 3.0e-5f, 128f};
		assertArrayEquals(v, FaceMath.fromBytes(FaceMath.toBytes(v)), 0f);
		assertEquals(v.length * 4, FaceMath.toBytes(v).length);
	}
}
