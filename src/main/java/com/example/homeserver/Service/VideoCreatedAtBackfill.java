package com.example.homeserver.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.homeserver.Repository.VideoRepository;

@Component
public class VideoCreatedAtBackfill implements ApplicationRunner {
    static final LocalDateTime FALLBACK_CREATED_AT = LocalDate.of(2026, 8, 18).atStartOfDay();
    private static final Logger logger = LoggerFactory.getLogger(VideoCreatedAtBackfill.class);

    private final VideoRepository repository;

    public VideoCreatedAtBackfill(VideoRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long missingCount = repository.countByCreatedAtIsNull();
        if (missingCount == 0) {
            return;
        }

        int updatedCount = repository.backfillMissingCreatedAt(FALLBACK_CREATED_AT);
        logger.info("Backfilled missing video creation timestamps: updatedCount={}", updatedCount);
    }
}
