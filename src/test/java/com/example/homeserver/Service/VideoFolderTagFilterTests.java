package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.homeserver.Entity.Video;
import com.example.homeserver.Repository.VideoRepository;

class VideoFolderTagFilterTests {
    @Test
    void filtersTheCurrentFolderByTagWithoutChangingSortSemantics() {
        VideoRepository repository = mock(VideoRepository.class);
        VideoService service = new VideoService();
        ReflectionTestUtils.setField(service, "videoRepository", repository);
        Video video = new Video();
		Sort expectedSort = Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"));
        when(repository.findByFolderAndTag(42L, "日常", expectedSort)).thenReturn(List.of(video));

        List<Video> result = service.getVideosByFolderAndTag(42L, "日常", "nameAsc");

        assertThat(result).containsExactly(video);
        verify(repository).findByFolderAndTag(42L, "日常", expectedSort);
    }

	@Test
	void combinesKeywordTagAndSortAtLibraryRoot() {
		VideoRepository repository = mock(VideoRepository.class);
		VideoService service = new VideoService();
		ReflectionTestUtils.setField(service, "videoRepository", repository);
		Sort expectedSort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

		service.searchVideosByTag("旅行", "日常", "newest");

		verify(repository).searchByTagAndTitleOrTag("日常", "旅行", expectedSort);
	}

	@Test
	void combinesFolderKeywordTagAndSort() {
		VideoRepository repository = mock(VideoRepository.class);
		VideoService service = new VideoService();
		ReflectionTestUtils.setField(service, "videoRepository", repository);
		Sort expectedSort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

		service.searchVideosByFolderAndTag(42L, "旅行", "日常", "oldest");

		verify(repository).searchByFolderAndTagAndTitleOrTag(42L, "日常", "旅行", expectedSort);
	}
}
