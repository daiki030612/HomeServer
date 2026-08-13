package com.example.homeserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class HomeServerApplicationTests {

    @Test
    void applicationEntryPointIsConfigured() {
        assertThat(HomeServerApplication.class)
                .hasAnnotation(SpringBootApplication.class);
    }
}
