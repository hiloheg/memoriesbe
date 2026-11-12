package ro.ghil.memoriesbe.faces;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

/**
 * YuNet (finds faces) and SFace (describes them), run by OpenCV on the CPU. One picture at a time: the detector keeps the size of
 * the last picture, so calls are serialized. Pictures larger than {@link #MAX_SIDE} pixels are shrunk first.
 */
public class OpenCvFaceEngine implements FaceEngine {
    public static final String DETECTOR_FILE = "face_detection_yunet_2023mar.onnx";
    public static final String RECOGNIZER_FILE = "face_recognition_sface_2021dec.onnx";
    private static final int MAX_SIDE = 1600;
    private static final float MIN_SCORE = 0.7f;

    private final FaceDetectorYN detector;
    private final FaceRecognizerSF recognizer;

    public OpenCvFaceEngine(Path modelDir) {
        OpenCV.loadLocally();
        this.detector = FaceDetectorYN.create(modelDir.resolve(DETECTOR_FILE).toString(), "", new Size(320, 320), MIN_SCORE, 0.3f, 5000);
        this.recognizer = FaceRecognizerSF.create(modelDir.resolve(RECOGNIZER_FILE).toString(), "");
    }

    @Override
    public synchronized List<Face> faces(byte[] image) {
        Mat bgr = Imgcodecs.imdecode(new MatOfByte(image), Imgcodecs.IMREAD_COLOR);
        if (bgr.empty()) return List.of();
        Mat work = bgr;
        double scale = 1.0;
        int longSide = Math.max(bgr.cols(), bgr.rows());
        if (longSide > MAX_SIDE) {
            scale = (double) MAX_SIDE / longSide;
            work = new Mat();
            Imgproc.resize(bgr, work, new Size(Math.round(bgr.cols() * scale), Math.round(bgr.rows() * scale)));
        }
        Mat found = new Mat();
        try {
            detector.setInputSize(new Size(work.cols(), work.rows()));
            detector.detect(work, found);
            var out = new ArrayList<Face>();
            for (int i = 0; i < found.rows(); i++) {
                Mat box = found.row(i);
                Mat aligned = new Mat();
                Mat feature = new Mat();
                try {
                    recognizer.alignCrop(work, box, aligned);
                    recognizer.feature(aligned, feature);
                    float[] values = new float[128];
                    feature.get(0, 0, values);
                    out.add(new Face((int) Math.round(found.get(i, 0)[0] / scale), (int) Math.round(found.get(i, 1)[0] / scale),
                            (int) Math.round(found.get(i, 2)[0] / scale), (int) Math.round(found.get(i, 3)[0] / scale), values));
                } finally {
                    box.release();
                    aligned.release();
                    feature.release();
                }
            }
            out.sort(Comparator.comparingLong((Face f) -> (long) f.width() * f.height()).reversed());
            return out;
        } finally {
            found.release();
            if (work != bgr) work.release();
            bgr.release();
        }
    }
}
