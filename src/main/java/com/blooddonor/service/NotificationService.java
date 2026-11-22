package com.blooddonor.service;

/**
 * Sends messages to donors / hospitals.
 * Implemented by {@link com.blooddonor.notification.SmsNotificationService}: logs by default, real SMS via Twilio.
 */
public interface NotificationService {

    void send(String phone, String message);
}
