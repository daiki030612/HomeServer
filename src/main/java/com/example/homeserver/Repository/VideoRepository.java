package com.example.homeserver.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.homeserver.Entity.Video;

@Repository // ← このアノテーションを追加します
public interface VideoRepository extends JpaRepository<Video, Long> {
	boolean existsByFileName(String fileName);
	
	@Query("""
		    SELECT DISTINCT v
		    FROM Video v
		    LEFT JOIN v.tags t
		    WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
		       OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
		""")
		List<Video> searchByTitleOrTag(@Param("keyword") String keyword);
	List<Video> findByFolderId(Long folderId);   

}