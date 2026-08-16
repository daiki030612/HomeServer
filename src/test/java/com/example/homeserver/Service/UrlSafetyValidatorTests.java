package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

class UrlSafetyValidatorTests {
	private final UrlSafetyValidator validator = new UrlSafetyValidator();

	@Test
	void acceptsPublicHttpAndHttpsAddresses() {
		assertEquals(URI.create("https://93.184.216.34/video.mp4"),
				validator.validate("https://93.184.216.34/video.mp4"));
	}

	@Test
	void rejectsNonHttpSchemesAndMissingHosts() {
		assertInvalid("file:///etc/passwd");
		assertInvalid("ftp://93.184.216.34/video.mp4");
		assertInvalid("not-a-url");
	}

	@Test
	void rejectsLoopbackPrivateLinkLocalMetadataAndCarrierGradeNat() {
		for (String url : new String[] {
				"http://localhost/video", "http://127.0.0.1/video", "http://127.9.8.7/video",
				"http://10.0.0.1/video", "http://172.16.0.1/video", "http://192.168.1.1/video",
				"http://169.254.169.254/latest/meta-data", "http://100.64.0.1/video",
				"http://[::1]/video", "http://[fc00::1]/video", "http://[fd00::1]/video" }) {
			assertInvalid(url);
		}
	}

	private void assertInvalid(String url) {
		VideoUrlImportException exception = assertThrows(VideoUrlImportException.class,
				() -> validator.validate(url));
		assertEquals(VideoUrlImportException.Reason.INVALID_URL, exception.getReason());
	}
}
