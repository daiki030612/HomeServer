package com.example.homeserver.Controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Tag;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.StorageInfo;
import com.example.homeserver.Service.StorageService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;

/**
 * Read-only API for native clients. Existing MVC endpoints remain unchanged so
 * the browser application keeps its current routes and behavior.
 */
@RestController
@RequestMapping("/api/v1")
public class LibraryApiController {
    private final VideoService videos;
    private final FolderService folders;
    private final TagService tags;
    private final StorageService storage;

    public LibraryApiController(VideoService videos, FolderService folders,
            TagService tags, StorageService storage) {
        this.videos = videos;
        this.folders = folders;
        this.tags = tags;
        this.storage = storage;
    }

    @GetMapping("/folders")
    public List<FolderResponse> folders(@RequestParam(required = false) Long parentId) {
        List<Folder> result = parentId == null
                ? folders.getRootFolders()
                : folders.getChildFolders(parentId);
        return result.stream().map(FolderResponse::from).toList();
    }

    @GetMapping("/folders/tree")
    public List<FolderResponse> folderTree() {
        return folders.getAllFolders().stream().map(FolderResponse::from).toList();
    }

    @GetMapping("/videos")
    public VideoListResponse videoList(
            @RequestParam(required = false) Long folderId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(defaultValue = "newest") String sort) {
        FolderResponse currentFolder = null;
        List<FolderResponse> breadcrumbs = List.of();
        if (folderId != null) {
            Folder folder = folders.getFolderById(folderId);
            if (folder == null) throw notFound("FOLDER_NOT_FOUND", "フォルダーが見つかりません。");
            currentFolder = FolderResponse.from(folder);
            breadcrumbs = folders.getBreadcrumbs(folderId).stream().map(FolderResponse::from).toList();
        }
        List<VideoResponse> items = videos.findLibraryVideos(folderId, query, tag, favorite, sort)
                .stream().map(VideoResponse::from).toList();
        List<FolderResponse> childFolders = (folderId == null ? folders.getRootFolders() : folders.getChildFolders(folderId))
                .stream().map(FolderResponse::from).toList();
        return new VideoListResponse(items, childFolders, breadcrumbs, currentFolder,
                tags.getAllTags().stream().map(TagResponse::from).toList(),
                new VideoFilterResponse(query == null ? "" : query, tag == null ? "" : tag, favorite, sort));
    }

    @GetMapping("/videos/{id}")
    public VideoDetailResponse video(@PathVariable Long id) {
        Video video = videos.getVideoById(id);
        if (video == null) throw notFound("VIDEO_NOT_FOUND", "動画が見つかりません。");
        return new VideoDetailResponse(VideoResponse.from(video),
                videos.getRelatedVideos(id).stream().map(VideoResponse::from).toList());
    }

    @GetMapping("/tags")
    public List<TagResponse> tags() {
        return tags.getAllTags().stream().map(TagResponse::from).toList();
    }

    @GetMapping("/storage")
    public StorageInfo storage() {
        return storage.current();
    }

    private static ResponseStatusException notFound(String code, String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code + ":" + message);
    }

    public record FolderResponse(Long id, String name, Integer sortOrder, Long parentId) {
        static FolderResponse from(Folder folder) {
            return new FolderResponse(folder.getId(), folder.getName(), folder.getSortOrder(),
                    folder.getParent() == null ? null : folder.getParent().getId());
        }
    }

    public record TagResponse(Long id, String name) {
        static TagResponse from(Tag tag) { return new TagResponse(tag.getId(), tag.getName()); }
    }

    public record VideoResponse(Long id, String title, boolean favorite, LocalDateTime createdAt,
            FolderResponse folder, List<TagResponse> tags, String thumbnailUrl,
            String streamUrl, String downloadUrl) {
        static VideoResponse from(Video video) {
            return new VideoResponse(video.getId(), video.getTitle(), video.isFavorite(), video.getCreatedAt(),
                    video.getFolder() == null ? null : FolderResponse.from(video.getFolder()),
                    video.getTags().stream().map(TagResponse::from).sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList(),
                    "/videos/" + video.getId() + "/thumbnail?v=" + nullableVersion(video.getCustomThumbnailVersion()),
                    "/videos/play/" + video.getId(), "/videos/" + video.getId() + "/download");
        }

        private static long nullableVersion(Long version) { return version == null ? 0 : version; }
    }

    public record VideoFilterResponse(String q, String tag, boolean favorite, String sort) { }
    public record VideoListResponse(List<VideoResponse> videos, List<FolderResponse> folders,
            List<FolderResponse> breadcrumbs, FolderResponse currentFolder,
            List<TagResponse> availableTags, VideoFilterResponse filters) { }
    public record VideoDetailResponse(VideoResponse video, List<VideoResponse> relatedVideos) { }
}
