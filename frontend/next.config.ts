import type { NextConfig } from "next";

import { assertApiBaseUrlForBuild } from "./lib/api-base-url";

// `NEXT_PUBLIC_*` values are inlined at build time, so a production bundle without an API origin
// would silently ship the development localhost default (review finding). Fail the build instead.
// `next typegen` also runs with NODE_ENV=production, so the guard keys off the actual `build` argv.
if (process.argv.includes("build")) {
  assertApiBaseUrlForBuild();
}

const nextConfig: NextConfig = {
  /* config options here */
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
