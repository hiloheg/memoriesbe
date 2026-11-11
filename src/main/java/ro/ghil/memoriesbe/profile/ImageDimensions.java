package ro.ghil.memoriesbe.profile;

/** Reads the pixel size straight from a PNG or JPEG header, without decoding the image. */
final class ImageDimensions {
    private ImageDimensions() {}

    /** Returns {width, height}, or null when the format is not PNG/JPEG or the header is unreadable (e.g. WebP). */
    static int[] of(byte[] b) {
        if (b.length >= 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return new int[] {int32(b, 16), int32(b, 20)};
        }
        if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
                int marker = b[i + 1] & 0xFF;
                if (marker == 0xFF) { i++; continue; }
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) return new int[] {((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF), ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF)};
                i += 2 + len;
            }
        }
        return null;
    }

    private static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
