package com.blooddonor.service;

/**
 * Sends messages to donors / hospitals.
 * The default implementation only logs; plug in an SMS / WhatsApp / email provider here.
 */
public interface NotificationService {

    void send(String phone, String message);
}
