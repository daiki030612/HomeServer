document.addEventListener("DOMContentLoaded", function() {

    /*
     * =========================
     * 要素取得
     * =========================
     */

    const form =
        document.getElementById("upload-form");

    const fileInput =
        document.getElementById("file");

    const fileName =
        document.getElementById("file-name");

    const uploadButton =
        document.getElementById("upload-button");

    const progressArea =
        document.getElementById("progress-area");

    const progressBar =
        document.getElementById("progress-bar");

    const progressText =
        document.getElementById("progress-text");

    const progressSize =
        document.getElementById("progress-size");

    const csrfToken =
        document.querySelector('meta[name="_csrf"]')?.content;

    const csrfHeader =
        document.querySelector('meta[name="_csrf_header"]')?.content;


    /*
     * =========================
     * ファイル選択
     * =========================
     */

    fileInput.addEventListener(
        "change",
        function() {

            if (this.files.length > 0) {

                fileName.textContent =
                    this.files[0].name;

            } else {

                fileName.textContent =
                    "動画を選択してください";

            }

        }
    );


    /*
     * =========================
     * アップロード
     * =========================
     */

    form.addEventListener("submit", function(event) {
        event.preventDefault();
        /* * ファイル取得 */
        const file = fileInput.files[0]; if (!file) { return; } 
		/* * FormData */ 
		const formData = new FormData();

		formData.append("file", file);


		// =========================
		// URLからfolderId取得
		// =========================

		const params =
		    new URLSearchParams(
		        window.location.search
		    );

		const folderId =
		    params.get("folderId");

		console.log("URL =", window.location.href);
		console.log("folderId =", folderId);


		// =========================
		// folderIdをFormDataへ追加
		// =========================

		if (folderId) {

		    formData.append(
		        "folderId",
		        folderId
		    );

		}


		// =========================
		// XMLHttpRequest
		// =========================

		const xhr =
		    new XMLHttpRequest();

		xhr.open(
		    "POST",
		    form.action
		);

		if (!csrfToken || !csrfHeader) {
		    alert("セキュリティトークンを取得できませんでした。ページを再読み込みしてください。");
		    return;
		}

		xhr.setRequestHeader(csrfHeader, csrfToken);
		 /* * UI変更 */ 
		 uploadButton.disabled = true; uploadButton.textContent = "アップロード中..."; 
		 progressArea.style.display = "block";

        /*
         * =========================
         * アップロード進捗
         * =========================
         */

        xhr.upload.addEventListener(
            "progress",
            function(event) {

                if (!event.lengthComputable) {
                    return;
                }


                const percent =
                    Math.round(
                        (event.loaded / event.total) * 100
                    );


                progressBar.style.width =
                    percent + "%";


                progressText.textContent =
                    "アップロード中... "
                    + percent
                    + "%";


                progressSize.textContent =
                    formatBytes(event.loaded)
                    + " / "
                    + formatBytes(event.total);

            }
        );


        /*
         * =========================
         * 完了
         * =========================
         */

        xhr.addEventListener(
            "load",
            function() {

                if (
                    xhr.status >= 200 &&
                    xhr.status < 300
                ) {

                    progressBar.style.width =
                        "100%";


                    progressText.textContent =
                        "アップロード完了！";


                    /*
                     * 少し表示して一覧へ
                     */

                    setTimeout(
                        function() {
                            const videoLibraryUrl = form.action.replace(/\/upload$/, "");
                            if (folderId) {
                                window.location.href = videoLibraryUrl + "/folder/" + encodeURIComponent(folderId);
                            } else {
                                window.location.href = videoLibraryUrl;
                            }
                        },
                        500
                    );


                } else {
                    const errorMessage = xhr.responseText || "アップロードに失敗しました。";
                    alert("エラー: " + errorMessage);
                    resetUpload();
                }

            }
        );


        /*
         * =========================
         * 通信エラー
         * =========================
         */

        xhr.addEventListener(
            "error",
            function() {

                alert(
                    "通信エラーが発生しました。"
                );

                resetUpload();

            }
        );


        /*
         * =========================
         * アップロード開始
         * =========================
         */

        xhr.send(formData);

    }
    );


    /*
     * =========================
     * ファイルサイズ表示
     * =========================
     */

    function formatBytes(bytes) {

        const units = [
            "B",
            "KB",
            "MB",
            "GB",
            "TB"
        ];


        if (bytes === 0) {
            return "0 B";
        }


        const i =
            Math.floor(
                Math.log(bytes) /
                Math.log(1024)
            );


        return (
            (bytes / Math.pow(1024, i))
                .toFixed(1)
            + " "
            + units[i]
        );

    }


    /*
     * =========================
     * エラー時にUIを戻す
     * =========================
     */

    function resetUpload() {

        uploadButton.disabled = false;

        uploadButton.textContent =
            "アップロード";

        progressArea.style.display =
            "none";

    }

    const urlImportForm = document.getElementById("url-import-form");
    const urlImportButton = document.getElementById("url-import-button");
    const urlImportProgress = document.getElementById("url-import-progress");
    const urlImportStatus = document.getElementById("url-import-status");
    const urlImportProgressBar = document.getElementById("url-import-progress-bar");
    const urlImportPercentage = document.getElementById("url-import-percentage");
    const urlImportSegments = document.getElementById("url-import-segments");
    const urlImportBytes = document.getElementById("url-import-bytes");
    const urlImportElapsed = document.getElementById("url-import-elapsed");
	const urlJobList = document.getElementById("url-job-list");

    if (urlImportForm) {
        const jobStorageKey = "video-url-import-job:" + urlImportForm.action;

        function formatBytes(bytes) {
            const value = Math.max(0, Number(bytes) || 0);
            if (value >= 1024 ** 3) return (value / 1024 ** 3).toFixed(2) + " GB";
            if (value >= 1024 ** 2) return (value / 1024 ** 2).toFixed(1) + " MB";
            if (value >= 1024) return (value / 1024).toFixed(1) + " KB";
            return value + " B";
        }

        function showProgress(progress) {
            urlImportProgress.hidden = false;
            const percentage = Math.max(0, Math.min(100, Number(progress.percentage) || 0));
            urlImportStatus.textContent = progress.message || "処理状況を確認しています";
            urlImportProgressBar.style.width = percentage + "%";
            urlImportPercentage.textContent = percentage + "%";
            urlImportSegments.textContent = progress.totalSegments > 0
                ? progress.completedSegments + " / " + progress.totalSegments + " セグメント"
                : "セグメント情報を取得中";
            urlImportBytes.textContent = formatBytes(progress.downloadedBytes) + " ダウンロード済み";
            if (progress.startedAt) {
                const elapsed = Math.max(0, Math.floor((Date.now() - Date.parse(progress.startedAt)) / 1000));
                urlImportElapsed.textContent = "経過時間 " + Math.floor(elapsed / 60) + "分 " + (elapsed % 60) + "秒";
            }
        }

		async function refreshJobHistory() {
			if (!urlJobList) return;
			const response = await fetch(urlImportForm.action + "/jobs", { headers: { "Accept": "application/json" } });
			if (!response.ok) return;
			const jobs = await response.json();
			urlJobList.replaceChildren();
			if (!jobs.length) {
				const empty = document.createElement("p"); empty.className = "url-job-empty";
				empty.textContent = "履歴はまだありません。"; urlJobList.append(empty); return;
			}
			jobs.forEach(job => {
				const card = document.createElement("article"); card.className = "url-job"; card.dataset.jobId = job.jobId;
				const heading = document.createElement("div"); heading.className = "url-job-heading";
				const url = document.createElement("p"); url.className = "url-job-url"; url.textContent = job.inputUrl;
				const state = document.createElement("span"); state.className = "url-job-state";
				state.dataset.state = job.state; state.textContent = job.state; heading.append(url, state);
				const operation = document.createElement("p"); operation.className = "url-job-operation";
				operation.textContent = job.errorMessage || job.message;
				const time = document.createElement("p"); time.className = "url-job-time";
				const start = job.startedAt || job.createdAt;
				time.textContent = (job.startedAt ? "開始 " : "作成 ") + new Date(start).toLocaleString("ja-JP")
					+ (job.finishedAt ? " / 完了 " + new Date(job.finishedAt).toLocaleString("ja-JP") : "");
				const progress = document.createElement("progress"); progress.max = 100; progress.value = job.percentage || 0;
				const actions = document.createElement("div"); actions.className = "url-job-actions";
				if (job.videoId) {
					const link = document.createElement("a");
					link.href = urlImportForm.action.replace(/\/import-url$/, "/view/" + encodeURIComponent(job.videoId));
					link.textContent = "保存した動画を開く"; actions.append(link);
				} else if (!["COMPLETED", "FAILED", "CANCELLED"].includes(job.state)) {
					const cancel = document.createElement("button"); cancel.type = "button"; cancel.className = "url-job-cancel";
					cancel.dataset.jobId = job.jobId; cancel.textContent = "キャンセル"; actions.append(cancel);
				}
				card.append(heading, operation, time, progress, actions); urlJobList.append(card);
			});
		}

		urlJobList?.addEventListener("click", async event => {
			const button = event.target.closest(".url-job-cancel");
			if (!button || !csrfToken || !csrfHeader) return;
			button.disabled = true; button.textContent = "キャンセル中...";
			const response = await fetch(urlImportForm.action + "/" + encodeURIComponent(button.dataset.jobId) + "/cancel", {
				method: "POST", headers: { [csrfHeader]: csrfToken, "Accept": "application/json" }
			});
			if (!response.ok) alert("キャンセルできませんでした。再読み込みして確認してください。");
			await refreshJobHistory();
		});

        async function pollJob(jobId) {
            urlImportButton.disabled = true;
            urlImportButton.textContent = "保存処理中...";
            try {
                const progressUrl = urlImportForm.action + "/progress/" + encodeURIComponent(jobId);
                const response = await fetch(progressUrl, { headers: { "Accept": "application/json" } });
                if (response.status === 404) {
                    sessionStorage.removeItem(jobStorageKey);
                    throw new Error("処理状況を確認できませんでした。もう一度実行してください。");
                }
                if (!response.ok) throw new Error("処理状況の取得に失敗しました。再試行しています。");
                const progress = await response.json();
                showProgress(progress);
                if (progress.status === "COMPLETED") {
                    sessionStorage.removeItem(jobStorageKey);
                    urlImportProgressBar.style.width = "100%";
                    urlImportPercentage.textContent = "100%";
                    const selectedFolder = urlImportForm.elements.folderId.value;
                    window.setTimeout(() => {
                        window.location.href = selectedFolder
                            ? urlImportForm.action.replace(/\/import-url$/, "/folder/" + encodeURIComponent(selectedFolder))
                            : urlImportForm.action.replace(/\/import-url$/, "");
                    }, 700);
                    return;
                }
				refreshJobHistory();
                if (progress.status === "FAILED" || progress.status === "CANCELLED") {
                    sessionStorage.removeItem(jobStorageKey);
                    urlImportButton.disabled = false;
                    urlImportButton.textContent = "URLから保存";
                    return;
                }
                window.setTimeout(() => pollJob(jobId), 1000);
            } catch (error) {
                urlImportProgress.hidden = false;
                urlImportStatus.textContent = error.message;
                if (sessionStorage.getItem(jobStorageKey)) {
                    window.setTimeout(() => pollJob(jobId), 2000);
                } else {
                    urlImportButton.disabled = false;
                    urlImportButton.textContent = "URLから保存";
                }
            }
        }

        urlImportForm.addEventListener("submit", async function(event) {
            event.preventDefault();
            if (!csrfToken || !csrfHeader) {
                alert("セキュリティトークンを取得できませんでした。ページを再読み込みしてください。");
                return;
            }

            urlImportButton.disabled = true;
            urlImportButton.textContent = "保存処理中...";
			urlImportProgress.hidden = false;
			urlImportStatus.textContent = "URLを解析しています";
			urlImportProgressBar.style.width = "0%";

            try {
                const response = await fetch(urlImportForm.action, {
                    method: "POST",
                    headers: {
                        [csrfHeader]: csrfToken,
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: new URLSearchParams(new FormData(urlImportForm))
                });
                if (!response.ok) {
                    const message = await response.text();
                    throw new Error(message || "URLから動画を保存できませんでした。");
                }
                const started = await response.json();
                sessionStorage.setItem(jobStorageKey, started.jobId);
				if (started.reused) urlImportStatus.textContent = "同じURLの実行中ジョブを表示しています";
				refreshJobHistory();
                pollJob(started.jobId);
            } catch (error) {
				urlImportProgress.hidden = false;
                urlImportStatus.textContent = error.message;
                urlImportButton.disabled = false;
                urlImportButton.textContent = "URLから保存";
            }
        });

        const existingJobId = sessionStorage.getItem(jobStorageKey);
        if (existingJobId) pollJob(existingJobId);
    }

});
