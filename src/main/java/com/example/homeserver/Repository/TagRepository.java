package com.example.homeserver.Repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.homeserver.Entity.Tag;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

	// タグ名からタグを検索
	Tag findByName(String name);

	// 登録されているタグを名前順で取得
	List<Tag> findAllByOrderByNameAsc();
	
	@Query("""
		    SELECT DISTINCT t.id
		    FROM Video v
		    JOIN v.tags t
		""")
		List<Long> findUsedTagIds();
	
	boolean existsByName(String name);

    @Query("""
        SELECT COUNT(v) > 0
        FROM Video v
        JOIN v.tags t
        WHERE t.id = :tagId
    """)
    boolean isTagUsed(@Param("tagId") Long tagId);
}
