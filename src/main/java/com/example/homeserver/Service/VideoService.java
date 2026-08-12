package com.example.homeserver.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
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
	private static final Logger logger = LoggerFactory.getLogger(VideoService.class);

	@Autowired
	private VideoRepository videoRepository;

	@Autowired
	private ThumbnailService thumbnailService;

	@Autowired
	private VideoProbeService videoProbeService;

	@Autowired
	private IPhoneSafariCompatibilityService compatibilityService;

	@Autowired
	private VideoRemuxService videoRemuxService;

	@Autowired
	private VideoAudioTranscodeService videoAudioTranscodeService;

	@Autowired
	private TagRepository tagRepository;

	@Autowired
	private FolderRepository folderRepository;

	// 動画保存場所
	@Value("${video.storage.path}")
	private String videoStoragePath;

	@Value("${thumbnail.storage.path}")
	private String thumbnailStoragePath;

	// 動画一覧取得
	public List<Video> getAllVideos(String sort) {

		if ("oldest".equals(sort)) {

			return videoRepository
					.findByFolderIsNullOrderByCreatedAtAsc();

		}

		if ("nameAsc".equals(sort)) {

			return videoRepository
					.findByFolderIsNullOrderByTitleAsc();

		}

		if ("nameDesc".equals(sort)) {

			return videoRepository
					.findByFolderIsNullOrderByTitleDesc();

		}

		// デフォルト：新しい順

		return videoRepository
				.findByFolderIsNullOrderByCreatedAtDesc();
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
		Path savePath = null;
		Path adoptedVideoPath = null;
		Path thumbnailPath = null;
		UploadArtifacts artifacts = null;
		boolean databaseSaved = false;

		try {
				// ファイルが空でないかチェック
				if (file.isEmpty()) {
					throw new RuntimeException("ファイルが選択されていません。");
				}

				// 拡張子取得
				String originalName = file.getOriginalFilename();
				String extension = "";

				if (originalName != null && originalName.contains(".")) {
					extension = originalName.substring(
							originalName.lastIndexOf(".")).toLowerCase();
				}

				// 動画ファイルかチェック（簡易的）
				if (!extension.equals(".mp4") && !extension.equals(".mov") && !extension.equals(".avi")) {
					throw new RuntimeException("許可されていないファイル形式です。(.mp4, .mov, .avi のみ)");
				}

				// 保存用ファイル名
				String fileName = UUID.randomUUID() + extension;

			// 動画保存先
			Path videoStorageRoot = storageRoot(videoStoragePath);
			Files.createDirectories(videoStorageRoot);
			Path thumbnailStorageRoot = storageRoot(thumbnailStoragePath);
			Files.createDirectories(thumbnailStorageRoot);
			artifacts = new UploadArtifacts(videoStorageRoot, thumbnailStorageRoot);
			savePath = artifacts.track(resolveWithinStorage(videoStorageRoot, fileName));

			System.out.println("====================");
			System.out.println("ORIGINAL NAME : " + originalName);
			System.out.println("GENERATED NAME: " + fileName);
			System.out.println("SAVE PATH     : " + savePath);
			System.out.println("====================");

			// ★① ファイル保存
			System.out.println("① ファイル保存開始");

			file.transferTo(savePath.toFile());

			System.out.println("② ファイル保存完了");

			VideoMetadata metadata = videoProbeService.probe(savePath, videoStorageRoot);
			VideoCompatibilityDecision decision = compatibilityService.evaluate(metadata);
			logCompatibility(originalName, metadata, compatibilityService.assess(metadata));

			if (decision == VideoCompatibilityDecision.PASSTHROUGH) {
				adoptedVideoPath = savePath;
			} else if (decision == VideoCompatibilityDecision.REMUX) {
				String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
				Path temporaryRemuxPath = artifacts.track(resolveWithinStorage(
						videoStorageRoot, "." + baseName + ".remux.tmp.mp4"));
				Path finalRemuxPath = artifacts.track(resolveWithinStorage(
						videoStorageRoot, baseName + ".mp4"));

				int normalVideoStreamIndex = metadata.normalVideoStreams().stream()
						.findFirst()
						.orElseThrow(() -> new IllegalStateException("Normal video stream is missing"))
						.streamIndex();
				videoRemuxService.remux(
						savePath, temporaryRemuxPath, videoStorageRoot, normalVideoStreamIndex);
				VideoMetadata remuxedMetadata = videoProbeService.probe(
						temporaryRemuxPath, videoStorageRoot);
				if (compatibilityService.evaluate(remuxedMetadata)
						!= VideoCompatibilityDecision.PASSTHROUGH) {
					throw new IllegalStateException("Remuxed video did not pass compatibility validation");
				}

				moveIntoPlace(temporaryRemuxPath, finalRemuxPath);
				adoptedVideoPath = finalRemuxPath;
				fileName = finalRemuxPath.getFileName().toString();
			} else if (decision == VideoCompatibilityDecision.TRANSCODE_AUDIO) {
				String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
				Path temporaryTranscodePath = artifacts.track(resolveWithinStorage(
						videoStorageRoot, "." + baseName + ".audio.tmp.mp4"));
				Path finalTranscodePath = artifacts.track(resolveWithinStorage(
						videoStorageRoot, baseName + ".mp4"));

				videoAudioTranscodeService.transcodeAudio(
						savePath, temporaryTranscodePath, videoStorageRoot);
				VideoMetadata transcodedMetadata = videoProbeService.probe(
						temporaryTranscodePath, videoStorageRoot);
				if (compatibilityService.evaluate(transcodedMetadata)
						!= VideoCompatibilityDecision.PASSTHROUGH) {
					throw new IllegalStateException(
							"Audio-transcoded video did not pass compatibility validation");
				}

				moveIntoPlace(temporaryTranscodePath, finalTranscodePath);
				adoptedVideoPath = finalTranscodePath;
				fileName = finalTranscodePath.getFileName().toString();
			} else {
				throw new UnsupportedVideoConversionException();
			}

			// サムネイル名
			String thumbnailName = fileName.substring(
					0,
					fileName.lastIndexOf("."))
					+ ".jpg";

			// サムネイル保存先
			thumbnailPath = artifacts.track(resolveWithinStorage(thumbnailStorageRoot, thumbnailName));

			// ★② サムネイル生成
			System.out.println("③ サムネイル生成開始");

			thumbnailService.createThumbnail(
					adoptedVideoPath.toString(),
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
					adoptedVideoPath.toString());

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

			videoRepository.saveAndFlush(video);
			databaseSaved = true;
			artifacts.cleanupAfterSuccess(adoptedVideoPath, thumbnailPath);

			System.out.println("⑥ DB保存完了");

			} catch (Exception e) {
				if (!databaseSaved) {
					if (artifacts != null) {
						artifacts.cleanupAfterFailure(e);
					} else {
						compensateUpload(savePath, thumbnailPath, e);
					}
				}
				if (e instanceof InvalidVideoFileException invalidVideo) {
					throw invalidVideo;
				}
				if (e instanceof UnsupportedVideoConversionException unsupported) {
					throw unsupported;
				}
				throw new RuntimeException("アップロードに失敗しました。", e);
			}

	}

	// 動画削除
	public void deleteVideo(Long id) {

		Video video = videoRepository.findById(id)
				.orElseThrow();

		Path videoFile = resolveStoredPath(
				video.getFilePath(), video.getFileName(), storageRoot(videoStoragePath));
		Path thumbnailFile = resolveStoredPath(
				video.getThumbnailPath(), video.getThumbnailName(), storageRoot(thumbnailStoragePath));

		// 動画削除

		if (videoFile != null) {

			deleteAndConfirm(videoFile);

		}

		// サムネ削除

		if (thumbnailFile != null) {

			deleteAndConfirm(thumbnailFile);

		}

		// DB削除

		videoRepository.delete(video);

	}

	private Path storageRoot(String configuredPath) {
		return Paths.get(configuredPath).toAbsolutePath().normalize();
	}

	private void logCompatibility(
			String originalName,
			VideoMetadata metadata,
			VideoCompatibilityAssessment assessment) {
		if (assessment == null) {
			return;
		}

		VideoStreamMetadata video = metadata.videoStreams().stream()
				.filter(stream -> !stream.auxiliary())
				.findFirst()
				.orElse(metadata.videoStreams().getFirst());
		AudioStreamMetadata audio = metadata.audioStreams().isEmpty()
				? null : metadata.audioStreams().getFirst();

		logger.info(
				"Video compatibility: originalName={}, container={}, videoCodec={}, videoCodecTag={}, "
				+ "videoProfile={}, videoLevel={}, pixelFormat={}, colorSpace={}, colorTransfer={}, "
				+ "colorPrimaries={}, hdrMetadata={}, dolbyVision={}, resolution={}x{}, fps={}, audioCodec={}, "
				+ "audioProfile={}, audioSampleRate={}, audioChannels={}, fastStart={}, "
				+ "videoStreamCount={}, audioStreamCount={}, decision={}, reasons={}",
				safeLogValue(originalName),
				safeLogValue(metadata.container().formatName()),
				safeLogValue(video.codec()),
				safeLogValue(video.codecTag()),
				safeLogValue(video.profile()),
				video.level(),
				safeLogValue(video.pixelFormat()),
				safeLogValue(video.colorSpace()),
				safeLogValue(video.colorTransfer()),
				safeLogValue(video.colorPrimaries()),
				video.hdrMetadata(),
				video.dolbyVision(),
				video.width(), video.height(), video.framesPerSecond(),
				audio == null ? "none" : safeLogValue(audio.codec()),
				audio == null ? "none" : safeLogValue(audio.profile()),
				audio == null ? 0 : audio.sampleRate(),
				audio == null ? 0 : audio.channels(),
				metadata.container().fastStart(),
				metadata.videoStreams().size(),
				metadata.audioStreams().size(),
				assessment.decision(),
				safeLogValue(String.join("; ", assessment.reasons())));
	}

	private String safeLogValue(String value) {
		if (value == null) {
			return "unknown";
		}
		return value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "_");
	}

	private void moveIntoPlace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private Path resolveWithinStorage(Path storageRoot, String fileName) {
		Path resolved = storageRoot.resolve(fileName).toAbsolutePath().normalize();
		ensureWithinStorage(resolved, storageRoot);
		return resolved;
	}

	private Path resolveStoredPath(String storedPath, String fileName, Path storageRoot) {
		if (storedPath != null && !storedPath.isBlank()) {
			Path resolved = Paths.get(storedPath).toAbsolutePath().normalize();
			ensureWithinStorage(resolved, storageRoot);
			return resolved;
		}

		if (fileName == null || fileName.isBlank()) {
			return null;
		}

		return resolveWithinStorage(storageRoot, fileName);
	}

	private void ensureWithinStorage(Path path, Path storageRoot) {
		try {
			Path verifiedRoot = Files.exists(storageRoot)
					? storageRoot.toRealPath()
					: storageRoot;
			Path verifiedPath;

			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				verifiedPath = path.toRealPath();
			} else if (path.getParent() != null && Files.exists(path.getParent())) {
				verifiedPath = path.getParent().toRealPath()
						.resolve(path.getFileName()).normalize();
			} else {
				verifiedPath = path;
			}

			if (!verifiedPath.startsWith(verifiedRoot)) {
				throw new IllegalArgumentException(
						"Media path is outside the configured storage directory");
			}
		} catch (IOException e) {
			throw new IllegalArgumentException("Media path could not be validated", e);
		}
	}

	private void deleteAndConfirm(Path path) {
		try {
			Files.deleteIfExists(path);
			if (Files.exists(path)) {
				throw new IOException("File still exists after deletion: " + path);
			}
		} catch (IOException e) {
			throw new IllegalStateException(
					"Physical media deletion failed; the database record was not deleted", e);
		}
	}

	private void compensateUpload(Path videoPath, Path thumbnailPath, Exception originalFailure) {
		deleteCompensationFile(thumbnailPath, originalFailure);
		deleteCompensationFile(videoPath, originalFailure);
	}

	private void deleteCompensationFile(Path path, Exception originalFailure) {
		if (path == null) {
			return;
		}

		try {
			Files.deleteIfExists(path);
			if (Files.exists(path)) {
				throw new IOException("Compensation deletion did not remove: " + path);
			}
		} catch (IOException cleanupFailure) {
			originalFailure.addSuppressed(cleanupFailure);
		}
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

	// =========================
	// 動画並び替え
	// =========================

	public List<Video> getVideosByFolder(
			Long folderId,
			String sort) {

		if ("oldest".equals(sort)) {

			return videoRepository
					.findByFolderIdOrderByCreatedAtAsc(folderId);

		}

		if ("nameAsc".equals(sort)) {

			return videoRepository
					.findByFolderIdOrderByTitleAsc(folderId);

		}

		if ("nameDesc".equals(sort)) {

			return videoRepository
					.findByFolderIdOrderByTitleDesc(folderId);

		}

		// デフォルト：新しい順

		return videoRepository
				.findByFolderIdOrderByCreatedAtDesc(folderId);
	}

	public List<Video> searchVideos(
			String keyword,
			String sort) {

		Sort sortOption;

		if ("oldest".equals(sort)) {

			sortOption = Sort.by("createdAt").ascending();

		} else if ("nameAsc".equals(sort)) {

			sortOption = Sort.by("title").ascending();

		} else if ("nameDesc".equals(sort)) {

			sortOption = Sort.by("title").descending();

		} else {

			// デフォルト：新しい順
			sortOption = Sort.by("createdAt").descending();
		}

		return videoRepository
				.searchByTitleOrTag(
						keyword,
						sortOption);
	}

	public List<Video> searchVideosByFolder(
			Long folderId,
			String keyword,
			String sort) {

		Sort sortOption;

		if ("oldest".equals(sort)) {

			sortOption = Sort.by("createdAt").ascending();

		} else if ("nameAsc".equals(sort)) {

			sortOption = Sort.by("title").ascending();

		} else if ("nameDesc".equals(sort)) {

			sortOption = Sort.by("title").descending();

		} else {

			// デフォルト：新しい順
			sortOption = Sort.by("createdAt").descending();
		}

		return videoRepository
				.searchByFolderAndTitleOrTag(
						folderId,
						keyword,
						sortOption);
	}
	
	// =========================
	// 関連動画取得
	// =========================

	public List<Video> getRelatedVideos(Long videoId) {

	    Video video = videoRepository
	            .findById(videoId)
	            .orElse(null);

	    if (video == null || video.getTags().isEmpty()) {
	        return List.of();
	    }

	    List<String> tagNames =
	            video.getTags()
	                 .stream()
	                 .map(Tag::getName)
	                 .toList();

	    return videoRepository.findRelatedVideos(
	            videoId,
	            tagNames
	    );
	}
	
	public List<Video> getVideosByTag(
	        String tag,
	        String sort) {

	    Sort sortOption;

	    if ("oldest".equals(sort)) {

	        sortOption =
	                Sort.by("createdAt").ascending();

	    } else if ("nameAsc".equals(sort)) {

	        sortOption =
	                Sort.by("title").ascending();

	    } else if ("nameDesc".equals(sort)) {

	        sortOption =
	                Sort.by("title").descending();

	    } else {

	        sortOption =
	                Sort.by("createdAt").descending();

	    }

	    return videoRepository.findByTag(
	            tag,
	            sortOption
	    );
	}

}
