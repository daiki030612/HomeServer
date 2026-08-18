package com.example.homeserver.Controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VideoMoveTouchUiTests {
    private static final Path LIST = Path.of("src/main/resources/templates/video/list.html");
    private static final Path SCRIPT = Path.of("src/main/resources/static/js/video/video-list.js");
    private static final Path STYLE = Path.of("src/main/resources/static/css/style.css");

    @Test
    void moveMenuCarriesCurrentLocationAndDialogOffersRoot() throws IOException {
        String html = Files.readString(LIST);

        assertTrue(html.contains("th:data-current-folder-id="));
        assertTrue(html.contains("th:data-current-folder-name="));
        assertTrue(html.contains("id=\"moveFolderModal\" class=\"modal\" role=\"dialog\""));
        assertTrue(html.contains("data-folder-name=\"ライブラリ（ルート）\""));
        assertTrue(html.contains("class=\"move-destination-list\" role=\"listbox\""));
        assertTrue(html.contains("id=\"moveFolderStatus\""));
        assertFalse(html.contains("id=\"moveFolderId\""));
    }

    @Test
    void touchMovePreventsNoOpAndUsesCsrfProtectedSharedRequest() throws IOException {
        String script = Files.readString(SCRIPT);

        assertTrue(script.contains("if (folderId === currentFolderId)"));
        assertTrue(script.contains("destination.disabled = isCurrent"));
        assertTrue(script.contains("function sendVideoMove(videoId, folderId)"));
        assertTrue(script.contains("if (!csrfToken || !csrfHeader)"));
        assertTrue(script.contains("headers: { [csrfHeader]: csrfToken }"));
        assertTrue(script.contains("status.className = \"move-folder-status success\""));
        assertTrue(script.contains("status.className = \"move-folder-status error\""));
    }

    @Test
    void desktopDragAndDropRemainsAndTouchListScrolls() throws IOException {
        String script = Files.readString(SCRIPT);
        String css = Files.readString(STYLE);

        assertTrue(script.contains("function dragVideo(event, card)"));
        assertTrue(script.contains("function dropVideo(event, folder)"));
        assertTrue(script.contains("moveVideoToFolder("));
        assertTrue(css.contains(".move-destination-list"));
        assertTrue(css.contains("overflow-y: auto"));
        assertTrue(css.contains("(hover: none) and (pointer: coarse)"));
        assertTrue(css.contains("align-items: flex-end"));
    }

    @Test
    void touchActionSheetKeepsAllMenuActionsAndRestoresMenuDom() throws IOException {
        String html = Files.readString(LIST);
        String script = Files.readString(SCRIPT);
        String css = Files.readString(STYLE);

        assertTrue(html.contains("onclick=\"openEditModalFromButton(event, this)\""));
        assertTrue(html.contains("onclick=\"openMoveFolderModal(event, this)\""));
        assertTrue(html.contains("class=\"video-delete-form\""));
        assertTrue(html.contains("class=\"action-sheet-cancel\" onclick=\"closeVideoMenus()\""));
        assertTrue(html.contains("id=\"videoMenuBackdrop\""));
        assertTrue(script.contains("document.body.appendChild(menu)"));
        assertTrue(script.contains("menu._videoMenuOwner.appendChild(menu)"));
		assertTrue(script.contains("function guardMenuInteraction(menu)"));
		assertTrue(script.contains("form.addEventListener(\"submit\""));
        assertTrue(script.contains("!event.target.closest(\".menu-dropdown\")"));
        assertTrue(css.contains(".menu-dropdown.touch-action-sheet"));
        assertTrue(css.contains("position: fixed"));
        assertTrue(css.contains("min-height: 50px"));
		assertTrue(css.contains("pointer-events: auto"));
		assertTrue(html.contains("onsubmit=\"event.stopPropagation(); return confirm('この動画を削除しますか？');\""));
    }

	@Test
	void folderMenuUsesSameTouchSafeActionSheet() throws IOException {
		String html = Files.readString(LIST);
		String script = Files.readString(SCRIPT);
		String css = Files.readString(STYLE);

		assertTrue(script.contains("menu._folderMenuOwner = button.parentElement"));
		assertTrue(script.contains("menu._folderMenuOwner.appendChild(menu)"));
		assertTrue(html.contains("class=\"action-sheet-cancel\" onclick=\"closeFolderMenus()\""));
		assertTrue(html.contains("return confirm('このフォルダーを削除しますか？')"));
		assertTrue(css.contains(".folder-menu-dropdown.touch-action-sheet"));
	}

    @Test
    void editFormIsValidAndSaveButtonRemainsInsideIt() throws IOException {
        String html = Files.readString(LIST);
        int editFormStart = html.indexOf("<form id=\"videoEditForm\"");
        int editFormEnd = html.indexOf("</form>", editFormStart);
        String editForm = html.substring(editFormStart, editFormEnd);

        assertTrue(editForm.contains("th:action=\"@{/videos/edit}\""));
        assertTrue(editForm.contains("<button type=\"submit\">"));
        assertTrue(editForm.contains("class=\"tag-delete-button\" formmethod=\"post\""));
        assertFalse(editForm.contains("<form class=\"tag-delete-form\""));
    }
}
