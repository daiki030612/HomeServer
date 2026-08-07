package com.example.homeserver;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository // ← このアノテーションを追加します
public interface VideoRepository extends JpaRepository<Video, Long> {
	boolean existsByFileName(String fileName);
}



