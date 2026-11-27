// Ejercita el ruleset distribuido con la versión exacta de la CLI que usa service-ci.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'axiz-contracts-'));
const rules = path.join(root, 'quality/axiz-code-standards/src/main/resources/axiz/spectral/axiz-openapi.yaml');
function contract(names, nullable) {
  return {openapi: '3.1.0', info: {title: 'Regression', version: '1.0.0'}, security: [],
    paths: {'/v1/items': {get: {operationId: 'listItems',
      parameters: names.map(name => ({name, in: 'query', schema: {type: 'string'}})),
      responses: {'200': {description: 'Page', content: {'application/json': {
        schema: {type: 'object', properties: {nextCursor: {type: nullable ? ['string', 'null'] : 'string'}}},
        example: {nextCursor: null}}}}}}}}};
}
try {
  const undeclared = contract(['page'], true); delete undeclared.security;
  const money = contract(['page'], true);
  money.components = {schemas: {Payment: {type: 'object', additionalProperties: false,
    properties: {amount: {type: 'number'}}}}};
  const inputs = {'valid.json': contract(['page', 'size', 'status'], true),
    'undeclared-access.json': undeclared, 'inexact-amount.json': money,
    'sensitive.json': contract(['card', 'PAN', 'DocumentNumber', 'PASSWORD', 'otp'], true),
    'invalid-response.json': contract([], false)};
  const files = Object.entries(inputs).map(([name, value]) => {
    const file = path.join(temp, name); fs.writeFileSync(file, JSON.stringify(value)); return file;
  });
  const result = spawnSync('npx', ['--yes', '@stoplight/spectral-cli@6.15.0', 'lint', '-r', rules,
    ...files, '--format', 'json', '--fail-severity', 'error'], {encoding: 'utf8'});
  assert.equal(result.status, 1, result.stderr || result.stdout);
  const errors = JSON.parse(result.stdout).filter(d => d.severity === 0);
  assert.equal(errors.filter(d => d.source.endsWith('/valid.json')).length, 0);
  const sensitive = errors.filter(d => d.source.endsWith('/sensitive.json'));
  assert.equal(sensitive.length, 5);
  assert.ok(sensitive.every(d => d.code === 'axiz-no-business-ids-in-query'));
  const response = errors.filter(d => d.source.endsWith('/invalid-response.json'));
  assert.equal(response.length, 1);
  assert.equal(response[0].code, 'oas3-valid-media-example');
  const access = errors.filter(d => d.source.endsWith('/undeclared-access.json'));
  assert.ok(access.length > 0 && access.every(d => d.code === 'axiz-explicit-http-access'));
  const amounts = errors.filter(d => d.source.endsWith('/inexact-amount.json'));
  assert.ok(amounts.length > 0 && amounts.every(d => d.code === 'axiz-exact-amounts'));
  console.log('PASS: ordinary queries, five sensitive names, nullable and invalid response examples,'
    + ' undeclared access and inexact amounts');
} finally {
  fs.rmSync(temp, {recursive: true, force: true});
}
