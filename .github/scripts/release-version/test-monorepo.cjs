"use strict";
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { DOMParser } = require("@xmldom/xmldom");

const root = path.resolve(__dirname, "../../..");
const config = JSON.parse(
  fs.readFileSync(path.join(root, "release-please-config.json"), "utf8"),
);
const components = Object.entries(config.packages);
const bootstrap = config["bootstrap-sha"];
assert.match(bootstrap, /^[a-f0-9]{40}$/);
assert.equal(
  execFileSync("git", ["cat-file", "-t", bootstrap], {
    cwd: root,
    encoding: "utf8",
  }).trim(),
  "commit",
);
execFileSync("git", ["merge-base", "--is-ancestor", bootstrap, "HEAD"], {
  cwd: root,
});
assert.deepEqual(Object.keys(config.packages).sort(), [
  ".github",
  "axiz-docs",
  "axiz-framework",
  "axiz-tools",
]);
assert.equal(config.packages[".github"].component, "axiz-workflows");
for (const name of ["axiz-framework", "axiz-tools"]) {
  assert.equal(config.packages[name].draft, true);
  assert.equal(
    config.packages[name]["force-tag-creation"],
    true,
    "a draft must create its build trigger tag",
  );
}
assert.equal(
  config.packages[".github"].draft,
  false,
  "the workflow release must be available to its consumers",
);
assert.equal(config.packages["axiz-docs"].draft, false);
const currentState = JSON.parse(
  fs.readFileSync(path.join(root, ".release-please-manifest.json"), "utf8"),
);
for (const [directory, version] of Object.entries(currentState)) {
  assert(Object.hasOwn(config.packages, directory));
  assert.match(version, /^\d+\.\d+\.\d+$/);
}

const releaseSha = "a".repeat(40);
const changeSha = "c".repeat(40);
const historicalSha = "d".repeat(40);

const releasePlan = require("./release-plan.cjs");
const repository = (...args) => releasePlan.repositoryFixture(root, ...args);

function selectedAndForeignVersions(xml, componentPath) {
  const document = new DOMParser().parseFromString(xml, "application/xml");
  const elements = Array.from(document.documentElement.childNodes).filter(
    (node) => node.nodeType === 1,
  );
  const selected =
    componentPath === "axiz-framework"
      ? Array.from(
          elements.find((node) => node.localName === "properties").childNodes,
        ).find((node) => node.localName === "revision")
      : elements.find((node) => node.localName === "version");
  const other = Array.from(document.getElementsByTagName("*"))
    .filter((node) => node.localName === "version" && node !== selected)
    .map((node) => node.textContent);
  return { selected: selected.textContent, foreign: other };
}

