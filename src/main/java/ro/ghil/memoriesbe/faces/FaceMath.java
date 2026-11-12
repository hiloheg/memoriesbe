package ro.ghil.memoriesbe.faces;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Comparing two faces (cosine similarity) and turning their numbers into bytes for the database. */
public final class FaceMath {
    private FaceMath() {}

    /** 1 for the same direction, 0 for unrelated; 0 when either vector is all zeros. */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Little-endian floats, 4 bytes each. */
    public static byte[] toBytes(float[] v) {
        ByteBuffer buffer = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) buffer.putFloat(f);
        return buffer.array();
    }

    public static float[] fromBytes(byte[] b) {
        ByteBuffer buffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[b.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buffer.getFloat();
        return out;
    }
}
