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

VideoServerがログイン資格情報を検証し、VideoServerとImageGalleryの両方で利用できる短寿命の署名付きCookieを発行します。Cookieは`HttpOnly`、`SameSite=Lax`、`Path=/`で、ポート番号には依存しません。トークン寿命は`SHARED_AUTH_TOKEN_TTL`（既定値30分）で変更できます。

ローカルで同じホスト名・異なるポートを使う場合、`SHARED_AUTH_COOKIE_DOMAIN` は空のままで構いません。リバースプロキシ配下では、ログイン後の戻り先を許可するために `IMAGE_APP_URL` を設定します。戻り先は現在のホスト、ImageGalleryの設定ポート、または設定済みImageGallery URLだけに制限されます。

`SERVER_FORWARD_HEADERS_STRATEGY=framework`は、Cloudflare Tunnel等の信頼できるリバースプロキシからの接続だけに限定して使用します。直接公開された接続で任意のForwardedヘッダーを受け入れないでください。

## テスト

```sh
./mvnw test
```

Cloudflare TunnelをPublic Hostnameとして使う場合、Cloudflare外に保存した動画の配信はプラン／サービス固有条件の対象です。公開前に [Cloudflareの動画配信ガイド](https://developers.cloudflare.com/fundamentals/reference/policies-compliances/delivering-videos-with-cloudflare/) と契約を確認してください。設定例と公開前チェックリストはAsset Managerリポジトリの `docs/cloudflare-tunnel.md` にあります。
