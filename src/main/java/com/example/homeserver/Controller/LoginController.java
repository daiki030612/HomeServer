package com.example.homeserver.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.ui.Model;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(name = "continue", defaultValue = "") String continueUrl, Model model) {
        model.addAttribute("continueUrl", continueUrl);
        return "login";
    }
}
