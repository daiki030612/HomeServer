package com.example.homeserver.Controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.homeserver.Service.MediaDownloadService;

@Controller
public class MediaDownloadController {
	private final MediaDownloadService downloadService;

	public MediaDownloadController(MediaDownloadService downloadService) {
		this.downloadService = downloadService;
	}

	@GetMapping("/videos/{id}/download")
	public ResponseEntity<StreamingResponseBody> downloadVideo(@PathVariable Long id)
			throws IOException {
		return downloadService.video(id);
	}

	@GetMapping("/folders/{id}/download")
	public ResponseEntity<StreamingResponseBody> downloadFolder(@PathVariable Long id)
			throws IOException {
		return downloadService.folder(id);
	}
}
