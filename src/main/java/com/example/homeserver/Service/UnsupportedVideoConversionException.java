package com.example.homeserver.Service;

public class UnsupportedVideoConversionException extends RuntimeException {
    public static final String USER_MESSAGE = "この動画のコーデックまたはストリーム構成は無劣化変換に対応していません。自動再圧縮は行いません。";
    public UnsupportedVideoConversionException() { super(USER_MESSAGE); }
    public UnsupportedVideoConversionException(java.util.List<String> reasons) {
        super(reasons == null || reasons.isEmpty() ? USER_MESSAGE : String.join(" ", reasons));
    }
}
