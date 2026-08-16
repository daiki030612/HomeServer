package com.example.homeserver.Service;

public class VideoUrlImportException extends RuntimeException {
	public enum Reason {
		INVALID_URL,
		UNSUPPORTED_SOURCE,
		PAGE_FETCH_FAILED,
		SOURCE_NOT_FOUND,
		MEDIA_DOWNLOAD_FAILED,
		SIZE_LIMIT_EXCEEDED,
		HLS_DOWNLOAD_FAILED,
		FFMPEG_FAILED,
		SAVE_FAILED,
		DATABASE_FAILED
	}

	private final Reason reason;

	public VideoUrlImportException(Reason reason, String userMessage) {
		super(userMessage);
		this.reason = reason;
	}

	public VideoUrlImportException(Reason reason, String userMessage, Throwable cause) {
		super(userMessage, cause);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
