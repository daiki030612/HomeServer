package com.example.homeserver.Service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SafeUrlHttpClientContextTests {

	@Test
	void springCanCreateSafeUrlHttpClientBean() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.register(UrlSafetyValidator.class, SafeUrlHttpClient.class);
			context.refresh();

			assertThat(context.getBean(SafeUrlHttpClient.class)).isNotNull();
			assertThat(context.getBean(UrlSafetyValidator.class)).isNotNull();
		}
	}
}
