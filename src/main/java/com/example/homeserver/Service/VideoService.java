package com.example.homeserver.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Video;
import com.example.homeserver.VideoRepository;

@Service
public class VideoService {

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private ThumbnailService thumbnailService;

	// 動画保存場所
	@Value("${video.storage.path}")
	private String videoStoragePath;

	// 動画一覧取得
	public List<Video> getAllVideos() {

		return videoRepository.findAll();

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
	public void upload(MultipartFile file) {

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

			Files.copy(
					file.getInputStream(),
					savePath,
					StandardCopyOption.REPLACE_EXISTING);

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

			// サムネ生成
			thumbnailService.createThumbnail(
					savePath.toString(),
					thumbnailPath.toString());

			// DB登録
			Video video = new Video();

			video.setFileName(
					fileName);

			video.setTitle(
					originalName);

			video.setThumbnailName(
					thumbnailName);

			// ★追加
			video.setFilePath(
					savePath.toString());

			// ★追加
			video.setThumbnailPath(
					thumbnailPath.toString());

			System.out.println("====================");
			System.out.println("FILE PATH : " + video.getFilePath());
			System.out.println("THUMB PATH: " + video.getThumbnailPath());
			System.out.println("====================");

			videoRepository.save(video);

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

}