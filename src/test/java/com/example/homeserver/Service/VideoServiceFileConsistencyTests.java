package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.FolderRepository;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Repository.VideoRepository;

@ExtendWith(MockitoExtension.class)
class VideoServiceFileConsistencyTests {

	@TempDir
	Path temporaryDirectory;

	@Mock
	VideoRepository videoRepository;

	@Mock
	ThumbnailService thumbnailService;

	@Mock
	VideoProbeService videoProbeService;

	@Mock
	TagRepository tagRepository;

	@Mock
	FolderRepository folderRepository;

	@Mock
	MultipartFile multipartFile;

	private VideoService videoService;
	private Path videoRoot;
	private Path thumbnailRoot;

	@BeforeEach
	void setUp() throws Exception {
		videoRoot = Files.createDirectory(temporaryDirectory.resolve("videos"));
		thumbnailRoot = Files.createDirectory(temporaryDirectory.resolve("thumbnails"));

		videoService = new VideoService();
		ReflectionTestUtils.setField(videoService, "videoRepository", videoRepository);
		ReflectionTestUtils.setField(videoService, "thumbnailService", thumbnailService);
		ReflectionTestUtils.setField(videoService, "videoProbeService", videoProbeService);
		ReflectionTestUtils.setField(videoService, "tagRepository", tagRepository);
		ReflectionTestUtils.setField(videoService, "folderRepository", folderRepository);
		ReflectionTestUtils.setField(videoService, "videoStoragePath", videoRoot.toString());
		ReflectionTestUtils.setField(videoService, "thumbnailStoragePath", thumbnailRoot.toString());

	}

	@Test
	void uploadRemovesVideoAndThumbnailWhenDatabaseSaveFails() throws Exception {
		configureMultipartFile();
		when(thumbnailService.createThumbnail(any(String.class), any(String.class)))
				.thenAnswer(invocation -> {
					Path thumbnail = Path.of(invocation.getArgument(1, String.class));
					Files.write(thumbnail, new byte[] { 4, 5, 6 });
					return thumbnail.toString();
				});
		when(videoRepository.saveAndFlush(any(Video.class)))
				.thenThrow(new IllegalStateException("database unavailable"));

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));

		assertDirectoryHasNoFiles(videoRoot);
		assertDirectoryHasNoFiles(thumbnailRoot);
	}

	@Test
	void uploadRemovesPartialFilesWhenThumbnailGenerationFails() throws Exception {
		configureMultipartFile();
		when(thumbnailService.createThumbnail(any(String.class), any(String.class)))
				.thenAnswer(invocation -> {
					Files.write(Path.of(invocation.getArgument(1, String.class)), new byte[] { 9 });
					throw new IllegalStateException("ffmpeg failed");
				});

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));

		assertDirectoryHasNoFiles(videoRoot);
		assertDirectoryHasNoFiles(thumbnailRoot);
		verify(videoRepository, never()).saveAndFlush(any(Video.class));
	}

	@Test
	void rejectedVideoIsRemovedBeforeThumbnailOrDatabaseWork() throws Exception {
		configureMultipartFile();
		when(videoProbeService.probe(any(Path.class), any(Path.class)))
				.thenThrow(new InvalidVideoFileException());

		InvalidVideoFileException exception = assertThrows(
				InvalidVideoFileException.class, () -> videoService.upload(multipartFile, null));

		org.junit.jupiter.api.Assertions.assertEquals(
				InvalidVideoFileException.USER_MESSAGE, exception.getMessage());
		assertDirectoryHasNoFiles(videoRoot);
		verify(thumbnailService, never()).createThumbnail(any(String.class), any(String.class));
		verify(videoRepository, never()).saveAndFlush(any(Video.class));
	}

	@Test
	void uploadRemovesPartialVideoWhenTransferFails() throws Exception {
		when(multipartFile.isEmpty()).thenReturn(false);
		when(multipartFile.getOriginalFilename()).thenReturn("sample.mp4");
		doAnswer(invocation -> {
			Path destination = invocation.getArgument(0, java.io.File.class).toPath();
			Files.write(destination, new byte[] { 1 });
			throw new java.io.IOException("transfer failed");
		}).when(multipartFile).transferTo(any(java.io.File.class));

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));

		assertDirectoryHasNoFiles(videoRoot);
		verify(thumbnailService, never()).createThumbnail(any(String.class), any(String.class));
		verify(videoRepository, never()).saveAndFlush(any(Video.class));
	}

	@Test
	void deleteDoesNotRemoveDatabaseRecordWhenPhysicalDeletionFails() throws Exception {
		Path nonEmptyDirectory = Files.createDirectory(videoRoot.resolve("cannot-delete"));
		Files.write(nonEmptyDirectory.resolve("child"), new byte[] { 1 });
		Video video = video(nonEmptyDirectory, thumbnailRoot.resolve("thumbnail.jpg"));
		when(videoRepository.findById(1L)).thenReturn(Optional.of(video));

		assertThrows(IllegalStateException.class, () -> videoService.deleteVideo(1L));

		verify(videoRepository, never()).delete(video);
	}

	@Test
	void deleteRejectsPathOutsideConfiguredStorage() throws Exception {
		Path outside = Files.write(temporaryDirectory.resolve("outside.mp4"), new byte[] { 1 });
		Video video = video(outside, thumbnailRoot.resolve("thumbnail.jpg"));
		when(videoRepository.findById(1L)).thenReturn(Optional.of(video));

		assertThrows(IllegalArgumentException.class, () -> videoService.deleteVideo(1L));

		assertFalse(Files.notExists(outside));
		verify(videoRepository, never()).delete(video);
	}

	private Video video(Path videoPath, Path thumbnailPath) {
		Video video = new Video();
		video.setId(1L);
		video.setFileName(videoPath.getFileName().toString());
		video.setThumbnailName(thumbnailPath.getFileName().toString());
		video.setFilePath(videoPath.toString());
		video.setThumbnailPath(thumbnailPath.toString());
		return video;
	}

	private void configureMultipartFile() throws Exception {
		when(multipartFile.isEmpty()).thenReturn(false);
		when(multipartFile.getOriginalFilename()).thenReturn("sample.mp4");
		doAnswer(invocation -> {
			Path destination = invocation.getArgument(0, java.io.File.class).toPath();
			Files.write(destination, new byte[] { 1, 2, 3 });
			return null;
		}).when(multipartFile).transferTo(any(java.io.File.class));
	}

	private void assertDirectoryHasNoFiles(Path directory) throws Exception {
		try (var entries = Files.list(directory)) {
			assertFalse(entries.anyMatch(Files::isRegularFile));
		}
	}
}
