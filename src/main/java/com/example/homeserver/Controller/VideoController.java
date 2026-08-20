package com.example.homeserver.Controller;

import java.io.IOException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoStreamService;
import com.example.homeserver.Service.VideoUrlImportJobService;
import com.example.homeserver.Service.InvalidVideoFileException;
import com.example.homeserver.Service.UnsupportedVideoConversionException;

@Controller
@RequestMapping("/videos")
public class VideoController {
	private static final Logger logger = LoggerFactory.getLogger(VideoController.class);

	@Autowired
	private VideoService videoService;

	@Autowired
	private VideoStreamService videoStreamService;

	@Autowired
	private FolderService folderService;

	@Autowired
	private TagService tagService;

	@Autowired
	private VideoUrlImportJobService videoUrlImportJobService;

	// =========================
	// 動画一覧表示
	// =========================

	@GetMapping("")
	public String getAllVideos(
	        @RequestParam(required = false) String tagDeleteError,
	        @RequestParam(required = false) String keyword,
	        @RequestParam(required = false) String tag,
	        @RequestParam(defaultValue = "newest") String sort,
	        Model model){
		
		if (tagDeleteError != null) {

		    model.addAttribute(
		            "tagDeleteError",
		            "このタグは動画で使用されているため削除できません。"
		    );

		}
		
		model.addAttribute("keyword", keyword);
		model.addAttribute("sort", sort);
		model.addAttribute("selectedTag", tag);

		List<Video> videos;

		if (tag != null && !tag.isBlank() && keyword != null && !keyword.isBlank()) {

			videos = videoService.searchVideosByTag(keyword, tag, sort);

		} else if (tag != null && !tag.isBlank()) {

		    videos = videoService.getVideosByTag(tag, sort);

		} else if (keyword == null || keyword.isBlank()) {

		    videos = videoService.getAllVideos(sort);

		} else {

		    videos = videoService.searchVideos(keyword, sort);

		}

		model.addAttribute("videos", videos);

		// ヘッダーの検索欄に入力した文字を残す
		model.addAttribute("keyword", keyword);

		// =========================
		// ルートフォルダ取得
		// =========================

		List<Folder> folders = folderService.getRootFolders();

		model.addAttribute(
				"folders",
				folders);
		
		// 登録済みタグ
	    model.addAttribute(
	            "tags",
	            tagService.getAllTags()
	    );
	    
	    List<Folder> allFolders =
	            folderService.getAllFolders();

	    model.addAttribute(
	            "allFolders",
	            allFolders
	    );

		return "video/list";
	}

	// =========================
	// フォルダを開く
	// =========================

	@GetMapping("/folder/{id}")
	public String openFolder(
	        @PathVariable Long id,
	        @RequestParam(required = false) String keyword,
	        @RequestParam(required = false) String tag,
	        @RequestParam(defaultValue = "newest") String sort,
	        Model model) {

	    // フォルダ取得
	    Folder folder = folderService.getFolderById(id);

	    // 存在しないフォルダの場合
	    if (folder == null) {
	        return "redirect:/videos";
	    }

	    // =========================
	    // フォルダ内動画取得
	    // =========================

	    List<Video> videos;

	    if (tag != null && !tag.isBlank() && keyword != null && !keyword.isBlank()) {

	        videos = videoService.searchVideosByFolderAndTag(id, keyword, tag, sort);

	    } else if (tag != null && !tag.isBlank()) {

	        videos = videoService.getVideosByFolderAndTag(id, tag, sort);

	    } else if (keyword == null || keyword.isBlank()) {

	        // 検索なし
	        videos = videoService.getVideosByFolder(id, sort);

	    } else {

	        // フォルダ内検索
	        videos = videoService.searchVideosByFolder(
	                id,
	                keyword,
	                sort
	        );
	    }

	    // =========================
	    // フォルダ情報
	    // =========================

	    List<Folder> allFolders =
	            folderService.getAllFolders();

	    model.addAttribute(
	            "allFolders",
	            allFolders
	    );

	    List<Folder> folders =
	            folderService.getChildFolders(id);

	    model.addAttribute(
	            "folders",
	            folders
	    );

	    model.addAttribute(
	            "videos",
	            videos
	    );

	    // 現在のフォルダ
	    model.addAttribute(
	            "currentFolder",
	            folder
	    );

	    // 検索・ソート状態
	    model.addAttribute(
	            "keyword",
	            keyword
	    );

	    model.addAttribute(
	            "sort",
	            sort
	    );

	    model.addAttribute(
	            "selectedTag",
	            tag
	    );

	    // 登録済みタグ
	    model.addAttribute(
	            "tags",
	            tagService.getAllTags()
	    );

	    // パンくず
	    List<Folder> breadcrumbs =
	            folderService.getBreadcrumbs(id);

	    model.addAttribute(
	            "breadcrumbs",
	            breadcrumbs
	    );

	    return "video/list";
	}


	// =========================
	// 動画再生
	// =========================

