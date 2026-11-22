package com.blooddonor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React app uses browser routes like /donor or /hospital.
 * When someone opens or refreshes one of those URLs directly, send them the React index.html;
 * React Router then shows the right page.
 */
@Controller
public class SpaController {

    public static final String[] ROUTES = {"/login", "/signup", "/donor", "/hospital", "/admin"};

    @GetMapping({"/login", "/signup", "/donor", "/hospital", "/admin"})
    public String forwardToReactApp() {
        return "forward:/index.html";
    }
}
