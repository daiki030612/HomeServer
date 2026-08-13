package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class VideoStreamServiceTests {
	@TempDir
	Path temporaryDirectory;

	private final VideoStreamService service = new VideoStreamService();

	@Test
	void servesRequestedRangeWithCompleteRangeHeaders() throws Exception {
		Path video = Files.write(temporaryDirectory.resolve("sample.mp4"),
				new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 });

		ResponseEntity<StreamingResponseBody> response = service.stream(video, "bytes=2-5");

		assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
		assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
		assertEquals("bytes 2-5/10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
		assertEquals(4, response.getHeaders().getContentLength());
		assertArrayEquals(new byte[] { 2, 3, 4, 5 }, body(response));
	}

	@Test
	void supportsOpenEndedAndSuffixRanges() throws Exception {
		Path video = Files.write(temporaryDirectory.resolve("sample.mp4"),
				new byte[] { 0, 1, 2, 3, 4, 5 });

		assertArrayEquals(new byte[] { 4, 5 }, body(service.stream(video, "bytes=4-")));
		assertArrayEquals(new byte[] { 3, 4, 5 }, body(service.stream(video, "bytes=-3")));
	}

	@Test
	void rangeFreeRequestStreamsWholeFileWith200() throws Exception {
		Path video = Files.write(temporaryDirectory.resolve("sample.mp4"),
				new byte[] { 0, 1, 2, 3 });

		ResponseEntity<StreamingResponseBody> response = service.stream(video, null);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(4, response.getHeaders().getContentLength());
		assertArrayEquals(new byte[] { 0, 1, 2, 3 }, body(response));
	}

	@Test
	void invalidAndMultipleRangesReturn416() throws Exception {
		Path video = Files.write(temporaryDirectory.resolve("sample.mp4"), new byte[] { 0, 1, 2 });

		for (String range : new String[] { "bytes=3-4", "bytes=2-1", "bytes=x-y", "bytes=0-1,2-2" }) {
			ResponseEntity<StreamingResponseBody> response = service.stream(video, range);
			assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
			assertEquals("bytes */3", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
		}
	}

	private byte[] body(ResponseEntity<StreamingResponseBody> response) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		response.getBody().writeTo(output);
		return output.toByteArray();
	}
}
