package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.homeserver.Entity.VideoUrlImportJob;
import com.example.homeserver.Repository.VideoUrlImportJobRepository;

class VideoUrlImportJobServiceTests {
	private VideoUrlImportJobService jobs;
	private final Map<UUID, VideoUrlImportJob> db = new ConcurrentHashMap<>();

	@AfterEach void shutdown() { if (jobs != null) jobs.shutdown(); }

	@Test
	void queuesDifferentUrlsInFifoOrderWithOneWorker() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger concurrent = new AtomicInteger(), maximum = new AtomicInteger();
		doAnswer(call -> {
			String url = call.getArgument(0); order.add(url);
			maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
			try {
				if (url.endsWith("/a")) { started.countDown(); release.await(2, TimeUnit.SECONDS); }
				return (long) order.size();
			} finally { concurrent.decrementAndGet(); }
		}).when(imports).importVideo(any(), any(), any());
		jobs = service(imports, 1);

		UUID a = jobs.start("https://example.com/a", null, "alice");
		assertTrue(started.await(2, TimeUnit.SECONDS));
		UUID b = jobs.start("https://example.com/b", null, "alice");
		UUID c = jobs.start("https://example.com/c", null, "alice");
		assertEquals(VideoUrlImportJobStatus.QUEUED, jobs.find(b, "alice").orElseThrow().state());
		assertEquals(1, jobs.find(b, "alice").orElseThrow().queuePosition());
		assertEquals(2, jobs.find(c, "alice").orElseThrow().queuePosition());
		release.countDown();

