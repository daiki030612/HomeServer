package com.example.homeserver.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class UploadArtifacts {

	private final List<Path> storageRoots;
	private final Set<Path> paths = new LinkedHashSet<>();

	public UploadArtifacts(Path... storageRoots) {
		this.storageRoots = java.util.Arrays.stream(storageRoots)
				.map(path -> path.toAbsolutePath().normalize())
				.toList();
	}

	public Path track(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (storageRoots.stream().noneMatch(normalized::startsWith)) {
			throw new IllegalArgumentException("Upload artifact is outside storage");
		}
		paths.add(normalized);
		return normalized;
	}

	public void cleanupAfterSuccess(Path... retainedPaths) {
		Set<Path> retained = new LinkedHashSet<>();
		for (Path path : retainedPaths) {
			if (path != null) {
				retained.add(path.toAbsolutePath().normalize());
			}
		}
		deleteTracked(retained, null);
	}

	public void cleanupAfterFailure(Exception originalFailure) {
		deleteTracked(Set.of(), originalFailure);
	}

	private void deleteTracked(Set<Path> retained, Exception originalFailure) {
		List<Path> reverseOrder = new ArrayList<>(paths);
		for (int index = reverseOrder.size() - 1; index >= 0; index--) {
			Path path = reverseOrder.get(index);
			if (retained.contains(path)) {
				continue;
			}
			try {
				Files.deleteIfExists(path);
				if (Files.exists(path)) {
					throw new IOException("Upload artifact still exists: " + path);
				}
			} catch (IOException cleanupFailure) {
				if (originalFailure != null) {
					originalFailure.addSuppressed(cleanupFailure);
				} else {
					throw new IllegalStateException("Unused upload artifact could not be deleted", cleanupFailure);
				}
			}
		}
	}
}
