package com.example.homeserver.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import org.springframework.stereotype.Service;

@Service
public class ThumbnailService {

	public String createThumbnail(String videoPath, String outputPath) {

		try {

			File file = new File(outputPath);
			file.getParentFile().mkdirs();

			String thumbnailTime = "5";

			ProcessBuilder builder = new ProcessBuilder(
					"ffmpeg",
					"-ss",
					String.valueOf(thumbnailTime),
					"-i",
					videoPath,
					"-frames:v",
					"1",
					"-y",
					outputPath);

			builder.inheritIO();

			Process process = builder.start();

			int result = process.waitFor();

			if (result == 0) {

				System.out.println(
						"サムネイル作成完了：" + outputPath);

				return outputPath;

			} else {

				System.out.println(
						"サムネイル作成失敗");

				return null;
			}

		} catch (Exception e) {

			e.printStackTrace();
			return null;
		}
	}

	private double getVideoDuration(String videoPath)
			throws Exception {

		ProcessBuilder builder = new ProcessBuilder(
				"ffprobe",
				"-v",
				"error",
				"-show_entries",
				"format=duration",
				"-of",
				"default=noprint_wrappers=1:nokey=1",
				videoPath);

		Process process = builder.start();

		BufferedReader reader = new BufferedReader(
				new InputStreamReader(
						process.getInputStream()));

		String line = reader.readLine();

		process.waitFor();

		return Double.parseDouble(line);
	}

	

}