package com.example.homeserver.Service;

public interface VideoUrlImportProgressListener {
	VideoUrlImportProgressListener NOOP = new VideoUrlImportProgressListener() { };

	default void onStage(VideoUrlImportStage stage) { }

	default void onHlsProgress(HlsDownloadService.HlsProgress progress) { }

	default boolean isCancellationRequested() { return Thread.currentThread().isInterrupted(); }

	default void checkCancellation() {
		if (isCancellationRequested()) throw new VideoUrlImportCancelledException();
	}
}
