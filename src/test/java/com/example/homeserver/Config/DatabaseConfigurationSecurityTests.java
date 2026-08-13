package com.example.homeserver.Config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class DatabaseConfigurationSecurityTests {

    @Test
    void databaseCredentialsAreReadFromEnvironmentVariables() throws IOException {
        Properties properties = loadApplicationProperties();

        assertThat(properties.getProperty("spring.datasource.username"))
                .isEqualTo("${HOMESERVER_DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .isEqualTo("${HOMESERVER_DB_PASSWORD}");
    }

    @Test
    void databaseUsernameIsNotConfiguredAsRoot() throws IOException {
        Properties properties = loadApplicationProperties();

        assertThat(properties.getProperty("spring.datasource.username"))
                .isNotEqualToIgnoringCase("root");
    }

    private Properties loadApplicationProperties() throws IOException {
        Properties properties = new Properties();

        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertThat(input).as("application.properties must exist").isNotNull();
            properties.load(input);
        }

        return properties;
    }
}
