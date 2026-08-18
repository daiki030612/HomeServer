package com.example.homeserver.Controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ContextPathUrlTests {
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path HEADER = Path.of("src/main/resources/templates/fragments/header.html");
    private static final Path LIST = Path.of("src/main/resources/templates/video/list.html");
    private static final Path UPLOAD = Path.of("src/main/resources/templates/video/upload.html");
    private static final Path TEST_PAGE = Path.of("src/main/resources/templates/test.html");
    private static final Path LIST_SCRIPT = Path.of("src/main/resources/static/js/video/video-list.js");
    private static final Path UPLOAD_SCRIPT = Path.of("src/main/resources/static/js/video/upload.js");
    private static final Path APP_NAVIGATION_SCRIPT = Path.of("src/main/resources/static/js/app-navigation.js");
    private static final Path HEADER_MENU_SCRIPT = Path.of("src/main/resources/static/js/header-menu.js");

    @Test
    void videoServerIsDeployedAtRootByDefault() throws IOException {
        assertFalse(Files.readString(PROPERTIES).contains("server.servlet.context-path="));
    }

    @Test
    void thymeleafGeneratesInternalVideoUrls() throws IOException {
        String header = Files.readString(HEADER);
        String list = Files.readString(LIST);
        String upload = Files.readString(UPLOAD);
        String testPage = Files.readString(TEST_PAGE);

        assertTrue(header.contains("th:href=\"@{/videos}\""));
		assertTrue(list.contains("class=\"gallery-filters video-filters\" method=\"get\""));
        assertFalse(header.contains("href=\"/videos\""));
        assertFalse(header.contains("action=\"/videos\""));
        assertTrue(list.contains("name=\"video-move-url\" th:content=\"@{/videos/move}\""));
        assertTrue(list.contains("th:href=\"@{/videos}\" class=\"breadcrumb-root\""));
        assertTrue(upload.contains("id=\"upload-form\" th:action=\"@{/videos/upload}\""));
        assertTrue(testPage.contains("th:src=\"@{/thumbnails/"));
        assertFalse(testPage.contains("src=\"/thumbnails/"));
    }

    @Test
    void javascriptUsesTemplateResolvedEndpoints() throws IOException {
        String listScript = Files.readString(LIST_SCRIPT);
        String uploadScript = Files.readString(UPLOAD_SCRIPT);

        assertFalse(listScript.contains("fetch(\"/videos/"));
        assertTrue(listScript.contains("return fetch(getVideoMoveUrl(),"));
        assertFalse(uploadScript.contains("\"/videos/upload\""));
        assertFalse(uploadScript.contains("window.location.href = \"/videos"));
        assertTrue(uploadScript.contains("form.action"));
    }

	@Test
	void desktopAndMobileAppLinksUseSameWebViewNavigation() throws IOException {
		String header = Files.readString(HEADER);
		String navigationScript = Files.readString(APP_NAVIGATION_SCRIPT);
		String menuScript = Files.readString(HEADER_MENU_SCRIPT);

		assertTrue(header.contains("class=\"app-switch-link\" data-app-target=\"image\""));
		assertTrue(header.contains("<a data-app-target=\"image\""));
		assertTrue(navigationScript.contains("querySelectorAll('[data-app-target]')"));
		assertTrue(navigationScript.contains("link.href = target.href"));
		assertFalse(navigationScript.contains("window.open"));
		assertFalse(navigationScript.contains("preventDefault"));
		assertFalse(menuScript.contains("preventDefault"));
	}
}