		awaitTerminal(a); awaitTerminal(b); awaitTerminal(c);
		assertEquals(List.of("https://example.com/a", "https://example.com/b", "https://example.com/c"), order);
		assertEquals(1, maximum.get());
	}

	@Test
	void queuedCancellationSkipsJobAndCompactsPositions() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(call -> {
			String url = call.getArgument(0); order.add(url);
			if (url.endsWith("/a")) { started.countDown(); release.await(2, TimeUnit.SECONDS); }
			return 1L;
		}).when(imports).importVideo(any(), any(), any());
		jobs = service(imports, 1);

		UUID a = jobs.start("https://example.com/a", null, "alice");
		assertTrue(started.await(2, TimeUnit.SECONDS));
		UUID b = jobs.start("https://example.com/b", null, "alice");
		UUID c = jobs.start("https://example.com/c", null, "alice");
		assertEquals(VideoUrlImportJobStatus.CANCELLED, jobs.cancel(b, "alice").orElseThrow().state());
		assertEquals(1, jobs.find(c, "alice").orElseThrow().queuePosition());
		release.countDown();

		awaitTerminal(a); awaitTerminal(c);
		assertEquals(List.of("https://example.com/a", "https://example.com/c"), order);
	}

	@Test
	void failureAndRunningCancellationDoNotStopNextJob() throws Exception {
		VideoUrlImportService failing = mock(VideoUrlImportService.class);
		doAnswer(call -> {
			if (((String) call.getArgument(0)).endsWith("/a"))
				throw new VideoUrlImportException(VideoUrlImportException.Reason.MEDIA_DOWNLOAD_FAILED, "failed");
			return 2L;
		}).when(failing).importVideo(any(), any(), any());
		jobs = service(failing, 1);
		UUID failed = jobs.start("https://example.com/a", null, "alice");
		UUID next = jobs.start("https://example.com/b", null, "alice");
		assertEquals(VideoUrlImportJobStatus.FAILED, awaitTerminal(failed).state());
		assertEquals(VideoUrlImportJobStatus.COMPLETED, awaitTerminal(next).state());
		jobs.shutdown();

		VideoUrlImportService cancellable = mock(VideoUrlImportService.class);
		CountDownLatch running = new CountDownLatch(1);
		doAnswer(call -> {
			if (((String) call.getArgument(0)).endsWith("/c")) {
				running.countDown(); while (true) Thread.sleep(100);
			}
			return 3L;
		}).when(cancellable).importVideo(any(), any(), any());
		jobs = service(cancellable, 1);
		UUID cancelled = jobs.start("https://example.com/c", null, "alice");
		assertTrue(running.await(2, TimeUnit.SECONDS));
		UUID afterCancel = jobs.start("https://example.com/d", null, "alice");
		jobs.cancel(cancelled, "alice").orElseThrow();
		assertEquals(VideoUrlImportJobStatus.CANCELLED, awaitTerminal(cancelled).state());
		assertEquals(VideoUrlImportJobStatus.COMPLETED, awaitTerminal(afterCancel).state());
	}

	@Test
	void preventsDuplicateActiveJobButAllowsDifferentUrl() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(call -> { running.countDown(); release.await(2, TimeUnit.SECONDS); return 41L; })
				.when(imports).importVideo(any(), any(), any());
		jobs = service(imports, 1);
		var first = jobs.startOrReuse("HTTPS://Example.COM:443/video?id=1", null, "alice");
		assertTrue(running.await(2, TimeUnit.SECONDS));
		var duplicate = jobs.startOrReuse("https://example.com/video?id=1", null, "alice");
		var other = jobs.startOrReuse("https://example.com/video?id=2", null, "alice");
		assertEquals(first.jobId(), duplicate.jobId()); assertTrue(duplicate.reused());
		assertFalse(other.reused());
		assertEquals(VideoUrlImportJobStatus.QUEUED, jobs.find(other.jobId(), "alice").orElseThrow().state());
		release.countDown(); awaitTerminal(first.jobId()); awaitTerminal(other.jobId());
	}

	@Test
	void queuesTokyoMotionUrlThroughTheExistingFifoWorker() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		when(imports.importVideo(any(), any(), any())).thenReturn(3475574L);
		jobs = service(imports, 1);
		String url = "https://www.tokyomotion.net/embed/13a5fcc5364b6dce1517";

		UUID id = jobs.start(url, null, "alice");

		assertEquals(VideoUrlImportJobStatus.COMPLETED, awaitTerminal(id).state());
		verify(imports).importVideo(eq(url), isNull(), any());
	}

	@Test
	void mediaForbiddenFailureIsPersistedWithSafeMessageAndNextJobCanRun() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		doThrow(new VideoUrlImportException(VideoUrlImportException.Reason.MEDIA_DOWNLOAD_FAILED,
				"HTTP request failed with status 403: secret-token"))
				.when(imports).importVideo(contains("tokyomotion.net"), any(), any());
		when(imports.importVideo(contains("example.com"), any(), any())).thenReturn(42L);
		jobs = service(imports, 1);

		UUID forbidden = jobs.start("https://www.tokyomotion.net/embed/test", null, "alice");
		UUID next = jobs.start("https://example.com/video.mp4", null, "alice");

		var failed = awaitTerminal(forbidden);
		assertEquals(VideoUrlImportJobStatus.FAILED, failed.state());
		assertEquals("動画データを取得できませんでした。", failed.errorMessage());
		assertFalse(failed.errorMessage().contains("secret-token"));
		assertEquals(VideoUrlImportJobStatus.COMPLETED, awaitTerminal(next).state());
	}

	@Test
	void startupFailsInterruptedJobButResumesQueuedJob() throws Exception {
		VideoUrlImportJob interrupted = entity(UUID.randomUUID(), "https://example.com/a",
				VideoUrlImportJobStatus.DOWNLOADING, Instant.parse("2026-08-20T00:00:00Z"));
		VideoUrlImportJob queued = entity(UUID.randomUUID(), "https://example.com/b",
				VideoUrlImportJobStatus.QUEUED, Instant.parse("2026-08-20T00:01:00Z"));
		db.put(interrupted.getId(), interrupted); db.put(queued.getId(), queued);
		jobs = service(mock(VideoUrlImportService.class), 1);
		jobs.recoverInterruptedJobs();
		assertEquals(VideoUrlImportJobStatus.FAILED, interrupted.getState());
		assertTrue(interrupted.getErrorMessage().contains("サーバー再起動"));
		assertEquals(VideoUrlImportJobStatus.COMPLETED, awaitTerminal(queued.getId()).state());
	}

	@Test
	void otherUserCannotReadOrCancelJob() throws Exception {
		jobs = service(mock(VideoUrlImportService.class), 1);
		UUID id = jobs.start("https://example.com/a", null, "alice");
		assertTrue(jobs.find(id, "bob").isEmpty());
		assertTrue(jobs.cancel(id, "bob").isEmpty());
		awaitTerminal(id);
	}

	@SuppressWarnings("unchecked")
	private VideoUrlImportJobService service(VideoUrlImportService imports, int workers) {
		VideoUrlImportJobRepository repo = mock(VideoUrlImportJobRepository.class);
		when(repo.saveAndFlush(any())).thenAnswer(call -> save(call.getArgument(0)));
		when(repo.save(any())).thenAnswer(call -> save(call.getArgument(0)));
		when(repo.findById(any())).thenAnswer(call -> Optional.ofNullable(db.get(call.getArgument(0))));
		when(repo.findByIdAndOwnerUsername(any(), any())).thenAnswer(call -> {
			VideoUrlImportJob job = db.get(call.getArgument(0));
			return job != null && job.getOwnerUsername().equals(call.getArgument(1)) ? Optional.of(job) : Optional.empty();
		});
		when(repo.findFirstByOwnerUsernameAndNormalizedUrlAndStateInOrderByCreatedAtDesc(any(), any(), any()))
				.thenAnswer(call -> db.values().stream().filter(job -> job.getOwnerUsername().equals(call.getArgument(0))
						&& job.getNormalizedUrl().equals(call.getArgument(1))
						&& ((Collection<VideoUrlImportJobStatus>) call.getArgument(2)).contains(job.getState())).findFirst());
		when(repo.findByStateIn(any())).thenAnswer(call -> db.values().stream()
				.filter(job -> ((Collection<VideoUrlImportJobStatus>) call.getArgument(0)).contains(job.getState())).toList());
		when(repo.findByStateOrderByCreatedAtAscIdAsc(any())).thenAnswer(call -> sortedJobs(null, call.getArgument(0)));
		when(repo.findByOwnerUsernameAndStateOrderByCreatedAtAscIdAsc(any(), any()))
				.thenAnswer(call -> sortedJobs(call.getArgument(0), call.getArgument(1)));
		when(repo.findByOwnerUsernameAndStateInOrderByCreatedAtAscIdAsc(any(), any()))
				.thenAnswer(call -> db.values().stream()
						.filter(job -> job.getOwnerUsername().equals(call.getArgument(0)))
						.filter(job -> ((Collection<VideoUrlImportJobStatus>) call.getArgument(1)).contains(job.getState()))
						.sorted(Comparator.comparing(VideoUrlImportJob::getCreatedAt)
								.thenComparing(VideoUrlImportJob::getId)).toList());
		when(repo.findByOwnerUsernameOrderByCreatedAtDesc(any(), any())).thenAnswer(call -> db.values().stream()
				.filter(job -> job.getOwnerUsername().equals(call.getArgument(0)))
				.sorted(Comparator.comparing(VideoUrlImportJob::getCreatedAt).reversed()).toList());
		return new VideoUrlImportJobService(imports, repo, workers, Duration.ofMillis(10), 25);
	}

	private List<VideoUrlImportJob> sortedJobs(String owner, VideoUrlImportJobStatus state) {
		Comparator<VideoUrlImportJob> order = Comparator.comparing(VideoUrlImportJob::getCreatedAt)
				.thenComparing(VideoUrlImportJob::getId);
		return db.values().stream().filter(job -> owner == null || job.getOwnerUsername().equals(owner))
				.filter(job -> job.getState() == state).sorted(order).toList();
	}

	private VideoUrlImportJob save(VideoUrlImportJob job) { db.put(job.getId(), job); return job; }

	private VideoUrlImportJob entity(UUID id, String url, VideoUrlImportJobStatus state, Instant createdAt) {
		VideoUrlImportJob job = new VideoUrlImportJob(); job.setId(id); job.setOwnerUsername("alice");
		job.setInputUrl(url); job.setNormalizedUrl(url); job.setState(state);
		job.setStage(state == VideoUrlImportJobStatus.QUEUED ? VideoUrlImportStage.URL_ANALYZING : VideoUrlImportStage.DOWNLOADING);
		job.setCurrentOperation(state == VideoUrlImportJobStatus.QUEUED ? "実行待ちです" : "downloading");
		job.setCreatedAt(createdAt); return job;
	}

	private VideoUrlImportJobService.JobProgress awaitTerminal(UUID id) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			var progress = jobs.find(id, "alice").orElseThrow();
			if (progress.state().terminal()) return progress;
			Thread.sleep(10);
		}
		throw new AssertionError("job did not finish: " + id);
	}
}
