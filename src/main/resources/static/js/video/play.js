/* =========================================================
   要素取得
========================================================= */

const video = document.getElementById("videoPlayer");

const player = document.getElementById(
    "videoPlayerContainer"
);

const playButton = document.getElementById(
    "playButton"
);

const centerPlayButton = document.getElementById(
    "centerPlayButton"
);

const seekBar = document.getElementById(
    "seekBar"
);

const currentTime = document.getElementById(
    "currentTime"
);

const duration = document.getElementById(
    "duration"
);

const muteButton = document.getElementById(
    "muteButton"
);

const fullscreenButton = document.getElementById(
    "fullscreenButton"
);

const speedIndicator = document.getElementById(
    "speedIndicator"
);

const volumeBar = document.getElementById(
    "volumeBar"
);


/* =========================================================
   再生
========================================================= */

/**
 * 再生 / 一時停止を切り替える
 */
function togglePlay() {

    if (video.paused) {

        video.play();

    } else {

        video.pause();

    }

}


/* 再生ボタン */

playButton.addEventListener("click", function(event) {

    event.stopPropagation();

    togglePlay();

});


/* 中央再生ボタン */

centerPlayButton.addEventListener(
    "click",
    function(event) {

        event.stopPropagation();

        togglePlay();

    }
);


/* 動画クリック */

let isLongPressing = false;

let ignoreNextClick = false;


video.addEventListener("click", function() {

    /*
     * 長押し終了直後のクリックを無視
     */
    if (isLongPressing) {

        return;

    }


    if (ignoreNextClick) {

        ignoreNextClick = false;

        return;

    }


    togglePlay();

});


/* =========================================================
   再生状態
========================================================= */

video.addEventListener("play", function() {

    playButton.textContent = "❚❚";

    centerPlayButton.style.display = "none";

});


video.addEventListener("pause", function() {

    playButton.textContent = "▶";

    centerPlayButton.style.display = "block";

});


/* =========================================================
   時間表示
========================================================= */

/**
 * 秒数を「分:秒」に変換
 */
function formatTime(seconds) {

    if (!isFinite(seconds)) {

        return "0:00";

    }


    const minutes =
        Math.floor(seconds / 60);


    const secondsPart =
        Math.floor(seconds % 60)
            .toString()
            .padStart(2, "0");


    return `${minutes}:${secondsPart}`;

}


/* 動画読み込み */

video.addEventListener(
    "loadedmetadata",
    function() {

        duration.textContent =
            formatTime(video.duration);

        seekBar.value = 0;

    }
);


/* 再生時間更新 */

video.addEventListener(
    "timeupdate",
    function() {

        currentTime.textContent =
            formatTime(video.currentTime);


        if (video.duration) {

            seekBar.value =
                (
                    video.currentTime /
                    video.duration
                ) * 10000;

        }

    }
);


/* =========================================================
   シーク
========================================================= */

seekBar.addEventListener(
    "input",
    function(event) {

        event.stopPropagation();


        if (!video.duration) {

            return;

        }


        video.currentTime =
            (
                seekBar.value / 10000
            ) * video.duration;

    }
);


/* =========================================================
   全画面
========================================================= */

fullscreenButton.addEventListener(
    "click",
    function(event) {

        event.stopPropagation();


        /*
         * すでに全画面なら解除
         */

        if (document.fullscreenElement) {

            document.exitFullscreen();

            return;

        }


        /*
         * iPhone
         */

        if (
            /iPhone|iPad|iPod/i.test(
                navigator.userAgent
            )
        ) {

            if (video.webkitEnterFullscreen) {

                video.webkitEnterFullscreen();

            }

            return;

        }


        /*
         * 通常ブラウザ
         */

        if (player.requestFullscreen) {

            player.requestFullscreen();

            return;

        }


        /*
         * Safariなど
         */

        if (video.webkitRequestFullscreen) {

            video.webkitRequestFullscreen();

        }

    }
);


/* =========================================================
   長押し2倍速
========================================================= */

let longPressTimer = null;


/**
 * 長押し開始
 */
function startLongPress(event) {

    /*
     * コントロール上では
     * 2倍速を発動しない
     */

    if (
        event.target.closest(".controls")
    ) {

        return;

    }


    longPressTimer = setTimeout(
        function() {

            isLongPressing = true;

            video.playbackRate = 2;

            speedIndicator.classList.add("show");

        },
        400
    );

}


/**
 * 長押し終了
 */
function endLongPress() {

    clearTimeout(longPressTimer);


    if (!isLongPressing) {

        return;

    }


    video.playbackRate = 1;

    speedIndicator.classList.remove("show");

    isLongPressing = false;

    ignoreNextClick = true;

}


