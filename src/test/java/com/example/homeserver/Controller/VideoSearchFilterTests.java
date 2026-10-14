package com.example.homeserver.Controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;

class VideoSearchFilterTests {

	private final VideoService videoService = mock(VideoService.class);
	private final FolderService folderService = mock(FolderService.class);
	private final TagService tagService = mock(TagService.class);
	private final VideoController controller = new VideoController();

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(controller, "videoService", videoService);
		ReflectionTestUtils.setField(controller, "folderService", folderService);
		ReflectionTestUtils.setField(controller, "tagService", tagService);
		when(folderService.getRootFolders()).thenReturn(List.of());
		when(folderService.getAllFolders()).thenReturn(List.of());
		when(tagService.getAllTags()).thenReturn(List.of());
	}

	@Test
	void rootSearchCombinesKeywordTagAndSortAndRetainsConditions() {
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.getAllVideos(null, "旅行", "日常", true, "oldest", model);

		assertThat(view).isEqualTo("video/list");
		verify(videoService).findLibraryVideos(null, "旅行", "日常", true, "oldest");
		assertThat(model.get("keyword")).isEqualTo("旅行");
		assertThat(model.get("selectedTag")).isEqualTo("日常");
		assertThat(model.get("favoriteOnly")).isEqualTo(true);
		assertThat(model.get("sort")).isEqualTo("oldest");
	}

	@Test
	void folderSearchCombinesKeywordTagAndSortAndRetainsConditions() {
		Folder folder = new Folder();
		when(folderService.getFolderById(42L)).thenReturn(folder);
		when(folderService.getChildFolders(42L)).thenReturn(List.of());
		when(folderService.getBreadcrumbs(42L)).thenReturn(List.of());
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.openFolder(42L, "旅行", "日常", true, "nameAsc", model);

		assertThat(view).isEqualTo("video/list");
		verify(videoService).findLibraryVideos(42L, "旅行", "日常", true, "nameAsc");
		assertThat(model.get("keyword")).isEqualTo("旅行");
		assertThat(model.get("selectedTag")).isEqualTo("日常");
		assertThat(model.get("sort")).isEqualTo("nameAsc");
		assertThat(model.get("favoriteOnly")).isEqualTo(true);
	}

	@Test
	void omittedFavoriteFilterKeepsAllVideos() {
		ExtendedModelMap model = new ExtendedModelMap();

		controller.getAllVideos(null, null, null, false, "newest", model);

		verify(videoService).findLibraryVideos(null, null, null, false, "newest");
		assertThat(model.get("favoriteOnly")).isEqualTo(false);
	}
}
