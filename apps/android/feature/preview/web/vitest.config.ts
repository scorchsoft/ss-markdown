import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // The e2e files each start Chromium; running them one at a time keeps memory flat.
    fileParallelism: false,
    projects: [
      {
        test: { name: "unit", include: ["test/unit/**/*.test.ts"], environment: "node" },
      },
      {
        test: {
          name: "e2e",
          include: ["test/e2e/**/*.test.ts"],
          environment: "node",
          testTimeout: 60_000,
          hookTimeout: 60_000,
        },
      },
    ],
  },
});
