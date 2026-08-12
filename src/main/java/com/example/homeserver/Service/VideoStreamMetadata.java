package com.example.homeserver.Service;

public record VideoStreamMetadata(
		String codec,
		String codecTag,
		String profile,
		int level,
		String pixelFormat,
		int width,
		int height,
		double framesPerSecond,
		long bitrate,
		int bitsPerRawSample,
		boolean attachedPicture,
		String colorSpace,
		String colorTransfer,
		String colorPrimaries,
		boolean hdrMetadata,
		boolean dolbyVision,
		int streamIndex,
		boolean stillImage,
		boolean timedThumbnails) {

	public VideoStreamMetadata(String codec, String codecTag, String profile, int level,
			String pixelFormat, int width, int height, double framesPerSecond, long bitrate,
			int bitsPerRawSample, boolean attachedPicture) {
		this(codec, codecTag, profile, level, pixelFormat, width, height, framesPerSecond,
				bitrate, bitsPerRawSample, attachedPicture, "", "", "", false, false,
				0, false, false);
	}

	public VideoStreamMetadata(String codec, String codecTag, String profile, int level,
			String pixelFormat, int width, int height, double framesPerSecond, long bitrate,
			int bitsPerRawSample, boolean attachedPicture, String colorSpace,
			String colorTransfer, String colorPrimaries, boolean hdrMetadata,
			boolean dolbyVision) {
		this(codec, codecTag, profile, level, pixelFormat, width, height, framesPerSecond,
				bitrate, bitsPerRawSample, attachedPicture, colorSpace, colorTransfer,
				colorPrimaries, hdrMetadata, dolbyVision, 0, false, false);
	}

	public boolean auxiliary() {
		return attachedPicture || stillImage || timedThumbnails;
	}
}
