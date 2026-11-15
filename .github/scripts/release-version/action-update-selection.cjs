"use strict";
// Replace only datasource transport. Renovate still applies the actual preset rules,
// version extraction, versioning and lookupUpdates selection to each extracted dependency.
const assert = require('node:assert/strict');
let sequence = 0;

async function lookup({config, moduleAt}, requests, tags) {
  const {getConfig} = moduleAt('config/defaults.js');
  const {applyPackageRules} = moduleAt('util/package-rules/index.js');
  const {lookupUpdates} = moduleAt('workers/repository/process/lookup/index.js');
  const {GithubTagsDatasource} = moduleAt('modules/datasource/github-tags/index.js');
  const prototype = GithubTagsDatasource.prototype;
  const original = Object.fromEntries(['getReleases', 'getDigest'].map(name =>
    [name, Object.getOwnPropertyDescriptor(prototype, name)]));
  const results = [];
  try {
    for (const request of requests) {
      const {dep, manager, packageFile, custom = {}} = request;
      const selected = await applyPackageRules({...getConfig(), ...config, ...config[manager],
        ...custom, ...dep, manager, packageFile, packageName: dep.packageName || dep.depName,
        packageRules: config.packageRules});
      assert.notEqual(selected.enabled, false);
      // Every transport fixture has its own cache key; tag order must never leak between cases.
      selected.packageName = `axiz-fixture/action-selection-${++sequence}`;
      prototype.getReleases = async () => ({releases: tags.map(version => ({version}))});
      prototype.getDigest = async (_config, version) => version === dep.currentValue
        ? dep.currentDigest : 'a'.repeat(40);
      const {val, err} = (await lookupUpdates(selected)).unwrap();
      if (err) throw err;
      assert(!val.warnings?.length, 'Unexpected datasource or lookup warning');
      results.push({manager, versioning: val.versioning, extractVersion: selected.extractVersion,
        updates: val.updates.filter(update => ['major', 'minor', 'patch'].includes(update.updateType))});
    }
  } finally {
    for (const [name, descriptor] of Object.entries(original)) {
      if (descriptor) Object.defineProperty(prototype, name, descriptor);
      else delete prototype[name];
    }
  }
  return results;
}

async function regression({config, source, moduleAt}) {
  const fs = require('node:fs'), path = require('node:path');
  const {matchRegexOrGlobList} = moduleAt('util/string-match.js');
  const regex = moduleAt('modules/manager/custom/regex/index.js');
  const actions = moduleAt('modules/manager/github-actions/index.js');
  const workflow = '.github/workflows/docs-docs.yaml';
  const registry = '.github/policies/action-pins-v2.json';
  const template = 'axiz-framework/codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service/github/ci.yaml.mustache';
  const native = (await actions.extractPackageFile(fs.readFileSync(path.join(source, workflow), 'utf8'), workflow, config)).deps
    .find(dep => dep.depName === 'actions/checkout');
  const requests = [{dep: native, manager: 'github-actions', packageFile: workflow}];
  for (const packageFile of [registry, template]) {
    const managers = config.customManagers.filter(custom => custom.customType === 'regex'
      && matchRegexOrGlobList(packageFile, custom.managerFilePatterns));
    const matches = managers.flatMap(custom =>
      (regex.extractPackageFile(fs.readFileSync(path.join(source, packageFile), 'utf8'), packageFile, custom)?.deps || [])
        .filter(dep => dep.depName === 'actions/checkout').map(dep => ({dep, custom})));
    assert.equal(matches.length, 1, 'Exactly one third-party extractor per source');
    requests.push({...matches[0], manager: 'regex', packageFile});
  }
  const current = native.currentValue;
  assert(requests.every(request => request.dep.currentValue === current));
  const major = Number(/^v(\d+)\./.exec(current)[1]) + 1;
  const full = `v${major}.0.0`, floating = `v${major}`, partial = `v${major}.0`;
  const cases = [];
  for (const tags of [[current, full, floating], [current, floating, full],
    [current, full, partial], [current, floating, partial]]) {
    const results = await lookup({config, moduleAt}, requests, tags);
    const expected = tags.includes(full) ? [full] : [];
    for (const result of results) {
      assert.equal(result.versioning, results[0].versioning);
      assert.equal(result.extractVersion, results[0].extractVersion);
      assert.deepEqual(result.updates.map(update => update.newValue), expected,
        `${result.manager}: only full release tags may be selected`);
      assert(result.updates.every(update => /^v\d+\.\d+\.\d+$/.test(update.newVersion)));
    }
    cases.push({tags, selected: results.map(result => ({manager: result.manager,
      versions: result.updates.map(update => update.newValue)}))});
  }
  return {passed: true, managers: requests.length, lookupCases: cases.length * requests.length, cases};
}

module.exports = {lookup, regression};
