package com.example.homeserver.Service;

public class VideoUploadException extends RuntimeException {
    private final String code;
    private final int status;
    public VideoUploadException(String code, int status, String message) {
        this(code, status, message, null);
    }
    public VideoUploadException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }

    public static boolean diskFull(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("no space left") || message.contains("disk full")
                    || message.contains("not enough space") || message.contains("quota exceeded")) return true;
        }
        return false;
    }
}
