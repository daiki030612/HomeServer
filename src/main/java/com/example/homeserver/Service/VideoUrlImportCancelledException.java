package com.example.homeserver.Service;

public class VideoUrlImportCancelledException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	public VideoUrlImportCancelledException() { super("Video URL import was cancelled"); }
}
