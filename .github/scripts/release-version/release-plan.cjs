"use strict";
// One Release Please planner for monorepo assertions and real local lifecycle fixtures.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { Manifest } = require("release-please");

function safeTarget(root, file) {
  const target = path.resolve(root, file);
  assert(
    target.startsWith(root + path.sep),
    "Release paths must stay inside the source fixture",
  );
  assert(
    !fs.lstatSync(target, { throwIfNoEntry: false })?.isSymbolicLink(),
    "Release update cannot follow a symlink",
  );
  let ancestor = target;
  while (!fs.existsSync(ancestor)) ancestor = path.dirname(ancestor);
  const actual = fs.realpathSync(ancestor);
  assert(
    actual === root || actual.startsWith(root + path.sep),
    "Release path resolves outside the source fixture",
  );
  return target;
}

function repositoryFixture(
  root,
  componentPath,
  component,
  released,
  message,
  historicalOnly = false,
) {
  const config = JSON.parse(
    fs.readFileSync(path.join(root, "release-please-config.json"), "utf8"),
  );
  const components = Object.entries(config.packages);
  const bootstrap = config["bootstrap-sha"];
  const releaseSha = "a".repeat(40),
    changeSha = "c".repeat(40),
    historicalSha = "d".repeat(40);
  const manifest = released
    ? Object.fromEntries(components.map(([directory]) => [directory, released]))
    : {};
  const content = (file) => {
    if (file === "release-please-config.json") return JSON.stringify(config);
    if (file === ".release-please-manifest.json")
      return JSON.stringify(manifest);
    const absolute = safeTarget(root, file);
    return fs.existsSync(absolute) ? fs.readFileSync(absolute, "utf8") : "";
  };
  return {
    repository: {
      owner: "edgarrth",
      repo: "java-framework-custom",
      defaultBranch: "main",
    },
    async getFileJson(file) {
      return JSON.parse(content(file));
    },
    async getFileContentsOnBranch(file) {
      const parsedContent = content(file);
      return {
        parsedContent,
        content: Buffer.from(parsedContent).toString("base64"),
        sha: "b".repeat(40),
      };
    },
    async *releaseIterator() {
      if (released)
        for (const [, entry] of components) {
          const tagName = `${entry.component}-v${released}`;
          yield { name: tagName, tagName, sha: releaseSha, notes: "", url: "" };
        }
    },
    async *tagIterator() {
      if (released)
        for (const [, entry] of components)
          yield { name: `${entry.component}-v${released}`, sha: releaseSha };
    },
    async *pullRequestIterator() {},
    async *mergeCommitIterator() {
      if (!historicalOnly)
        yield {
          sha: changeSha,
          message,
          files: [
            `${componentPath}/version.txt`,
            `${componentPath}/src/main/java/ReleaseFixture.java`,
          ],
        };
      if (released)
        yield {
          sha: releaseSha,
          message: `chore: release ${component}`,
          files: [`${componentPath}/version.txt`],
        };
      yield {
        sha: bootstrap,
        message: "chore: exclusive bootstrap boundary",
        files: [`${componentPath}/version.txt`],
      };
      yield {
        sha: historicalSha,
        message: "feat!: EXCLUDED_HISTORICAL_CHANGE",
        files: [`${componentPath}/version.txt`],
      };
    },
  };
}

async function plans(github, branch) {
  return (await Manifest.fromManifest(github, branch)).buildPullRequests();
}

function assertWorkflowPaths(root, config, componentPath, updates) {
  const expected = JSON.parse(execFileSync(
    process.env.AXIZ_POLICY_PYTHON || 'python3',
    [path.join(root, '.github/scripts/release_permissions.py'), 'targets'],
    {input: JSON.stringify(config), encoding: 'utf8'},
  ));
  assert(Object.hasOwn(expected, componentPath), 'The planned component must be configured');
  const actual = [...new Set(updates.map(update => update.path)
    .filter(file => file.startsWith('.github/workflows/')))].sort();
  assert.deepEqual(actual, expected[componentPath],
    componentPath + ': planned workflow destinations differ from the static release contract');
  return actual;
}

