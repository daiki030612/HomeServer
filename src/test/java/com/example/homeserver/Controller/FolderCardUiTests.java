package com.example.homeserver.Controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FolderCardUiTests {
	private static final Path TEMPLATE = Path.of("src/main/resources/templates/video/list.html");
	private static final Path SCRIPT = Path.of("src/main/resources/static/js/video/video-list.js");
	private static final Path STYLE = Path.of("src/main/resources/static/css/video-library.css");

	@Test
	void cardOpensFolderAndOptionsButtonHasAccessibleMenuRelationship() throws IOException {
		String html = Files.readString(TEMPLATE);

		assertTrue(html.contains("class=\"folder-link\" th:href=\"@{/videos/folder/{id}"));
		assertTrue(html.contains("class=\"folder-menu-button\""));
		assertTrue(html.contains("aria-haspopup=\"menu\" aria-expanded=\"false\""));
		assertTrue(html.contains("th:aria-controls=\"|folder-menu-${folder.id}|\""));
	}

	@Test
	void cardUsesImageGalleryStructureAndResponsiveGrid() throws IOException {
		String html = Files.readString(TEMPLATE);
		String css = Files.readString(STYLE);

		assertTrue(html.contains("class=\"card folder-card\""));
		assertTrue(html.contains("class=\"cover folder-cover\""));
		assertTrue(html.contains("class=\"card-body folder-card-body\""));
		assertTrue(html.contains("<h2 class=\"folder-name\""));
		assertTrue(css.contains("grid-template-columns: repeat(2, minmax(0, 1fr));"));
		assertTrue(css.contains("@media (min-width: 650px)"));
		assertTrue(css.contains("grid-template-columns: repeat(3, 1fr);"));
		assertTrue(css.contains("@media (min-width: 950px)"));
		assertTrue(css.contains("grid-template-columns: repeat(5, 1fr);"));
	}

	@Test
	void deleteRemainsPostWithConfirmationAndRenameUsesExistingModal() throws IOException {
		String html = Files.readString(TEMPLATE);

		assertTrue(html.contains("th:action=\"@{/folders/delete/{id}(id=${folder.id})}\" method=\"post\""));
		assertTrue(html.contains("confirm('このフォルダーを削除しますか？')"));
		assertTrue(html.contains("onclick=\"openRenameFolderModal(event, this)\""));
	}

	@Test
	void menuUsesExplicitClickWithoutLegacyLongPressHandlers() throws IOException {
		String script = Files.readString(SCRIPT);

		assertTrue(script.contains("event.stopPropagation();"));
		assertTrue(script.contains("closeFolderMenus(menu)"));
		assertTrue(script.contains("event.key !== \"Escape\""));
		assertFalse(script.contains("startFolderLongPress"));
		assertFalse(script.contains("folderLongPressTimer"));
	}
}
