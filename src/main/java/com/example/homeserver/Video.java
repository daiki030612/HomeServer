package com.example.homeserver;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "video")
@Getter
@Setter
public class Video {

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
    private LocalDateTime created_at;

    public Video() {
    }

    public Video(
            String title,
            String fileName,
            String thumbnailName,
            String filePath,
            String thumbnailPath,
            String folderName,
            LocalDateTime created_at) {

        this.title = title;
        this.fileName = fileName;
        this.thumbnailName = thumbnailName;
        this.filePath = filePath;
        this.thumbnailPath = thumbnailPath;
        this.folderName = folderName;
        this.created_at = created_at;
    }
}