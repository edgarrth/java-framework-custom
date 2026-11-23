// Versión de release de un proyecto generado, calculada por el propio release-please (la biblioteca que usa
// release-please-action fijado) sobre proyectos generados reales: sin llamadas remotas.
//
//   npm ci --prefix .github/scripts/release-version
//   node .github/scripts/release-version/test.cjs target/samples/modules.json
//
// La configuración bajo prueba es la plantilla que recibe cada proyecto generado.
'use strict';
const path = require('path');
const fs = require('fs');
const {Manifest} = require('release-please');
const assert = require('node:assert/strict');
const {DOMParser} = require('@xmldom/xmldom');

if (process.argv.length !== 3) throw new Error('Pass modules.json from actual generated projects');
const projects = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
assert(Array.isArray(projects) && projects.length > 0, 'Generated projects are required');
let projectRoot, config, initial;
const source = file => {
  const target = path.resolve(projectRoot, file);
  assert(target.startsWith(projectRoot + path.sep), 'Fixture paths must remain in the project');
  assert(fs.existsSync(target), `Generated fixture is missing ${file}`);
  return fs.readFileSync(target, 'utf8');
};
function pomVersions(xml) {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  const nodes = Array.from(document.getElementsByTagName('*')).filter(n => n.localName === 'version');
  return {
    own: nodes.filter(n => n.parentNode === document.documentElement).map(n => n.textContent),
    foreign: nodes.filter(n => n.parentNode !== document.documentElement).map(n => n.textContent),
  };
}

const RELEASE_SHA = 'r'.repeat(40);

/** Repositorio cuyo historial es `commits` (el más reciente primero) sobre una release opcional. */
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
  return prs[0];
}

const INITIAL = 'feat: generate orders-business with axiz initializr';
const CASES = [
  // sin ninguna release: la primera es la versión a la que apunta el pom generado, sean cuales sean los commits
  {name: 'first release, initial commit', commits: [INITIAL], expected: '0.1.0'},
  {name: 'first release, a fix only', commits: ['fix: rounding of fees'], expected: '0.1.0'},
  {name: 'first release, a breaking change', commits: ['feat!: new contract', INITIAL], expected: '0.1.0'},
  // tras una release deciden los Conventional Commits (sin bump-minor-pre-major: un cambio incompatible pasa a 1.0.0)
  {name: 'after 0.1.0, a fix', released: '0.1.0', commits: ['fix: rounding of fees'], expected: '0.1.1'},
  {name: 'after 0.1.0, a feature', released: '0.1.0', commits: ['feat: list by date'], expected: '0.2.0'},
  {name: 'after 0.1.0, a breaking change', released: '0.1.0', commits: ['feat!: new contract'], expected: '1.0.0'},
];

(async () => {
  let failed = 0;
  for (const project of projects) {
    projectRoot = path.resolve(project);
    config = JSON.parse(source('release-please-config.json'));
    initial = JSON.parse(source('.release-please-manifest.json'))['.'];
    const poms = config['extra-files'].filter(file => typeof file === 'object' && file.type === 'xml')
      .map(file => file.path);
    assert(poms.length > 0, 'Generated projects must update actual Maven coordinates');
  for (const c of CASES) {
    const pr = await next(c.commits, c.released);
    const actual = pr && pr.version ? pr.version.toString() : null;
    const ok = actual === c.expected;
    failed += ok ? 0 : 1;
    for (const pom of poms) {
      const update = pr.updates.find(update => update.path === pom);
      assert(update, `Release plan omitted ${pom}`);
      const original = source(pom);
      const updated = update.updater.updateContent(original);
      assert.equal(pomVersions(updated).own[0], c.expected, `${project}/${pom}: wrong Maven coordinate`);
      assert.deepEqual(pomVersions(updated).foreign, pomVersions(original).foreign,
        `${project}/${pom}: SDK parents and dependency versions must remain independent`);
    }
    console.log(`${ok ? 'PASS' : 'FAIL'} ${path.basename(projectRoot)}: ${c.name}: expected ${c.expected}, got ${actual}`);
  }
  }
  console.log(`Validated ${projects.length * CASES.length} actual release plans and their Maven POM updates`);
  process.exitCode = failed === 0 ? 0 : 1;
})().catch((e) => {
  console.error(e);
  process.exitCode = 2;
});
