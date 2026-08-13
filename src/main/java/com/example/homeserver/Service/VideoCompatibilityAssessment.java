package com.example.homeserver.Service;

import java.util.List;

public record VideoCompatibilityAssessment(
		VideoCompatibilityDecision decision,
		List<String> reasons) {
}
