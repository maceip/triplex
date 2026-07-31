import { readFile, writeFile } from "node:fs/promises";

const bundleUrl = new URL(
  "../../../src/triplex/transport/meeting_gateway_static/meeting-bridge.js",
  import.meta.url,
);
const bundle = await readFile(bundleUrl, "utf8");
const normalized = bundle
  .split("\n")
  .map((line) => line.trimEnd())
  .join("\n");
await writeFile(bundleUrl, normalized);
