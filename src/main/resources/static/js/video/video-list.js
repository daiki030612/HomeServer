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