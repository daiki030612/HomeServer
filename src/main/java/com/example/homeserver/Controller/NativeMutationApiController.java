package com.example.homeserver.Controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.CustomThumbnailService;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.InvalidThumbnailException;
import com.example.homeserver.Service.InvalidVideoFileException;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.UnsupportedVideoConversionException;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoUrlImportJobService;

/** Native mutation facade. Existing MVC routes and services stay unchanged. */
@RestController
@RequestMapping("/api/v1")
public class NativeMutationApiController {
    private final VideoService videos;
    private final FolderService folders;
    private final TagService tags;
    private final CustomThumbnailService thumbnails;
    private final VideoUrlImportJobService imports;

    public NativeMutationApiController(VideoService videos, FolderService folders, TagService tags,
            CustomThumbnailService thumbnails, VideoUrlImportJobService imports) {
        this.videos = videos;
        this.folders = folders;
        this.tags = tags;
        this.thumbnails = thumbnails;
        this.imports = imports;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/folders")
    public ResponseEntity<LibraryApiController.FolderResponse> createFolder(@RequestBody FolderWriteRequest request) {
        String name = required(request.name(), "FOLDER_NAME_REQUIRED", "フォルダー名を入力してください。");
        if (request.parentId() != null && folders.getFolderById(request.parentId()) == null) {
            throw error(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND", "親フォルダーが見つかりません。");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LibraryApiController.FolderResponse.from(folders.createFolder(name, request.parentId())));
    }

    @PatchMapping("/folders/{id}")
    public LibraryApiController.FolderResponse renameFolder(@PathVariable Long id,
            @RequestBody FolderWriteRequest request) {
        if (folders.getFolderById(id) == null) throw error(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND", "フォルダーが見つかりません。");
        folders.renameFolder(id, required(request.name(), "FOLDER_NAME_REQUIRED", "フォルダー名を入力してください。"));
        return LibraryApiController.FolderResponse.from(folders.getFolderById(id));
    }

    @DeleteMapping("/folders/{id}")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id) {
        if (folders.getFolderById(id) == null) throw error(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND", "フォルダーが見つかりません。");
        if (!folders.deleteFolder(id)) throw error(HttpStatus.CONFLICT, "FOLDER_NOT_EMPTY", "中身のあるフォルダーは削除できません。");
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/videos/{id}")
    public LibraryApiController.VideoResponse editVideo(@PathVariable Long id, @RequestBody VideoEditRequest request) {
        Video current = requireVideo(id);
        String title = request.title() == null ? current.getTitle()
                : required(request.title(), "TITLE_REQUIRED", "タイトルを入力してください。");
        String tagText = request.tags() == null
                ? current.getTags().stream().map(tag -> tag.getName()).sorted().reduce((a, b) -> a + "," + b).orElse("")
                : String.join(",", request.tags());
        videos.updateVideo(id, title, tagText);
        return LibraryApiController.VideoResponse.from(requireVideo(id));
    }

    @DeleteMapping("/videos/{id}")
    public ResponseEntity<Void> deleteVideo(@PathVariable Long id) {
        requireVideo(id);
        videos.deleteVideo(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/videos/{id}/favorite")
    public LibraryApiController.VideoResponse favorite(@PathVariable Long id, @RequestBody FavoriteRequest request) {
        Video video = videos.setFavorite(id, request.favorite());
        if (video == null) throw error(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "動画が見つかりません。");
        return LibraryApiController.VideoResponse.from(video);
    }

    @PutMapping("/videos/{id}/folder")
    public LibraryApiController.VideoResponse move(@PathVariable Long id, @RequestBody MoveRequest request) {
        Video current = requireVideo(id);
        if (request.folderId() != null && folders.getFolderById(request.folderId()) == null) {
            throw error(HttpStatus.NOT_FOUND, "FOLDER_NOT_FOUND", "移動先フォルダーが見つかりません。");
        }
        Long currentFolderId = current.getFolder() == null ? null : current.getFolder().getId();
        if (java.util.Objects.equals(currentFolderId, request.folderId())) {
            throw error(HttpStatus.CONFLICT, "ALREADY_IN_FOLDER", "動画はすでに選択した場所にあります。");
        }
        videos.moveVideo(id, request.folderId());
        return LibraryApiController.VideoResponse.from(requireVideo(id));
    }

    @DeleteMapping("/tags/{id}")
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        if (!tags.deleteTag(id)) throw error(HttpStatus.CONFLICT, "TAG_IN_USE", "使用中のタグは削除できません。");
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/videos/uploads", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> uploadVideo(@RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long folderId) {
        try {
            videos.upload(file, folderId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "COMPLETED"));
        } catch (InvalidVideoFileException | UnsupportedVideoConversionException exception) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VIDEO", exception.getMessage());
        }
    }

    @PostMapping(path = "/videos/{id}/thumbnail", consumes = "multipart/form-data")
    public Map<String, Object> uploadThumbnail(@PathVariable Long id,
            @RequestParam("thumbnail") MultipartFile thumbnail) {
        try {
            Video video = thumbnails.upload(id, thumbnail);
            if (video == null) throw error(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "動画が見つかりません。");
            return thumbnailResponse(video);
        } catch (InvalidThumbnailException exception) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_THUMBNAIL", exception.getMessage());
        }
    }

    @DeleteMapping("/videos/{id}/thumbnail")
    public Map<String, Object> resetThumbnail(@PathVariable Long id) {
        Video video = thumbnails.reset(id);
        if (video == null) throw error(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "動画が見つかりません。");
        return thumbnailResponse(video);
    }

    @PostMapping("/video-import-jobs")
    public ResponseEntity<ImportStartedResponse> startImport(@RequestBody ImportRequest request, Principal principal) {
        var result = imports.startOrReuse(required(request.url(), "URL_REQUIRED", "URLを入力してください。"),
                request.folderId(), owner(principal));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ImportStartedResponse(result.jobId(), result.reused()));
    }

    @GetMapping("/video-import-jobs")
    public List<VideoUrlImportJobService.JobProgress> importJobs(Principal principal) {
        return imports.recent(owner(principal));
    }

    @GetMapping("/video-import-jobs/{id}")
    public VideoUrlImportJobService.JobProgress importJob(@PathVariable UUID id, Principal principal) {
        return imports.find(id, owner(principal))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "取込ジョブが見つかりません。"));
    }

