package com.example.homeserver.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.homeserver.Entity.VideoUrlImportJob;
import com.example.homeserver.Service.VideoUrlImportJobStatus;

public interface VideoUrlImportJobRepository extends JpaRepository<VideoUrlImportJob, UUID> {
	Optional<VideoUrlImportJob> findFirstByOwnerUsernameAndNormalizedUrlAndStateInOrderByCreatedAtDesc(
			String ownerUsername, String normalizedUrl, Collection<VideoUrlImportJobStatus> states);
	Optional<VideoUrlImportJob> findByIdAndOwnerUsername(UUID id, String ownerUsername);
	List<VideoUrlImportJob> findByOwnerUsernameOrderByCreatedAtDesc(String ownerUsername, Pageable pageable);
	List<VideoUrlImportJob> findByStateIn(Collection<VideoUrlImportJobStatus> states);
}
