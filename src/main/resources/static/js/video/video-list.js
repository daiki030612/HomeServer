/*
 * =========================
 * 動画メニュー
 * =========================
 */

function toggleMenu(event, button) {

    event.preventDefault();
    event.stopPropagation();

    const owner = button.parentElement;
    const menu = owner.querySelector(".menu-dropdown");
	guardMenuInteraction(menu);
    const willOpen = !menu.classList.contains("show");

    closeFolderMenus();
    closeVideoMenus(menu);

    if (!willOpen) {
        closeVideoMenu(menu);
        return;
    }

    const touchMenu = window.matchMedia("(hover: none) and (pointer: coarse)").matches;
    menu.querySelector(".action-sheet-title").textContent =
        owner.closest(".video-card").querySelector(".video-card-title").textContent;

    if (touchMenu) {
        menu._videoMenuOwner = owner;
        menu.classList.add("touch-action-sheet");
        document.body.appendChild(menu);
        document.getElementById("videoMenuBackdrop").classList.add("show");
    }

    menu.classList.add("show");
}

function guardMenuInteraction(menu) {
    if (!menu || menu.dataset.interactionGuard === "true") return;
    menu.dataset.interactionGuard = "true";
    ["pointerdown", "click"].forEach(function(type) {
        menu.addEventListener(type, function(event) { event.stopPropagation(); });
    });
    menu.querySelectorAll("form").forEach(function(form) {
        form.addEventListener("submit", function(event) { event.stopPropagation(); });
    });
}

function closeVideoMenu(menu) {
    if (!menu) return;

    const wasTouchMenu = menu.classList.contains("touch-action-sheet");
    menu.classList.remove("show", "touch-action-sheet");
    if (menu._videoMenuOwner) {
        menu._videoMenuOwner.appendChild(menu);
        menu._videoMenuOwner = null;
    }
    if (wasTouchMenu) {
        document.getElementById("videoMenuBackdrop")?.classList.remove("show");
    }
}

function closeVideoMenus(exceptMenu) {
    document.querySelectorAll(".menu-dropdown").forEach(function(menu) {
        if (menu !== exceptMenu) closeVideoMenu(menu);
    });

    if (!exceptMenu || !exceptMenu.classList.contains("touch-action-sheet")) {
        document.getElementById("videoMenuBackdrop")?.classList.remove("show");
    }
}


/*
 * カード外をクリックしたら
 * 動画メニューを閉じる
 */

