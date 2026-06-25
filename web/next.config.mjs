/** @type {import('next').NextConfig} */
const isDev = process.env.NODE_ENV === "development";

const nextConfig = {
  ...(isDev ? {} : { output: "export" }),
  images: {
    unoptimized: true
  },
  trailingSlash: true
};

export default nextConfig;
