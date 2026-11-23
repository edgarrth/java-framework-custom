"use strict";
// Ejercita el resolvedor de presets real de Renovate, sus extractores, las reglas de paquetes y el motor de reemplazo.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const source = path.resolve(
  process.argv[2] || path.join(__dirname, "../../.."),
);
const adoption = process.argv[3];
const vendor = "edgarrth/java-framework-custom";
const python = process.env.AXIZ_POLICY_PYTHON || "python3";
const installation = path.dirname(
  require.resolve("renovate/package.json", { paths: [__dirname] }),
);
const expectedVersion = require("./package.json").devDependencies.renovate;
assert.equal(
  require(path.join(installation, "package.json")).version,
  expectedVersion,
);
const moduleAt = (relative) =>
  require(path.join(installation, "dist", relative));
const { GlobalConfig } = moduleAt("config/global.js");
const { matchRegexOrGlobList } = moduleAt("util/string-match.js");
const { applyPackageRules } = moduleAt("util/package-rules/index.js");
const { RegExpVersioningApi } = moduleAt("modules/versioning/regex/index.js");
const actions = moduleAt("modules/manager/github-actions/index.js");
const regex = moduleAt("modules/manager/custom/regex/index.js");
const presets = moduleAt("modules/manager/renovate-config/index.js");
const git = (...args) =>
  execFileSync("git", ["-C", source, ...args], { encoding: "utf8" }).trim();
const rootConfig = JSON.parse(
  fs.readFileSync(path.join(source, "renovate.json"), "utf8"),
);
const adopted = /#(axiz-workflows-v2\.\d+\.\d+)/.exec(rootConfig.extends[0])[1];
const inspected = JSON.parse(
  execFileSync(
    python,
    [
      path.join(
        source,
        ".github/actions/check-workflow-security/workflow_pins.py",
      ),
      source,
      "--validated-history",
    ],
    { encoding: "utf8" },
  ),
);
const tags = inspected.tags;
const bootstrap = inspected.bootstrap;
assert(
  tags.includes(adopted) || bootstrap,
  "The already adopted preset must have trusted history",
);
if (adoption) {
  assert.notEqual(
    adoption,
    adopted,
    "Adoption must change the consumer version",
  );
  assert(tags.includes(adoption), "Adoption requires an existing target tag");
}
const temporary = fs.mkdtempSync(
  path.join(os.tmpdir(), "axiz-renovate-adoption-"),
);
GlobalConfig.set({
  localDir: temporary,
  platform: "github",
  endpoint: "https://api.github.com/",
  presetCachePersistence: false,
});
// Solo se reemplaza el transporte de archivos remotos con el tag exacto de este fixture. Toda la herencia
// de configuración (incluidos los presets integrados) la resuelve el propio Renovate, sin red.
const { GithubHttp } = moduleAt("util/http/github.js");
const loaded = [];
GithubHttp.prototype.getJsonUnchecked = async function (location) {
  const url = new URL(location, "https://api.github.com/");
  const prefix = `/repos/${vendor}/contents/`;
  assert(url.pathname.startsWith(prefix));
  const filename = decodeURIComponent(url.pathname.slice(prefix.length));
  const tag = url.searchParams.get("ref");
  assert.equal(tag, adopted, "Never load the not-yet-adopted workspace preset");
  loaded.push({ filename, tag });
  const content = tags.includes(tag)
    ? git("show", `refs/tags/${tag}:${filename}`)
    : fs.readFileSync(path.join(source, filename), "utf8");
  return {
    body: { content: Buffer.from(content).toString("base64") },
    statusCode: 200,
    headers: {},
  };
};
const { resolveConfigPresets } = moduleAt("config/presets/index.js");
const { doAutoReplace } = moduleAt(
  "workers/repository/update/branch/auto-replace.js",
);

