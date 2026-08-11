package com.example.homeserver.Service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Repository.FolderRepository;
import com.example.homeserver.Repository.VideoRepository;

@Service
public class FolderService {

	@Autowired
	private FolderRepository folderRepository;

	@Autowired
	private VideoRepository videoRepository;

	// =========================
	// フォルダ作成
	// =========================

	public Folder createFolder(
			String name,
			Long parentId) {

		Folder folder = new Folder();

		folder.setName(name);

		folder.setSortOrder(0);

		// 親フォルダがある場合
		if (parentId != null) {

			Folder parent = folderRepository
					.findById(parentId)
					.orElse(null);

			folder.setParent(parent);

		}

		return folderRepository.save(folder);

	}

	// =========================
	// ルートフォルダ取得
	// =========================

	public List<Folder> getRootFolders() {

		return folderRepository
				.findByParentIsNullOrderBySortOrderAsc();

	}

	// =========================
	// 子フォルダ取得
	// =========================

	public List<Folder> getChildFolders(
			Long parentId) {

		Folder parent = folderRepository
				.findById(parentId)
				.orElse(null);

		if (parent == null) {

			return List.of();

		}

		return folderRepository
				.findByParentOrderBySortOrderAsc(parent);

	}

	// =========================
	// ID検索
	// =========================

	public Folder getFolderById(Long id) {

		return folderRepository
				.findById(id)
				.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("フォルダーが見つかりませんでした (ID: " + id + ")"));

	}

	// =========================
	// 全フォルダ取得
	// =========================

	public List<Folder> getAllFolders() {

		return folderRepository.findAll();

	}

	// =========================
	// フォルダー名前変更
	// =========================

	public void renameFolder(Long id, String name) {

		Folder folder = folderRepository
				.findById(id)
				.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("フォルダーが見つかりませんでした (ID: " + id + ")"));

		folder.setName(name);

		folderRepository.save(folder);
	}

	// =========================
	// フォルダー削除
	// =========================

	public boolean deleteFolder(Long id) {

		Folder folder = folderRepository
				.findById(id)
				.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("フォルダーが見つかりませんでした (ID: " + id + ")"));

		// 子フォルダーがある場合
		if (folderRepository.existsByParent(folder)) {
			return false;
		}

		// 動画が入っている場合
		if (videoRepository.existsByFolder(folder)) {
			return false;
		}

		folderRepository.delete(folder);

		return true;
	}
	
	// =========================
	// パンくずリスト取得
	// =========================

	public List<Folder> getBreadcrumbs(Long folderId) {

	    List<Folder> breadcrumbs = new ArrayList<>();

	    Folder folder =
	            folderRepository.findById(folderId)
	                    .orElse(null);

	    while (folder != null) {

	        breadcrumbs.add(0, folder);

	        folder = folder.getParent();
	    }

	    return breadcrumbs;
	}
}