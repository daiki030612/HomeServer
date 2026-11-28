# AV1 / MKV upload and playback

Based on origin/main `02bf5cc65b050d4c23cff6f32962a74ef4248b1a`, including the Tailscale independent-session authentication fix. No deployment is performed by this change.

## 保存方針

| 入力 | 処理 |
|---|---|
| AV1 / H.264 / HEVC のMP4 | 原本をバイト単位で保持。Fast Startがない場合もRange配信 |
| 対応コーデックを含むMKV / MOV / AVI | FFmpegでMP4へストリームコピー。映像・音声の再エンコードなし |
| その他の映像・音声コーデック | コーデック名を含むエラー。自動再圧縮なし |

通常映像1本、音声0～1本を対象とします。映像はAV1 / H.264 / HEVC、音声はAAC / MP3 / AC-3 / E-AC-3 / ALAC。複数の通常映像・音声は、意図しない選択・欠落を避けるため明示的に拒否します。MP4以外の詰め替えでは対象映像と音声を保存し、字幕・添付ファイルは対象外です。HEVC Main 10・HDRなどもサーバー側で再圧縮しませんが、クライアントの再生対応とは別です。

120fpsを含め上限60fpsの判定を撤廃しました。FFmpegには`-r`、fpsフィルター、`setpts`、映像・音声エンコーダーを指定しません。`-c copy -copyts -start_at_zero -copytb 1 -avoid_negative_ts disabled`で相対タイムスタンプを維持します。非ゼロ開始時刻は共通の原点へ移します。変換後のコーデック、寸法、fps、音声コーデック一覧、再生時間を照合し、想定外の差異は保存前に拒否します。特殊なタイムラインは検証エラーになる場合があり、推測でフレームレートを書き換えません。HEVCの詰め替え出力には`hvc1`を付けます。

サムネイルはソフトウェアデコード可能なFFmpegを使用し、通常映像の先頭フレームから生成します。AV1デコーダー（例: dav1d）を含むFFmpeg / ffprobeがPATH上に必要です。サムネイル生成には60秒、詰め替えには30分の上限があります。

## クライアント

既存のstreamUrl、downloadUrl、動画DTOは変更しません。Range配信、シーク、認証付きダウンロードを継続します。変換したMP4のダウンロード名は元の.mkv/.mov/.aviから.mp4へ補正します。

追加API:
- `GET /api/v1/videos/{id}/media-info`
- `GET /videos/{id}/media-info`

どちらも既存の認証ポリシーで保護し、videoCodec、profile、level、pixelFormat、width、height、framesPerSecond、durationSecondsを返します。DB列の追加や既存動画の一括変更はありません。

ブラウザは実際のHTMLMediaElementエラーから通信失敗とデコード・形式の問題を区別し、コーデック・寸法・fpsを併記します。UA文字列だけで対応を断定して再生を止めることはしません。SafariのAV1対応にはハードウェア依存があります。120fpsのデコードや滑らかな表示は端末ごとに確認が必要です。

iOSネイティブ版の既存ソースはAVURLAsset / AVPlayerを利用し、既存URLを継続使用できます。HomeServerリポジトリにはアプリ本体がないため、`ios-native-av1.patch`に別ソース向けの追加変更を同梱しました。アプリのルートで`git apply --check /path/to/ios-native-av1.patch`を確認してから適用します。これは既存のVideoPlayerScreen.swift（手元の2026-11-19プロジェクト）に対するパッチです。AVPlayerItem.statusの監視、media-infoの取得、形式を添えた失敗表示を追加します。サーバーのPRをマージするだけでは、インストール済みのiOSアプリにこの表示変更は入りません。

