package com.example.homeserver.Service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.homeserver.Entity.Tag;
import com.example.homeserver.Repository.TagRepository;

@Service
public class TagService {

	@Autowired
	private TagRepository tagRepository;

	/*
	 * 登録済みタグを取得
	 */
	public List<Tag> getAllTags() {

		return tagRepository
				.findAllByOrderByNameAsc();

	}

	/*
	 * タグ削除
	 */
	public boolean deleteTag(Long tagId) {

		/*
		 * タグが存在しない
		 */
		Tag tag = tagRepository.findById(tagId)
				.orElse(null);

		if (tag == null) {

			return false;

		}

		/*
		 * 動画で使用されているか確認
		 */
		if (tagRepository.isTagUsed(tagId)) {

			return false;

		}

		/*
		 * 使用されていなければ削除
		 */
		tagRepository.delete(tag);

		return true;

	}

}