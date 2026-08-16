package com.example.homeserver.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

@Service
public class VideoUrlImportJobService {
	private static final Logger logger = LoggerFactory.getLogger(VideoUrlImportJobService.class);
	private static final int MAX_WORKERS = 4;

	private final VideoUrlImportService imports;
	private final ConcurrentMap<UUID, JobState> jobs = new ConcurrentHashMap<>();
	private final ExecutorService importExecutor;
	private final ScheduledExecutorService cleanupExecutor;
	private final Duration retention;

	public VideoUrlImportJobService(VideoUrlImportService imports,
			@Value("${video.url-import.job-workers:2}") int workers,
			@Value("${video.url-import.job-retention:PT30M}") Duration retention) {
		this.imports = imports;
		this.retention = retention == null || retention.isNegative() || retention.isZero()
				? Duration.ofMinutes(30) : retention;
		int poolSize = Math.max(1, Math.min(MAX_WORKERS, workers));
		AtomicInteger workerNumber = new AtomicInteger();
		this.importExecutor = Executors.newFixedThreadPool(poolSize, runnable ->
				Thread.ofPlatform().daemon().name("video-url-import-" + workerNumber.incrementAndGet())
						.unstarted(runnable));
		this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
				Thread.ofPlatform().daemon().name("video-url-import-cleanup").unstarted(runnable));
		this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredJobs, 1, 1, TimeUnit.MINUTES);
	}

	public UUID start(String rawUrl, Long folderId, String owner) {
		if (owner == null || owner.isBlank()) throw new IllegalArgumentException("Job owner is required");
		UUID jobId;
		JobState job;
		do {
			jobId = UUID.randomUUID();
			job = new JobState(jobId, owner);
		} while (jobs.putIfAbsent(jobId, job) != null);
		UUID submittedJobId = jobId;
		JobState submittedJob = job;
		importExecutor.execute(() -> runImport(submittedJobId, submittedJob, rawUrl, folderId));
		return jobId;
	}

	public Optional<JobProgress> find(UUID jobId, String owner) {
		JobState job = jobs.get(jobId);
		if (job == null || owner == null || !job.owner.equals(owner)) return Optional.empty();
		return Optional.of(job.snapshot.get());
	}

	private void runImport(UUID jobId, JobState job, String rawUrl, Long folderId) {
		try {
			imports.importVideo(rawUrl, folderId, new VideoUrlImportProgressListener() {
				@Override public void onStage(VideoUrlImportStage stage) {
					job.updateStage(stage);
				}

				@Override public void onHlsProgress(HlsDownloadService.HlsProgress progress) {
					job.updateHls(progress);
				}
			});
			job.complete();
		} catch (VideoUrlImportException e) {
			logger.warn("Video URL import job failed: jobId={}, reason={}", jobId, e.getReason());
			job.fail(e.getReason(), safeErrorMessage(e.getReason()));
		} catch (Exception e) {
			logger.error("Unexpected video URL import job failure: jobId={}", jobId, e);
			job.fail(VideoUrlImportException.Reason.SAVE_FAILED,
					"動画を保存できませんでした。サーバー設定と空き容量を確認してください。");
		}
	}

	private String safeErrorMessage(VideoUrlImportException.Reason reason) {
		return switch (reason) {
		case INVALID_URL -> "有効な公開HTTP(S) URLを入力してください。";
		case UNSUPPORTED_SOURCE -> "このURLには対応していません。";
		case PAGE_FETCH_FAILED -> "動画ページを取得できませんでした。";
		case SOURCE_NOT_FOUND -> "このページから動画URLを取得できませんでした。";
		case HLS_DOWNLOAD_FAILED -> "動画のダウンロードに失敗しました。";
		case FFMPEG_FAILED -> "動画ファイルの作成に失敗しました。";
		case MEDIA_DOWNLOAD_FAILED -> "動画データを取得できませんでした。";
		case SIZE_LIMIT_EXCEEDED -> "動画サイズが上限を超えています。";
		case SAVE_FAILED -> "動画を保存できませんでした。空き容量または容量制限を確認してください。";
		case DATABASE_FAILED -> "動画を取得しましたが、ライブラリへ登録できませんでした。";
		};
	}

	void cleanupExpiredJobs() {
		Instant cutoff = Instant.now().minus(retention);
		jobs.entrySet().removeIf(entry -> {
			Instant finishedAt = entry.getValue().snapshot.get().finishedAt();
			return finishedAt != null && finishedAt.isBefore(cutoff);
		});
	}

	@PreDestroy
	void shutdown() {
		cleanupExecutor.shutdownNow();
		importExecutor.shutdownNow();
	}

	public record JobProgress(UUID jobId, VideoUrlImportStage status, String message,
			int completedSegments, int totalSegments, int percentage, long downloadedBytes,
			Instant startedAt, Instant finishedAt, VideoUrlImportException.Reason errorReason) { }

	private static final class JobState {
		private final String owner;
		private final AtomicReference<JobProgress> snapshot;

		private JobState(UUID jobId, String owner) {
			this.owner = owner;
			this.snapshot = new AtomicReference<>(new JobProgress(jobId,
					VideoUrlImportStage.URL_ANALYZING, message(VideoUrlImportStage.URL_ANALYZING),
					0, 0, 0, 0, Instant.now(), null, null));
		}

		private void updateStage(VideoUrlImportStage stage) {
			snapshot.updateAndGet(old -> {
				if (old.status() == VideoUrlImportStage.COMPLETED || old.status() == VideoUrlImportStage.FAILED
						|| stage.ordinal() < old.status().ordinal()) return old;
				return new JobProgress(old.jobId(), stage, message(stage),
					old.completedSegments(), old.totalSegments(), old.percentage(), old.downloadedBytes(),
					old.startedAt(), old.finishedAt(), old.errorReason());
			});
		}

		private void updateHls(HlsDownloadService.HlsProgress progress) {
			snapshot.updateAndGet(old -> {
				int total = Math.max(old.totalSegments(), Math.max(0, progress.totalSegments()));
				int completed = Math.max(old.completedSegments(), Math.max(0, progress.completedSegments()));
				completed = Math.min(total, completed);
				int percentage = total == 0 ? 0 : Math.min(100, (int) ((long) completed * 100 / total));
				long bytes = Math.max(old.downloadedBytes(), Math.max(0, progress.downloadedBytes()));
				return new JobProgress(old.jobId(), old.status(), old.message(), completed, total,
						percentage, bytes, old.startedAt(), old.finishedAt(), old.errorReason());
			});
		}

		private void complete() {
			Instant now = Instant.now();
			snapshot.updateAndGet(old -> new JobProgress(old.jobId(), VideoUrlImportStage.COMPLETED,
					message(VideoUrlImportStage.COMPLETED), old.completedSegments(), old.totalSegments(),
					100, old.downloadedBytes(),
					old.startedAt(), now, null));
		}

		private void fail(VideoUrlImportException.Reason reason, String safeMessage) {
			Instant now = Instant.now();
			snapshot.updateAndGet(old -> new JobProgress(old.jobId(), VideoUrlImportStage.FAILED,
					safeMessage, old.completedSegments(), old.totalSegments(), old.percentage(),
					old.downloadedBytes(), old.startedAt(), now, reason));
		}

		private static String message(VideoUrlImportStage stage) {
			return switch (stage) {
			case URL_ANALYZING -> "URLを解析しています";
			case VIDEO_INFO_FETCHING -> "動画情報を取得しています";
			case HLS_PLAYLIST_ANALYZING -> "HLSプレイリストを解析しています";
			case DOWNLOADING -> "動画をダウンロードしています";
			case MP4_CREATING -> "MP4を作成しています";
			case THUMBNAIL_GENERATING -> "サムネイルを生成しています";
			case SAVING -> "動画をライブラリへ保存しています";
			case COMPLETED -> "保存が完了しました";
			case FAILED -> "動画の保存に失敗しました";
			};
		}
	}
}
