package com.example.homeserver.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;

@Service
public class MediaDownloadService {
	private static final Logger logger = LoggerFactory.getLogger(MediaDownloadService.class);
	private static final int BUFFER_SIZE = 64 * 1024;

	private final VideoService videoService;
	private final FolderService folderService;

	public MediaDownloadService(VideoService videoService, FolderService folderService) {
		this.videoService = videoService;
		this.folderService = folderService;
	}

	public ResponseEntity<StreamingResponseBody> video(Long id) throws IOException {
		Video video = videoService.getVideoById(id);
		Path path = videoService.getVideoPath(id);
		if (video == null || path == null) {
			return ResponseEntity.notFound().build();
		}

		String filename = videoFilename(video, path);
		StreamingResponseBody body = output -> {
			logger.info("VIDEO_DOWNLOAD_STARTED videoId={}", id);
			try {
				copy(path, output);
			} catch (IOException e) {
				logStreamFailure("VIDEO_DOWNLOAD_FAILED", "videoId", id, e);
				throw e;
			}
		};

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
				.contentType(mediaType(path))
				.contentLength(Files.size(path))
				.body(body);
	}

	public ResponseEntity<StreamingResponseBody> folder(Long id) throws IOException {
		Folder folder = folderService.getFolderById(id);
		if (folder == null) {
			return ResponseEntity.notFound().build();
		}

		List<Video> videos = videoService.getVideosByFolder(id);
		if (videos.isEmpty()) {
			return ResponseEntity.noContent().build();
		}

		List<DownloadEntry> entries = new ArrayList<>(videos.size());
		Map<String, Integer> usedNames = new HashMap<>();
		for (Video video : videos) {
			Path path = videoService.getVideoPath(video.getId());
			if (path == null) {
				return ResponseEntity.notFound().build();
			}
			String name = uniqueName(videoFilename(video, path), usedNames);
			entries.add(new DownloadEntry(path, name));
		}

		StreamingResponseBody body = output -> {
			logger.info("FOLDER_DOWNLOAD_STARTED folderId={} videoCount={}", id, entries.size());
			try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
				for (DownloadEntry entry : entries) {
					zip.putNextEntry(new ZipEntry(entry.name()));
					copy(entry.path(), zip);
					zip.closeEntry();
				}
				zip.finish();
			} catch (IOException e) {
				logStreamFailure("FOLDER_DOWNLOAD_FAILED", "folderId", id, e);
				throw e;
			}
		};

		String zipName = safeName(folder.getName(), "folder-" + id) + ".zip";
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, attachment(zipName))
				.contentType(MediaType.parseMediaType("application/zip"))
				.body(body);
	}

	private void copy(Path path, OutputStream output) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) output.write(buffer, 0, read);
			}
		}
	}

	private String videoFilename(Video video, Path path) {
		String fallback = "video-" + video.getId() + extension(path.getFileName().toString());
		String name = safeName(video.getTitle(), fallback);
		if (!name.contains(".")) name += extension(path.getFileName().toString());
		return name;
	}

	private String safeName(String candidate, String fallback) {
		if (candidate == null || candidate.isBlank()) return fallback;
		String safe = candidate.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
		safe = safe.replaceAll("^\\.+$", "");
		return safe.isBlank() ? fallback : safe;
	}

	private String uniqueName(String name, Map<String, Integer> usedNames) {
		String key = name.toLowerCase(Locale.ROOT);
		int occurrence = usedNames.merge(key, 1, Integer::sum);
		if (occurrence == 1) return name;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String extension = dot > 0 ? name.substring(dot) : "";
		String candidate = base + " (" + occurrence + ")" + extension;
		while (usedNames.putIfAbsent(candidate.toLowerCase(Locale.ROOT), 1) != null) {
			occurrence++;
			candidate = base + " (" + occurrence + ")" + extension;
		}
		return candidate;
	}

	private String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(dot) : "";
	}

	private MediaType mediaType(Path path) {
		try {
			return Optional.ofNullable(Files.probeContentType(path))
					.map(MediaType::parseMediaType)
					.orElse(MediaType.APPLICATION_OCTET_STREAM);
		} catch (Exception e) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	private String attachment(String filename) {
		return ContentDisposition.attachment()
				.filename(filename, StandardCharsets.UTF_8)
				.build().toString();
	}

	private void logStreamFailure(String event, String idName, Long id, IOException error) {
		String message = Optional.ofNullable(error.getMessage()).orElse("").toLowerCase(Locale.ROOT);
		if (message.contains("broken pipe") || message.contains("connection reset")
				|| message.contains("abort")) {
			logger.debug("{} {}={} clientDisconnected=true", event, idName, id);
		} else {
			logger.warn("{} {}={} errorType={}", event, idName, id,
					error.getClass().getSimpleName());
		}
	}

	private record DownloadEntry(Path path, String name) {}
}
