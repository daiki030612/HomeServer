package com.example.homeserver.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

		return "redirect:/videos";

	}

	// =========================
	// フォルダー名前変更
	// =========================

	@PostMapping("/folders/rename")
	public String renameFolder(
			@RequestParam Long id,
			@RequestParam String name) {

		folderService.renameFolder(id, name);

		return "redirect:/videos";
	}

	// =========================
	// フォルダー削除
	// =========================

	@GetMapping("/folders/delete/{id}")
	public String deleteFolder(
			@PathVariable Long id) {

		boolean deleted = folderService.deleteFolder(id);

		if (!deleted) {

			return "redirect:/videos?folderDeleteError";

		}

		return "redirect:/videos";
	}

}