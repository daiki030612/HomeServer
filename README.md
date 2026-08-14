# VideoServer

## ImageGalleryとの共有認証

VideoServerがログイン資格情報を検証し、VideoServerとImageGalleryの両方で利用できる短寿命の署名付きCookieを発行します。両アプリには必ず同じ32文字以上の秘密値を設定してください。

```text
SHARED_AUTH_SECRET=<十分に長いランダム値>
```

Cookieは`HttpOnly`、`SameSite=Lax`、`Path=/`で、ポート番号には依存しません。HTTPSまたはTailscale／リバースプロキシ環境では両アプリに`SHARED_AUTH_COOKIE_SECURE=true`を設定してください。トークン寿命は`SHARED_AUTH_TOKEN_TTL`（既定値30分）で変更できます。

リバースプロキシ配下では、必要に応じて`IMAGE_APP_URL=https://example.ts.net/gallery`を設定してください。ログイン後の戻り先は現在のホスト、ImageGalleryの設定ポート、または設定済みImageGallery URLだけに制限されます。
