package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeUrlHttpClientRequestContextTests {
	@TempDir
	Path directory;

	@Test
	void sendsOnlyTypedHeadersAndKeepsThemAcrossRedirects() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI initial = URI.create("https://media.example/master.m3u8?token=secret");
		URI redirected = URI.create("https://cdn.example/master.m3u8?token=other-secret");
		URI referer = URI.create("https://missav.live/ja/ipzz-640");
		when(validator.validate(initial)).thenReturn(initial);
		when(validator.validate(redirected)).thenReturn(redirected);
		ArrayDeque<HttpResponse<java.io.InputStream>> responses = new ArrayDeque<>();
		responses.add(response(302, Map.of("Location", List.of(redirected.toString())), ""));
		responses.add(response(200, Map.of("Content-Type", List.of("application/vnd.apple.mpegurl")), "#EXTM3U"));
		List<HttpRequest> requests = new ArrayList<>();
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0, HttpRequest.class));
			return responses.removeFirst();
		});
		VideoSourceRequestContext context = new VideoSourceRequestContext("Test Browser/1.0", referer);

		new SafeUrlHttpClient(validator, client, "Default Agent/1.0").getText(initial, 1024,
				context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);

		assertEquals(2, requests.size());
		for (HttpRequest request : requests) {
			assertEquals("Test Browser/1.0", request.headers().firstValue("User-Agent").orElseThrow());
			assertEquals(referer.toString(), request.headers().firstValue("Referer").orElseThrow());
			assertEquals("application/vnd.apple.mpegurl,application/x-mpegURL;q=0.9,*/*;q=0.5",
					request.headers().firstValue("Accept").orElseThrow());
			assertEquals(3, request.headers().map().size());
		}
		verify(validator).validate(initial);
		verify(validator).validate(redirected);
	}

	@Test
	void ordinaryDownloadUsesConfiguredAgentWithoutReferer() throws Exception {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		HttpClient client = mock(HttpClient.class);
		URI media = URI.create("https://media.example/video.mp4");
		when(validator.validate(media)).thenReturn(media);
		List<HttpRequest> requests = new ArrayList<>();
		when(client.send(any(HttpRequest.class), anyInputStreamHandler())).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0, HttpRequest.class));
			return response(200, Map.of("Content-Type", List.of("video/mp4")), "video");
		});

		new SafeUrlHttpClient(validator, client, "Configured Agent/2.0")
				.download(media, directory.resolve("video.mp4"), 1024);

		HttpRequest request = requests.getFirst();
		assertEquals("Configured Agent/2.0", request.headers().firstValue("User-Agent").orElseThrow());
		assertFalse(request.headers().firstValue("Referer").isPresent());
	}

	@Test
	void rejectsHeaderLineInjection() {
		assertThrows(IllegalArgumentException.class,
				() -> new VideoSourceRequestContext("Browser/1.0\r\nX-Injected: yes", URI.create("https://example.com")));
		assertThrows(IllegalArgumentException.class,
				() -> new SafeUrlHttpClient(mock(UrlSafetyValidator.class), mock(HttpClient.class),
						"Browser/1.0\nX-Injected: yes"));
	}

	@SuppressWarnings("unchecked")
	private HttpResponse.BodyHandler<java.io.InputStream> anyInputStreamHandler() {
		return any(HttpResponse.BodyHandler.class);
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<java.io.InputStream> response(int status, Map<String, List<String>> headers, String body) {
		HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
		when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
		return response;
	}
}
