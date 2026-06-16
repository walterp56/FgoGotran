/** @type {import('next').NextConfig} */
const isDev = process.env.NODE_ENV === "development";

const nextConfig = {
  ...(isDev ? {} : { output: "export" }),
  images: {
    unoptimized: true
  },
  trailingSlash: true,
  ...(isDev
    ? {
        async rewrites() {
          return [
            {
              source: "/preview/:path*",
              destination: "https://cdn.fgogotran.com/preview/:path*"
            }
          ];
        }
      }
    : {})
};

export default nextConfig;
