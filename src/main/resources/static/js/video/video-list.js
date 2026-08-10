/*
 * =========================
 * 動画メニュー
 * =========================
 */

function toggleMenu(event, button) {

    event.preventDefault();
    event.stopPropagation();

    const menu = button.nextElementSibling;

    document
        .querySelectorAll(".menu-dropdown")
        .forEach(function(otherMenu) {

            if (otherMenu !== menu) {
                otherMenu.classList.remove("show");
            }

        });

    menu.classList.toggle("show");
}


/*
 * カード外をクリックしたら
 * 動画メニューを閉じる
 */

document.addEventListener("click", function(event) {

    if (!event.target.closest(".video-menu")) {

        document
            .querySelectorAll(".menu-dropdown")
            .forEach(function(menu) {

                menu.classList.remove("show");

            });

    }

});


/*
 * =========================
 * タグ入力
 * =========================
 */

const tagInput =
    document.getElementById("tagInput");

const tagChips =
    document.getElementById("tag-chips");

const hiddenTags =
    document.getElementById("editTags");

const tagSuggestions =
    document.getElementById("tag-suggestions");


let currentTags = [];


/*
 * =========================
 * タグを画面に表示
 * =========================
 */

function renderTags() {

    tagChips.innerHTML = "";

    currentTags.forEach(function(tag, index) {

        const chip =
            document.createElement("div");

        chip.className = "tag-chip";


        const tagText =
            document.createElement("span");

        tagText.textContent = tag;


        const removeButton =
            document.createElement("button");

        removeButton.type = "button";
        removeButton.className = "tag-remove";
        removeButton.textContent = "×";


        removeButton.onclick =
            function() {

                currentTags.splice(index, 1);

                renderTags();

                updateTagSuggestions();

            };


        chip.appendChild(tagText);
        chip.appendChild(removeButton);

        tagChips.appendChild(chip);

    });


    /*
     * Spring Bootへ送信
     */

    hiddenTags.value =
        currentTags.join(",");

}


/*
 * =========================
 * タグ候補を更新
 * =========================
 */

function updateTagSuggestions() {

    const keyword =
        tagInput.value
            .trim()
            .toLowerCase();


    let visibleCount = 0;


    tagSuggestions
        .querySelectorAll(".tag-suggestion")
        .forEach(function(suggestion) {

            const tag =
                suggestion.dataset.tag;


            const alreadyAdded =
                currentTags.includes(tag);


            const matches =
                keyword === ""
                || tag
                    .toLowerCase()
                    .includes(keyword);


            if (alreadyAdded || !matches) {

                suggestion.style.display =
                    "none";

                return;

            }


            suggestion.style.display =
                "block";

            visibleCount++;

        });


    tagSuggestions.style.display =
        visibleCount > 0
            ? "block"
            : "none";

}


/*
 * =========================
 * タグ入力欄
 * =========================
 */

tagInput.addEventListener(
    "focus",
    function() {

        updateTagSuggestions();

    }
);


tagInput.addEventListener(
    "input",
    function() {

        updateTagSuggestions();

    }
);


tagInput.addEventListener(
    "blur",
    function() {

        setTimeout(function() {

            hideTagSuggestions();

        }, 150);

    }
);


/*
 * =========================
 * 過去のタグをクリック
 * =========================
 */

tagSuggestions
    .querySelectorAll(".tag-suggestion")
    .forEach(function(suggestion) {

        suggestion.addEventListener(
            "mousedown",
            function(event) {

                /*
                 * 削除ボタンを押した場合は
                 * タグ選択処理をしない
                 */

                if (
                    event.target
                        .closest(".tag-delete-button")
                ) {

                    return;

                }


                event.preventDefault();


                const tag =
                    suggestion.dataset.tag;


                /*
                 * 重複防止
                 */

                if (!currentTags.includes(tag)) {

                    currentTags.push(tag);

                }


                tagInput.value = "";

                renderTags();

                hideTagSuggestions();

                tagInput.focus();

            }
        );

    });


