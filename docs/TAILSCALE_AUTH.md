# Tailscale経由の直接ログイン

## 対象と認証構成

ベースは `main` の `fadf0884c3d6345ac4c3217d434735f69751296d`、Spring Boot 3.2.5 / Java 21。共通認証は外部ライブラリではなく、このリポジトリの `Auth` パッケージと `SecurityConfig` に実装されています。

公開側の署名付き `MEDIA_AUTH` Cookie、秘密鍵、Domain設定、トークン形式は変更しません。Tailscale側は、同じ `CustomUserDetailsService` と既存ユーザーDBでID・パスワードを検証し、`VIDEO_DIRECT_SECURITY_CONTEXT` を持つ独立セッションを使用します。共通CookieだけではTailscale側を認証できず、Tailscaleセッションだけでは公開側を認証できません。

DB接続、動画・フォルダ・サムネイルの保存先、既存ネイティブAPIは変更しません。他アプリのコード変更は不要です。

## 本番設定

既存の共有秘密鍵、Cookie設定、DB接続、メディア保存先を維持し、以下を確認してください。

```dotenv
SERVER_PORT=8081
VIDEO_DIRECT_ORIGIN=http://192.0.2.10:8081
VIDEO_APP_URL=https://video.media.example.com/videos
IMAGE_APP_URL=https://<既存のImageGalleryホスト>/gallery
SHARED_AUTH_COOKIE_DOMAIN=media.example.com
SHARED_AUTH_COOKIE_SECURE=true
HOMESERVER_UPLOAD_TEMP=/srv/videoserver/tmp
```

上記のIPアドレス・ドメイン・保存先はすべて例示です。実際のTailscale IP・公開ドメイン・保存先へ置換してください。`VIDEO_DIRECT_ORIGIN` の既定値は空で、明示設定するまで直接接続用の認証は有効になりません。ImageGalleryへの絶対URLの戻り先は、設定したスキーム・ホスト・ポートの組合せだけを許可します。Hostヘッダーから許可先を組み立てる処理は廃止しました。

- `VIDEO_DIRECT_ORIGIN` はスキーム・IP・ポートに加えて、実際の受信インターフェースとコンテキストパスを照合します。NATや別プロキシを経由し受信インターフェースが変わる接続は対象外です。
- `VIDEO_APP_URL` を設定すると、公開側もそのオリジンと照合します。公開側の既存プロキシが正しいスキーム・ホスト・ポートを渡すことを確認してください。
- 公開側で必要な転送ヘッダー設定は維持します。プロキシではクライアントが指定した転送ヘッダーを除去し、信頼できる値を設定してください。Tailscale側では偽の転送ホストやパスで経路を切り替えられません。
- セッションCookie名はVideoServer固有の `VIDEO_SESSION`。Domain未指定、HttpOnly、SameSite=Lax、URL書換えなしです。HTTP直接接続でも使えるよう、`SERVER_SERVLET_SESSION_COOKIE_DOMAIN` と `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` を別途設定しないでください。公開HTTPS接続のセッションCookieにはコンテナがSecure属性を付けます。公開側の `SHARED_AUTH_COOKIE_SECURE` は維持できます。
- 既存ブラウザのCSRF用セッションCookie名は切り替わるため、反映後はログイン画面・アップロード画面を再読込してください。

ログイン前のGETページとクエリを保存し、成功後は同じ経路内の相対パスへ戻します。Tailscale側は外部 `continue` を使用しません。公開側のImageGalleryへの明示的な `continue` は、許可されたオリジンの場合に限り維持します。

CSRF検証はログイン・アップロード・ログアウト等で維持し、ログイン時のセッションID更新とログアウト時のセッション破棄も維持します。Tailscale側のログアウトでは共通Cookieを削除しません。不正なオリジンを拒否するときはHTTP 400で終了し、保護された `/error` ページへの再ディスパッチからログイン転送が発生することも防ぎます。

## 3.82GB以上のアップロード

`spring.servlet.multipart.max-file-size` / `max-request-size` は **10GBのまま**です。リクエスト全体の上限にはmultipartのヘッダー等も含むため、10GBちょうどのファイルはこの設定では収まりません。

`file-size-threshold=0B` を明示し、受信ファイルを直ちに一時ディスクへ書き出します。既存 `VideoService.upload` は `MultipartFile.transferTo(File)` を使い、ファイル全体の `getBytes()` は呼びません。ファイルサイズの処理はlong型です。受信だけのために数GBのJavaヒープを用意する構造ではありません。

ただしディスク容量は別途必要です。一時領域には入力全体が必要で、保存先が別ファイルシステムならコピー中に追加の容量を消費します。remux・音声変換中は入力と出力が同時に存在します。3.82GiBの1ファイルなら合計約12GiBの空きと追加の余裕を目安にし、同時アップロードや変換出力の増加を考慮してください。これは全形式の変換を保証する上限ではありません。8GBメモリのサーバーでは一時領域がtmpfsでないことも確認します。FFmpegのネイティブメモリや並列処理はJavaヒープとは別です。

