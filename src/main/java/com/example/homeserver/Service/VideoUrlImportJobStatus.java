package com.example.homeserver.Service;

public enum VideoUrlImportJobStatus {
	QUEUED, ANALYZING, DOWNLOADING, PROCESSING, COMPLETED, FAILED, CANCELLED;

	public boolean terminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
