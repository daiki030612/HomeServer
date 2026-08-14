package com.example.homeserver.Controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class NavigationModelAdvice {
    @Value("${navigation.image.url:}") private String imageUrl;
    @Value("${navigation.image-port:8081}") private int imagePort;
    @Value("${navigation.image-path:/gallery}") private String imagePath;

    @ModelAttribute("imageAppUrl") String imageAppUrl() { return imageUrl; }
    @ModelAttribute("imageAppPort") int imageAppPort() { return imagePort; }
    @ModelAttribute("imageAppPath") String imageAppPath() { return imagePath; }
}
