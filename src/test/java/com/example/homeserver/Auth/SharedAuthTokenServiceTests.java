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
    @Test void diagnosticsDistinguishValidExpiredAndTamperedTokensWithoutExposingValues() {
        SharedAuthProperties properties = properties(Duration.ofHours(1));
        SharedAuthTokenService issuer = new SharedAuthTokenService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = issuer.create("viewer", List.of("ROLE_USER"));

        var valid = issuer.diagnoseAndVerify(token);
        assertThat(valid.identity()).isPresent();
        assertThat(valid.parseSuccess()).isTrue();
        assertThat(valid.signatureValid()).isTrue();
        assertThat(valid.expired()).isFalse();
        assertThat(valid.expirationTime()).isEqualTo(NOW.plus(Duration.ofHours(1)));

        var expired = new SharedAuthTokenService(properties, Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                .diagnoseAndVerify(token);
        assertThat(expired.identity()).isEmpty();
        assertThat(expired.signatureValid()).isTrue();
        assertThat(expired.expired()).isTrue();
        assertThat(expired.failureReason()).isEqualTo("expired");

        var tampered = issuer.diagnoseAndVerify(token + "x");
        assertThat(tampered.identity()).isEmpty();
        assertThat(tampered.signatureValid()).isFalse();
    }
    private static SharedAuthProperties properties(Duration ttl) {
        SharedAuthProperties properties = new SharedAuthProperties();
        properties.setSecret("test-only-shared-auth-secret-32-characters-minimum");
        properties.setTokenTtl(ttl);
        return properties;
    }
}
