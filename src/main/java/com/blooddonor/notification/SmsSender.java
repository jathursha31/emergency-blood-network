package com.blooddonor.notification;

/** Low-level "deliver this text to this number" (number already in E.164 form, e.g. +919840000001). */
public interface SmsSender {

    void send(String e164Phone, String text);

    /** Short name for logs, e.g. "twilio". */
    String name();
}
