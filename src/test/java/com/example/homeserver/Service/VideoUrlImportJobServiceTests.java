package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.homeserver.Entity.VideoUrlImportJob;
import com.example.homeserver.Repository.VideoUrlImportJobRepository;

class VideoUrlImportJobServiceTests {
	private VideoUrlImportJobService jobs;
	private final Map<UUID, VideoUrlImportJob> db = new ConcurrentHashMap<>();
	@AfterEach void shutdown() { if (jobs != null) jobs.shutdown(); }

	@Test void preventsDuplicateActiveJobAndPreservesQuery() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class);
		CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(c -> { running.countDown(); release.await(2, TimeUnit.SECONDS); return 41L; })
				.when(imports).importVideo(any(), any(), any());
		jobs = service(imports);
		var first = jobs.startOrReuse("HTTPS://Example.COM:443/video?id=1", null, "alice");
		assertTrue(running.await(2, TimeUnit.SECONDS));
		var duplicate = jobs.startOrReuse("https://example.com/video?id=1", null, "alice");
		var otherQuery = jobs.startOrReuse("https://example.com/video?id=2", null, "alice");
		assertEquals(first.jobId(), duplicate.jobId()); assertTrue(duplicate.reused());
		assertFalse(otherQuery.reused()); release.countDown();
		awaitTerminal(first.jobId()); awaitTerminal(otherQuery.jobId());
	}

	@Test void cancellationIsPersistedAndInterruptsRunningJob() throws Exception {
		VideoUrlImportService imports = mock(VideoUrlImportService.class); CountDownLatch running = new CountDownLatch(1);
		doAnswer(c -> { running.countDown(); while (true) Thread.sleep(100); })
				.when(imports).importVideo(any(), any(), any());
		jobs = service(imports); UUID id = jobs.start("https://example.com/long", null, "alice");
		assertTrue(running.await(2, TimeUnit.SECONDS)); jobs.cancel(id, "alice").orElseThrow();
		assertEquals(VideoUrlImportJobStatus.CANCELLED, awaitTerminal(id).state());
	}

	@Test void startupRecoveryFailsPreviouslyActiveJobs() {
		VideoUrlImportJob active = entity(UUID.randomUUID(), "https://example.com", VideoUrlImportJobStatus.DOWNLOADING);
		db.put(active.getId(), active); jobs = service(mock(VideoUrlImportService.class)); jobs.recoverInterruptedJobs();
		assertEquals(VideoUrlImportJobStatus.FAILED, active.getState());
		assertTrue(active.getErrorMessage().contains("サーバー再起動"));
	}

	@SuppressWarnings("unchecked")
	private VideoUrlImportJobService service(VideoUrlImportService imports) {
		VideoUrlImportJobRepository repo = mock(VideoUrlImportJobRepository.class);
		when(repo.saveAndFlush(any())).thenAnswer(c -> save(c.getArgument(0)));
		when(repo.save(any())).thenAnswer(c -> save(c.getArgument(0)));
		when(repo.findById(any())).thenAnswer(c -> Optional.ofNullable(db.get(c.getArgument(0))));
		when(repo.findByIdAndOwnerUsername(any(), any())).thenAnswer(c -> {
			VideoUrlImportJob j = db.get(c.getArgument(0));
			return j != null && j.getOwnerUsername().equals(c.getArgument(1)) ? Optional.of(j) : Optional.empty(); });
		when(repo.findFirstByOwnerUsernameAndNormalizedUrlAndStateInOrderByCreatedAtDesc(any(), any(), any()))
				.thenAnswer(c -> db.values().stream().filter(j -> j.getOwnerUsername().equals(c.getArgument(0))
						&& j.getNormalizedUrl().equals(c.getArgument(1))
						&& ((Collection<VideoUrlImportJobStatus>)c.getArgument(2)).contains(j.getState())).findFirst());
		when(repo.findByStateIn(any())).thenAnswer(c -> db.values().stream()
				.filter(j -> ((Collection<VideoUrlImportJobStatus>)c.getArgument(0)).contains(j.getState())).toList());
		when(repo.findByOwnerUsernameOrderByCreatedAtDesc(any(), any())).thenAnswer(c -> db.values().stream()
				.filter(j -> j.getOwnerUsername().equals(c.getArgument(0))).sorted(Comparator.comparing(VideoUrlImportJob::getCreatedAt).reversed()).toList());
		return new VideoUrlImportJobService(imports, repo, 2, Duration.ofMillis(10), 25);
	}
	private VideoUrlImportJob save(VideoUrlImportJob job) { db.put(job.getId(), job); return job; }
	private VideoUrlImportJob entity(UUID id, String url, VideoUrlImportJobStatus state) {
		VideoUrlImportJob j = new VideoUrlImportJob(); j.setId(id); j.setOwnerUsername("alice");
		j.setInputUrl(url); j.setNormalizedUrl(url); j.setState(state); j.setStage(VideoUrlImportStage.DOWNLOADING);
		j.setCurrentOperation("downloading"); j.setCreatedAt(java.time.Instant.now()); return j;
	}
	private VideoUrlImportJobService.JobProgress awaitTerminal(UUID id) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) { var p = jobs.find(id, "alice").orElseThrow();
			if (p.state().terminal()) return p; Thread.sleep(10); } throw new AssertionError("job did not finish");
	}
}