    @PostMapping("/video-import-jobs/{id}/cancel")
    public VideoUrlImportJobService.JobProgress cancelImport(@PathVariable UUID id, Principal principal) {
        return imports.cancel(id, owner(principal))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "取込ジョブが見つかりません。"));
    }

    private Video requireVideo(Long id) {
        Video video = videos.getVideoById(id);
        if (video == null) throw error(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "動画が見つかりません。");
        return video;
    }

    private static String required(String value, String code, String message) {
        if (value == null || value.isBlank()) throw error(HttpStatus.BAD_REQUEST, code, message);
        return value.trim();
    }

    private static String owner(Principal principal) { return principal == null ? "anonymous" : principal.getName(); }
    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ":" + message);
    }
    private static Map<String, Object> thumbnailResponse(Video video) {
        return Map.of("id", video.getId(), "version",
                video.getCustomThumbnailVersion() == null ? 0L : video.getCustomThumbnailVersion(),
                "custom", video.getCustomThumbnailName() != null);
    }

    public record CsrfResponse(String headerName, String parameterName, String token) { }
    public record FolderWriteRequest(String name, Long parentId) { }
    public record VideoEditRequest(String title, List<String> tags) { }
    public record FavoriteRequest(boolean favorite) { }
    public record MoveRequest(Long folderId) { }
    public record ImportRequest(String url, Long folderId) { }
    public record ImportStartedResponse(UUID jobId, boolean reused) { }
}