document.addEventListener("click", function(event) {
    if (!event.target.closest(".video-menu") && !event.target.closest(".menu-dropdown")) {
        closeVideoMenus();
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

                if (event.target.closest(".tag-delete-form")) {

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

    closeVideoMenus();


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

// =========================
// フォルダー移動モーダル
// =========================

function openMoveFolderModal(event, button) {

    event.preventDefault();
    event.stopPropagation();

    closeVideoMenus();

    const currentFolderId = button.dataset.currentFolderId || "";

    document.getElementById("moveVideoId").value = button.dataset.id;
    document.getElementById("moveCurrentFolderId").value = currentFolderId;
    document.getElementById("moveTargetFolderId").value = "";
    document.getElementById("moveVideoTitle").textContent = button.dataset.title || "";
    document.getElementById("moveCurrentFolder").textContent =
        button.dataset.currentFolderName || "ライブラリ（ルート）";

    const status = document.getElementById("moveFolderStatus");
    status.textContent = "";
    status.className = "move-folder-status";

    const submit = document.getElementById("moveFolderSubmit");
    submit.disabled = true;
    submit.textContent = "移動";

    document.querySelectorAll(".move-destination-button").forEach(function(destination) {
        const isCurrent = (destination.dataset.folderId || "") === currentFolderId;
        destination.disabled = isCurrent;
        destination.classList.toggle("current", isCurrent);
        destination.classList.remove("selected");
        destination.setAttribute("aria-selected", "false");
        destination.toggleAttribute("aria-current", isCurrent);
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

    const videoId = document.getElementById("moveVideoId").value;
    const folderId = document.getElementById("moveTargetFolderId").value;
    const currentFolderId = document.getElementById("moveCurrentFolderId").value;
    const status = document.getElementById("moveFolderStatus");
    const submit = document.getElementById("moveFolderSubmit");

    if (folderId === currentFolderId) {
        status.textContent = "現在と同じ場所には移動できません。";
        status.className = "move-folder-status error";
        submit.disabled = true;
        return;
    }

    submit.disabled = true;
    submit.textContent = "移動中…";

    sendVideoMove(videoId, folderId)
        .then(function(response) {

            if (!response.ok) {

                throw new Error(
                    "動画の移動に失敗しました"
                );

            }
            status.textContent = "移動しました。画面を更新します。";
            status.className = "move-folder-status success";
            window.setTimeout(function() { window.location.reload(); }, 350);

        })
        .catch(function(error) {

            console.error(error);

            status.textContent = "動画の移動に失敗しました。もう一度お試しください。";
            status.className = "move-folder-status error";
            submit.disabled = false;
            submit.textContent = "移動";

        });

}

// =========================
// フォルダーメニュー
// =========================

function toggleFolderMenu(event, button) {

    event.preventDefault();
    event.stopPropagation();


	const owner = button.closest(".folder-card-shell");
    const menu = owner.querySelector(".folder-menu-dropdown");
	guardMenuInteraction(menu);
    const willOpen = !menu.classList.contains("show");

    closeFolderMenus(menu);
	closeVideoMenus();

    if (!willOpen) {
		closeFolderMenu(menu);
        return;
    }

    menu.classList.remove("open-upward");
	const touchMenu = window.matchMedia("(hover: none) and (pointer: coarse)").matches;
	if (touchMenu) {
		menu._folderMenuOwner = owner;
		menu.classList.add("touch-action-sheet");
		document.body.appendChild(menu);
		document.getElementById("videoMenuBackdrop")?.classList.add("show");
	}
    menu.classList.add("show");
    button.setAttribute("aria-expanded", "true");

	if (!touchMenu && menu.getBoundingClientRect().bottom > window.innerHeight - 12) {
        menu.classList.add("open-upward");
    }

    const firstItem = menu.querySelector('[role="menuitem"]');
    if (firstItem) firstItem.focus({ preventScroll: true });

}

function selectMoveDestination(button) {

    if (button.disabled) {
        return;
    }

    document.getElementById("moveTargetFolderId").value = button.dataset.folderId || "";

    document.querySelectorAll(".move-destination-button").forEach(function(destination) {
        const selected = destination === button;
        destination.classList.toggle("selected", selected);
        destination.setAttribute("aria-selected", selected ? "true" : "false");
    });

    const status = document.getElementById("moveFolderStatus");
    status.textContent = "移動先: " + (button.dataset.folderName || "ライブラリ（ルート）");
    status.className = "move-folder-status";
    document.getElementById("moveFolderSubmit").disabled = false;
}

function closeFolderMenus(exceptMenu) {
    document.querySelectorAll(".folder-menu-dropdown.show").forEach(function(menu) {
        if (menu === exceptMenu) return;
		closeFolderMenu(menu);
    });
	if (!exceptMenu) document.getElementById("videoMenuBackdrop")?.classList.remove("show");
}

function closeFolderMenu(menu) {
	if (!menu) return;
	menu.classList.remove("show", "open-upward", "touch-action-sheet");
	if (menu._folderMenuOwner) {
		menu._folderMenuOwner.appendChild(menu);
		menu._folderMenuOwner = null;
	}
	const button = menu.parentElement?.querySelector(".folder-menu-button");
	if (button) button.setAttribute("aria-expanded", "false");
	document.getElementById("videoMenuBackdrop")?.classList.remove("show");
}

function closeAllMediaMenus() {
	closeVideoMenus();
	closeFolderMenus();
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

    closeFolderMenus();

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
// フォルダ外クリックで閉じる
// =========================

document.addEventListener(
    "click",
    function(event) {

        if (!event.target.closest(".folder-menu-dropdown")
                && !event.target.closest(".folder-menu-button")) closeFolderMenus();

    }
);

// モーダルの背景タップとEscapeキーで、安全に操作をキャンセルする。
document.querySelectorAll(".modal").forEach(function(modal) {
    modal.addEventListener("click", function(event) {
        if (event.target === modal) {
            modal.classList.remove("show");
        }
    });
});

document.addEventListener("keydown", function(event) {
    if (event.key !== "Escape") return;

    document.querySelectorAll(".modal.show").forEach(function(modal) {
        modal.classList.remove("show");
    });

    closeVideoMenus();
    document.querySelectorAll(".folder-menu-dropdown.show")
        .forEach(function(menu) { menu.classList.remove("show", "open-upward"); });

    document.querySelectorAll(".folder-menu-button[aria-expanded='true']")
        .forEach(function(button) {
            button.setAttribute("aria-expanded", "false");
            button.focus({ preventScroll: true });
        });
});

// =========================
// 動画ドラッグ＆ドロップ
// =========================

let draggedVideoId = null;
let dragScrollFrame = null;
let dragScrollSpeed = 0;

const dragScrollEdge = 110;
const dragScrollMaxSpeed = 20;

function updateDragAutoScroll(event) {

    if (!draggedVideoId) {
        return;
    }

    const pointerY = event.clientY;
    const viewportHeight = window.innerHeight;

    if (pointerY < dragScrollEdge) {
        dragScrollSpeed = -dragScrollMaxSpeed
            * Math.min(1, 1 - pointerY / dragScrollEdge);
    } else if (pointerY > viewportHeight - dragScrollEdge) {
        dragScrollSpeed = dragScrollMaxSpeed
            * Math.min(1, 1 - (viewportHeight - pointerY) / dragScrollEdge);
    } else {
        dragScrollSpeed = 0;
    }

    if (dragScrollSpeed !== 0 && dragScrollFrame === null) {
        dragScrollFrame = requestAnimationFrame(runDragAutoScroll);
    }
}

function runDragAutoScroll() {

    if (!draggedVideoId || dragScrollSpeed === 0) {
        dragScrollFrame = null;
        return;
    }

    window.scrollBy(0, dragScrollSpeed);
    dragScrollFrame = requestAnimationFrame(runDragAutoScroll);
}

function stopDragAutoScroll() {

    dragScrollSpeed = 0;

    if (dragScrollFrame !== null) {
        cancelAnimationFrame(dragScrollFrame);
        dragScrollFrame = null;
    }
}

document.addEventListener("dragover", updateDragAutoScroll);
window.addEventListener("drop", stopDragAutoScroll);
window.addEventListener("dragend", stopDragAutoScroll);


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

    stopDragAutoScroll();

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

    stopDragAutoScroll();

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

    sendVideoMove(videoId, folderId)
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

function sendVideoMove(videoId, folderId) {

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    if (!csrfToken || !csrfHeader) {
        return Promise.reject(new Error("CSRFトークンを取得できません"));
    }

    const formData = new FormData();
    formData.append("videoId", videoId);

    if (folderId !== null && folderId !== undefined && folderId !== "") {
        formData.append("folderId", folderId);
    }

    return fetch(getVideoMoveUrl(), {
        method: "POST",
        headers: { [csrfHeader]: csrfToken },
        body: formData
    });
}

function getVideoMoveUrl() {
    const url = document.querySelector('meta[name="video-move-url"]')?.content;
    if (!url) {
        throw new Error("動画移動先URLを取得できません");
    }
    return url;
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
