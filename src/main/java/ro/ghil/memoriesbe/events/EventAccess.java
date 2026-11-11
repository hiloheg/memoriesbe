package ro.ghil.memoriesbe.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ro.ghil.memoriesbe.events.EventRepository.Event;
import ro.ghil.memoriesbe.sftpgo.SecretBox;

/** Decides whether a guest may see the photos of an event: open events always, protected ones only with the right password. */
@Component
public class EventAccess {
    private final SecretBox secrets;

    EventAccess(SecretBox secrets) {
        this.secrets = secrets;
    }

    static boolean isProtected(Event e) {
        return e.accessPasswordEnc() != null;
    }

    /** 401 when no password was sent, 403 when it is wrong. */
    void require(Event e, String given) {
        if (!isProtected(e)) return;
        if (given == null || given.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This event is protected. Enter its password.");
        }
        String real = secrets.decrypt(e.accessPasswordEnc());
        boolean ok = real != null && MessageDigest.isEqual(real.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That password is not right.");
    }

    /** The password for the owner to see (null when the event is open). */
    String passwordOf(Event e) {
        return isProtected(e) ? secrets.decrypt(e.accessPasswordEnc()) : null;
    }

    String encrypt(String password) {
        return secrets.encrypt(password);
    }
}
