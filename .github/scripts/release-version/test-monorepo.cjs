'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {Manifest} = require('release-please');
const {DOMParser} = require('@xmldom/xmldom');

const root = path.resolve(__dirname, '../../..');
const config = JSON.parse(fs.readFileSync(path.join(root, 'release-please-config.json'), 'utf8'));
const components = Object.entries(config.packages);
const bootstrap = config['bootstrap-sha'];
assert.match(bootstrap, /^[a-f0-9]{40}$/);
assert.equal(execFileSync('git', ['cat-file', '-t', bootstrap], {cwd: root, encoding: 'utf8'}).trim(), 'commit');
execFileSync('git', ['merge-base', '--is-ancestor', bootstrap, 'HEAD'], {cwd: root});
assert.deepEqual(Object.keys(config.packages).sort(), ['.github', 'axiz-docs', 'axiz-framework', 'axiz-tools']);
assert.equal(config.packages['.github'].component, 'axiz-workflows');
for (const name of ['axiz-framework', 'axiz-tools']) {
  assert.equal(config.packages[name].draft, true);
  assert.equal(config.packages[name]['force-tag-creation'], true, 'a draft must create its build trigger tag');
}
assert.equal(config.packages['.github'].draft, false, 'the workflow release must be available to its consumers');
assert.equal(config.packages['axiz-docs'].draft, false);
const currentState = JSON.parse(fs.readFileSync(path.join(root, '.release-please-manifest.json'), 'utf8'));
for (const [directory, version] of Object.entries(currentState)) {
  assert(Object.hasOwn(config.packages, directory));
  assert.match(version, /^\d+\.\d+\.\d+$/);
}

const releaseSha = 'a'.repeat(40);
const changeSha = 'c'.repeat(40);
const historicalSha = 'd'.repeat(40);

function repository(componentPath, component, released, message, historicalOnly = false) {
  const manifest = released ? Object.fromEntries(components.map(([directory]) => [directory, released])) : {};
  const content = file => {
    if (file === 'release-please-config.json') return JSON.stringify(config);
    if (file === '.release-please-manifest.json') return JSON.stringify(manifest);
    const absolute = path.resolve(root, file);
    assert(absolute.startsWith(root + path.sep));
    return fs.existsSync(absolute) ? fs.readFileSync(absolute, 'utf8') : '';
  };
  return {
    repository: {owner: 'edgarrth', repo: 'java-framework-custom', defaultBranch: 'main'},
    async getFileJson(file) { return JSON.parse(content(file)); },
    async getFileContentsOnBranch(file) {
      const parsedContent = content(file);
      return {parsedContent, content: Buffer.from(parsedContent).toString('base64'), sha: 'b'.repeat(40)};
    },
    async *releaseIterator() {
      if (released) for (const [, entry] of components) {
        const tagName = `${entry.component}-v${released}`;
        yield {name: tagName, tagName, sha: releaseSha, notes: '', url: ''};
      }
    },
    async *tagIterator() {
      if (released) for (const [, entry] of components)
        yield {name: `${entry.component}-v${released}`, sha: releaseSha};
    },
    async *pullRequestIterator() {},
    async *mergeCommitIterator() {
      if (!historicalOnly)
        yield {sha: changeSha, message, files: [`${componentPath}/version.txt`, `${componentPath}/src/main/java/ReleaseFixture.java`]};
      if (released) yield {sha: releaseSha, message: `chore: release ${component}`, files: [`${componentPath}/version.txt`]};
      yield {sha: bootstrap, message: 'chore: exclusive bootstrap boundary', files: [`${componentPath}/version.txt`]};
      yield {sha: historicalSha, message: 'feat!: EXCLUDED_HISTORICAL_CHANGE', files: [`${componentPath}/version.txt`]};
    },
  };
}

function selectedAndForeignVersions(xml, componentPath) {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  const elements = Array.from(document.documentElement.childNodes).filter(node => node.nodeType === 1);
  const selected = componentPath === 'axiz-framework'
    ? Array.from(elements.find(node => node.localName === 'properties').childNodes).find(node => node.localName === 'revision')
    : elements.find(node => node.localName === 'version');
  const other = Array.from(document.getElementsByTagName('*'))
    .filter(node => node.localName === 'version' && node !== selected).map(node => node.textContent);
  return {selected: selected.textContent, foreign: other};
}

(async () => {
  const results = [];
  for (const [componentPath, component] of components) {
    for (const scenario of [
      {name: 'initial-fix', message: 'fix: initial capability', expected: '2.0.0'},
      {name: 'initial-breaking', message: 'feat!: initial public API', expected: '2.0.0'},
      {name: 'patch', message: 'fix: financial validation', released: '2.0.0', expected: '2.0.1'},
      {name: 'minor', message: 'feat: additional capability', released: '2.0.0', expected: '2.1.0'},
    ]) {
      const github = repository(componentPath, component.component, scenario.released, scenario.message);
      const manifest = await Manifest.fromManifest(github, 'main');
      const plans = await manifest.buildPullRequests();
      assert.equal(plans.length, 1, `${componentPath}: one changed component`);
      const updates = plans[0].updates;
      const version = updates.find(update => update.path === `${componentPath}/version.txt`);
      assert(version, `${componentPath}: version.txt updater required`);
      assert.equal(version.updater.updateContent('2.0.0\n').trim(), scenario.expected);
      assert(!updates.some(update => components.some(([directory]) => directory !== componentPath && update.path.startsWith(directory + '/'))),
        `${componentPath}: independent component updates`);
      const poms = updates.filter(update => update.path.endsWith('/pom.xml'));
      assert.equal(poms.length, componentPath === 'axiz-framework' ? 3 : componentPath === 'axiz-tools' ? 4 : 0);
      for (const update of poms) {
        const input = fs.readFileSync(path.join(root, update.path), 'utf8');
        const before = selectedAndForeignVersions(input, componentPath);
        const after = selectedAndForeignVersions(update.updater.updateContent(input), componentPath);
        assert.equal(after.selected, scenario.expected);
        assert.deepEqual(after.foreign, before.foreign, `${update.path}: foreign parent/BOM/dependency versions`);
      }
      const changelog = updates.find(update => update.path === `${componentPath}/CHANGELOG.md`);
      assert(changelog);
      assert(!changelog.updater.updateContent('').includes('EXCLUDED_HISTORICAL_CHANGE'), 'exclusive history boundary');
      if (componentPath === '.github') {
        const consumer = updates.find(update => update.path === '.github/workflows/lib-build.yaml');
        assert(consumer, 'workflow release updates its own internal pins');
        const output = consumer.updater.updateContent(fs.readFileSync(path.join(root, consumer.path), 'utf8'));
        assert(output.includes(`@axiz-workflows-v${scenario.expected}`));
        assert(!output.includes('axiz-framework-v'), 'workflow updates preserve component identities');
      }
      results.push({component: component.component, scenario: scenario.name, version: scenario.expected, pomUpdates: poms.length});
    }
    const historical = await Manifest.fromManifest(repository(componentPath, component.component, undefined, '', true), 'main');
    assert.deepEqual(await historical.buildPullRequests(), [], `${componentPath}: old history alone does not create an initial release`);
  }
  console.log(JSON.stringify({releasePlease: require('release-please/package.json').version, checks: results.length + components.length, results}, null, 2));
})().catch(error => { console.error(error); process.exitCode = 1; });
