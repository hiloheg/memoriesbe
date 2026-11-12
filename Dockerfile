# Build stage: compile the jar with Maven (tests are run in CI/locally, not on the server).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Face models from OpenCV Zoo (YuNet detector, MIT; SFace recognizer, Apache 2.0). Checked against the SHA-256 of the files we tested with.
RUN mkdir /models \
 && curl -fsSL -o /models/face_detection_yunet_2023mar.onnx https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx \
 && curl -fsSL -o /models/face_recognition_sface_2021dec.onnx https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx \
 && echo "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  /models/face_detection_yunet_2023mar.onnx" | sha256sum -c - \
 && echo "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79  /models/face_recognition_sface_2021dec.onnx" | sha256sum -c -

# Run stage: just the JRE and the jar, as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /models /app/models
ENV FACE_MODEL_DIR=/app/models
USER app

# Port the API listens on (SERVER_PORT, default 8081).
EXPOSE 8081

# Cap the heap so the JVM can't take the whole server; adjust with JAVA_OPTS if needed.
ENV JAVA_OPTS="-Xmx768m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
