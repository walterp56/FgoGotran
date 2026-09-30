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
