package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.homeserver.Entity.User;
import com.example.homeserver.Repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class InitialAdminServiceTests {

    @Mock
    private EnvironmentVariables environmentVariables;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private InitialAdminService service;

    @BeforeEach
    void setUp() {
        service = new InitialAdminService(environmentVariables, userRepository, passwordEncoder);
    }

    @Test
    void doesNotCreateAdministratorWhenEnvironmentVariablesAreMissing() {
        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotCreateAdministratorWhenOnlyOneEnvironmentVariableIsSet() {
        when(environmentVariables.get(InitialAdminService.USERNAME_ENV)).thenReturn("homeserver-owner");

        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsShortPassword() {
        configure("homeserver-owner", "short");

        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsObviousPassword() {
        configure("homeserver-owner", "Password123!");

        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsObviousAdministratorPassword() {
        configure("homeserver-owner", "AdminAdmin123!");

        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotChangeExistingUser() {
        configure("homeserver-owner", "Correct-Horse-Battery-Staple-2026");
        when(userRepository.findByUsername("homeserver-owner"))
                .thenReturn(Optional.of(new User()));

        service.createInitialAdminIfConfigured();

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void createsAdministratorFromEnvironmentVariables() {
        String rawPassword = "Correct-Horse-Battery-Staple-2026";
        configure("homeserver-owner", rawPassword);
        when(userRepository.findByUsername("homeserver-owner")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(rawPassword)).thenReturn("encoded-password");

        service.createInitialAdminIfConfigured();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("homeserver-owner");
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getRole()).isEqualTo("ROLE_ADMIN");
    }

    private void configure(String username, String password) {
        when(environmentVariables.get(InitialAdminService.USERNAME_ENV)).thenReturn(username);
        when(environmentVariables.get(InitialAdminService.PASSWORD_ENV)).thenReturn(password);
    }
}