/*
 * =========================
 * Enterでタグ追加
 * =========================
 */

tagInput.addEventListener(
    "keydown",
    function(event) {

        if (event.key !== "Enter") {

            return;

        }


        event.preventDefault();


        const tag =
            tagInput.value.trim();


        /*
         * 空文字なら何もしない
         */

        if (!tag) {

            return;

        }


        /*
         * 重複防止
         */

        if (!currentTags.includes(tag)) {

            currentTags.push(tag);

        }


        tagInput.value = "";

        renderTags();

        hideTagSuggestions();

    }
);


/*
 * =========================
 * タグ候補を閉じる
 * =========================
 */

function hideTagSuggestions() {

    tagSuggestions.style.display =
        "none";

}


/*
 * =========================
 * 名前・タグ編集
 * =========================
 */

function openEditModalFromButton(
    event,
    button
) {

    event.preventDefault();
    event.stopPropagation();


    const id =
        button.dataset.id;

    const title =
        button.dataset.title;

    const tags =
        button.dataset.tags;


    openEditModal(
        id,
        title,
        tags
    );

}


/*
 * =========================
 * 編集モーダルを開く
 * =========================
 */

function openEditModal(
    id,
    title,
    tags
) {

    document
        .getElementById("editId")
        .value = id;


    document
        .getElementById("editTitle")
        .value = title;


    currentTags =
        tags
            ? tags
                .split(",")
                .map(function(tag) {

                    return tag.trim();

                })
                .filter(function(tag) {

                    return tag !== "";

                })
            : [];


    renderTags();

    tagInput.value = "";

    hideTagSuggestions();


    document
        .getElementById("editModal")
        .classList.add("show");

}


/*
 * =========================
 * 編集モーダルを閉じる
 * =========================
 */

function closeEditModal() {

    document
        .getElementById("editModal")
        .classList.remove("show");

    hideTagSuggestions();

}


/*
 * =========================
 * フォルダ作成モーダル
 * =========================
 */

function openFolderModal() {

    document
        .getElementById("folderModal")
        .classList.add("show");

}


function closeFolderModal() {

    document
        .getElementById("folderModal")
        .classList.remove("show");

}

function closeFolderModal() {

    document
        .getElementById("folderModal")
        .classList.remove("show");
}

// =========================
// フォルダー移動モーダル
// =========================

function openMoveFolderModal(event, button) {

    event.preventDefault();
    event.stopPropagation();


    // 動画IDを取得
    const videoId =
        button.dataset.id;


    // hiddenに保存
    document
        .getElementById("moveVideoId")
        .value = videoId;


    // メニューを閉じる
    document
        .querySelectorAll(".menu-dropdown")
        .forEach(function(menu) {

            menu.classList.remove("show");

        });


    // モーダルを表示
    document
        .getElementById("moveFolderModal")
        .classList.add("show");

}


// =========================
// フォルダー移動モーダルを閉じる
// =========================

function closeMoveFolderModal() {

    document
        .getElementById("moveFolderModal")
        .classList.remove("show");

}


// =========================
// 動画を移動
// =========================

function moveVideo() {

    const videoId =
        document
            .getElementById("moveVideoId")
            .value;


    const folderId =
        document
            .getElementById("moveFolderId")
            .value;


    // FormData
    const formData =
        new FormData();


    formData.append(
        "videoId",
        videoId
    );


    // folderIdが空なら
    // メインページへ移動
    if (folderId !== "") {

        formData.append(
            "folderId",
            folderId
        );

    }


    // POST
    fetch(
        "/videos/move",
        {
            method: "POST",
            body: formData
        }
    )
        .then(function(response) {

            if (!response.ok) {

                throw new Error(
                    "動画の移動に失敗しました"
                );

            }


            // 移動後にページ更新
            window.location.reload();

        })
        .catch(function(error) {

            console.error(error);

            alert(
                "動画の移動に失敗しました。"
            );

        });

}

// =========================
// フォルダーメニュー
// =========================

