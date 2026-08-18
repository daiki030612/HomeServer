package com.example.homeserver.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.homeserver.Entity.VideoUrlImportJob;
import com.example.homeserver.Repository.VideoUrlImportJobRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class VideoUrlImportJobService {
	private static final Logger logger = LoggerFactory.getLogger(VideoUrlImportJobService.class);
	private static final int MAX_WORKERS = 4;
	private static final EnumSet<VideoUrlImportJobStatus> ACTIVE = EnumSet.of(
			VideoUrlImportJobStatus.QUEUED, VideoUrlImportJobStatus.ANALYZING,
			VideoUrlImportJobStatus.DOWNLOADING, VideoUrlImportJobStatus.PROCESSING);
	private final VideoUrlImportService imports;
	private final VideoUrlImportJobRepository repository;
	private final ExecutorService executor;
	private final Duration progressInterval;
	private final int historyLimit;
	private final ConcurrentMap<UUID, RunningJob> running = new ConcurrentHashMap<>();

	public VideoUrlImportJobService(VideoUrlImportService imports, VideoUrlImportJobRepository repository,
			@Value("${video.url-import.job-workers:2}") int workers,
			@Value("${video.url-import.job-progress-interval:PT1S}") Duration progressInterval,
			@Value("${video.url-import.job-history-limit:25}") int historyLimit) {
		this.imports = imports;
		this.repository = repository;
		this.progressInterval = progressInterval == null || progressInterval.isNegative()
				? Duration.ofSeconds(1) : progressInterval;
		this.historyLimit = Math.max(1, Math.min(100, historyLimit));
		AtomicInteger number = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(Math.max(1, Math.min(MAX_WORKERS, workers)), runnable ->
				Thread.ofPlatform().daemon().name("video-url-import-" + number.incrementAndGet()).unstarted(runnable));
	}

	@PostConstruct
	@Transactional
	void recoverInterruptedJobs() {
		Instant now = Instant.now();
		for (VideoUrlImportJob job : repository.findByStateIn(ACTIVE)) {
			job.setState(VideoUrlImportJobStatus.FAILED);
			job.setStage(VideoUrlImportStage.FAILED);
			job.setCurrentOperation("サーバー再起動により中断されました");
			job.setErrorMessage("サーバー再起動により中断されました。もう一度実行してください。");
			job.setCompletedAt(now);
			repository.save(job);
		}
	}

	@Transactional
	public synchronized StartResult startOrReuse(String rawUrl, Long folderId, String owner) {
		if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Job owner is required");
		String normalized = normalize(rawUrl);
		Optional<VideoUrlImportJob> duplicate = repository
				.findFirstByOwnerUsernameAndNormalizedUrlAndStateInOrderByCreatedAtDesc(owner, normalized, ACTIVE);
		if (duplicate.isPresent()) return new StartResult(duplicate.get().getId(), true);
		VideoUrlImportJob job = new VideoUrlImportJob();
		job.setId(UUID.randomUUID()); job.setOwnerUsername(owner);
		job.setInputUrl(rawUrl == null ? "" : rawUrl.trim()); job.setNormalizedUrl(normalized);
		job.setFolderId(folderId); job.setState(VideoUrlImportJobStatus.QUEUED);
		job.setStage(VideoUrlImportStage.URL_ANALYZING); job.setCurrentOperation("実行待ちです");
		job.setCreatedAt(Instant.now()); repository.saveAndFlush(job);
		RunningJob runtime = new RunningJob(); running.put(job.getId(), runtime);
		runtime.future = executor.submit(() -> runImport(job.getId(), rawUrl, folderId, runtime));
		return new StartResult(job.getId(), false);
	}

	public UUID start(String rawUrl, Long folderId, String owner) { return startOrReuse(rawUrl, folderId, owner).jobId(); }
	public Optional<JobProgress> find(UUID id, String owner) {
		return repository.findByIdAndOwnerUsername(id, owner).map(this::snapshot);
	}
	public List<JobProgress> recent(String owner) {
		return repository.findByOwnerUsernameOrderByCreatedAtDesc(owner, PageRequest.of(0, historyLimit))
				.stream().map(this::snapshot).toList();
	}

	@Transactional
	public Optional<JobProgress> cancel(UUID id, String owner) {
		Optional<VideoUrlImportJob> found = repository.findByIdAndOwnerUsername(id, owner);
		if (found.isEmpty()) return Optional.empty();
		VideoUrlImportJob job = found.get();
		if (job.getState().terminal()) return Optional.of(snapshot(job));
		job.setCancelRequested(true); job.setCurrentOperation("キャンセルしています");
		RunningJob runtime = running.get(id);
		if (runtime != null) {
			runtime.cancelled.set(true);
			if (runtime.future != null) runtime.future.cancel(true);
		}
		if (job.getState() == VideoUrlImportJobStatus.QUEUED) markCancelled(job);
		return Optional.of(snapshot(job));
	}

	private void runImport(UUID id, String rawUrl, Long folderId, RunningJob runtime) {
		try {
			markStarted(id);
			Long videoId = imports.importVideo(rawUrl, folderId, new VideoUrlImportProgressListener() {
				@Override public void onStage(VideoUrlImportStage stage) { updateStage(id, stage, runtime); }
				@Override public void onHlsProgress(HlsDownloadService.HlsProgress p) { updateProgress(id, p, runtime); }
				@Override public boolean isCancellationRequested() {
					return runtime.cancelled.get() || Thread.currentThread().isInterrupted();
				}
			});
			complete(id, videoId);
		} catch (VideoUrlImportCancelledException e) { cancelled(id);
		} catch (VideoUrlImportException e) {
			if (runtime.cancelled.get() || Thread.currentThread().isInterrupted()) cancelled(id);
			else fail(id, e.getReason(), safeErrorMessage(e.getReason()));
		} catch (Exception e) {
			if (runtime.cancelled.get() || Thread.currentThread().isInterrupted()) cancelled(id);
			else { logger.error("Unexpected video URL import job failure: jobId={}", id, e);
				fail(id, VideoUrlImportException.Reason.SAVE_FAILED,
						"動画を保存できませんでした。サーバー設定と空き容量を確認してください。"); }
		} finally { running.remove(id); }
	}

	@Transactional protected void markStarted(UUID id) { repository.findById(id).ifPresent(job -> {
		if (job.isCancelRequested()) throw new VideoUrlImportCancelledException();
		job.setStartedAt(Instant.now()); job.setState(VideoUrlImportJobStatus.ANALYZING);
			job.setCurrentOperation(message(VideoUrlImportStage.URL_ANALYZING)); repository.save(job); }); }
	@Transactional protected synchronized void updateStage(UUID id, VideoUrlImportStage stage, RunningJob runtime) {
		if (runtime.cancelled.get()) throw new VideoUrlImportCancelledException();
		repository.findById(id).ifPresent(job -> {
			if (job.getState().terminal() || stage.ordinal() < job.getStage().ordinal()) return;
			job.setStage(stage); job.setState(stateFor(stage));
			job.setCurrentOperation(message(stage)); repository.save(job); });
	}
	@Transactional protected synchronized void updateProgress(UUID id, HlsDownloadService.HlsProgress p, RunningJob runtime) {
		if (runtime.cancelled.get()) throw new VideoUrlImportCancelledException();
		int total = Math.max(0, p.totalSegments());
		int percentage = total == 0 ? 0 : Math.min(100, (int)((long)Math.max(0, p.completedSegments()) * 100 / total));
		long now = System.nanoTime();
		if (percentage < 100 && percentage <= runtime.lastPercentage
				&& now - runtime.lastPersistedNanos < progressInterval.toNanos()) return;
		runtime.lastPercentage = percentage; runtime.lastPersistedNanos = now;
		repository.findById(id).ifPresent(job -> {
			job.setTotalSegments(Math.max(job.getTotalSegments(), total));
			job.setCompletedSegments(Math.max(job.getCompletedSegments(), Math.max(0, p.completedSegments())));
			job.setProgress(Math.max(job.getProgress(), percentage));
			job.setDownloadedBytes(Math.max(job.getDownloadedBytes(), Math.max(0, p.downloadedBytes())));
			repository.save(job); });
	}
	@Transactional protected void complete(UUID id, Long videoId) { repository.findById(id).ifPresent(job -> {
		job.setState(VideoUrlImportJobStatus.COMPLETED); job.setStage(VideoUrlImportStage.COMPLETED);
		job.setCurrentOperation(message(VideoUrlImportStage.COMPLETED)); job.setProgress(100);
		job.setVideoId(videoId); job.setCompletedAt(Instant.now()); repository.save(job); }); }
	@Transactional protected void fail(UUID id, VideoUrlImportException.Reason reason, String text) {
		repository.findById(id).ifPresent(job -> { job.setState(VideoUrlImportJobStatus.FAILED);
			job.setStage(VideoUrlImportStage.FAILED); job.setCurrentOperation(text); job.setErrorMessage(text);
			job.setErrorReason(reason); job.setCompletedAt(Instant.now()); repository.save(job); }); }
	@Transactional protected void cancelled(UUID id) { repository.findById(id).ifPresent(this::markCancelled); }
	private void markCancelled(VideoUrlImportJob job) { job.setState(VideoUrlImportJobStatus.CANCELLED);
		job.setStage(VideoUrlImportStage.CANCELLED); job.setCurrentOperation("キャンセルしました");
		job.setCompletedAt(Instant.now()); repository.save(job); }

	private JobProgress snapshot(VideoUrlImportJob j) { return new JobProgress(j.getId(), j.getStage(), j.getState(),
			j.getCurrentOperation(), j.getCompletedSegments(), j.getTotalSegments(), j.getProgress(), j.getDownloadedBytes(),
			j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt(), j.getErrorReason(), j.getErrorMessage(),
			j.getVideoId(), j.isCancelRequested(), j.getInputUrl()); }
	private VideoUrlImportJobStatus stateFor(VideoUrlImportStage stage) { return switch (stage) {
		case URL_ANALYZING, VIDEO_INFO_FETCHING, HLS_PLAYLIST_ANALYZING -> VideoUrlImportJobStatus.ANALYZING;
		case DOWNLOADING -> VideoUrlImportJobStatus.DOWNLOADING;
		case MP4_CREATING, THUMBNAIL_GENERATING, SAVING -> VideoUrlImportJobStatus.PROCESSING;
		case COMPLETED -> VideoUrlImportJobStatus.COMPLETED; case FAILED -> VideoUrlImportJobStatus.FAILED;
		case CANCELLED -> VideoUrlImportJobStatus.CANCELLED; }; }
	private String normalize(String raw) {
		String trimmed = raw == null ? "" : raw.trim();
		try { URI u = new URI(trimmed); String scheme = lower(u.getScheme()); String host = lower(u.getHost());
			int port = (("http".equals(scheme) && u.getPort() == 80) || ("https".equals(scheme) && u.getPort() == 443)) ? -1 : u.getPort();
			return new URI(scheme, u.getUserInfo(), host, port, u.getPath(), u.getRawQuery(), null).normalize().toASCIIString();
		} catch (URISyntaxException e) { return trimmed; }
	}
	private String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }
	private String safeErrorMessage(VideoUrlImportException.Reason r) { return switch (r) {
		case INVALID_URL -> "有効な公開HTTP(S) URLを入力してください。"; case UNSUPPORTED_SOURCE -> "このURLには対応していません。";
		case PAGE_FETCH_FAILED -> "動画ページを取得できませんでした。"; case SOURCE_NOT_FOUND -> "このページから動画URLを取得できませんでした。";
		case HLS_DOWNLOAD_FAILED -> "動画のダウンロードに失敗しました。"; case FFMPEG_FAILED -> "動画ファイルの作成に失敗しました。";
		case MEDIA_DOWNLOAD_FAILED -> "動画データを取得できませんでした。"; case SIZE_LIMIT_EXCEEDED -> "動画サイズが上限を超えています。";
		case SAVE_FAILED -> "動画を保存できませんでした。空き容量または容量制限を確認してください。";
		case DATABASE_FAILED -> "動画を取得しましたが、ライブラリへ登録できませんでした。"; }; }
	static String message(VideoUrlImportStage s) { return switch (s) {
		case URL_ANALYZING -> "URLを解析しています"; case VIDEO_INFO_FETCHING -> "動画情報を取得しています";
		case HLS_PLAYLIST_ANALYZING -> "HLSプレイリストを解析しています"; case DOWNLOADING -> "動画をダウンロードしています";
		case MP4_CREATING -> "MP4を作成しています"; case THUMBNAIL_GENERATING -> "サムネイルを生成しています";
		case SAVING -> "動画をライブラリへ保存しています"; case COMPLETED -> "保存が完了しました";
		case FAILED -> "動画の保存に失敗しました"; case CANCELLED -> "キャンセルしました"; }; }
	@PreDestroy void shutdown() { executor.shutdownNow(); try { executor.awaitTermination(5, TimeUnit.SECONDS); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
	public record StartResult(UUID jobId, boolean reused) { }
	public record JobProgress(UUID jobId, VideoUrlImportStage status, VideoUrlImportJobStatus state, String message,
			int completedSegments, int totalSegments, int percentage, long downloadedBytes, Instant createdAt,
			Instant startedAt, Instant finishedAt, VideoUrlImportException.Reason errorReason, String errorMessage,
			Long videoId, boolean cancelRequested, String inputUrl) {
		public JobProgress(UUID jobId, VideoUrlImportStage status, String message, int completedSegments,
				int totalSegments, int percentage, long downloadedBytes, Instant startedAt,
				Instant finishedAt, VideoUrlImportException.Reason errorReason) {
			this(jobId, status, stateForLegacy(status), message, completedSegments, totalSegments, percentage,
					downloadedBytes, startedAt, startedAt, finishedAt, errorReason, null, null, false, null);
		}
		private static VideoUrlImportJobStatus stateForLegacy(VideoUrlImportStage stage) {
			if (stage == VideoUrlImportStage.COMPLETED) return VideoUrlImportJobStatus.COMPLETED;
			if (stage == VideoUrlImportStage.FAILED) return VideoUrlImportJobStatus.FAILED;
			if (stage == VideoUrlImportStage.CANCELLED) return VideoUrlImportJobStatus.CANCELLED;
			return VideoUrlImportJobStatus.PROCESSING;
		}
	}
	private static final class RunningJob { final AtomicBoolean cancelled = new AtomicBoolean(); volatile Future<?> future;
		volatile long lastPersistedNanos; volatile int lastPercentage = -1; }
}
