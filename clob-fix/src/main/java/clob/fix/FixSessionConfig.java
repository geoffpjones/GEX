package clob.fix;

/**
 * One permitted FIX session: the client's SenderCompID, the CLOB user it logs in as (Username 553 and
 * Password 554 on Logon), whether its orders are cancelled when it disconnects, and its inbound
 * message rate limit. Passwords are plain text here; production would hold salted hashes.
 */
public record FixSessionConfig(String senderCompId, String username, String password, int userId,
        boolean cancelOnDisconnect, int maxMessagesPerSecond) {}
