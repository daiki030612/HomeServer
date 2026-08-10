package com.example.homeserver.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Tag;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.FolderRepository;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Repository.VideoRepository;

@Service
public class VideoService {

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private ThumbnailService thumbnailService;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private FolderRepository folderRepository;

	// 動画保存場所
	@Value("${video.storage.path}")
	private String videoStoragePath;

	// 動画一覧取得
	public List<Video> getAllVideos() {

	    return videoRepository.findByFolderIsNull();

	}

	// 動画保存
	public void saveVideo(Video video) {

		videoRepository.save(video);

	}

	// ID検索
	public Video getVideoById(Long id) {

		return videoRepository.findById(id)
				.orElse(null);

	}

	// 動画再生用Resource取得
	public Resource getVideo(Long id) {

		Optional<Video> optionalVideo = videoRepository.findById(id);

		if (optionalVideo.isEmpty()) {
			return null;
		}

		Video video = optionalVideo.get();

		Path path;

		// filePathが登録されている場合
		if (video.getFilePath() != null &&
				!video.getFilePath().isBlank()) {

			path = Paths.get(video.getFilePath());

		} else {

			// 古いデータ対策
			path = Paths.get(
					videoStoragePath,
					video.getFileName());
		}

		Resource resource = new FileSystemResource(path);

		if (resource.exists() &&
				resource.isReadable()) {

			return resource;
		}

		return null;
	}

	// 動画アップロード
	public void upload(MultipartFile file, Long folderId) {

		try {

			// 拡張子取得
			String originalName = file.getOriginalFilename();

			String extension = "";

			if (originalName != null && originalName.contains(".")) {
				extension = originalName.substring(
						originalName.lastIndexOf("."));
			}

			// 保存用ファイル名
			String fileName = UUID.randomUUID() + extension;

			// 動画保存先
			Path savePath = Paths.get(
					videoStoragePath,
					fileName);

			System.out.println("====================");
			System.out.println("ORIGINAL NAME : " + originalName);
			System.out.println("GENERATED NAME: " + fileName);
			System.out.println("SAVE PATH     : " + savePath);
			System.out.println("====================");

			// ★① ファイル保存
			System.out.println("① ファイル保存開始");

			file.transferTo(savePath.toFile());

			System.out.println("② ファイル保存完了");

			// サムネイル名
			String thumbnailName = fileName.substring(
					0,
					fileName.lastIndexOf("."))
					+ ".jpg";

			// サムネイル保存先
			Path thumbnailPath = Paths.get(
					videoStoragePath,
					"thumbnails",
					thumbnailName);

			// ★② サムネイル生成
			System.out.println("③ サムネイル生成開始");

			thumbnailService.createThumbnail(
					savePath.toString(),
					thumbnailPath.toString());

			System.out.println("④ サムネイル生成完了");

			// DB登録
			Video video = new Video();

			video.setFileName(
					fileName);

			video.setTitle(
					originalName);

			video.setThumbnailName(
					thumbnailName);

			video.setFilePath(
					savePath.toString());

			video.setThumbnailPath(
					thumbnailPath.toString());

			// =========================
			// フォルダ設定
			// =========================

			if (folderId != null) {

				Folder folder = folderRepository
						.findById(folderId)
						.orElse(null);

				if (folder != null) {

					video.setFolder(folder);

				}

			}

			System.out.println("====================");
			System.out.println("FILE PATH : " + video.getFilePath());
			System.out.println("THUMB PATH: " + video.getThumbnailPath());
			System.out.println("====================");

			// ★③ DB保存
			System.out.println("⑤ DB保存開始");

			videoRepository.save(video);

			System.out.println("⑥ DB保存完了");

		} catch (Exception e) {

			e.printStackTrace();

		}

	}

	// 動画削除
	public void deleteVideo(Long id) {

		Video video = videoRepository.findById(id)
				.orElseThrow();

		// 動画削除

		if (video.getFilePath() != null) {

			File videoFile = new File(
					video.getFilePath());

			if (videoFile.exists()) {

				videoFile.delete();

			}

		}

		// サムネ削除

		if (video.getThumbnailPath() != null) {

			File thumbnailFile = new File(
					video.getThumbnailPath());

			if (thumbnailFile.exists()) {

				thumbnailFile.delete();

			}

		}

		// DB削除

		videoRepository.delete(video);

	}

	public List<Video> searchVideos(String keyword) {

		return videoRepository
				.searchByTitleOrTag(keyword);

	}

	// フォルダ内の動画取得
	public List<Video> getVideosByFolder(Long folderId) {

		return videoRepository.findByFolderId(folderId);

	}

	// =========================
	// 動画タイトル・タグ更新
	// =========================

	public void updateVideo(
			Long id,
			String title,
			String tags) {

		Video video = videoRepository
				.findById(id)
				.orElse(null);

		if (video == null) {
			return;
		}

		// タイトル更新
		video.setTitle(title);

		// =========================
		// タグ更新
		// =========================

		// 現在のタグを削除
		video.getTags().clear();
		// タグが入力されている場合
		if (tags != null && !tags.isBlank()) {

			String[] tagNames = tags.split(",");
			for (String tagName : tagNames) {
				// 前後の空白を削除
				String name = tagName.trim();
				// 空文字は無視
				if (name.isBlank()) {
					continue;
				}
				// 既存タグを検索
				Tag tag = tagRepository
						.findByName(name);
				// 存在しなければ新規作成
				if (tag == null) {
					tag = new Tag(name);
					tag = tagRepository.save(tag);
				}

				// 動画にタグを追加
				video.getTags().add(tag);

			}

		}

		// 動画を保存
		videoRepository.save(video);

	}

	public void moveVideo(
			Long videoId,
			Long folderId) {

		Video video = videoRepository
				.findById(videoId)
				.orElse(null);

		if (video == null) {
			return;
		}
		// =========================
		// ルートへ移動
		// =========================

		if (folderId == null) {
			video.setFolder(null);
		}

		// =========================
		// フォルダーへ移動
		// =========================

		else {

			Folder folder = folderRepository
					.findById(folderId)
					.orElse(null);

			if (folder == null) {
				return;
			}

			video.setFolder(folder);

		}
		// DB更新
		videoRepository.save(video);
	}

}