function toggleFolderMenu(event, button) {

    event.preventDefault();
    event.stopPropagation();


    const menu =
        button.nextElementSibling;


    document
        .querySelectorAll(".menu-dropdown")
        .forEach(function(otherMenu) {

            if (otherMenu !== menu) {

                otherMenu.classList.remove("show");

            }

        });


    menu.classList.toggle("show");

}


// =========================
// フォルダー名前変更モーダル
// =========================

function openRenameFolderModal(
    event,
    button
) {

    event.preventDefault();
    event.stopPropagation();


    const id =
        button.dataset.id;

    const name =
        button.dataset.name;


    document
        .getElementById("renameFolderId")
        .value = id;


    document
        .getElementById("renameFolderName")
        .value = name;


    document
        .getElementById("renameFolderModal")
        .classList.add("show");

}


// =========================
// フォルダー名前変更モーダルを閉じる
// =========================

function closeRenameFolderModal() {

    document
        .getElementById("renameFolderModal")
        .classList.remove("show");

}
// =========================
// フォルダメニュー
// =========================

let folderLongPressTimer = null;


// =========================
// PC：右クリック
// =========================

function openFolderMenu(event, card) {

    event.preventDefault();
    event.stopPropagation();

    const menu =
        card.querySelector(".folder-menu-dropdown");


    // 他のメニューを閉じる

    document
        .querySelectorAll(".folder-menu-dropdown")
        .forEach(function(otherMenu) {

            if (otherMenu !== menu) {

                otherMenu.classList.remove("show");

            }

        });


    // メニュー位置

    const rect =
        card.getBoundingClientRect();

    menu.style.left =
        (event.clientX - rect.left) + "px";

    menu.style.top =
        (event.clientY - rect.top) + "px";


    menu.classList.add("show");

}


// =========================
// スマホ：長押し開始
// =========================

function startFolderLongPress(event, card) {

    folderLongPressTimer =
        setTimeout(function() {

            const touch =
                event.touches[0];

            openFolderMenu(
                {
                    preventDefault: function() {},
                    stopPropagation: function() {},
                    clientX: touch.clientX,
                    clientY: touch.clientY
                },
                card
            );

        }, 600);

}


// =========================
// 長押しキャンセル
// =========================

function cancelFolderLongPress() {

    clearTimeout(
        folderLongPressTimer
    );

}


// =========================
// フォルダ外クリックで閉じる
// =========================

document.addEventListener(
    "click",
    function(event) {

        if (
            !event.target.closest(".folder-card")
        ) {

            document
                .querySelectorAll(
                    ".folder-menu-dropdown"
                )
                .forEach(function(menu) {

                    menu.classList.remove("show");

                });

        }

    }
);

// =========================
// フォルダー削除
// =========================

function deleteFolder(event, button) {

    event.preventDefault();
    event.stopPropagation();


    const id =
        button.dataset.id;


    if (!confirm(
        "このフォルダーを削除しますか？"
    )) {

        return;

    }


    window.location.href =
        "/folders/delete/" + id;

}

// =========================
// 動画ドラッグ＆ドロップ
// =========================

let draggedVideoId = null;


// ドラッグ開始
function dragVideo(event, card) {

    draggedVideoId = card.dataset.id;

    event.dataTransfer.effectAllowed = "move";

    event.dataTransfer.setData(
        "text/plain",
        draggedVideoId
    );

    card.classList.add("dragging");
}


// ドラッグ終了
function dragVideoEnd(event, card) {

    card.classList.remove("dragging");

    document
        .querySelectorAll(".folder-card")
        .forEach(function(folder) {

            folder.classList.remove("drag-over");

        });

    draggedVideoId = null;
}


// フォルダー上にいる間
function allowVideoDrop(event, folder) {

    event.preventDefault();

    event.dataTransfer.dropEffect = "move";

}


// フォルダー上に入った
function enterVideoDrop(event, folder) {

    event.preventDefault();

    folder.classList.add("drag-over");
}


