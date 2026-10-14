package com.example.homeserver.Entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class VideoCreatedAtTests {

	@Test
	void prePersistSetsMissingTimestamp() {
		Video video = new Video();

		video.ensureCreatedAt();

		assertThat(video.getCreatedAt()).isNotNull();
	}

	@Test
	void prePersistPreservesExplicitTimestamp() {
		LocalDateTime explicitTimestamp = LocalDateTime.of(2024, 2, 3, 4, 5, 6);
		Video video = new Video();
		video.setCreatedAt(explicitTimestamp);

		video.ensureCreatedAt();

		assertThat(video.getCreatedAt()).isEqualTo(explicitTimestamp);
	}
}