/* スマホ */

video.addEventListener(
    "touchstart",
    startLongPress
);

video.addEventListener(
    "touchend",
    endLongPress
);

video.addEventListener(
    "touchcancel",
    endLongPress
);


/* PC */

video.addEventListener(
    "mousedown",
    startLongPress
);

video.addEventListener(
    "mouseup",
    endLongPress
);

video.addEventListener(
    "mouseleave",
    endLongPress
);


/* =========================================================
   ピンチズーム
========================================================= */

let zoom = 1;

let lastDistance = 0;

let translateX = 0;

let translateY = 0;

let lastTouchX = 0;

let lastTouchY = 0;

let isDragging = false;


/**
 * 2本指間の距離を取得
 */
function getDistance(touch1, touch2) {

    const dx =
        touch1.clientX -
        touch2.clientX;

    const dy =
        touch1.clientY -
        touch2.clientY;


    return Math.sqrt(
        dx * dx +
        dy * dy
    );

}


/**
 * 動画のズーム・移動を反映
 */
function applyTransform() {

    video.style.transform =
        `translate(${translateX}px, ${translateY}px) scale(${zoom})`;

}


/* タッチ開始 */

video.addEventListener(
    "touchstart",
    function(event) {

        /*
         * ピンチ開始
         */

        if (event.touches.length === 2) {

            lastDistance =
                getDistance(
                    event.touches[0],
                    event.touches[1]
                );

        }


        /*
         * ズーム中の1本指ドラッグ開始
         */

        if (
            event.touches.length === 1 &&
            zoom > 1
        ) {

            lastTouchX =
                event.touches[0].clientX;

            lastTouchY =
                event.touches[0].clientY;

            isDragging = true;

        }

    },
    {
        passive: false
    }
);


/* タッチ移動 */

video.addEventListener(
    "touchmove",
    function(event) {

        /*
         * ピンチズーム
         */

        if (event.touches.length === 2) {

            event.preventDefault();


            const distance =
                getDistance(
                    event.touches[0],
                    event.touches[1]
                );


            if (lastDistance > 0) {

                const difference =
                    distance -
                    lastDistance;


                zoom +=
                    difference * 0.005;


                zoom =
                    Math.max(
                        1,
                        Math.min(2.5, zoom)
                    );


                applyTransform();

            }


            lastDistance = distance;

        }


        /*
         * ズーム中のドラッグ
         */

        if (
            event.touches.length === 1 &&
            zoom > 1 &&
            isDragging
        ) {

            event.preventDefault();


            const currentX =
                event.touches[0].clientX;

            const currentY =
                event.touches[0].clientY;


            translateX +=
                currentX -
                lastTouchX;


            translateY +=
                currentY -
                lastTouchY;


            lastTouchX = currentX;

            lastTouchY = currentY;


            applyTransform();

        }

    },
    {
        passive: false
    }
);


/* タッチ終了 */

video.addEventListener(
    "touchend",
    function(event) {

        if (event.touches.length === 0) {

            lastDistance = 0;

            isDragging = false;

        }

    }
);


/* =========================================================
   ダブルタップでズーム解除
========================================================= */

let lastTapTime = 0;


video.addEventListener(
    "touchend",
    function() {

        const now = Date.now();


        if (
            now - lastTapTime < 300
        ) {

            zoom = 1;

            translateX = 0;

            translateY = 0;

            applyTransform();

        }


        lastTapTime = now;

    }
);


/* =========================================================
   音量
========================================================= */

video.volume = 1;

volumeBar.value = 100;


/* 音量変更 */

volumeBar.addEventListener(
    "input",
    function(event) {

        event.stopPropagation();


        video.volume =
            volumeBar.value / 100;


        /*
         * 音量変更時はミュート解除
         */

        video.muted = false;

        updateVolumeIcon();

    }
);


/* ミュートボタン */

muteButton.addEventListener(
    "click",
    function(event) {

        event.stopPropagation();


        /*
         * スマホでは
         * 音量バーを表示 / 非表示
         */

        if (window.innerWidth <= 600) {

            const volumeControl =
                muteButton.closest(
                    ".volume-control"
                );


            volumeControl.classList.toggle(
                "show-volume"
            );

        }


        video.muted =
            !video.muted;


        updateVolumeIcon();

    }
);


/**
 * 音量アイコンを更新
 */
function updateVolumeIcon() {

    if (
        video.muted ||
        video.volume === 0
    ) {

        muteButton.textContent = "🔇";

        return;

    }


    if (video.volume < 0.5) {

        muteButton.textContent = "🔉";

        return;

    }


    muteButton.textContent = "🔊";

}


updateVolumeIcon();


