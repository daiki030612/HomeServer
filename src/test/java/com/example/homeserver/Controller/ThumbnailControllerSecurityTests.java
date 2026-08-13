package com.example.homeserver.Controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class ThumbnailControllerSecurityTests {
	@TempDir
	Path temporaryDirectory;

	@Test
	void rejectsTraversalOutsideThumbnailRoot() throws Exception {
		Path root = Files.createDirectory(temporaryDirectory.resolve("thumbnails"));
		Files.write(temporaryDirectory.resolve("outside.jpg"), new byte[] { 1 });
		ThumbnailController controller = new ThumbnailController();
		ReflectionTestUtils.setField(controller, "thumbnailPath", root.toString());

		assertEquals(HttpStatus.NOT_FOUND,
				controller.getThumbnail("..\\outside.jpg").getStatusCode());
	}
}
