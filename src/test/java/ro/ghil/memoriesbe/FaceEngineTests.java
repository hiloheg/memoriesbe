package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.AffineTransformOp;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ro.ghil.memoriesbe.faces.FaceEngine;
import ro.ghil.memoriesbe.faces.OpenCvFaceEngine;

/** Runs the real YuNet and SFace models. Skipped when the models or sample pictures are not on this machine (see models/ and testdata/faces/). */
class FaceEngineTests {
    private static final Path MODELS = Path.of(System.getProperty("face.models", "models"));
    private static final Path PICS = Path.of("testdata/faces");
    private static final double THRESHOLD = 0.363;

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** The same person in a different picture: mirrored and at 70% size. */
    private static byte[] variantOf(byte[] jpeg) throws Exception {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        AffineTransform t = AffineTransform.getScaleInstance(-0.7, 0.7);
        t.translate(-src.getWidth(), 0);
        BufferedImage out = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR).filter(src, null);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void sameAndDifferentPeople() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(MODELS) && Files.exists(PICS.resolve("lena.jpg")),
                "models or sample pictures missing: skipping the real face engine test");
        FaceEngine engine = new OpenCvFaceEngine(MODELS);
        byte[] a1 = Files.readAllBytes(PICS.resolve("lena.jpg"));
        byte[] a2 = variantOf(a1);
        byte[] b = Files.readAllBytes(PICS.resolve("messi.jpg"));
        byte[] none = Files.readAllBytes(PICS.resolve("nofaces.jpg"));

        List<FaceEngine.Face> fa1 = engine.faces(a1);
        List<FaceEngine.Face> fa2 = engine.faces(a2);
        List<FaceEngine.Face> fb = engine.faces(b);
        assertEquals(1, fa1.size());
        assertEquals(1, fa2.size());
        assertTrue(fb.size() >= 1);
        assertEquals(128, fa1.get(0).embedding().length);
        assertTrue(cosine(fa1.get(0).embedding(), fa2.get(0).embedding()) >= THRESHOLD, "same person should match");
        assertTrue(cosine(fa1.get(0).embedding(), fb.get(0).embedding()) < THRESHOLD, "different people should not match");
        assertTrue(engine.faces(none).isEmpty(), "a picture without faces gives no faces");
    }
}
