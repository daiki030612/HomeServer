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
		    "/videos/upload"
		);

		const csrfToken =
		    document.querySelector('meta[name="_csrf"]')?.content;

		const csrfHeader =
		    document.querySelector('meta[name="_csrf_header"]')?.content;

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
                            if (folderId) {
                                window.location.href = "/videos/folder/" + folderId;
                            } else {
                                window.location.href = "/videos";
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

});
