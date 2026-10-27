// Release version of a generated project, computed by release-please itself (the library that the
// pinned release-please-action uses) against a local fixture: no GitHub call, no pull request.
//
//   npm ci --prefix .github/scripts/release-version
//   node .github/scripts/release-version/test.cjs
//
// The configuration under test is the template every generated project gets.
'use strict';
const path = require('path');
const fs = require('fs');
const {Manifest} = require('release-please');

const TEMPLATES = path.resolve(
  __dirname,
  '../../../codegen/axiz-codegen-templates/src/main/resources/axiz/templates/shared/release');
const config = JSON.parse(fs.readFileSync(path.join(TEMPLATES, 'release-please-config.json'), 'utf8'));
const initial = JSON.parse(
  fs.readFileSync(path.join(TEMPLATES, 'release-please-manifest.json'), 'utf8'))['.'];

const RELEASE_SHA = 'r'.repeat(40);

/** A repository whose history is `commits` (newest first) on top of an optional release. */
function repository(commits, released) {
  const manifest = {'.': released || initial};
  const text = (file) =>
    file === 'release-please-config.json' ? JSON.stringify(config)
      : file === '.release-please-manifest.json' ? JSON.stringify(manifest)
      : file === 'version.txt' ? `${manifest['.']}\n` : '';
  const history = commits.map((message, i) => ({
    sha: String(i).padStart(40, 'a'), message, files: ['pom.xml', 'src/main/java/App.java'],
  }));
  if (released) {
    history.push({sha: RELEASE_SHA, message: `chore(main): release ${released}`, files: ['version.txt']});
  }
  return {
    repository: {owner: 'synthetic', repo: 'service', defaultBranch: 'main'},
    async getFileJson(file) { return JSON.parse(text(file)); },
    async getFileContentsOnBranch(file) {
      const t = text(file);
      return {parsedContent: t, content: Buffer.from(t).toString('base64'), sha: 'b'.repeat(40)};
    },
    async *releaseIterator() {
      if (released) {
        yield {name: `v${released}`, tagName: `v${released}`, sha: RELEASE_SHA, notes: '', url: ''};
      }
    },
    async *tagIterator() {
      if (released) {
        yield {name: `v${released}`, sha: RELEASE_SHA};
      }
    },
    async *pullRequestIterator() {},
    async *mergeCommitIterator() { yield* history; },
  };
}

async function next(commits, released) {
  const manifest = await Manifest.fromManifest(repository(commits, released), 'main');
  const prs = await manifest.buildPullRequests();
  return prs[0] && prs[0].version ? prs[0].version.toString() : null;
}

const INITIAL = 'feat: generate orders-business with axiz initializr';
const CASES = [
  // no release yet: the first one is the version the generated pom targets, whatever the commits
  {name: 'first release, initial commit', commits: [INITIAL], expected: '0.1.0'},
  {name: 'first release, a fix only', commits: ['fix: rounding of fees'], expected: '0.1.0'},
  {name: 'first release, a breaking change', commits: ['feat!: new contract', INITIAL], expected: '0.1.0'},
  // after a release, Conventional Commits decide (no bump-minor-pre-major: a break goes to 1.0.0)
  {name: 'after 0.1.0, a fix', released: '0.1.0', commits: ['fix: rounding of fees'], expected: '0.1.1'},
  {name: 'after 0.1.0, a feature', released: '0.1.0', commits: ['feat: list by date'], expected: '0.2.0'},
  {name: 'after 0.1.0, a breaking change', released: '0.1.0', commits: ['feat!: new contract'], expected: '1.0.0'},
];

(async () => {
  let failed = 0;
  for (const c of CASES) {
    const actual = await next(c.commits, c.released);
    const ok = actual === c.expected;
    failed += ok ? 0 : 1;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${c.name}: expected ${c.expected}, got ${actual}`);
  }
  process.exitCode = failed === 0 ? 0 : 1;
})().catch((e) => {
  console.error(e);
  process.exitCode = 2;
});
