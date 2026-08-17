package com.example.homeserver.Controller;

import com.example.homeserver.Service.StorageInfo;
import com.example.homeserver.Service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StorageService storage;
    public StorageController(StorageService storage) { this.storage = storage; }
    @GetMapping public StorageInfo current() { return storage.current(); }
}
