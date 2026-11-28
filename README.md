# VideoServer

Spring Boot 3 / Java 21の認証付き動画ライブラリです。既存のMySQL DBとファイル保存を維持し、Rangeストリーミング、サムネイル、フォルダ、タグ、アップロード、URL取込を提供します。コード上の既定ポートは8080で、`SERVER_PORT`により変更できます。旧運用の8081を継続する場合は本番設定を確認してから`SERVER_PORT=8081`を明示します。

## ImageGallery / iOS統合

画面は白基調のiPhone向けレイアウトで、ImageGalleryへの切り替えとモバイル下部タブを備えます。ImageGalleryと同じ `SHARED_AUTH_SECRET` を設定すると、署名付き `MEDIA_AUTH` Cookieを共有できます。

異なるHTTPSサブドメインを使う場合:

```dotenv
SHARED_AUTH_SECRET=<32文字以上の共通ランダム値>
SHARED_AUTH_COOKIE_DOMAIN=.media.example.com
SHARED_AUTH_COOKIE_SECURE=true
IMAGE_APP_URL=https://images.media.example.com
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

Asset Manager iOSアプリは `https://video.media.example.com/videos` をWKWebViewで表示します。認証、ストリーミング、サムネイルの各エンドポイントは従来どおりSpring Securityの保護対象です。

iOSの統合メディア画面はHomeServer用とImageGallery用のWKWebViewを同時に保持し、画面切り替え時にもCookie、履歴、一覧位置を維持します。TailscaleのHTTP直接アクセスは、公開側の共通Cookieとは独立したホスト専用セッションで認証します。公開側の`SHARED_AUTH_COOKIE_SECURE`やCookie Domainを切り替える必要はありません。設定と確認手順は[Tailscale認証](docs/TAILSCALE_AUTH.md)を参照してください。

VideoServerがログイン資格情報を検証し、VideoServerとImageGalleryの両方で利用できる短寿命の署名付きCookieを発行します。Cookieは`HttpOnly`、`SameSite=Lax`、`Path=/`で、ポート番号には依存しません。トークン寿命は`SHARED_AUTH_TOKEN_TTL`（既定値30分）で変更できます。

ローカルで同じホスト名・異なるポートを使う場合、`SHARED_AUTH_COOKIE_DOMAIN` は空のままで構いません。ImageGalleryへのログイン後の遷移を利用する場合は、ローカル環境でも `IMAGE_APP_URL` を明示してください。絶対URLの戻り先は、設定したImageGalleryのスキーム・ホスト・ポートの組合せだけに制限されます。

`SERVER_FORWARD_HEADERS_STRATEGY=framework`は、Cloudflare Tunnel等の信頼できるリバースプロキシからの接続だけに限定して使用します。直接公開された接続で任意のForwardedヘッダーを受け入れないでください。

## テスト

```sh
./mvnw test
```

Cloudflare TunnelをPublic Hostnameとして使う場合、Cloudflare外に保存した動画の配信はプラン／サービス固有条件の対象です。公開前に [Cloudflareの動画配信ガイド](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/delivering-videos-with-cloudflare/) と契約を確認してください。設定例と公開前チェックリストはAsset Managerリポジトリの `docs/cloudflare-tunnel.md` にあります。

## AV1 / MKV

AV1・H.264・HEVCのMP4原本保存とMKVの無劣化MP4変換に対応します。端末ごとの再生対応、FFmpeg要件、エラーと検証手順は[AV1 / MKV対応](docs/AV1_MKV.md)を参照してください。
