package com.example.homeserver.Controller;

import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.CustomThumbnailService;
import com.example.homeserver.Service.InvalidThumbnailException;

@Controller
@RequestMapping("/videos/{id}/thumbnail")
public class VideoThumbnailController {
	private final CustomThumbnailService thumbnailService;

	public VideoThumbnailController(CustomThumbnailService thumbnailService) {
		this.thumbnailService = thumbnailService;
	}

	@GetMapping
	public ResponseEntity<FileSystemResource> display(@PathVariable Long id) {
		var thumbnail = thumbnailService.resolve(id);
		if (thumbnail == null) return ResponseEntity.notFound().build();
		MediaType contentType;
		try {
			contentType = MediaType.parseMediaType(thumbnail.contentType());
		} catch (Exception ignored) {
			contentType = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noCache().mustRevalidate())
				.contentType(contentType)
				.contentLength(thumbnail.path().toFile().length())
				.body(new FileSystemResource(thumbnail.path()));
	}

	@PostMapping
	public ResponseEntity<?> upload(@PathVariable Long id,
			@RequestParam("thumbnail") MultipartFile thumbnail) {
		Video video = thumbnailService.upload(id, thumbnail);
		if (video == null) return ResponseEntity.notFound().build();
		return ResponseEntity.ok(response(video));
	}

	@PostMapping("/reset")
	public ResponseEntity<?> reset(@PathVariable Long id) {
		Video video = thumbnailService.reset(id);
		if (video == null) return ResponseEntity.notFound().build();
		return ResponseEntity.ok(response(video));
	}

	@ExceptionHandler(InvalidThumbnailException.class)
	public ResponseEntity<Map<String, String>> invalidThumbnail(InvalidThumbnailException error) {
		return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
	}

	private ThumbnailResponse response(Video video) {
		return new ThumbnailResponse(video.getId(), video.getCustomThumbnailVersion(),
				video.getCustomThumbnailName() != null);
	}

	private record ThumbnailResponse(Long id, Long version, boolean custom) { }
}
