package com.example.homeserver.Controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class NavigationModelAdvice {
    private final org.springframework.beans.factory.ObjectProvider<com.example.homeserver.Service.StorageService> storage;
    public NavigationModelAdvice(org.springframework.beans.factory.ObjectProvider<com.example.homeserver.Service.StorageService> storage) { this.storage = storage; }
    @Value("${navigation.image.url:}") private String imageUrl;
    @Value("${navigation.image-port:8081}") private int imagePort;
    @Value("${navigation.image-path:/gallery}") private String imagePath;

    @ModelAttribute("imageAppUrl") String imageAppUrl() { return imageUrl; }
    @ModelAttribute("imageAppPort") int imageAppPort() { return imagePort; }
    @ModelAttribute("imageAppPath") String imageAppPath() { return imagePath; }
    @ModelAttribute("storageInfo") com.example.homeserver.Service.StorageInfo storageInfo() {
        var service = storage.getIfAvailable();
        return service == null ? com.example.homeserver.Service.StorageInfo.unavailable() : service.current();
    }
}
