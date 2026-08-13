package com.example.homeserver.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Service.FolderService;

@Controller
public class FolderController {

	@Autowired
	private FolderService folderService;

	// フォルダ作成
	@PostMapping("/folders/create")
	public String createFolder(
			@RequestParam String name,
			@RequestParam(required = false) Long parentId) {

		folderService.createFolder(
				name,
				parentId);
		
		// フォルダー内から作成した場合
	    if (parentId != null) {
	        return "redirect:/videos/folder/" + parentId;
	    }

		return "redirect:/videos";

	}

	// =========================
	// フォルダー名前変更
	// =========================

	@PostMapping("/folders/rename")
	public String renameFolder(
			@RequestParam Long id,
			@RequestParam String name,
			@RequestParam(required = false) Long parentId) {

		folderService.renameFolder(id, name);
		
		// フォルダー内から変更した場合
	    if (parentId != null) {
	        return "redirect:/videos/folder/" + parentId;
	    }

		return "redirect:/videos";
	}

	// =========================
	// フォルダー削除
	// =========================

	@PostMapping("/folders/delete/{id}")
	public String deleteFolder(
	        @PathVariable Long id) {

	    Folder folder = folderService.getFolderById(id);

	    if (folder == null) {
	        return "redirect:/videos";
	    }

	    // 削除前に親フォルダーを取得
	    Folder parent = folder.getParent();

	    boolean deleted = folderService.deleteFolder(id);

	    if (!deleted) {
	        return "redirect:/videos?folderDeleteError";
	    }

	    // 親フォルダーがある場合
	    if (parent != null) {
	        return "redirect:/videos/folder/" + parent.getId();
	    }

	    // 親がない = ルートフォルダー
	    return "redirect:/videos";
	}

}
