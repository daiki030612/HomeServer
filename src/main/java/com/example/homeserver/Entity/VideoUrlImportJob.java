package com.example.homeserver.Entity;

import java.time.Instant;
import java.util.UUID;

import com.example.homeserver.Service.VideoUrlImportException;
import com.example.homeserver.Service.VideoUrlImportJobStatus;
import com.example.homeserver.Service.VideoUrlImportStage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "video_url_import_job")
@Getter
@Setter
public class VideoUrlImportJob {
	@Id
	private UUID id;
	@Column(nullable = false, length = 190)
	private String ownerUsername;
	@Column(nullable = false, length = 2048)
	private String inputUrl;
	@Column(nullable = false, length = 2048)
	private String normalizedUrl;
	private Long folderId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private VideoUrlImportJobStatus state;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private VideoUrlImportStage stage;
	@Column(nullable = false, length = 240)
	private String currentOperation;
	private int progress;
	private int completedSegments;
	private int totalSegments;
	private long downloadedBytes;
	@Column(length = 500)
	private String errorMessage;
	@Enumerated(EnumType.STRING)
	@Column(length = 40)
	private VideoUrlImportException.Reason errorReason;
	@Column(nullable = false)
	private Instant createdAt;
	private Instant startedAt;
	private Instant completedAt;
	private Long videoId;
	@Column(nullable = false)
	private boolean cancelRequested;
	@Version
	private long version;
}
