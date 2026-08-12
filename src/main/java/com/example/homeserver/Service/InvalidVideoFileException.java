package com.example.homeserver.Service;

public class InvalidVideoFileException extends RuntimeException {

	public static final String USER_MESSAGE = "動画ファイルとして認識できません。";

	public InvalidVideoFileException(Throwable cause) {
		super(USER_MESSAGE, cause);
	}

	public InvalidVideoFileException() {
		super(USER_MESSAGE);
	}
}
