package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.jpa.repository.Query;

import com.example.homeserver.Repository.VideoRepository;

@ExtendWith(MockitoExtension.class)
class VideoCreatedAtBackfillTests {

	@Mock
	private VideoRepository repository;

	@Test
	void skipsUpdateWhenNoVideoDateIsMissing() {
		when(repository.countByCreatedAtIsNull()).thenReturn(0L);

		new VideoCreatedAtBackfill(repository).run(new DefaultApplicationArguments(new String[0]));

		verify(repository, never()).backfillMissingCreatedAt(VideoCreatedAtBackfill.FALLBACK_CREATED_AT);
	}

	@Test
	void backfillsMissingDatesWithFixedFallback() {
		when(repository.countByCreatedAtIsNull()).thenReturn(2L);
		when(repository.backfillMissingCreatedAt(VideoCreatedAtBackfill.FALLBACK_CREATED_AT)).thenReturn(2);

		new VideoCreatedAtBackfill(repository).run(new DefaultApplicationArguments(new String[0]));

		verify(repository).backfillMissingCreatedAt(VideoCreatedAtBackfill.FALLBACK_CREATED_AT);
	}

	@Test
	void updateQueryDoesNotOverwriteExistingDates() throws Exception {
		Query query = VideoRepository.class
				.getMethod("backfillMissingCreatedAt", java.time.LocalDateTime.class)
				.getAnnotation(Query.class);

		assertThat(query.value()).containsIgnoringCase("WHERE v.createdAt IS NULL");
	}
}
