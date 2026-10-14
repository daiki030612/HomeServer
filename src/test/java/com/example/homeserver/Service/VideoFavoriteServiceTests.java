package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.VideoRepository;

class VideoFavoriteServiceTests {
	private final VideoRepository repository = mock(VideoRepository.class);
	private final VideoService service = createService();

	@Test
	void changesFavoriteToTrueAndFalseUsingDesiredState() {
		Video video = new Video();
		when(repository.findById(7L)).thenReturn(Optional.of(video));
		when(repository.save(video)).thenReturn(video);

		assertThat(service.setFavorite(7L, true).isFavorite()).isTrue();
		assertThat(service.setFavorite(7L, false).isFavorite()).isFalse();
		verify(repository, org.mockito.Mockito.times(2)).save(video);
	}

	@Test
	void missingVideoIsNotUpdated() {
		when(repository.findById(404L)).thenReturn(Optional.empty());
		assertThat(service.setFavorite(404L, true)).isNull();
	}

	@Test
	void combinesFavoriteKeywordTagFolderAndSortInDatabaseQuery() {
		Sort sort = Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"));
		when(repository.findLibraryVideos(42L, "猫", "旅行", true, sort)).thenReturn(List.of());

		service.findLibraryVideos(42L, " 猫 ", " 旅行 ", true, "nameAsc");

		verify(repository).findLibraryVideos(42L, "猫", "旅行", true, sort);
	}

	@Test
	void falseFavoriteFilterDoesNotRestrictDatabaseQuery() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		service.findLibraryVideos(null, null, null, false, "newest");
		verify(repository).findLibraryVideos(null, "", "", false, sort);
	}

	@Test
	void favoriteOnlyFilterWorksByItself() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		service.findLibraryVideos(null, null, null, true, "newest");
		verify(repository).findLibraryVideos(null, "", "", true, sort);
	}

	@Test
	void favoriteOnlyCombinesWithSearch() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		service.findLibraryVideos(null, "猫", null, true, "newest");
		verify(repository).findLibraryVideos(null, "猫", "", true, sort);
	}

	@Test
	void favoriteOnlyCombinesWithTag() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		service.findLibraryVideos(null, null, "旅行", true, "newest");
		verify(repository).findLibraryVideos(null, "", "旅行", true, sort);
	}

	@Test
	void favoriteOnlyCombinesWithFolder() {
		Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
		service.findLibraryVideos(42L, null, null, true, "newest");
		verify(repository).findLibraryVideos(42L, "", "", true, sort);
	}

	@Test
	void favoriteOnlyCombinesWithSort() {
		Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
		service.findLibraryVideos(null, null, null, true, "oldest");
		verify(repository).findLibraryVideos(null, "", "", true, sort);
	}

	private VideoService createService() {
		VideoService result = new VideoService();
		ReflectionTestUtils.setField(result, "videoRepository", repository);
		return result;
	}
}
