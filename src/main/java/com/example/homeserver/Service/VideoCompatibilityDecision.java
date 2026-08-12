package com.example.homeserver.Service;

public enum VideoCompatibilityDecision {
	PASSTHROUGH,
	REMUX,
	TRANSCODE_AUDIO,
	TRANSCODE_VIDEO,
	TRANSCODE_BOTH
}
