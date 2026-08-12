package com.example.homeserver.Service;

public class UnsupportedVideoConversionException extends RuntimeException {

	public static final String USER_MESSAGE = "この動画はまだ自動変換に対応していません。";

	public UnsupportedVideoConversionException() {
		super(USER_MESSAGE);
	}
}
