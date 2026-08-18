package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.example.homeserver.Repository.VideoUrlImportJobRepository;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VideoUrlImportApplicationContextTests.Config.class)
@TestPropertySource(properties = {
		"spring.servlet.multipart.location=target/test-url-import-context",
		"video.url-import.job-workers=1"
})
class VideoUrlImportApplicationContextTests {
	@Autowired private ApplicationContext context;

	@Test
	void createsCompleteUrlImportDependencyChain() {
		assertNotNull(context.getBean(HlsDownloadService.class));
		assertNotNull(context.getBean(VideoUrlImportService.class));
		assertNotNull(context.getBean(VideoUrlImportJobService.class));
	}

	@TestConfiguration(proxyBeanMethods = false)
	@Import({FfmpegProcessRunner.class, HlsDownloadService.class, VideoUrlImportJobService.class})
	static class Config {
		@Bean static BeanFactoryPostProcessor applicationConversionService() {
			return beanFactory -> beanFactory.setConversionService(ApplicationConversionService.getSharedInstance());
		}
		@Bean SafeUrlHttpClient safeUrlHttpClient() { return mock(SafeUrlHttpClient.class); }
		@Bean UrlSafetyValidator urlSafetyValidator() { return mock(UrlSafetyValidator.class); }
		@Bean VideoUrlImportService videoUrlImportService(UrlSafetyValidator validator,
				SafeUrlHttpClient http, HlsDownloadService hls) {
			return new VideoUrlImportService(validator, List.of(), http, hls, mock(VideoService.class),
					5_368_709_120L, "target/test-url-import-context");
		}
		@Bean VideoUrlImportJobRepository videoUrlImportJobRepository() {
			return mock(VideoUrlImportJobRepository.class);
		}
	}
}
