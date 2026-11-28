package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
	CustomThumbnailService customThumbnailService;

	@Mock
	VideoProbeService videoProbeService;

	@Mock
	IPhoneSafariCompatibilityService compatibilityService;

	@Mock
	VideoRemuxService videoRemuxService;

	@Mock
	VideoAudioTranscodeService videoAudioTranscodeService;

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
		ReflectionTestUtils.setField(videoService, "customThumbnailService", customThumbnailService);
		ReflectionTestUtils.setField(videoService, "videoProbeService", videoProbeService);
		ReflectionTestUtils.setField(videoService, "compatibilityService", compatibilityService);
		ReflectionTestUtils.setField(videoService, "videoRemuxService", videoRemuxService);
		ReflectionTestUtils.setField(videoService, "videoAudioTranscodeService", videoAudioTranscodeService);
		ReflectionTestUtils.setField(videoService, "tagRepository", tagRepository);
		ReflectionTestUtils.setField(videoService, "folderRepository", folderRepository);
		ReflectionTestUtils.setField(videoService, "videoStoragePath", videoRoot.toString());
		ReflectionTestUtils.setField(videoService, "thumbnailStoragePath", thumbnailRoot.toString());
		org.mockito.Mockito.lenient().when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.PASSTHROUGH);
		org.mockito.Mockito.lenient().when(videoProbeService.probe(any(Path.class), any(Path.class)))
				.thenReturn(compatibleMetadata());

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
	void passthroughDoesNotCallFfmpeg() throws Exception {
		configureMultipartFile();
		configureSuccessfulThumbnail();
		when(compatibilityService.evaluate(any())).thenReturn(VideoCompatibilityDecision.PASSTHROUGH);

		videoService.upload(multipartFile, null);

		verify(videoRemuxService, never()).remux(any(), any(), any(), anyInt(), any());
		try (var files = Files.list(videoRoot)) {
			org.junit.jupiter.api.Assertions.assertEquals(1, files.count());
		}
	}

	@Test
	void downloadedMp4UsesExistingUploadThumbnailAndDatabasePipeline() throws Exception {
		Path downloaded = Files.write(temporaryDirectory.resolve("downloaded.mp4"), new byte[] { 1, 2, 3 });
		configureSuccessfulThumbnail();

		videoService.importDownloadedVideo(downloaded, "Page: title?", null);

		verify(thumbnailService).createThumbnail(any(String.class), any(String.class));
		verify(videoRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(video ->
				video.getTitle().equals("Page_ title_.mp4")
						&& video.getFileName().endsWith(".mp4")
						&& video.getThumbnailName().endsWith(".jpg")));
	}

	@Test
	void remuxDecisionProducesMp4AndRemovesMovOriginal() throws Exception {
		configureMultipartFile("sample.mov");
		configureSuccessfulThumbnail();
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.REMUX, VideoCompatibilityDecision.PASSTHROUGH);
		doAnswer(invocation -> {
			Files.write(invocation.getArgument(1, Path.class), new byte[] { 7, 8, 9 });
			return null;
		}).when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());

		videoService.upload(multipartFile, null);

		try (var files = Files.list(videoRoot)) {
			List<Path> remaining = files.toList();
			org.junit.jupiter.api.Assertions.assertEquals(1, remaining.size());
			org.junit.jupiter.api.Assertions.assertTrue(
					remaining.getFirst().getFileName().toString().endsWith(".mp4"));
		}
	}

	@Test
	void fastStartMissingMp4UsesSameRemuxPath() throws Exception {
		configureMultipartFile("sample.mp4");
		configureSuccessfulThumbnail();
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.REMUX, VideoCompatibilityDecision.PASSTHROUGH);
		doAnswer(invocation -> {
			Files.write(invocation.getArgument(1, Path.class), new byte[] { 7 });
			return null;
		}).when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());

		videoService.upload(multipartFile, null);

		verify(videoRemuxService).remux(any(), any(), any(), anyInt(), any());
	}

	@Test
	void remuxOutputThatIsNotPassthroughIsFullyRemoved() throws Exception {
		configureMultipartFile("sample.mov");
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.REMUX, VideoCompatibilityDecision.REMUX);
		doAnswer(invocation -> {
			Files.write(invocation.getArgument(1, Path.class), new byte[] { 7 });
			return null;
		}).when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));

		assertDirectoryHasNoFiles(videoRoot);
		verify(videoRepository, never()).saveAndFlush(any());
	}

	@Test
	void remuxFailureRemovesOriginalAndPartialOutput() throws Exception {
		configureMultipartFile("sample.mov");
		when(compatibilityService.evaluate(any())).thenReturn(VideoCompatibilityDecision.REMUX);
		doAnswer(invocation -> {
			Files.write(invocation.getArgument(1, Path.class), new byte[] { 7 });
			throw new IllegalStateException("ffmpeg exit 1");
		}).when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));
		assertDirectoryHasNoFiles(videoRoot);
	}

	@Test
	void remuxTimeoutRemovesOriginalAndPartialOutput() throws Exception {
		configureMultipartFile("sample.mov");
		when(compatibilityService.evaluate(any())).thenReturn(VideoCompatibilityDecision.REMUX);
		doThrow(new FfmpegProcessRunner.FfmpegTimeoutException("timeout"))
				.when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));
		assertDirectoryHasNoFiles(videoRoot);
	}

	@Test
	void remuxDatabaseFailureRemovesEveryGeneratedArtifact() throws Exception {
		configureMultipartFile("sample.mov");
		configureSuccessfulThumbnail();
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.REMUX, VideoCompatibilityDecision.PASSTHROUGH);
		doAnswer(invocation -> {
			Files.write(invocation.getArgument(1, Path.class), new byte[] { 7 });
			return null;
		}).when(videoRemuxService).remux(any(), any(), any(), anyInt(), any());
		when(videoRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("database"));

		assertThrows(RuntimeException.class, () -> videoService.upload(multipartFile, null));
		assertDirectoryHasNoFiles(videoRoot);
		assertDirectoryHasNoFiles(thumbnailRoot);
	}

	@Test
	void videoTranscodeDecisionRemainsUnsupportedAndNeverCallsConverters() throws Exception {
		configureMultipartFile();
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.TRANSCODE_VIDEO);

		UnsupportedVideoConversionException exception = assertThrows(
				UnsupportedVideoConversionException.class,
				() -> videoService.upload(multipartFile, null));

		org.junit.jupiter.api.Assertions.assertEquals(
				UnsupportedVideoConversionException.USER_MESSAGE, exception.getMessage());
		verify(videoRemuxService, never()).remux(any(), any(), any(), anyInt(), any());
		verify(videoAudioTranscodeService, never()).transcodeAudio(any(), any(), any());
		assertDirectoryHasNoFiles(videoRoot);
	}

	@Test
	void bothTranscodeDecisionRemainsUnsupportedAndNeverCallsConverters() throws Exception {
		configureMultipartFile();
		when(compatibilityService.evaluate(any()))
				.thenReturn(VideoCompatibilityDecision.TRANSCODE_BOTH);

		assertThrows(UnsupportedVideoConversionException.class,
				() -> videoService.upload(multipartFile, null));

		verify(videoRemuxService, never()).remux(any(), any(), any(), anyInt(), any());
		verify(videoAudioTranscodeService, never()).transcodeAudio(any(), any(), any());
		assertDirectoryHasNoFiles(videoRoot);
	}

    @Test
    void timingValidationRejectsSlowMotionAndDurationChanges() {
        var source = IPhoneSafariCompatibilityServiceTests.metadata("av1", "mov,mp4", "mp4", 120, true);
        var slow = IPhoneSafariCompatibilityServiceTests.metadata("av1", "mov,mp4", "mp4", 30, true);
        assertThrows(VideoUploadException.class, () -> VideoService.validateRemuxTiming(source, slow));
        var c = source.container();
        var longer = new VideoMetadata(new ContainerMetadata(c.formatName(), c.formatLongName(),
                c.majorBrand(), c.fileExtension(), c.durationSeconds() * 4, 0, c.sizeBytes(), c.bitrate(),
                c.fastStart()), source.videoStreams(), source.audioStreams());
        assertThrows(VideoUploadException.class, () -> VideoService.validateRemuxTiming(source, longer));
    }

    @Test
    void audioTranscodeDecisionIsRejectedWithoutCallingAnyEncoder() throws Exception {
        configureMultipartFile("sample.mkv");
        when(compatibilityService.evaluate(any())).thenReturn(VideoCompatibilityDecision.TRANSCODE_AUDIO);
        assertThrows(UnsupportedVideoConversionException.class, () -> videoService.upload(multipartFile, null));
        verify(videoAudioTranscodeService, never()).transcodeAudio(any(), any(), any());
        verify(videoRemuxService, never()).remux(any(), any(), any(), anyInt(), any());
        assertDirectoryHasNoFiles(videoRoot);
    }

    @Test
    void oversizedFileIsRejectedBeforeTransfer() throws Exception {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("large.mkv");
        when(multipartFile.getSize()).thenReturn(10L * 1024 * 1024 * 1024 + 1);
        var failure = assertThrows(VideoUploadException.class, () -> videoService.upload(multipartFile, null));
        org.junit.jupiter.api.Assertions.assertEquals("FILE_TOO_LARGE", failure.code());
        verify(multipartFile, never()).transferTo(any(java.io.File.class));
    }

    @Test
    void outOfSpaceDuringTransferIsIdentifiedAndCleanedUp() throws Exception {
        when(multipartFile.isEmpty()).thenReturn(false);
        when(multipartFile.getOriginalFilename()).thenReturn("sample.mp4");
        doThrow(new java.io.IOException("No space left on device")).when(multipartFile).transferTo(any(java.io.File.class));
        var failure = assertThrows(VideoUploadException.class, () -> videoService.upload(multipartFile, null));
        org.junit.jupiter.api.Assertions.assertEquals("INSUFFICIENT_STORAGE", failure.code());
        assertDirectoryHasNoFiles(videoRoot);
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

	@Test
	void deleteRemovesCustomThumbnailBeforeDatabaseRecord() throws Exception {
		Path videoFile = Files.write(videoRoot.resolve("inside.mp4"), new byte[] { 1 });
		Path automatic = Files.write(thumbnailRoot.resolve("thumbnail.jpg"), new byte[] { 2 });
		Video video = video(videoFile, automatic);
		video.setCustomThumbnailName("1-custom.jpg");
		when(videoRepository.findById(1L)).thenReturn(Optional.of(video));

		videoService.deleteVideo(1L);

		verify(customThumbnailService).deleteForVideo(video);
		verify(videoRepository).delete(video);
		assertFalse(Files.exists(videoFile));
		assertFalse(Files.exists(automatic));
	}

	@Test
	void streamingRejectsDatabasePathOutsideConfiguredStorage() throws Exception {
		Path outside = Files.write(temporaryDirectory.resolve("outside.mp4"), new byte[] { 1 });
		Video video = video(outside, thumbnailRoot.resolve("thumbnail.jpg"));
		when(videoRepository.findById(1L)).thenReturn(Optional.of(video));

		org.junit.jupiter.api.Assertions.assertNull(videoService.getVideoPath(1L));
	}

	@Test
	void streamingReturnsReadableFileInsideConfiguredStorage() throws Exception {
		Path inside = Files.write(videoRoot.resolve("inside.mp4"), new byte[] { 1 });
		Video video = video(inside, thumbnailRoot.resolve("thumbnail.jpg"));
		when(videoRepository.findById(1L)).thenReturn(Optional.of(video));

		org.junit.jupiter.api.Assertions.assertEquals(inside.toRealPath(),
				videoService.getVideoPath(1L).toRealPath());
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
		configureMultipartFile("sample.mp4");
	}

	private void configureMultipartFile(String originalName) throws Exception {
		when(multipartFile.isEmpty()).thenReturn(false);
		when(multipartFile.getOriginalFilename()).thenReturn(originalName);
		doAnswer(invocation -> {
			Path destination = invocation.getArgument(0, java.io.File.class).toPath();
			Files.write(destination, new byte[] { 1, 2, 3 });
			return null;
		}).when(multipartFile).transferTo(any(java.io.File.class));
	}

	private void configureSuccessfulThumbnail() {
		when(thumbnailService.createThumbnail(any(String.class), any(String.class)))
				.thenAnswer(invocation -> {
					Path thumbnail = Path.of(invocation.getArgument(1, String.class));
					Files.write(thumbnail, new byte[] { 4 });
					return thumbnail.toString();
				});
	}

	private VideoMetadata compatibleMetadata() {
		ContainerMetadata container = new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "isom", "mp4",
				10, 0, 100, 1_000_000, true);
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "Main", 40, "yuv420p", 1920, 1080, 30,
				1_000_000, 8, false, "bt709", "bt709", "bt709", false, false,
				0, false, false);
		AudioStreamMetadata audio = new AudioStreamMetadata(
				"aac", "mp4a", "LC", 48_000, 2, "stereo", 128_000);
		return new VideoMetadata(container, List.of(video), List.of(audio));
	}

	private void assertDirectoryHasNoFiles(Path directory) throws Exception {
		try (var entries = Files.list(directory)) {
			assertFalse(entries.anyMatch(Files::isRegularFile));
		}
	}
}
