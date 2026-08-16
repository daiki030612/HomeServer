package com.example.homeserver.Auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SharedAuthTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Pattern AUTHORITY = Pattern.compile("ROLE_[A-Z0-9_]{1,64}");

    private final SharedAuthProperties properties;
    private final Clock clock;

    public SharedAuthTokenService(SharedAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    SharedAuthTokenService(SharedAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String create(String username, List<String> authorities) {
        long expires = Instant.now(clock).plus(properties.getTokenTtl()).getEpochSecond();
        String roles = authorities.stream()
                .filter(role -> AUTHORITY.matcher(role).matches())
                .distinct()
                .limit(16)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String payload = encode(expires + "\n" + encode(username) + "\n" + encode(roles));
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }

    public Optional<SharedIdentity> verify(String token) {
        return diagnoseAndVerify(token).identity();
    }

    public TokenVerification diagnoseAndVerify(String token) {
        Instant now = Instant.now(clock);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return TokenVerification.failure(now, "token-format", false, false);
        }

        final byte[] providedSignature;
        try {
            providedSignature = DECODER.decode(parts[1]);
        } catch (RuntimeException exception) {
            return TokenVerification.failure(now, "signature-format", true, false);
        }

        boolean signatureValid = MessageDigest.isEqual(sign(parts[0]), providedSignature);
        if (!signatureValid) {
            return TokenVerification.failure(now, "signature-invalid", true, false);
        }

        try {
            String[] body = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8).split("\n", -1);
            if (body.length != 3) {
                return TokenVerification.failure(now, "payload-format", true, true);
            }

            Instant expirationTime = Instant.ofEpochSecond(Long.parseLong(body[0]));
            boolean expired = !expirationTime.isAfter(now);
            String username = decode(body[1]);
            boolean usernameExtracted = !username.isBlank() && username.length() <= 200;
            List<String> roles = Arrays.stream(decode(body[2]).split(","))
                    .filter(role -> AUTHORITY.matcher(role).matches())
                    .distinct()
                    .limit(16)
                    .toList();
            boolean authoritiesPresent = !roles.isEmpty();

            if (expired || !usernameExtracted || !authoritiesPresent) {
                String reason = expired ? "expired" : !usernameExtracted ? "username-invalid" : "authorities-empty";
                return new TokenVerification(Optional.empty(), true, true, expired, expirationTime, now,
                        usernameExtracted, authoritiesPresent, reason);
            }

            return new TokenVerification(Optional.of(new SharedIdentity(username, roles)), true, true, false,
                    expirationTime, now, true, true, "valid");
        } catch (RuntimeException exception) {
            return TokenVerification.failure(now, "payload-invalid", true, true);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign the shared authentication token", exception);
        }
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    public record SharedIdentity(String username, List<String> authorities) {
    }

    public record TokenVerification(Optional<SharedIdentity> identity, boolean parseSuccess, boolean signatureValid,
            boolean expired, Instant expirationTime, Instant currentTime, boolean usernameExtracted,
            boolean authoritiesPresent, String failureReason) {
        static TokenVerification failure(Instant now, String reason, boolean parseSuccess, boolean signatureValid) {
            return new TokenVerification(Optional.empty(), parseSuccess, signatureValid, false, null, now, false,
                    false, reason);
        }
    }
}
