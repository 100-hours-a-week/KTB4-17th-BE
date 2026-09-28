# File storage AWS setup

## Before deploying

This repository does not currently run Flyway or Liquibase. Apply
`src/main/resources/db/manual/20260926_create_file_upload_intents.sql` to the
production database before deploying this version. Production uses
`ddl-auto=validate`, so the application will not create this table itself.

The bucket must remain private with S3 Block Public Access enabled. The
application role needs `s3:PutObject` on `staging/*` and `files/*`,
`s3:GetObject` on both prefixes, and `s3:DeleteObject` on both prefixes.
Presigned URLs use this role's credentials; do not grant public bucket access.

## Mobile-web CORS

Set the bucket's CORS policy to the exact deployed web origins (and local
development origin when needed). Allow `PUT` and `GET`, and the `Content-Type`
request header. Expose `Content-Disposition`, `Content-Length`, and `ETag` for
clients that fetch download URLs as blobs. The mobile web client must send the
exact `Content-Type` returned in the upload-intent response. No browser
credentials or authorization headers are sent to S3.

Example shape; replace the origin with the real site origin in AWS:

```json
[
  {
    "AllowedOrigins": ["https://your-mobile-web-origin.example"],
    "AllowedMethods": ["PUT", "GET"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["Content-Disposition", "Content-Length", "ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

## Cleanup and size policy

The application cleanup job deletes expired staging objects and uncommitted
final objects every `APP_FILE_CLEANUP_DELAY_MS`, then removes cleaned intent
rows after `APP_FILE_UPLOAD_INTENT_RETENTION`. Add an S3 lifecycle rule as a
safety net to expire objects under the `staging/` prefix after one day. Do not
apply that rule to `files/`, which contains active files.

The AWS SDK presigned `PUT` flow does not enforce the configured maximum size
before S3 receives the bytes. The completion API checks the actual S3 object
size and removes oversized uploads; the mobile client should pre-check the
size for user experience, but that check is not a security boundary. If a
strict pre-upload size cap becomes mandatory, move to a signed S3 POST policy
or another upload gateway.

## Supported images

The initial allowlist is JPEG, PNG, and WebP, up to 10 MiB. SVG, GIF, and
HEIC/HEIF are rejected. The completion API checks S3's stored content type,
actual byte count, and the image signature prefix. This is format validation,
not full image decoding, malware scanning, or dating-profile moderation.