(async () => {
  const { config } = await resolveConfigPresets(rootConfig);
  const { config: standalone } = await resolveConfigPresets({
    extends: [rootConfig.extends[0].replace("/platform#", "/service#")],
  });
  const standaloneRule = await applyPackageRules({
    depName: vendor,
    packageName: vendor,
    datasource: "github-tags",
    manager: "github-actions",
    packageFile: ".github/workflows/native.yaml",
    packageRules: standalone.packageRules,
  });
  assert.notEqual(
    standaloneRule.enabled,
    false,
    "Do not exclude consumer filenames in the shared preset",
  );
  const actionCoverage = await require('./action-pin-coverage.cjs')({ config, source, moduleAt, python, temporary });
  const pins = inspected.pins;
  const files = [...new Set(pins.map((pin) => pin.path))];
  const proposed = new Map();
  const groups = new Set();
  const covered = new Map();
  let enabledPins = 0,
    disabledProductDependencies = 0,
    thirdPartyProductDependencies = 0;
  const originalConsumerVersions = [
    ...new Set(
      pins.filter((pin) => pin.owner === "renovate").map((pin) => pin.version),
    ),
  ];
  const next =
    adoption || adopted.replace(/(\d+)$/, (value) => String(Number(value) + 1));
  for (const file of files) {
    const original = fs.readFileSync(path.join(source, file), "utf8");
    const destination = path.join(temporary, file);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, original);
    const records = [];
    if (matchRegexOrGlobList(file, actions.defaultConfig.managerFilePatterns)) {
      const extracted = await actions.extractPackageFile(
        original,
        file,
        config,
      );
      for (const [depIndex, dep] of (extracted?.deps || []).entries())
        records.push({
          dep,
          depIndex,
          manager: "github-actions",
          kind: "uses",
        });
    }
    if (
      matchRegexOrGlobList(file, [
        ...presets.defaultConfig.managerFilePatterns,
        ...config["renovate-config"].managerFilePatterns,
      ])
    ) {
      for (const [depIndex, dep] of (
        presets.extractPackageFile(original, file)?.deps || []
      ).entries())
        records.push({
          dep,
          depIndex,
          manager: "renovate-config",
          kind: "preset",
        });
    }
    for (const manager of config.customManagers || []) {
      if (manager.customType !== "regex") continue;
      if (!matchRegexOrGlobList(file, manager.managerFilePatterns)) continue;
      const kind = manager.matchStrings[0].startsWith("AXIZ_")
        ? "guard"
        : manager.matchStrings[0].startsWith("repository:")
          ? "ref"
          : "uses";
      for (const [depIndex, dep] of (
        regex.extractPackageFile(original, file, manager)?.deps || []
      ).entries())
        records.push({
          dep,
          depIndex,
          manager: "regex",
          kind,
          managerConfig: manager,
        });
    }
    let content = original;
    for (const { dep, depIndex, manager, kind, managerConfig } of records) {
      const selected = await applyPackageRules({
        ...dep,
        packageName: dep.packageName || dep.depName,
        manager,
        packageFile: file,
        packageRules: config.packageRules,
      });
      if (dep.depName !== vendor) {
        if (
          pins.some(
            (pin) => pin.path === file && pin.owner === "release-please",
          ) &&
          selected.enabled !== false
        )
          thirdPartyProductDependencies++;
        continue;
      }
      const own = pins.filter(
        (pin) => pin.path === file && pin.owner === "release-please",
      );
      if (own.length) {
        assert.equal(
          selected.enabled,
          false,
          `Renovate must not own product self pins: ${file}`,
        );
        disabledProductDependencies++;
        continue;
      }
      assert.notEqual(
        selected.enabled,
        false,
        `Consumer pin is disabled: ${file}`,
      );
      assert.equal(selected.groupName, "axiz workflows");
      assert.equal(
        selected.pinDigests,
        false,
        "Keep the explicit immutable release contract",
      );
      const versioning = new RegExpVersioningApi(
        selected.versioning.replace(/^regex:/, ""),
      );
      assert(versioning.isValid(next));
      for (const foreign of [
        "axiz-framework-v2.9.9",
        "axiz-tools-v2.9.9",
        "axiz-docs-v2.9.9",
        "axiz-workflows-v1.9.9",
      ])
        assert(
          !versioning.isValid(foreign),
          `Do not mix component/version families: ${foreign}`,
        );
      groups.add(selected.groupName);
      const key = `${file}:${kind}:${dep.currentValue}`;
      covered.set(key, (covered.get(key) || 0) + 1);
      const update = {
        ...managerConfig,
        ...selected,
        depIndex,
        manager,
        packageFile: file,
        newValue: next,
        newVersion: next,
        autoReplaceGlobalMatch: true,
        packageFileConfig:
          manager === "regex"
            ? config.customManagers.find((m) =>
                m.matchStrings.some((s) =>
                  s.startsWith(
                    kind === "guard"
                      ? "AXIZ_"
                      : kind === "ref"
                        ? "repository:"
                        : "uses:",
                  ),
                ),
              )
            : undefined,
      };
      try {
        content = await doAutoReplace(update, content, false);
      } catch (error) {
        throw new Error(
          `Replacement failed for ${file}, ${manager}, ${kind}: ${error.message}`,
          { cause: error },
        );
      }
      assert(content, `Renovate failed to update ${file}`);
      enabledPins++;
    }
    const expected = new Map();
    for (const pin of pins.filter(
      (pin) => pin.path === file && pin.owner === "renovate",
    )) {
      const key = `${file}:${pin.kind}:${pin.version}`;
      expected.set(key, (expected.get(key) || 0) + 1);
    }
    for (const [key, count] of expected)
      assert.equal(
        covered.get(key),
        count,
        `Each pin needs one effective extractor: ${key}`,
      );
    if (pins.some((pin) => pin.path === file && pin.owner === "release-please"))
      assert.equal(content, original);
    proposed.set(file, content);
  }
  assert.equal(groups.size, 1);
  assert(
    disabledProductDependencies > 0 && thirdPartyProductDependencies > 0,
    "Self pins disabled; third-party product dependencies remain maintained",
  );
  const consumers = pins.filter((pin) => pin.owner === "renovate");
  for (const file of new Set(consumers.map((pin) => pin.path))) {
    assert(proposed.get(file).includes(next), `Adoption misses ${file}`);
    if (adoption) fs.writeFileSync(path.join(source, file), proposed.get(file));
  }
  let changedConsumerPins = 0;
  if (adoption) {
    const after = JSON.parse(
      execFileSync(
        python,
        [
          path.join(
            source,
            ".github/actions/check-workflow-security/workflow_pins.py",
          ),
          source,
          "--validated-history",
        ],
        { encoding: "utf8" },
      ),
    ).pins.filter((pin) => pin.owner === "renovate");
    assert.equal(after.length, consumers.length);
    assert(after.every((pin) => pin.version === adoption));
    changedConsumerPins = after.filter(
      (pin, index) => pin.version !== consumers[index].version,
    ).length;
    assert.equal(
      changedConsumerPins,
      consumers.length,
      "Every inventoried consumer pin must actually change",
    );
    assert(
      originalConsumerVersions.length === 1 &&
        originalConsumerVersions[0] !== adoption,
    );
  }
  console.log(
    JSON.stringify(
      {
        passed: true,
        actionCoverage,
        changedConsumerPins,
        originalConsumerVersions,
        adoptedConsumerVersions: adoption
          ? [adoption]
          : originalConsumerVersions,
        renovateVersion: require(path.join(installation, "package.json"))
          .version,
        presetTag: adopted,
        loadedPresetFiles: loaded,
        group: "axiz workflows",
        consumerPins: consumers.length,
        consumerFiles: new Set(consumers.map((pin) => pin.path)).size,
        enabledDependencies: enabledPins,
        disabledProductDependencies,
        thirdPartyProductDependencies,
        proposedVersion: next,
        written: !!adoption,
      },
      null,
      2,
    ),
  );
})()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => fs.rmSync(temporary, { recursive: true, force: true }));
