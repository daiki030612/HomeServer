package com.example.homeserver;

import java.io.File;
import java.time.LocalDateTime;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.homeserver.Entity.User;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.UserRepository;
import com.example.homeserver.Repository.VideoRepository;
import com.example.homeserver.Service.ThumbnailService;
import org.springframework.security.crypto.password.PasswordEncoder;


@Component
public class VideoScanner {
	
	@Autowired
	private ThumbnailService thumbnailService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${video.storage.path}")
    private String videoStoragePath;

    @Value("${thumbnail.storage.path}")
    private String thumbnailStoragePath;

    @PostConstruct
    public void init() {
        initUser();
        scanVideos();
    }

    private void initUser() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin")); // デフォルトパスワード
            admin.setRole("ROLE_ADMIN");
            userRepository.save(admin);
            System.out.println("初期ユーザー(admin/admin)を作成しました。");
        }
    }

    public void scanVideos() {

        File folder = new File(videoStoragePath);

        if (!folder.exists()) {
            System.out.println("動画フォルダが存在しません");
            return;
        }

        File[] files = folder.listFiles();

        if (files == null) {
            return;
        }


        for (File file : files) {

            if (file.isFile() && file.getName().endsWith(".mp4")) {

                boolean exists =
                    videoRepository.existsByFileName(file.getName());

                if (!exists) {

                	Video video = new Video();

                	video.setTitle(file.getName());
                	video.setFileName(file.getName());


                	// サムネイル作成
                	String thumbnailFilePath =
                	        thumbnailStoragePath 
                	        + File.separator
                	        + file.getName().replace(".mp4", ".jpg");


                	thumbnailService.createThumbnail(
                	        file.getAbsolutePath(),
                	        thumbnailFilePath
                	);


                	// DB保存用はファイル名だけ
                	video.setThumbnailName(
                	        file.getName().replace(".mp4", ".jpg")
                	);


                	video.setCreatedAt(LocalDateTime.now());

                	videoRepository.save(video);

                    System.out.println(
                        "登録しました：" + file.getName()
                    );
                }
            }
        }
    }
}