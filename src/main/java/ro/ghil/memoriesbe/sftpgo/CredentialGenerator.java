package ro.ghil.memoriesbe.sftpgo;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Generates random camera usernames and passwords. */
@Component
public class CredentialGenerator {
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final String PASS = LOWER + "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();

    public String username() {
        return "cam_" + pick(LOWER, 8);
    }

    public String password() {
        return pick(PASS, 20);
    }

    private String pick(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }
}
