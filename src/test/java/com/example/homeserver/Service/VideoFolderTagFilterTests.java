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
}