参照:
- [WebKit: AV1 support on hardware-decoding devices](https://webkit.org/blog/14445/webkit-features-in-safari-17-0/)
- [FFmpeg streamcopy and timestamp options](https://ffmpeg.org/ffmpeg.html)

## 容量・検証・エラー

上限は既存の10GBです。ブラウザでは拡張子、サイズ、先頭16バイトのコンテナ識別子を送信前に確認します。サーバーでは先頭識別子とffprobeによる実データを確認し、プローブのネットワークプロトコルを無効化します。拡張子・MIMEだけで映像コーデックを信用しません。

multipartは即座にディスクへ保存し、保存はtransferTo、配信・ダウンロードは64KiBのバッファを使います。ffprobe出力とFFmpegログにも保持量の上限があります。動画全体をbyte[]へ読み込む経路は使いません。

保存前と詰め替え前に保存先の空き容量を確認します。MKVの詰め替え中は入力と出力の両方が必要で、別マウントのmultipart一時領域にも容量が必要です。失敗時は今回の処理で作ったファイルを補償削除し、保存済み動画や既存DBには触れません。

| API code | HTTP | 意味 |
|---|---|---|
| FILE_TOO_LARGE | 413 | 10GB超過 |
| UNSUPPORTED_CONTAINER | 415 | 非対応拡張子 |
| INVALID_VIDEO | 422 | データ・メタデータ不正 |
| UNSUPPORTED_CODEC | 422 | コーデックまたはストリーム構成非対応 |
| INSUFFICIENT_STORAGE | 507 | 保存先・一時保存先の容量不足 |
| REMUX_FAILED | 422 | ストリームコピーまたは出力検証失敗 |
| REMUX_TIMING_MISMATCH | 422 | 変換前後の時間・fpsなどの不一致 |
| THUMBNAIL_FAILED | 422 | デコーダー等によるサムネイル生成失敗 |
| UPLOAD_FAILED | 500 | その他の保存処理失敗 |

Web版は同じ内容をテキストで表示し、ネイティブAPIは既存のerrorエンベロープを維持します。

## 検証

Java 21 / FFmpeg 9.0.2で検証しました。Javaテストは282件成功・大容量HTTP試験2件スキップ、JavaScriptテスト3件成功です。

```sh
./mvnw -DmediaIntegrationTests=true test
node --test src/test/js/*.test.cjs
./mvnw -DlargeUploadTests=true -Dtest=LargeDirectUploadTests -DargLine=-Xmx256m test
```

- AV1 / H.264 / HEVC × MP4 / MKVの6通りを実FFmpegで生成し、実際のアップロード保存パイプラインを通過。
- 120fps、再生時間、各映像・音声パケットのPTSと同期、SHA-256一致を確認。MP4はファイル全体の一致も確認。
- AV1 Mainの1920×1080 / 120fpsサンプルで保存判定・サムネイル生成を確認。
- サムネイル、206 Range、ダウンロード内容とMP4ファイル名、保存後の不要ファイル削除を確認。
- 4GiB超の疎ファイルを実際にRange配信・全量読み出しし、64KiBバッファとlong型の長さを確認。
- 不正なコンテナ、容量超過、容量不足、変換失敗、時間・fps不一致、非対応音声、DB失敗時の補償削除を検証。
- 既存のTailscale認証、公開側共通認証、CSRF、ネイティブAPIの回帰テストを実施。

未確認・実行制限:
- ユーザーの4.10GB / 約78分の実ファイルはこの作業環境にないため、実ファイルの全編再生は未確認。
- 実HTTPの4GiB超multipart試験はローカルソケット作成が`Operation not permitted`で拒否され、この環境では実行できません。通常テストでは既存の2件をスキップします。上記コマンドとGitHub Actionsに再実行手順を用意しています。
- ブラウザ実機およびiOS実機での再生・シーク・音声同期は未確認。サーバーの自動テストを端末での実再生確認と同一視しません。
- iOSパッチはSwift構文確認済み。アプリ全体のiPhone向けビルドは環境側のSwiftUIマクロプラグイン応答エラーで失敗し、シミュレータへの接続も制限されているため、型検査・動作確認完了とはしていません。

GitHub ActionsではJava・実コーデック・ブラウザJS・4GiB multipartを実行します。結果はPRのChecksを確認してください。
