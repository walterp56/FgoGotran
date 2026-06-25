# FgoGotran Web

Modern static-first website for FgoGotran.

## Local Development

```bash
npm install
npm run dev
```

The site reads release data from `NEXT_PUBLIC_CDN_BASE_URL` when available.
Default:

```text
https://cdn.fgogotran.com
```

## Deployment

Deploy `web/` with AWS Amplify Hosting. Keep downloadable assets and DB
packages on S3 + CloudFront under `cdn.fgogotran.com`.

Website-only glossary preview JSON is generated into:

```text
web/public/term-preview/zh-Hans/latest/
```

Run this before deploying the website when TSV files change:

```bash
../scripts/release-preview.bat
```
