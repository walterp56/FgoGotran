# FgoGotran Web

Localized static-first website for FgoGotran, built with Next.js and `next-intl`.

## Local Development

```bash
npm ci
npm run check:messages
npm run dev
```

Localized routes:

```text
/zh-CN/
/zh-TW/
/en/
```

Legacy root-level pages redirect to the corresponding localized routes.

The site reads release data from `NEXT_PUBLIC_CDN_BASE_URL` when available.
Default:

```text
https://cdn.fgogotran.com
```

## Localization

Website strings live in:

```text
web/messages/zh-CN.json
web/messages/zh-TW.json
web/messages/en.json
```

Run `npm run check:messages` after editing any message file. The three catalogs must keep identical keys and array shapes.

### Example Images

Homepage slideshow images live in:

```text
web/public/examples/        Simplified Chinese and default fallback
web/public/examples/en/     English
web/public/examples/zh-TW/  Traditional Chinese
```

Add Traditional Chinese screenshots directly to `web/public/examples/zh-TW/`, for example `1.jpg`, `2.jpg`, and `3.jpg`. Supported formats are `.jpg`, `.jpeg`, `.png`, `.webp`, and `.avif`. Images are sorted by filename with numeric ordering; filenames and image counts do not need to match the default or English set.

When the Traditional Chinese folder contains any supported images, `/zh-TW/` uses only that folder's images. While it is empty, the page uses the default images in `web/public/examples/`. The `.gitkeep` file preserves the empty folder in Git and is ignored by the slideshow.

Run `npm run check:images` to report image coverage. Rebuild and deploy the website after adding or changing images so the published slideshow includes them.

## Deployment

The GitHub Actions workflow at `.github/workflows/deploy-web.yml` builds `web/out` and deploys it to S3 with a CloudFront invalidation. Keep downloadable assets and DB packages on S3 + CloudFront under `cdn.fgogotran.com`.

## Glossary Preview

Website-only glossary preview JSON is generated into:

```text
web/public/term-preview/zh-Hans/latest/
```

Run this before deploying the website when TSV files change:

```bash
../scripts/release-preview.bat
```
