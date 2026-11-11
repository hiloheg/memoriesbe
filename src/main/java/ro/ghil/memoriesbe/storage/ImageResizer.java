package ro.ghil.memoriesbe.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;

/** Makes the small JPEG copies. Camera orientation (EXIF) is applied, and a photo smaller than the target is not enlarged. */
public final class ImageResizer {
    private static final float QUALITY = 0.8f;
    /** About the size of the biggest medium-format cameras (150 MP). Anything above is not a photo we should decode. */
    static final long MAX_PIXELS = 150_000_000L;

    static {
        // Thumbnailator decodes a huge JPEG at a fraction of its size instead of in full (a 108 MP photo fits in a 250 MB heap),
        // and ImageIO must not write a temp file for every image it reads. Both are read once, so set them before first use.
        System.setProperty("thumbnailator.conserveMemoryWorkaround", "true");
        ImageIO.setUseCache(false);
    }

    private ImageResizer() {}

    /** The photo as a JPEG exactly {@code width} px wide, or at its own size if it is already smaller. Throws IOException for formats Java cannot read (such as WebP). */
    public static byte[] resize(byte[] source, int width) throws IOException {
        int[] size = size(source);
        if ((long) size[0] * size[1] > MAX_PIXELS) throw new IOException("Image too large to resize: " + size[0] + "x" + size[1]);
        var out = new ByteArrayOutputStream();
        var job = Thumbnails.of(new ByteArrayInputStream(source));
        if (Math.max(size[0], size[1]) <= width) job.scale(1.0);
        else job.width(width);
        job.outputFormat("jpg").outputQuality(QUALITY).toOutputStream(out);
        return out.toByteArray();
    }

    /** Width and height read from the header only, without decoding the picture. */
    private static int[] size(byte[] source) throws IOException {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            var readers = in == null ? java.util.Collections.<javax.imageio.ImageReader>emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Not an image Java can read");
            var reader = readers.next();
            try {
                reader.setInput(in);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }
}
