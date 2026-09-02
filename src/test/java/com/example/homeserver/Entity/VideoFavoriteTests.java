package com.example.homeserver.Entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoFavoriteTests {
	@Test
	void favoriteDefaultsToFalse() {
		assertThat(new Video().isFavorite()).isFalse();
	}
}