function localRepository(root) {
  const git = (...args) =>
    execFileSync("git", ["-C", root, ...args], {
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    }).trim();
  const tags = git("tag", "--list").split("\n").filter(Boolean);
  const content = (file) => {
    const target = safeTarget(root, file);
    return fs.existsSync(target) ? fs.readFileSync(target, "utf8") : "";
  };
  return {
    repository: {
      owner: "edgarrth",
      repo: "java-framework-custom",
      defaultBranch: git("branch", "--show-current"),
    },
    async getFileJson(file) {
      return JSON.parse(content(file));
    },
    async getFileContentsOnBranch(file) {
      const parsedContent = content(file);
      return {
        parsedContent,
        content: Buffer.from(parsedContent).toString("base64"),
        sha: "b".repeat(40),
      };
    },
    async *releaseIterator() {
      for (const tag of tags)
        yield {
          name: tag,
          tagName: tag,
          sha: git("rev-parse", tag + "^{commit}"),
          notes: "",
          url: "",
        };
    },
    async *tagIterator() {
      for (const tag of tags)
        yield { name: tag, sha: git("rev-parse", tag + "^{commit}") };
    },
    async *pullRequestIterator() {},
    async *mergeCommitIterator() {
      for (const sha of git("rev-list", "--first-parent", "HEAD").split("\n")) {
        const parents = git("rev-list", "--parents", "-n", "1", sha)
          .split(" ")
          .slice(1);
        const names = parents.length
          ? git("diff", "--name-only", "-z", parents[0], sha)
          : git(
              "diff-tree",
              "--root",
              "--no-commit-id",
              "--name-only",
              "-r",
              "-z",
              sha,
            );
        yield {
          sha,
          message: git("show", "-s", "--format=%B", sha),
          files: names.split("\0").filter(Boolean),
        };
      }
    },
  };
}

async function applyWorkflowPlan(root) {
  root = fs.realpathSync(root);
  assert.notEqual(
    root,
    fs.realpathSync(path.resolve(__dirname, "../../..")),
    "Lifecycle updates belong only in an isolated fixture",
  );
  const github = localRepository(root);
  const candidates = await plans(github, github.repository.defaultBranch);
  const matching = candidates.filter((plan) =>
    plan.updates.some((update) => update.path === ".github/version.txt"),
  );
  assert.equal(
    matching.length,
    1,
    "Exactly one workflow component release plan is required",
  );
  assertWorkflowPaths(root, JSON.parse(fs.readFileSync(path.join(root, 'release-please-config.json'), 'utf8')),
    '.github', matching[0].updates);
  const changed = [];
  for (const update of matching[0].updates) {
    const target = safeTarget(root, update.path);
    const exists = fs.existsSync(target);
    assert(
      exists || update.createIfMissing,
      "Release plan expects an existing source file: " + update.path,
    );
    const before = exists ? fs.readFileSync(target, "utf8") : "";
    const after = update.updater.updateContent(before);
    assert.equal(typeof after, "string");
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, after);
    if (before !== after) changed.push(update.path);
  }
  assert(
    changed.includes(".release-please-manifest.json"),
    "Apply the real manifest updater",
  );
  assert(
    changed.includes(".github/CHANGELOG.md"),
    "Apply the real changelog updater",
  );
  return {
    version: fs
      .readFileSync(path.join(root, ".github/version.txt"), "utf8")
      .trim(),
    changed,
    updates: matching[0].updates.map((update) => update.path),
    releasePlease: require("release-please/package.json").version,
  };
}

module.exports = {
  repositoryFixture,
  plans,
  assertWorkflowPaths,
  localRepository,
  applyWorkflowPlan,
};
if (require.main === module)
  applyWorkflowPlan(process.argv[2])
    .then((result) =>
      console.log("AXIZ_RELEASE_PLAN_RESULT=" + JSON.stringify(result)),
    )
    .catch((error) => {
      console.error(error);
      process.exitCode = 1;
    });
