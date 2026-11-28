package com.example.homeserver.Controller;

import com.example.homeserver.Service.VideoUploadException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class UploadExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> multipart(MultipartException failure, HttpServletRequest request) {
        boolean tooLarge = failure instanceof MaxUploadSizeExceededException;
        boolean full = VideoUploadException.diskFull(failure);
        int status = tooLarge ? 413 : full ? 507 : 400;
        String code = tooLarge ? "FILE_TOO_LARGE" : full ? "INSUFFICIENT_STORAGE" : "MULTIPART_FAILED";
        String message = tooLarge ? "アップロード上限は10GBです。"
                : full ? "アップロード一時保存先の空き容量が不足しています。"
                : "アップロードの受信に失敗しました。通信と一時保存先を確認してください。";
        Object body = request.getServletPath().startsWith("/api/")
                ? Map.of("error", Map.of("code", code, "message", message, "retryable", false))
                : message;
        return ResponseEntity.status(status).body(body);
    }
}
