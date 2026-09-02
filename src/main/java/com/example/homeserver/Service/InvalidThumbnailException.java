package com.example.homeserver.Service;

public class InvalidThumbnailException extends RuntimeException {
	public InvalidThumbnailException(String message) {
		super(message);
	}

	public InvalidThumbnailException(String message, Throwable cause) {
		super(message, cause);
	}
}
