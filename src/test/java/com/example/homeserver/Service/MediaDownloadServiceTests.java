package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;

@ExtendWith(MockitoExtension.class)
class MediaDownloadServiceTests {
	@TempDir Path temp;
	@Mock VideoService videoService;
	@Mock FolderService folderService;

	private MediaDownloadService downloads;

	@BeforeEach
	void setUp() {
		downloads = new MediaDownloadService(videoService, folderService);
	}

	@Test
	void videoStreamsOriginalBytesWithAttachmentHeadersAndUtf8Filename() throws Exception {
		Path path = Files.write(temp.resolve("stored.mp4"), new byte[] { 1, 2, 3, 4 });
		Video video = video(1L, "日本語 動画.mp4");
		when(videoService.getVideoById(1L)).thenReturn(video);
		when(videoService.getVideoPath(1L)).thenReturn(path);

		ResponseEntity<StreamingResponseBody> response = downloads.video(1L);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(4, response.getHeaders().getContentLength());
		assertEquals("video/mp4", response.getHeaders().getContentType().toString());
		String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
		assertTrue(disposition.startsWith("attachment;"));
		assertTrue(disposition.contains("filename*="));
		assertArrayEquals(new byte[] { 1, 2, 3, 4 }, stream(response));
	}

	@Test
	void missingVideoOrRejectedPhysicalPathReturns404() throws Exception {
		when(videoService.getVideoById(99L)).thenReturn(null);
		assertEquals(HttpStatus.NOT_FOUND, downloads.video(99L).getStatusCode());

		when(videoService.getVideoById(1L)).thenReturn(video(1L, "outside.mp4"));
		when(videoService.getVideoPath(1L)).thenReturn(null);
		assertEquals(HttpStatus.NOT_FOUND, downloads.video(1L).getStatusCode());
	}

	@Test
	void folderZipStreamsDirectVideosAndDisambiguatesDuplicateNames() throws Exception {
		Folder folder = folder(10L, "旅行 動画");
		Video first = video(1L, "movie.mp4");
		Video second = video(2L, "movie.mp4");
		Path firstPath = Files.write(temp.resolve("one.mp4"), new byte[] { 1 });
		Path secondPath = Files.write(temp.resolve("two.mp4"), new byte[] { 2 });
		when(folderService.getFolderById(10L)).thenReturn(folder);
		when(videoService.getVideosByFolder(10L)).thenReturn(List.of(first, second));
		when(videoService.getVideoPath(1L)).thenReturn(firstPath);
		when(videoService.getVideoPath(2L)).thenReturn(secondPath);

		ResponseEntity<StreamingResponseBody> response = downloads.folder(10L);
		List<ZipFile> files = unzip(stream(response));

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals("application/zip", response.getHeaders().getContentType().toString());
		assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("filename*="));
		assertEquals(List.of("movie.mp4", "movie (2).mp4"), files.stream().map(ZipFile::name).toList());
		assertArrayEquals(new byte[] { 1 }, files.get(0).content());
		assertArrayEquals(new byte[] { 2 }, files.get(1).content());
	}

	@Test
	void zipEntryNamesCannotCreatePaths() throws Exception {
		Folder folder = folder(10L, "../folder\r\n");
		Video video = video(1L, "../../evil.mp4\r\n");
		Path path = Files.write(temp.resolve("stored.mp4"), new byte[] { 1 });
		when(folderService.getFolderById(10L)).thenReturn(folder);
		when(videoService.getVideosByFolder(10L)).thenReturn(List.of(video));
		when(videoService.getVideoPath(1L)).thenReturn(path);

		ResponseEntity<StreamingResponseBody> response = downloads.folder(10L);
		String name = unzip(stream(response)).getFirst().name();

		assertFalse(name.contains("/"));
		assertFalse(name.contains("\\"));
		assertFalse(name.contains("\r"));
		assertFalse(name.contains("\n"));
		assertFalse(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("\r"));
	}

	@Test
	void emptyMissingOrInconsistentFolderHasNoPartialArchive() throws Exception {
		when(folderService.getFolderById(99L)).thenReturn(null);
		assertEquals(HttpStatus.NOT_FOUND, downloads.folder(99L).getStatusCode());

		Folder folder = folder(10L, "empty");
		when(folderService.getFolderById(10L)).thenReturn(folder);
		when(videoService.getVideosByFolder(10L)).thenReturn(List.of());
		assertEquals(HttpStatus.NO_CONTENT, downloads.folder(10L).getStatusCode());

		Video missing = video(1L, "missing.mp4");
		when(videoService.getVideosByFolder(10L)).thenReturn(List.of(missing));
		when(videoService.getVideoPath(1L)).thenReturn(null);
		assertEquals(HttpStatus.NOT_FOUND, downloads.folder(10L).getStatusCode());
	}

	private byte[] stream(ResponseEntity<StreamingResponseBody> response) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		response.getBody().writeTo(output);
		return output.toByteArray();
	}

	private List<ZipFile> unzip(byte[] archive) throws Exception {
		List<ZipFile> files = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				files.add(new ZipFile(entry.getName(), zip.readAllBytes()));
			}
		}
		return files;
	}

	private Video video(Long id, String title) {
		Video video = new Video();
		video.setId(id);
		video.setTitle(title);
		return video;
	}

	private Folder folder(Long id, String name) {
		Folder folder = new Folder();
		folder.setId(id);
		folder.setName(name);
		return folder;
	}

	private record ZipFile(String name, byte[] content) {}
}
