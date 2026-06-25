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

Deploy `web/` with AWS Amplify Hosting. Keep downloadable assets, DB packages,
and TSV preview files on S3 + CloudFront under `cdn.fgogotran.com`.
