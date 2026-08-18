package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.homeserver.Repository.VideoRepository;

class VideoSortStabilityTests {
    @Test
    void keywordAndTagQueriesUseCreatedAtAndIdForStableNewestSort() {
        VideoRepository repository = mock(VideoRepository.class);
        VideoService service = new VideoService();
        ReflectionTestUtils.setField(service, "videoRepository", repository);

        service.searchVideos("camera", "newest");
        service.getVideosByTag("daily", "newest");

        ArgumentCaptor<Sort> keywordSort = ArgumentCaptor.forClass(Sort.class);
        verify(repository).searchByTitleOrTag(org.mockito.ArgumentMatchers.eq("camera"), keywordSort.capture());
        assertThat(keywordSort.getValue().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(keywordSort.getValue().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);

        ArgumentCaptor<Sort> tagSort = ArgumentCaptor.forClass(Sort.class);
        verify(repository).findByTag(org.mockito.ArgumentMatchers.eq("daily"), tagSort.capture());
        assertThat(tagSort.getValue().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(tagSort.getValue().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void folderOldestSortUsesCreatedAtThenIdAscending() {
        VideoRepository repository = mock(VideoRepository.class);
        VideoService service = new VideoService();
        ReflectionTestUtils.setField(service, "videoRepository", repository);

        service.getVideosByFolder(9L, "oldest");

        verify(repository).findByFolderIdOrderByCreatedAtAscIdAsc(9L);
    }
}
