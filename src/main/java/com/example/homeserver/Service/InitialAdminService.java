package com.example.homeserver.Service;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.homeserver.Entity.User;
import com.example.homeserver.Repository.UserRepository;

@Service
public class InitialAdminService {

    static final String USERNAME_ENV = "INITIAL_ADMIN_USERNAME";
    static final String PASSWORD_ENV = "INITIAL_ADMIN_PASSWORD";
    static final int MINIMUM_PASSWORD_LENGTH = 12;

    private static final Logger logger = LoggerFactory.getLogger(InitialAdminService.class);

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "admin",
            "administrator",
            "root",
            "password",
            "password123",
            "123456",
            "12345678",
            "123456789",
            "qwerty",
            "letmein",
            "welcome");

    private final EnvironmentVariables environmentVariables;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public InitialAdminService(
            EnvironmentVariables environmentVariables,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.environmentVariables = environmentVariables;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void createInitialAdminIfConfigured() {
        String username = trimToNull(environmentVariables.get(USERNAME_ENV));
        String password = environmentVariables.get(PASSWORD_ENV);

        if (username == null && isBlank(password)) {
            logger.info("Initial administrator environment variables are not set; no administrator was created.");
            return;
        }

        if (username == null || isBlank(password)) {
            logger.warn("Initial administrator configuration is incomplete; no administrator was created.");
            return;
        }

        if (!isPasswordAcceptable(username, password)) {
            logger.warn("Initial administrator password does not meet the security requirements; no administrator was created.");
            return;
        }

        if (userRepository.findByUsername(username).isPresent()) {
            logger.info("The configured initial administrator already exists; no changes were made.");
            return;
        }

        User administrator = new User();
        administrator.setUsername(username);
        administrator.setPassword(passwordEncoder.encode(password));
        administrator.setRole("ROLE_ADMIN");
        userRepository.save(administrator);

        logger.info("Initial administrator was created successfully.");
    }

    boolean isPasswordAcceptable(String username, String password) {
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            return false;
        }

        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        String normalizedUsername = username.toLowerCase(Locale.ROOT);

        return !COMMON_PASSWORDS.contains(normalizedPassword)
                && !normalizedPassword.contains("admin")
                && !normalizedPassword.contains("password")
                && !normalizedPassword.equals(normalizedUsername);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