本番での読取専用確認例（パスは実際の設定に置換）:

```bash
sudo systemctl show videoserver -p WorkingDirectory -p FragmentPath -p MemoryMax
sudo grep -E '^(SERVER_PORT|HOMESERVER_UPLOAD_TEMP|HOMESERVER_VIDEO_STORAGE|HOMESERVER_THUMBNAIL_STORAGE|VIDEO_APP_URL|IMAGE_APP_URL|VIDEO_DIRECT_ORIGIN|SHARED_AUTH_COOKIE_DOMAIN|SHARED_AUTH_COOKIE_SECURE|SERVER_FORWARD_HEADERS_STRATEGY|SPRING_SERVLET_MULTIPART_[A-Z_]+)=' /etc/videoserver.env
free -h
findmnt -T /srv/videoserver/tmp
findmnt -T /srv/videoserver/videos
df -h /srv/videoserver/tmp /srv/videoserver/videos
df -i /srv/videoserver/tmp /srv/videoserver/videos
```

一時ディレクトリはサービスユーザーが書き込める状態で用意します。本番サーバーの実際の空き容量、メモリ制限、変換中のピークはこの変更作業では未確認です。

## 自動テスト

- `RouteAuthenticationTests`: 両経路のログイン失敗/成功、元のフォルダ・ページへの復帰、アップロード、ログアウト、セッションID更新、CSRF、Cookie/セッション分離、Host/転送ヘッダー/戻り先の検証。
- `NativeCookieAuthenticationTests`: 実際のネイティブAPIコントローラーを、従来形式の署名付きCookieで呼び出し。実際のCSRF取得セッションを使った更新と、CSRFなしの拒否を確認。
- 既存の共通Cookie Domainテスト、ネイティブ読取/更新API、ストリーミング、アップロード等の回帰テストを維持。
- `LargeDirectUploadTests`: 組み込みTomcatと実HTTPでログイン、ホスト専用Cookie、CSRF、**4GiB + 17バイト**のmultipart受信・`transferTo(File)`・一時ファイル削除・ログアウトを検証。`SERVER_FORWARD_HEADERS_STRATEGY=framework` でも偽の転送ホスト/パスを拒否することを確認。

通常のテスト:

```sh
./mvnw test
```

ディスクを使用する実HTTPテストは通常実行ではスキップします。空き容量を確保し、明示的に実行します。

```sh
./mvnw -Dtest=LargeDirectUploadTests -DlargeUploadTests=true -DargLine=-Xmx256m test
```

Mockitoの動的アタッチが制限された環境では、argLineに `-javaagent:<byte-buddy-agent.jarの絶対パス>` を追加します。この実HTTPテストはテスト用アカウントと保存エンドポイントを使うため、本番DB、動画のffprobe/FFmpeg処理、Cloudflare、本番サーバーの実機検証を代替しません。

## 手動反映後の確認

1. 公開URLのログイン、元ページへの復帰、ImageGalleryとの共通認証、iOSの動画一覧/更新を確認。
2. Tailscale URLでログインし、公開ドメインへ移動しないこと、CookieにDomainがないことを確認。
3. 両経路で小さい有効動画をアップロードし、同じ動画・フォルダが見えることを確認。
4. Tailscaleから3.82GB超の有効動画をアップロードし、一時領域・保存先の空き、メモリ、再生、サムネイル、一時ファイルの解放を確認。
5. 両経路のログアウトと再認証、CSRFなしの拒否を確認。公開経路のアップロードには従来どおりCloudflareの制限があります。

本番サーバーへのデプロイはこの作業では実施しません。

## 実行結果（2026-11-27）

- 全体テスト実行: 284件中279件成功、既存3件エラー、明示実行専用2件スキップ。
- エラーは `CustomThumbnailServiceTests.rejectsCustomDirectorySymlinkOutsideStorage`（Windowsのシンボリックリンク作成権限）、`HlsMpegTsFfmpegIntegrationTests`（FFmpeg処理）、`VideoUrlImportApplicationContextTests`（一時ディレクトリの書込み可否）です。変更を加えていないmainの同一テストでも3件とも再現しました。
- 明示実行した `LargeDirectUploadTests`: 2件成功。実HTTPで4,294,967,313バイトを受信・保存し、最大Javaヒープ268,435,456バイトで成功、一時ファイルの残留なし。転送ヘッダー有効時の不正ホスト/パスもHTTP 400で拒否。
- 実際のネイティブAPIに対する共有Cookie認証とCSRF更新テストも成功しています。
- 本番の空き容量、実際の動画変換、ImageGallery/Asset Manager/iPhone実機の動作は、手動デプロイ後に確認してください。

公開用の例示アドレス・環境変数へ整理した後も、認証・ネイティブAPI・アップロードの重点テスト47件がすべて成功しました。
