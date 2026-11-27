package com.example.homeserver.Auth;

import com.example.homeserver.Config.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real Tomcat multipart test; run with -DlargeUploadTests=true and -Xmx256m. */
@EnabledIfSystemProperty(named = "largeUploadTests", matches = "true")
@SpringBootTest(classes = LargeDirectUploadTests.App.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class LargeDirectUploadTests {
    static final int PORT = freePort();
    static final long FILE_SIZE = 4L * 1024 * 1024 * 1024 + 17;
    static final Path ROOT = createRoot();
    static int freePort() { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException e) { throw new UncheckedIOException(e); } }
    static Path createRoot() { try { return Files.createTempDirectory(Path.of("target"), "large-upload-it-").toAbsolutePath(); }
        catch (IOException e) { throw new UncheckedIOException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("video-auth.direct-origin", () -> "http://127.0.0.1:" + PORT);
        registry.add("navigation.video.url", () -> "");
        registry.add("server.forward-headers-strategy", () -> "framework");
        registry.add("spring.servlet.multipart.location", () -> ROOT.toString());
        registry.add("shared-auth.secret", () -> "test-only-shared-auth-secret-32-characters-minimum");
        registry.add("shared-auth.cookie-secure", () -> "true");
        registry.add("shared-auth.cookie-domain", () -> "media.example.com");
    }
    @org.springframework.boot.test.context.TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class})
    @Import({SecurityConfig.class, Endpoints.class})
    static class App {
        @Bean UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("existing-user")
                    .password(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test-password"))
                    .roles("USER").build());
        }
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class Endpoints {
        @GetMapping("/login") Map<String,String> login(HttpServletRequest request) {
            CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            return Map.of("header", csrf.getHeaderName(), "token", csrf.getToken());
        }
        @GetMapping("/videos") String videos() { return "library"; }
        @PostMapping("/videos/upload") long upload(@RequestParam MultipartFile file) throws IOException {
            Path destination = ROOT.resolve("stored-video");
            try {
                file.transferTo(destination.toFile());
                assertThat(Files.size(destination)).isEqualTo(FILE_SIZE);
                return file.getSize();
            } finally { Files.deleteIfExists(destination); }
        }
    }
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path)).timeout(Duration.ofMinutes(10));
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private Map<String,String> csrf() throws Exception {
        var response = send(request("/login").GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper().readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    @Test void streamsMoreThanFourGiBWithHostOnlySessionUnderSmallHeap() throws Exception {
        assertThat(Files.getFileStore(ROOT).getUsableSpace()).isGreaterThan(FILE_SIZE * 2);
        var challenge = send(request("/videos?folderId=42").GET().build());
        assertThat(challenge.statusCode()).isEqualTo(302);
        assertThat(challenge.headers().firstValue("location")).hasValue("http://127.0.0.1:" + PORT + "/login");
        String setCookie = challenge.headers().firstValue("set-cookie").orElseThrow();
        assertThat(setCookie).startsWith("VIDEO_SESSION=").contains("HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure");
        var token = csrf();
        var login = send(request("/login").header(token.get("header"), token.get("token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=existing-user&password=test-password")).build());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("location")).hasValue("http://127.0.0.1:" + PORT + "/videos?folderId=42");
        assertThat(login.headers().allValues("set-cookie")).noneMatch(value -> value.contains("MEDIA_AUTH"));
        assertThat(send(request("/videos").GET().build()).statusCode()).isEqualTo(200);
        token = csrf();
        byte[] prefix = ("--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\r\n--test-boundary--\r\n".getBytes(StandardCharsets.US_ASCII);
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(
                Collections.enumeration(List.of(new ByteArrayInputStream(prefix), new ZeroInput(FILE_SIZE),
                        new ByteArrayInputStream(suffix)))));
        var upload = send(request("/videos/upload").header(token.get("header"), token.get("token"))
                .header("Content-Type", "multipart/form-data; boundary=test-boundary")
                .POST(HttpRequest.BodyPublishers.fromPublisher(publisher, FILE_SIZE + prefix.length + suffix.length)).build());
        assertThat(upload.statusCode()).isEqualTo(200);
        assertThat(upload.body()).isEqualTo(Long.toString(FILE_SIZE));
        try (var files = Files.list(ROOT)) { assertThat(files.toList()).isEmpty(); }
        System.out.println("REAL_MULTIPART bytes=" + FILE_SIZE + " maxHeap=" + Runtime.getRuntime().maxMemory()
                + " temporaryFilesAfterRequest=0");
        var logout = send(request("/logout").header(token.get("header"), token.get("token"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(send(request("/videos").GET().build()).statusCode()).isEqualTo(302);
    }
    @Test void rejectsForwardedOriginAndPrefixEvenWhenFrameworkRemovesHeaders() throws Exception {
        for (String header : List.of("X-Forwarded-Host", "X-Forwarded-Prefix")) {
            String value = header.equals("X-Forwarded-Host") ? "attacker.example" : "//attacker.example";
            var response = send(request("/videos").header(header, value).GET().build());
            assertThat(response.statusCode()).as(header).isEqualTo(400);
            assertThat(response.headers().firstValue("location")).isEmpty();
        }
    }
    @AfterAll static void cleanup() throws IOException { Files.deleteIfExists(ROOT); }
    static class ZeroInput extends InputStream {
        private long remaining;
        ZeroInput(long size) { remaining = size; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return 0; }
        @Override public int read(byte[] b, int offset, int length) {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            Arrays.fill(b, offset, offset + count, (byte)0);
            remaining -= count;
            return count;
        }
    }
}
