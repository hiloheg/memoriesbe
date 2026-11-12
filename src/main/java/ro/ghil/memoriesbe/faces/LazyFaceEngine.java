package ro.ghil.memoriesbe.faces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The face engine the app uses: loads OpenCV and the models the first time it is needed, and reports whether the models are installed. */
@Component
public class LazyFaceEngine implements FaceEngine {
    private final Path modelDir;
    private volatile OpenCvFaceEngine real;

    LazyFaceEngine(@Value("${app.faces.model-dir:}") String modelDir) {
        this.modelDir = modelDir == null || modelDir.isBlank() ? null : Path.of(modelDir);
    }

    @Override
    public boolean available() {
        return modelDir != null
                && Files.isRegularFile(modelDir.resolve(OpenCvFaceEngine.DETECTOR_FILE))
                && Files.isRegularFile(modelDir.resolve(OpenCvFaceEngine.RECOGNIZER_FILE));
    }

    @Override
    public List<Face> faces(byte[] image) {
        return engine().faces(image);
    }

    private OpenCvFaceEngine engine() {
        OpenCvFaceEngine have = real;
        if (have != null) return have;
        synchronized (this) {
            if (real == null) {
                if (!available()) throw new IllegalStateException("The face models are not installed (set FACE_MODEL_DIR)");
                real = new OpenCvFaceEngine(modelDir);
            }
            return real;
        }
    }
}
