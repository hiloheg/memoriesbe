package ro.ghil.memoriesbe.faces;

import java.util.List;

/** Finds faces in a picture and describes each one with 128 numbers; the only part of the code that knows about OpenCV. */
public interface FaceEngine {
    /** A face found in an image: position and size in pixels of the original, and the 128 numbers that describe it. */
    record Face(int x, int y, int width, int height, float[] embedding) {}

    /** Every face in the JPEG/PNG bytes, largest first. Empty when there is none or the bytes are not a picture. */
    List<Face> faces(byte[] image);
}
