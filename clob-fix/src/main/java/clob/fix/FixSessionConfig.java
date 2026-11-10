package clob.fix;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * One permitted FIX session: the client's SenderCompID, the CLOB user it logs in as (Username 553 and
 * Password 554 on Logon), whether its orders are cancelled when it disconnects, and its inbound
 * message rate limit. {@code password} is {@code sha256$<salt>$<hex>} (see {@link #hash}), or plain text
 * where the node explicitly allows it for development.
 */
public record FixSessionConfig(String senderCompId, String username, String password, int userId,
        boolean cancelOnDisconnect, int maxMessagesPerSecond) {

    private static final String PREFIX = "sha256$";

    public boolean hasHashedPassword() {
        return password.startsWith(PREFIX);
    }

    /** Constant-time password check. */
    public boolean passwordMatches(String supplied) {
        if (supplied == null) {
            return false;
        }
        byte[] expected;
        byte[] actual;
        if (hasHashedPassword()) {
            String[] parts = password.split("\\$");
            if (parts.length != 3) {
                return false;
            }
            expected = parts[2].getBytes(StandardCharsets.US_ASCII);
            actual = digest(parts[1], supplied).getBytes(StandardCharsets.US_ASCII);
        } else {
            expected = password.getBytes(StandardCharsets.UTF_8);
            actual = supplied.getBytes(StandardCharsets.UTF_8);
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /** A salted hash of {@code password}, in the form this config accepts. */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        return PREFIX + saltHex + "$" + digest(saltHex, password);
    }

    private static String digest(String salt, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(sha.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
