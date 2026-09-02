package com.example.homeserver.Repository;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
	boolean existsByFileName(String fileName);

	@Query("""
		SELECT DISTINCT v
		FROM Video v
		LEFT JOIN v.tags searchableTag
		WHERE ((:folderId IS NULL AND v.folder IS NULL) OR v.folder.id = :folderId)
		  AND (:favoriteOnly = false OR v.favorite = true)
		  AND (:tag IS NULL OR :tag = '' OR EXISTS (
		      SELECT selectedTag.id FROM Video taggedVideo
		      JOIN taggedVideo.tags selectedTag
		      WHERE taggedVideo = v AND selectedTag.name = :tag
		  ))
		  AND (:keyword IS NULL OR :keyword = '' OR
		      LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
		      LOWER(searchableTag.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
		""")
	List<Video> findLibraryVideos(
			@Param("folderId") Long folderId,
			@Param("keyword") String keyword,
			@Param("tag") String tag,
			@Param("favoriteOnly") boolean favoriteOnly,
			Sort sort);
	
	@Query("""
		    SELECT DISTINCT v
		    FROM Video v
		    LEFT JOIN v.tags t
		    WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
		       OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
		""")
		List<Video> searchByTitleOrTag(
		        @Param("keyword") String keyword,
		        Sort sort);
	
	@Query("""
	    SELECT DISTINCT v
	    FROM Video v
	    LEFT JOIN v.tags t
	    WHERE v.folder.id = :folderId
	      AND (
	          LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
	          OR LOWER(t.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
	      )
	""")
	List<Video> searchByFolderAndTitleOrTag(
	        @Param("folderId") Long folderId,
	        @Param("keyword") String keyword,
	        Sort sort);

	@Query("""
		SELECT DISTINCT v
		FROM Video v
		JOIN v.tags selectedTag
		LEFT JOIN v.tags searchableTag
		WHERE selectedTag.name = :tag
		  AND (
		      LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
		      OR LOWER(searchableTag.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
		  )
	""")
	List<Video> searchByTagAndTitleOrTag(
			@Param("tag") String tag,
			@Param("keyword") String keyword,
			Sort sort);

	@Query("""
		SELECT DISTINCT v
		FROM Video v
		JOIN v.tags selectedTag
		LEFT JOIN v.tags searchableTag
		WHERE v.folder.id = :folderId
		  AND selectedTag.name = :tag
		  AND (
		      LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
		      OR LOWER(searchableTag.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
		  )
	""")
	List<Video> searchByFolderAndTagAndTitleOrTag(
			@Param("folderId") Long folderId,
			@Param("tag") String tag,
			@Param("keyword") String keyword,
			Sort sort);

	@Query("""
		    SELECT DISTINCT v
		    FROM Video v
		    JOIN v.tags t
		    WHERE t.name IN :tagNames
		      AND v.id <> :videoId
		""")
		List<Video> findRelatedVideos(
		        @Param("videoId") Long videoId,
		        @Param("tagNames") List<String> tagNames);
	
		@Query("""
			    SELECT DISTINCT v
			    FROM Video v
			    JOIN v.tags t
			    WHERE t.name = :tag
			""")
			List<Video> findByTag(
			        @Param("tag") String tag,
			        Sort sort);

	@Query("""
		SELECT DISTINCT v
		FROM Video v
		JOIN v.tags t
		WHERE v.folder.id = :folderId
		  AND t.name = :tag
	""")
	List<Video> findByFolderAndTag(
			@Param("folderId") Long folderId,
			@Param("tag") String tag,
			Sort sort);
	
	List<Video> findByFolderId(Long folderId);   
	
	List<Video> findByFolderIsNull();
	
	List<Video> findByFolderIdOrderByCreatedAtDescIdDesc(Long folderId);

	List<Video> findByFolderIdOrderByCreatedAtAscIdAsc(Long folderId);

	List<Video> findByFolderIdOrderByTitleAsc(Long folderId);

	List<Video> findByFolderIdOrderByTitleDesc(Long folderId);


	// メインページ
	List<Video> findByFolderIsNullOrderByCreatedAtDescIdDesc();

	List<Video> findByFolderIsNullOrderByCreatedAtAscIdAsc();

	List<Video> findByFolderIsNullOrderByTitleAsc();

	List<Video> findByFolderIsNullOrderByTitleDesc();
	
	boolean existsByFolder(Folder folder);

	long countByCreatedAtIsNull();

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Video v SET v.createdAt = :createdAt WHERE v.createdAt IS NULL")
	int backfillMissingCreatedAt(@Param("createdAt") java.time.LocalDateTime createdAt);

}
