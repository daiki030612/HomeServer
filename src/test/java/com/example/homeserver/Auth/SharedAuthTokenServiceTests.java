package com.example.homeserver.Auth;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedAuthTokenServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    @Test void signedTokenCarriesIdentityAndRejectsTampering() {
        SharedAuthProperties properties = properties(Duration.ofHours(1));
        SharedAuthTokenService service = new SharedAuthTokenService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = service.create("viewer", List.of("ROLE_USER"));
        assertThat(service.verify(token)).get().satisfies(identity -> {
            assertThat(identity.username()).isEqualTo("viewer");
            assertThat(identity.authorities()).containsExactly("ROLE_USER");
        });
        assertThat(service.verify(token + "x")).isEmpty();
    }
    @Test void expiredTokenIsRejected() {
        SharedAuthProperties properties = properties(Duration.ofSeconds(1));
        String token = new SharedAuthTokenService(properties, Clock.fixed(NOW, ZoneOffset.UTC)).create("viewer", List.of("ROLE_USER"));
        assertThat(new SharedAuthTokenService(properties, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)).verify(token)).isEmpty();
    }
    private static SharedAuthProperties properties(Duration ttl) {
        SharedAuthProperties properties = new SharedAuthProperties();
        properties.setSecret("test-only-shared-auth-secret-32-characters-minimum");
        properties.setTokenTtl(ttl);
        return properties;
    }
}