(async () => {
  const results = [];
  const inheritanceCases = [], unexpectedSourcesRejected = [];
  for (const [componentPath, component] of components) {
    for (const scenario of [
      {
        name: "initial-fix",
        message: "fix: initial capability",
        expected: "2.0.0",
      },
      {
        name: "initial-breaking",
        message: "feat!: initial public API",
        expected: "2.0.0",
      },
      {
        name: "patch",
        message: "fix: financial validation",
        released: "2.0.0",
        expected: "2.0.1",
      },
      {
        name: "minor",
        message: "feat: additional capability",
        released: "2.0.0",
        expected: "2.1.0",
      },
    ]) {
      const github = repository(
        componentPath,
        component.component,
        scenario.released,
        scenario.message,
      );
      const plans = await releasePlan.plans(github, "main");
      assert.equal(plans.length, 1, `${componentPath}: one changed component`);
      const updates = plans[0].updates;
      const workflowPaths = releasePlan.assertWorkflowPaths(root, config, componentPath, updates);
      const changedWorkflows = updates.filter(update => workflowPaths.includes(update.path)).filter(update => {
        const before = fs.readFileSync(path.join(root, update.path), 'utf8');
        return before !== update.updater.updateContent(before);
      }).length;
      const version = updates.find(
        (update) => update.path === `${componentPath}/version.txt`,
      );
      assert(version, `${componentPath}: version.txt updater required`);
      assert.equal(
        version.updater.updateContent("2.0.0\n").trim(),
        scenario.expected,
      );
      assert(
        !updates.some((update) =>
          components.some(
            ([directory]) =>
              directory !== componentPath &&
              update.path.startsWith(directory + "/"),
          ),
        ),
        `${componentPath}: independent component updates`,
      );
      const poms = updates.filter((update) => update.path.endsWith("/pom.xml"));
      assert.equal(
        poms.length,
        componentPath === "axiz-framework"
          ? 3
          : componentPath === "axiz-tools"
            ? 4
            : 0,
      );
      for (const update of poms) {
        const input = fs.readFileSync(path.join(root, update.path), "utf8");
        const before = selectedAndForeignVersions(input, componentPath);
        const after = selectedAndForeignVersions(
          update.updater.updateContent(input),
          componentPath,
        );
        assert.equal(after.selected, scenario.expected);
        assert.deepEqual(
          after.foreign,
          before.foreign,
          `${update.path}: foreign parent/BOM/dependency versions`,
        );
      }
      const changelog = updates.find(
        (update) => update.path === `${componentPath}/CHANGELOG.md`,
      );
      assert(changelog);
      assert(
        !changelog.updater
          .updateContent("")
          .includes("EXCLUDED_HISTORICAL_CHANGE"),
        "exclusive history boundary",
      );
      if (componentPath === ".github") {
        const consumer = updates.find(
          (update) => update.path === ".github/workflows/lib-build.yaml",
        );
        assert(consumer, "workflow release updates its own internal pins");
        const output = consumer.updater.updateContent(
          fs.readFileSync(path.join(root, consumer.path), "utf8"),
        );
        assert(output.includes(`@axiz-workflows-v${scenario.expected}`));
        assert(
          !output.includes("axiz-framework-v"),
          "workflow updates preserve component identities",
        );
        const forbidden = config.packages[".github"]["extra-files"].map(
          (entry) => `.github/${entry.path}`,
        );
        assert.equal(
          forbidden.length,
          12,
          "only published self references have a release updater",
        );
        for (const file of [
          "tools-ci.yaml",
          "framework-ci.yaml",
          "docs-pr.yaml",
          "demo-ci.yaml",
        ])
          assert(
            !updates.some(
              (update) => update.path === `.github/workflows/${file}`,
            ),
            "release PR preserves consumer pins",
          );
      }
      results.push({
        component: component.component,
        scenario: scenario.name,
        version: scenario.expected,
        pomUpdates: poms.length,
        workflowUpdates: workflowPaths.length,
        changedWorkflows,
      });
    }
    assert.deepEqual(
      await releasePlan.plans(
        repository(componentPath, component.component, undefined, "", true),
        "main",
      ),
      [],
      `${componentPath}: old history alone does not create an initial release`,
    );
  }
  for (const mode of ['inherit', 'replace', 'empty']) {
    const changedConfig = structuredClone(config);
    changedConfig['extra-files'] = [{type:'generic', path:'/.github/workflows/native.yaml'}];
    if (mode === 'inherit') delete changedConfig.packages['.github']['extra-files'];
    if (mode === 'empty') changedConfig.packages['.github']['extra-files'] = [];
    const github = repository('.github', 'axiz-workflows', '2.0.0', 'fix: validate extra-file inheritance');
    const original = github.getFileJson.bind(github);
    github.getFileJson = async file => file === 'release-please-config.json' ? changedConfig : original(file);
    const real = await releasePlan.plans(github, 'main');
    assert.equal(real.length, 1);
    const destinations = releasePlan.assertWorkflowPaths(root, changedConfig, '.github', real[0].updates);
    assert.equal(destinations.length, {inherit:1, replace:7, empty:0}[mode]);
    inheritanceCases.push({mode, workflowUpdates: destinations.length});
  }
  // These writes come from the real strategy, outside extra-files. Never silently infer
  // sufficient permissions from the explicit destinations while ignoring a new write source.
  for (const option of ['version-file', 'changelog-path']) {
    const changedConfig = structuredClone(config);
    changedConfig.packages['.github'][option] = '/.github/workflows/unreviewed-destination.yaml';
    const github = repository('.github', 'axiz-workflows', '2.0.0', 'fix: validate release destinations');
    const original = github.getFileJson.bind(github);
    github.getFileJson = async file => file === 'release-please-config.json' ? changedConfig : original(file);
    const real = await releasePlan.plans(github, 'main');
    assert.equal(real.length, 1);
    assert(real[0].updates.some(update => update.path === '.github/workflows/unreviewed-destination.yaml'));
    assert.throws(() => releasePlan.assertWorkflowPaths(root, changedConfig, '.github', real[0].updates),
      /planned workflow destinations/);
    unexpectedSourcesRejected.push({option, actualWorkflowUpdates: real[0].updates
      .filter(update => update.path.startsWith('.github/workflows/')).length});
  }
  const bootstrapRepository = repository(
    ".github",
    "axiz-workflows",
    undefined,
    "fix: bootstrap all components",
  );
  bootstrapRepository.mergeCommitIterator = async function* () {
    yield {
      sha: changeSha,
      message: "fix: bootstrap all components",
      files: components.map(([directory]) => `${directory}/version.txt`),
    };
    yield {
      sha: bootstrap,
      message: "chore: exclusive bootstrap boundary",
      files: [".github/version.txt"],
    };
  };
  const plans = await releasePlan.plans(bootstrapRepository, "main");
  assert.equal(
    plans.length,
    4,
    "bootstrap must allow publishing workflows before other components",
  );
  for (const [directory] of components) {
    const own = plans.filter((plan) =>
      plan.updates.some((update) => update.path === `${directory}/version.txt`),
    );
    assert.equal(own.length, 1);
    for (const [foreign] of components.filter(
      ([candidate]) => candidate !== directory,
    ))
      assert(
        !own[0].updates.some(
          (update) => update.path === `${foreign}/version.txt`,
        ),
        "independent component release PRs",
      );
  }
  console.log(
    JSON.stringify(
      {
        releasePlease: require("release-please/package.json").version,
        checks: results.length + components.length + plans.length + inheritanceCases.length + unexpectedSourcesRejected.length,
        workflowDestinations: {baselinePlans: results.length, inheritanceCases, unexpectedSourcesRejected},
        bootstrapComponentPRs: plans.length,
        results,
      },
      null,
      2,
    ),
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
