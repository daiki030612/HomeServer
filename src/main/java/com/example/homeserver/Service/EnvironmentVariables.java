package com.example.homeserver.Service;

import org.springframework.stereotype.Component;

@Component
public class EnvironmentVariables {

    public String get(String name) {
        return System.getenv(name);
    }
}
