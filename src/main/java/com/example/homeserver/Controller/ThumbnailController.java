package com.example.homeserver.Controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/thumbnails")
public class ThumbnailController {

	@Value("${thumbnail.storage.path}")
	private String thumbnailPath;

	@GetMapping("/{fileName}")
	public ResponseEntity<Resource> getThumbnail(
			@PathVariable String fileName) {

		Path root = Paths.get(thumbnailPath).toAbsolutePath().normalize();
		Path path = root.resolve(fileName).toAbsolutePath().normalize();
		if (!path.startsWith(root)) {
			return ResponseEntity.notFound().build();
		}

		try {
			if (Files.exists(path) && !path.toRealPath().startsWith(root.toRealPath())) {
				return ResponseEntity.notFound().build();
			}
		} catch (java.io.IOException e) {
			return ResponseEntity.notFound().build();
		}

		Resource resource = new FileSystemResource(path);

		if (resource.exists()) {

			return ResponseEntity.ok()
					.contentType(MediaType.IMAGE_JPEG)
					.body(resource);

		}

		return ResponseEntity.notFound().build();
	}
	
	
}
