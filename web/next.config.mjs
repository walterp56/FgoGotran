import createNextIntlPlugin from "next-intl/plugin";

const isDev = process.env.NODE_ENV === "development";
const withNextIntl = createNextIntlPlugin();

const nextConfig = {
  ...(isDev ? {} : { output: "export" }),
  images: {
    unoptimized: true
  },
  trailingSlash: true
};

export default withNextIntl(nextConfig);