	@GetMapping("/play/{id}")
	public ResponseEntity<StreamingResponseBody> playVideo(
			@PathVariable Long id,
			@RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
			throws IOException {

		Path video = videoService.getVideoPath(id);
		if (video == null) {
			return ResponseEntity.notFound().build();
		}

		return videoStreamService.stream(video, range);
	}

	// =========================
	// 動画視聴画面
	// =========================

	@GetMapping("/view/{id}")
	public String viewVideo(
	        @PathVariable Long id,
	        Model model) {

	    Video video =
	            videoService.getVideoById(id);

	    if (video == null) {
	        return "redirect:/videos";
	    }

	    // 現在の動画
	    model.addAttribute(
	            "video",
	            video);

	    // 関連動画
	    model.addAttribute(
	            "relatedVideos",
	            videoService.getRelatedVideos(id));

	    return "video/play";
	}

	// =========================
	// アップロード画面
	// =========================

	@GetMapping("/upload")
	public String uploadPage(
			@RequestParam(value = "folderId", required = false) Long folderId,
			Model model, Principal principal) {
		if (folderId != null) {
			Folder folder = folderService.getFolderById(folderId);
			if (folder == null) return "redirect:/videos";
			model.addAttribute("currentFolder", folder);
		}
		var urlImportJobs = videoUrlImportJobService.recent(principal.getName());
		model.addAttribute("urlImportJobs", urlImportJobs);
		model.addAttribute("urlImportActiveJobs", urlImportJobs.stream()
				.filter(job -> !job.state().terminal()).toList());
		model.addAttribute("urlImportHistoryJobs", urlImportJobs.stream()
				.filter(job -> job.state().terminal()).toList());

		return "video/upload";

	}

	// =========================
	// 動画アップロード
	// =========================

	@PostMapping("/upload")
	public ResponseEntity<?> upload(
	        @RequestParam("file") MultipartFile file,
	        @RequestParam(value = "folderId", required = false) Long folderId) {

	    try {
	        videoService.upload(file, folderId);
	        return ResponseEntity.ok().build();
	    } catch (InvalidVideoFileException | UnsupportedVideoConversionException e) {
	        return ResponseEntity.badRequest().body(e.getMessage());
	    } catch (Exception e) {
	        logger.error("Video upload failed", e);
	        return ResponseEntity.badRequest().body(
	                "アップロードに失敗しました。保存先の空き容量とサーバー設定を確認してください。");
	    }
	}

	@PostMapping("/import-url")
	public ResponseEntity<?> importFromUrl(
			@RequestParam("url") String url,
			@RequestParam(value = "folderId", required = false) Long folderId,
			Principal principal) {
		var result = videoUrlImportJobService.startOrReuse(url, folderId, principal.getName());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ImportJobStarted(result.jobId(), result.reused()));
	}

	@GetMapping("/import-url/progress/{jobId}")
	public ResponseEntity<VideoUrlImportJobService.JobProgress> importProgress(
			@PathVariable UUID jobId, Principal principal) {
		return videoUrlImportJobService.find(jobId, principal.getName())
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/import-url/jobs")
	public ResponseEntity<List<VideoUrlImportJobService.JobProgress>> importJobs(Principal principal) {
		return ResponseEntity.ok(videoUrlImportJobService.recent(principal.getName()));
	}

	@PostMapping("/import-url/{jobId}/cancel")
	public ResponseEntity<VideoUrlImportJobService.JobProgress> cancelImport(
			@PathVariable UUID jobId, Principal principal) {
		return videoUrlImportJobService.cancel(jobId, principal.getName())
				.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
	}

	private record ImportJobStarted(UUID jobId, boolean reused) { }
	// =========================
	// 動画削除
	// =========================

	@PostMapping("/delete/{id}")
	public String deleteVideo(
			@PathVariable Long id) {

		videoService.deleteVideo(id);

		return "redirect:/videos";

	}

	// =========================
	// 名前変更画面
	// =========================

	@GetMapping("/edit/{id}")
	public String editVideo(
			@PathVariable Long id,
			Model model) {

		Video video = videoService.getVideoById(id);
		if (video == null) {
			return "redirect:/videos";
		}

		model.addAttribute(
				"video",
				video);

		return "video-edit";

	}

	// =========================
	// 名前変更・タグ更新
	// =========================

	@PostMapping("/edit")
	public String editVideo(
	        @RequestParam Long id,
	        @RequestParam String title,
	        @RequestParam(required = false) String tags
	) {

	    videoService.updateVideo(
	            id,
	            title,
	            tags
	    );

	    return "redirect:/videos";
	}
	
	// =========================
	// タグ削除
	// =========================

	@PostMapping("/tag/delete/{id}")
	public String deleteTag(
	        @PathVariable Long id,
	        Model model) {

	    boolean deleted =
	            tagService.deleteTag(id);
	    if (!deleted) {

	        return "redirect:/videos?tagDeleteError";

	    }
	    return "redirect:/videos";

	}

	// =========================
	// 動画をフォルダーへ移動
	// =========================

		@PostMapping("/move")
		public ResponseEntity<?> moveVideo(
		        @RequestParam Long videoId,
		        @RequestParam(required = false) Long folderId) {

		    try {
		        videoService.moveVideo(videoId, folderId);
		        return ResponseEntity.ok().build();
		    } catch (Exception e) {
		        logger.warn("Video move failed: {}", e.getMessage());
		        return ResponseEntity.badRequest().body("動画を移動できませんでした。");
		    }
		}


}
