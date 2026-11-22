package com.blooddonor.security;

/**
 * Published when sessions end, so access tokens and live streams can be cut off immediately.
 * Listeners run after the transaction commits.
 */
public final class SessionEvents {

    private SessionEvents() {
    }

    /** One login session (refresh-token family) ended: logout on a device, or detected token theft. */
    public record SessionEnded(String username, String sessionId) {
    }

    /** Every session of the account ended (log out everywhere); the account's token version was bumped. */
    public record AllSessionsEnded(String username) {
    }
}
