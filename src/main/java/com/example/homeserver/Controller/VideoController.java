package com.example.homeserver.Controller;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;

@Controller
@RequestMapping("/videos")
public class VideoController {

	@Autowired
	private VideoService videoService;

	@Autowired
	private FolderService folderService;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private TagService tagService;

	// =========================
	// 動画一覧表示
	// =========================

	@GetMapping("")
	public String getAllVideos(
			@RequestParam(required = false) String tagDeleteError,
			@RequestParam(required = false) String keyword,
			Model model) {
		
		if (tagDeleteError != null) {

		    model.addAttribute(
		            "tagDeleteError",
		            "このタグは動画で使用されているため削除できません。"
		    );

		}

		List<Video> videos;

		if (keyword == null || keyword.isBlank()) {

			// 検索キーワードがない
			videos = videoService.getAllVideos();

		} else {

			// 検索キーワードがある
			videos = videoService.searchVideos(keyword);

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

		return "video/list";
	}

	// =========================
	// フォルダを開く
	// =========================

	@GetMapping("/folder/{id}")
	public String openFolder(
			@PathVariable Long id,
			Model model) {

		// フォルダ取得
		Folder folder = folderService.getFolderById(id);

		// 存在しないフォルダの場合
		if (folder == null) {

			return "redirect:/videos";

		}

		// フォルダ内の動画を取得
		List<Video> videos = videoService.getVideosByFolder(id);

		// フォルダ内の子フォルダを取得
		List<Folder> folders = folderService.getChildFolders(id);

		model.addAttribute(
				"videos",
				videos);

		model.addAttribute(
				"folders",
				folders);

		// 現在開いているフォルダ
		model.addAttribute(
				"currentFolder",
				folder);
		
		// 登録済みタグ
		model.addAttribute("tags", tagService.getAllTags());

		return "video/list";
	}

	// =========================
	// 動画再生
	// =========================

	@GetMapping("/play/{id}")
	public ResponseEntity<Resource> playVideo(
			@PathVariable Long id,
			@RequestHeader HttpHeaders headers) throws IOException {

		Resource resource = videoService.getVideo(id);

		if (resource == null) {

			return ResponseEntity
					.notFound()
					.build();

		}

		long fileLength = resource.contentLength();

		return ResponseEntity
				.ok()
				.header(
						HttpHeaders.ACCEPT_RANGES,
						"bytes")
				.contentType(
						MediaType.parseMediaType(
								"video/mp4"))
				.contentLength(fileLength)
				.body(resource);
	}

	// =========================
	// 動画視聴画面
	// =========================

	@GetMapping("/view/{id}")
	public String viewVideo(
			@PathVariable Long id,
			Model model) {

		Video video = videoService.getVideoById(id);

		if (video == null) {

			return "redirect:/videos";

		}

		model.addAttribute(
				"video",
				video);

		return "video/play";
	}

	// =========================
	// アップロード画面
	// =========================

	@GetMapping("/upload")
	public String uploadPage() {

		return "video/upload";

	}

	// =========================
	// 動画アップロード
	// =========================

	@PostMapping("/upload")
	public String upload(
	        @RequestParam("file") MultipartFile file,
	        @RequestParam(value = "folderId", required = false) Long folderId) {

	    System.out.println("① Controller開始");
	    System.out.println("folderId = " + folderId);

	    videoService.upload(file, folderId);

	    System.out.println("② Service完了");

	    if (folderId != null) {
	        return "redirect:/videos/folder/" + folderId;
	    }

	    return "redirect:/videos";
	}
	// =========================
	// 動画削除
	// =========================

	@GetMapping("/delete/{id}")
	public String deleteVideo(
			@PathVariable Long id) {

		videoService.deleteVideo(id);

		return "redirect:/videos";

	}

	// =========================
	// 名前変更画面
	// =========================

	@GetMapping("/videos/edit/{id}")
	public String editVideo(
			@PathVariable Long id,
			Model model) {

		Video video = videoService.getVideoById(id);

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

	@GetMapping("/tag/delete/{id}")
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



}
