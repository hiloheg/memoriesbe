package ro.ghil.memoriesbe;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Sends the reason of a deliberate error (400, 404, 409, 502) as {"message": ...} so the UI can show it. */
@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
        String reason = ex.getReason() == null ? "Request failed" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", reason));
    }
}
