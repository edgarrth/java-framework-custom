import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { lint } from "markdownlint/promise";
import { parse } from "yaml";

// El árbol de documentación es explícito. El recorrido nativo evita introducir un compilador de globs.
const root = path.resolve(process.argv[2] || "axiz-docs");
const docs = path.join(root, "docs");
const entries = await readdir(docs, { recursive: true, withFileTypes: true });
const files = entries.filter(entry => entry.isFile() && entry.name.endsWith(".md"))
  .map(entry => path.join(entry.parentPath, entry.name));
files.push(path.join(root, "README.md"));
files.sort();
const config = parse(await readFile(path.join(root, ".markdownlint.yaml"), "utf8"));
const results = await lint({ files, config });
const diagnostics = Object.entries(results).flatMap(([file, issues]) =>
  issues.map(issue => `${file}:${issue.lineNumber} ${issue.ruleNames.join("/")} ${issue.ruleDescription}${issue.errorDetail ? ` (${issue.errorDetail})` : ""}`)
).join("\n");
if (diagnostics) {
  process.stderr.write(diagnostics + "\n");
  process.exitCode = 1;
} else {
  process.stdout.write(`Markdown: ${files.length} files passed.\n`);
}
