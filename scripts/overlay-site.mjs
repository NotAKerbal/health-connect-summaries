#!/usr/bin/env node
// Copies this repository's cloud/ source onto a Site checkout created by the Sites plugin's
// Vinext starter (project-setup.mjs). The starter supplies the build integration listed in
// cloud/sites-starter-files.txt; this script adds and replaces only the app's own files.
//
// Usage: node scripts/overlay-site.mjs <site-checkout>
//
// Safe to re-run when updating. It never copies symlinks, Git metadata, dependencies, or
// local state, never writes a project_id, and keeps the checkout's own project_id.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const source = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "cloud");
const SKIP = new Set([".git", "node_modules", ".wrangler", ".vinext", ".next", ".sites-runtime", "dist", "outputs", "downloads"]);
const SKIP_FILES = /^(\.env|\.dev\.vars)|\.tsbuildinfo$/;

function fail(message) {
  console.error(`overlay-site: ${message}`);
  process.exit(1);
}

const [target, ...extra] = process.argv.slice(2);
if (!target || extra.length) fail("usage: node scripts/overlay-site.mjs <site-checkout>");
if (!existsSync(target) || !lstatSync(target).isDirectory()) fail("the Site checkout must be an existing directory");
const checkout = realpathSync(target);
const repo = realpathSync(path.dirname(source));
if (checkout === repo || checkout.startsWith(repo + path.sep)) fail("the Site checkout must be outside this repository");

const starterFiles = readFileSync(path.join(source, "sites-starter-files.txt"), "utf8")
  .split("\n")
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith("#"));
const missing = starterFiles.filter((file) => !existsSync(path.join(checkout, file)));
if (missing.length) {
  fail(
    `${missing.length} Sites starter file(s) are missing (first: ${missing[0]}). ` +
      "Create the checkout with the Sites plugin's project-setup.mjs first.",
  );
}

const manifestPath = path.join(source, ".openai", "hosting.json");
const ours = JSON.parse(readFileSync(manifestPath, "utf8"));
if (ours.project_id) fail("cloud/.openai/hosting.json must not contain a project_id");

let copied = 0;
function walk(relative) {
  for (const entry of readdirSync(path.join(source, relative), { withFileTypes: true })) {
    const rel = path.join(relative, entry.name);
    if (entry.isSymbolicLink()) fail(`refusing symlink ${rel}`);
    if (entry.isDirectory()) {
      if (!SKIP.has(entry.name)) walk(rel);
      continue;
    }
    if (!entry.isFile() || SKIP_FILES.test(entry.name) || rel === path.join(".openai", "hosting.json")) continue;
    mkdirSync(path.join(checkout, relative), { recursive: true });
    copyFileSync(path.join(source, rel), path.join(checkout, rel));
    copied++;
  }
}
walk("");

// Merge the hosting manifest: our bindings and capabilities, the checkout's own Site identity.
const targetManifest = path.join(checkout, ".openai", "hosting.json");
const theirs = existsSync(targetManifest) ? JSON.parse(readFileSync(targetManifest, "utf8")) : {};
const merged = { ...theirs, d1: ours.d1, r2: ours.r2 ?? null, capabilities: ours.capabilities };
if (theirs.project_id) merged.project_id = theirs.project_id;
mkdirSync(path.dirname(targetManifest), { recursive: true });
writeFileSync(targetManifest, JSON.stringify(merged, null, 2) + "\n");

console.log(
  `Copied ${copied} file(s) into ${checkout}. Hosting manifest: d1=${merged.d1}, capabilities=${merged.capabilities.join(",")}, ` +
    `project_id ${merged.project_id ? "kept" : "not set yet (run set-project-id.mjs after create_site)"}.`,
);
