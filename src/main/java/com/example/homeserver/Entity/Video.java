package com.example.homeserver.Entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "video")
@Getter
@Setter
public class Video {
	private static final ZoneId HOME_SERVER_ZONE = ZoneId.of("Asia/Tokyo");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String title;

	// 動画ファイル名
	private String fileName;

	// サムネイルファイル名
	private String thumbnailName;

	private String filePath;

	private String thumbnailPath;

	private String folderName;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(nullable = false, columnDefinition = "boolean default false")
	private boolean favorite;

	@ManyToOne
	@JoinColumn(name = "folder_id")
	private Folder folder;

	// ========================= // タグ // ========================= 
	@ManyToMany
	@JoinTable(
			name = "video_tag",
			joinColumns = @JoinColumn(name = "video_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id")
			)
	private Set<Tag> tags = new HashSet<>();

	public Video() {
	}

	@PrePersist
	void ensureCreatedAt() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now(HOME_SERVER_ZONE);
		}
	}

	public Video(
	        String title,
	        String fileName,
	        String thumbnailName,
	        String filePath,
	        String thumbnailPath,
	        String folderName,
	        LocalDateTime createdAt) {

	    this.title = title;
	    this.fileName = fileName;
	    this.thumbnailName = thumbnailName;
	    this.filePath = filePath;
	    this.thumbnailPath = thumbnailPath;
	    this.folderName = folderName;
	    this.createdAt = createdAt;
	}

	public Folder getFolder() {
		return folder;
	}

	public void setFolder(Folder folder) {
		this.folder = folder;
	}
}
