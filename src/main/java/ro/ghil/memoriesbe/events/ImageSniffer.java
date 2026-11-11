package ro.ghil.memoriesbe.events;

import java.util.Optional;

/** Tells what a file really is from its first bytes, so a web page renamed to .jpg is not taken for a photo. */
public final class ImageSniffer {
    private ImageSniffer() {}

    /** "jpg", "png" or "webp"; empty for anything else. */
    public static Optional<String> detect(byte[] b) {
        if (b == null) return Optional.empty();
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Optional.of("jpg");
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return Optional.of("png");
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Optional.of("webp");
        return Optional.empty();
    }

    public static String contentType(String ext) {
        return switch (ext) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> "image/webp";
        };
    }
}
