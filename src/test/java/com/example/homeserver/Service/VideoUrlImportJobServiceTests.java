package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VideoUrlImportJobServiceTests {
	private VideoUrlImportJobService jobs;

	@AfterEach
	void shutdown() {
		if (jobs != null) jobs.shutdown();
	}

	@Test
	void jobIdsAreUniqueAndProgressIsIsolatedByOwner() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		jobs = new VideoUrlImportJobService(imports, 2, Duration.ofMinutes(30));

		UUID first = jobs.start("https://one.example", null, "alice");
		UUID second = jobs.start("https://two.example", null, "bob");

		assertNotEquals(first, second);
		awaitTerminal(first, "alice");
		awaitTerminal(second, "bob");
		assertFalse(jobs.find(first, "bob").isPresent());
		assertFalse(jobs.find(second, "alice").isPresent());
	}

	@Test
	void exposesHlsCountsBytesAndClampedPercentageWhileRunning() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		CountDownLatch published = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			VideoUrlImportProgressListener listener = invocation.getArgument(2);
			listener.onStage(VideoUrlImportStage.DOWNLOADING);
			listener.onHlsProgress(new HlsDownloadService.HlsProgress(10, 4, 1_073_741_824));
			published.countDown();
			release.await(2, TimeUnit.SECONDS);
			return null;
		}).when(imports).importVideo(eq("https://hls.example"), eq(7L), any());
		jobs = new VideoUrlImportJobService(imports, 1, Duration.ofMinutes(30));

		UUID jobId = jobs.start("https://hls.example", 7L, "alice");
		assertTrue(published.await(2, TimeUnit.SECONDS));
		var progress = jobs.find(jobId, "alice").orElseThrow();
		assertEquals(VideoUrlImportStage.DOWNLOADING, progress.status());
		assertEquals(4, progress.completedSegments());
		assertEquals(10, progress.totalSegments());
		assertEquals(40, progress.percentage());
		assertEquals(1_073_741_824, progress.downloadedBytes());
		release.countDown();
		assertEquals(VideoUrlImportStage.COMPLETED, awaitTerminal(jobId, "alice").status());
	}

	@Test
	void concurrentProgressUpdatesRemainMonotonicAndConsistent() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		CountDownLatch published = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			VideoUrlImportProgressListener listener = invocation.getArgument(2);
			listener.onStage(VideoUrlImportStage.DOWNLOADING);
			ExecutorService publishers = Executors.newFixedThreadPool(8);
			for (int i = 1; i <= 100; i++) {
				int completed = i;
				publishers.submit(() -> listener.onHlsProgress(
						new HlsDownloadService.HlsProgress(100, completed, completed * 1000L)));
			}
			publishers.shutdown();
			assertTrue(publishers.awaitTermination(2, TimeUnit.SECONDS));
			published.countDown();
			release.await(2, TimeUnit.SECONDS);
			return null;
		}).when(imports).importVideo(eq("https://parallel.example"), eq(null), any());
		jobs = new VideoUrlImportJobService(imports, 1, Duration.ofMinutes(30));

		UUID jobId = jobs.start("https://parallel.example", null, "alice");
		assertTrue(published.await(2, TimeUnit.SECONDS));
		var progress = jobs.find(jobId, "alice").orElseThrow();
		assertEquals(100, progress.completedSegments());
		assertEquals(100, progress.totalSegments());
		assertEquals(100, progress.percentage());
		assertEquals(100_000, progress.downloadedBytes());
		release.countDown();
		var completed = awaitTerminal(jobId, "alice");
		assertEquals(VideoUrlImportStage.COMPLETED, completed.status());
		assertEquals(100, completed.percentage());
	}

	@Test
	void mapsFailureToSafeReasonAndMessage() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		doThrow(new VideoUrlImportException(VideoUrlImportException.Reason.FFMPEG_FAILED,
				"secret URL https://private.example/token"))
				.when(imports).importVideo(eq("https://failure.example"), eq(null), any());
		jobs = new VideoUrlImportJobService(imports, 1, Duration.ofMinutes(30));

		var progress = awaitTerminal(jobs.start("https://failure.example", null, "alice"), "alice");
		assertEquals(VideoUrlImportStage.FAILED, progress.status());
		assertEquals(VideoUrlImportException.Reason.FFMPEG_FAILED, progress.errorReason());
		assertEquals("動画ファイルの作成に失敗しました。", progress.message());
		assertFalse(progress.message().contains("private.example"));
	}

	@Test
	void directMp4ImportCanReportSavingStageBeforeCompletion() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		CountDownLatch saving = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			VideoUrlImportProgressListener listener = invocation.getArgument(2);
			listener.onStage(VideoUrlImportStage.DOWNLOADING);
			listener.onStage(VideoUrlImportStage.THUMBNAIL_GENERATING);
			listener.onStage(VideoUrlImportStage.SAVING);
			saving.countDown();
			release.await(2, TimeUnit.SECONDS);
			return null;
		}).when(imports).importVideo(eq("https://direct.example/video.mp4"), eq(null), any());
		jobs = new VideoUrlImportJobService(imports, 1, Duration.ofMinutes(30));

		UUID jobId = jobs.start("https://direct.example/video.mp4", null, "alice");
		assertTrue(saving.await(2, TimeUnit.SECONDS));
		assertEquals(VideoUrlImportStage.SAVING, jobs.find(jobId, "alice").orElseThrow().status());
		release.countDown();
		var completed = awaitTerminal(jobId, "alice");
		assertEquals(VideoUrlImportStage.COMPLETED, completed.status());
		assertEquals(100, completed.percentage());
	}

	@Test
	void removesFinishedJobsAfterRetentionPeriod() throws Exception {
		jobs = new VideoUrlImportJobService(mock(VideoUrlImportService.class), 1, Duration.ofMillis(5));
		UUID jobId = jobs.start("https://done.example", null, "alice");
		awaitTerminal(jobId, "alice");
		Thread.sleep(20);
		jobs.cleanupExpiredJobs();
		assertFalse(jobs.find(jobId, "alice").isPresent());
	}

	private VideoUrlImportJobService.JobProgress awaitTerminal(UUID jobId, String owner) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			var progress = jobs.find(jobId, owner).orElseThrow();
			if (progress.finishedAt() != null) return progress;
			Thread.sleep(10);
		}
		throw new AssertionError("job did not finish");
	}
}
