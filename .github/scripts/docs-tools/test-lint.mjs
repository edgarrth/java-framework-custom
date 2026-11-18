import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const script = fileURLToPath(new URL("./lint.mjs", import.meta.url));
test("native enumeration validates nested files and rejects a Markdown defect", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "axiz-markdown-"));
  try {
    await mkdir(path.join(root, "docs", "nested"), { recursive: true });
    await writeFile(path.join(root, ".markdownlint.yaml"), "default: true\nMD013: false\n");
    await writeFile(path.join(root, "README.md"), "# Documentation\n\nA valid introduction.\n");
    const file = path.join(root, "docs", "nested", "example.md");
    await writeFile(file, "# Example\n\nA valid paragraph.\n");
    const run = () => spawnSync(process.execPath, [script, root], { encoding: "utf8", timeout: 30000 });
    const valid = run();
    assert.ifError(valid.error);
    assert.equal(valid.status, 0, valid.stderr);
    assert.match(valid.stdout, /2 files passed/);
    await writeFile(file, "# Example\n\nBad trailing spaces.   \n");
    const invalid = run();
    assert.ifError(invalid.error);
    assert.equal(invalid.status, 1, invalid.stderr);
    assert.match(invalid.stderr, /example.md:3 MD009/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
