package com.blooddonor.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Client IP of the current HTTP request. */
public final class RequestIp {

    private RequestIp() {
    }

    /**
     * First X-Forwarded-For hop (the client, when running behind a proxy such as Render or nginx), else the
     * socket address; null outside a web request. Only trust X-Forwarded-For when a proxy you control sets it.
     */
    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpServletRequest req = attrs.getRequest();
        String forwarded = req.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        return ip == null ? null : ip.length() <= 45 ? ip : ip.substring(0, 45);
    }
}