// フォルダーから出た
function leaveVideoDrop(event, folder) {

    // フォルダー内部の子要素へ移動しただけなら
    // 緑枠を消さない
    if (folder.contains(event.relatedTarget)) {
        return;
    }

    folder.classList.remove("drag-over");
}


// ドロップ
function dropVideo(event, folder) {

    event.preventDefault();

    folder.classList.remove("drag-over");

    const videoId =
        event.dataTransfer.getData("text/plain");

    const folderId =
        folder.dataset.id;

    if (!videoId || !folderId) {
        return;
    }

    moveVideoToFolder(
        videoId,
        folderId
    );
}


// =========================
// 実際に動画を移動
// =========================

function moveVideoToFolder(videoId, folderId) {

    const formData = new FormData();

    formData.append(
        "videoId",
        videoId
    );


    // フォルダーIDがある場合だけ送信
    if (folderId !== null && folderId !== undefined) {

        formData.append(
            "folderId",
            folderId
        );

    }


    fetch(
        "/videos/move",
        {
            method: "POST",
            body: formData
        }
    )
        .then(function(response) {

            if (!response.ok) {

                throw new Error(
                    "動画の移動に失敗しました"
                );

            }

            // 移動後に画面更新
            window.location.reload();

        })
        .catch(function(error) {

            console.error(error);

            alert(
                "動画の移動に失敗しました。"
            );

        });

}

// =========================
// パンくずへ動画をドロップ
// =========================

function dropVideoToBreadcrumb(event, breadcrumb) {

    event.preventDefault();

    const videoId =
        event.dataTransfer.getData("text/plain");

    const folderId =
        breadcrumb.dataset.id;

    if (!videoId || !folderId) {
        return;
    }

    moveVideoToFolder(
        videoId,
        folderId
    );
}

// =========================
// パンくずへ動画をドロップ
// =========================

function dropVideoToBreadcrumb(event, breadcrumb) {

    event.preventDefault();
    event.stopPropagation();

    const videoId =
        event.dataTransfer.getData("text/plain");

    const folderId =
        breadcrumb.dataset.id;

    console.log("パンくずへドロップ");
    console.log("videoId =", videoId);
    console.log("folderId =", folderId);

    if (!videoId || !folderId) {
        console.log("ID取得失敗");
        return;
    }

    moveVideoToFolder(
        videoId,
        folderId
    );
}

// =========================
// パンくずへの動画ドラッグ＆ドロップ
// =========================

// パンくず上にいる間
function allowBreadcrumbDrop(event, element) {

    event.preventDefault();

    event.dataTransfer.dropEffect = "move";
}


// パンくずに入った
function enterBreadcrumbDrop(event, element) {

    event.preventDefault();

    element.classList.add("drag-over");
}


// パンくずから出た
function leaveBreadcrumbDrop(event, element) {

    // パンくず内部の子要素へ移動しただけなら
    // 緑枠を消さない
    if (element.contains(event.relatedTarget)) {
        return;
    }

    element.classList.remove("drag-over");
}


// パンくずにドロップ
function dropBreadcrumbVideo(event, element) {

    event.preventDefault();
    event.stopPropagation();

    element.classList.remove("drag-over");

    const videoId =
        event.dataTransfer.getData("text/plain");

    if (!videoId) {
        console.log("動画ID取得失敗");
        return;
    }


    // =========================
    // Videos = ルートへ移動
    // =========================

    if (element.classList.contains("breadcrumb-root")) {

        console.log("ルートへ移動");
        console.log("videoId =", videoId);

        moveVideoToFolder(
            videoId,
            null
        );

        return;
    }


    // =========================
    // フォルダーへ移動
    // =========================

    const folderId =
        element.dataset.id;

    if (!folderId) {

        console.log("フォルダーID取得失敗");

        return;
    }

    console.log("フォルダーへ移動");
    console.log("videoId =", videoId);
    console.log("folderId =", folderId);


    moveVideoToFolder(
        videoId,
        folderId
    );
}
