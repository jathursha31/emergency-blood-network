package com.blooddonor.push;

import java.util.Set;

/**
 * A notification for the browsers of some donors. Publish it with ApplicationEventPublisher; it is delivered
 * after the transaction commits, on a background thread.
 *
 * @param tag    notifications with the same tag replace each other on the device (e.g. "request-12")
 * @param urgent keeps the notification on screen until the donor acts on it
 */
public record PushMessage(Set<Long> donorIds, String title, String body, String url, String tag, boolean urgent) {

    /** The JSON the service worker reads in its "push" handler. */
    String toJson() {
        return "{\"title\":" + quote(title) + ",\"body\":" + quote(body) + ",\"url\":" + quote(url)
                + ",\"tag\":" + quote(tag) + ",\"urgent\":" + urgent + "}";
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
