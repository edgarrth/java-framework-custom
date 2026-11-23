"use strict";
// Se invoca con el preset efectivo resuelto por el propio Renovate en la regresión de adopción.
module.exports = async function actionCoverage({ config, source, moduleAt, python, temporary }) {
  const assert = require('node:assert/strict');
  const fs = require('node:fs');
  const path = require('node:path');
  const {execFileSync} = require('node:child_process');
  const actions = moduleAt('modules/manager/github-actions/index.js');
  const regex = moduleAt('modules/manager/custom/regex/index.js');
  const {matchRegexOrGlobList} = moduleAt('util/string-match.js');
  const {applyPackageRules} = moduleAt('util/package-rules/index.js');
  const {doAutoReplace} = moduleAt('workers/repository/update/branch/auto-replace.js');
  const inventory = JSON.parse(execFileSync(python,[path.join(source,'.github/scripts/check_action_pins.py'),'--root',source],{encoding:'utf8'}));
  assert(inventory.passed);
  const exceptions = new Map([
    ['.github/actions/setup-maven/action.yaml:java-jdk:unsupported-version',2],
    ['.github/workflows/workflows-ci.yaml:node:unspecified-version',1],
    ['.github/workflows/docs-docs.yaml:node:unspecified-version',1],
    ['.github/workflows/framework-ci.yaml:node:unspecified-version',1],
  ]);
  const skipped = new Map(); let covered=0, replacements=0;
  function enabled(manager, options={}) {
    assert.notEqual(options.enabled,false,`Unexpected disabled manager: ${manager}`);
    if(config.enabledManagers) assert(config.enabledManagers.includes(manager==='regex'?'custom.regex':manager),`Unexpected excluded manager: ${manager}`);
  }
  for (const file of [...new Set(inventory.pins.map(p=>p.path))]) {
    const original=fs.readFileSync(path.join(source,file),'utf8');
    const destination=path.join(temporary,file);
    fs.mkdirSync(path.dirname(destination),{recursive:true});fs.writeFileSync(destination,original);
    const records=[];
    if(matchRegexOrGlobList(file,actions.defaultConfig.managerFilePatterns)) {
      enabled('github-actions',config['github-actions']);
      for(const [depIndex,dep] of ((await actions.extractPackageFile(original,file,config))?.deps||[]).entries()) records.push({dep,depIndex,manager:'github-actions'});
    }
    for(const custom of config.customManagers||[]) {
      if(custom.customType!=='regex'||!matchRegexOrGlobList(file,custom.managerFilePatterns)) continue;
      enabled('regex',custom);
      for(const [depIndex,dep] of (regex.extractPackageFile(original,file,custom)?.deps||[]).entries())records.push({dep,depIndex,manager:'regex',custom});
    }
    for(const {dep} of records) if(dep.skipReason) {
      const key=`${file}:${dep.depName}:${dep.skipReason}`;
      assert(exceptions.has(key),`Unexpected extractor skip: ${key}`);
      skipped.set(key,(skipped.get(key)||0)+1);
    }
    for(const {dep,manager,custom} of records) {
      if(dep.depName==='edgarrth/java-framework-custom'||dep.skipReason)continue;
      const selected=await applyPackageRules({...config[manager],...custom,...dep,manager,packageFile:file,packageName:dep.packageName||dep.depName,packageRules:config.packageRules});
      assert.notEqual(selected.enabled,false,`Unexpected disabled third-party dependency: ${file}:${dep.depName}`);
    }
    const expected=inventory.pins.filter(p=>p.path===file);
    const groups=new Map();
    for(const row of expected){const key=`${row.action.split('/').slice(0,2).join('/')}:${row.sha}:${row.tag}`;groups.set(key,(groups.get(key)||0)+1);}
    for(const [key,count] of groups) {
      const hits=records.filter(({dep})=>`${dep.depName}:${dep.currentDigest}:${dep.currentValue}`===key);
      assert.equal(hits.length,count,`Each third-party pin needs one effective extractor: ${file}:${key}`);
      for(const record of hits){
        const {dep,depIndex,manager,custom}=record;
        const selected=await applyPackageRules({...dep,manager,packageFile:file,packageName:dep.packageName||dep.depName,packageRules:config.packageRules});
        assert.notEqual(selected.enabled,false,`Unexpected disabled third-party pin: ${file}:${key}`);
        if(dep.depName==='googleapis/release-please-action') {
          assert.equal(selected.groupName,'release planner toolchain');
          assert.equal(selected.dependencyDashboardApproval,true);
          assert.equal(selected.automerge,false);
        }
        assert(!dep.skipReason,`Unexpected skipped pin: ${file}:${key}`);
        const update={...(custom||{}),...selected,depIndex,manager,packageFile:file,
          newValue:'v99.99.99',newVersion:'v99.99.99',newDigest:'a'.repeat(40),autoReplaceGlobalMatch:true,
          packageFileConfig:custom};
        const changed=await doAutoReplace(update,original,false);
        assert(changed&&changed!==original,`Native replacement failed: ${file}:${key}`);
        assert(changed.includes('@'+'a'.repeat(40)+' # v99.99.99'),`SHA/comment replacement incomplete: ${file}:${key}`);
        covered++;replacements++;
      }
    }
    const extracted=records.filter(({dep})=>dep.currentDigest&&/^[a-f0-9]{40}$/.test(dep.currentDigest));
    assert.equal(extracted.length,expected.length,`Unexpected or duplicate third-party extraction: ${file}`);
  }
  assert.deepEqual([...skipped.entries()].sort(),[...exceptions.entries()].sort(),'Exact reviewed input exceptions, with no missing or broadened skips');
  for(const file of ['.github/scripts/release-version/package.json','axiz-framework/.github/scripts/release-version/package.json']) {
    const manifest=JSON.parse(fs.readFileSync(path.join(source,file),'utf8'));
    assert(manifest.private && manifest.devDependencies['release-please']);
    const selected=await applyPackageRules({depName:'release-please',packageName:'release-please',manager:'npm',packageFile:file,packageRules:config.packageRules});
    assert.equal(selected.groupName,'release planner toolchain');
    assert.equal(selected.dependencyDashboardApproval,true);assert.equal(selected.automerge,false);
  }
  const nodeFile='.github/scripts/release-version/.node-version';
  const nodenv=moduleAt('modules/manager/nodenv/index.js');
  enabled('nodenv',config.nodenv);
  assert(matchRegexOrGlobList(nodeFile,[...nodenv.defaultConfig.managerFilePatterns,...(config.nodenv?.managerFilePatterns||[])]));
  const nodeContent=fs.readFileSync(path.join(source,nodeFile),'utf8');
  const nodeDeps=(await nodenv.extractPackageFile(nodeContent,nodeFile,config.nodenv||{}))?.deps||[];
  assert.equal(nodeDeps.length,1,'The Node version file needs exactly one native extractor');
  const nodeDep=nodeDeps[0];assert(!nodeDep.skipReason);assert.equal(nodeDep.currentValue,nodeContent.trim());
  const nodeSelected=await applyPackageRules({...config.nodenv,...nodeDep,manager:'nodenv',packageFile:nodeFile,packageName:nodeDep.packageName||nodeDep.depName,packageRules:config.packageRules});
  assert.notEqual(nodeSelected.enabled,false,'The Node version file must remain maintained');
  const nodeDestination=path.join(temporary,nodeFile);fs.mkdirSync(path.dirname(nodeDestination),{recursive:true});fs.writeFileSync(nodeDestination,nodeContent);
  const nextNode=nodeContent.trim().replace(/(\d+)$/,value=>String(Number(value)+1));
  const changedNode=await doAutoReplace({...nodeSelected,manager:'nodenv',packageFile:nodeFile,depIndex:0,newValue:nextNode,newVersion:nextNode},nodeContent,false);
  assert.equal(changedNode.trim(),nextNode,'The native nodenv replacement must update the pinned runtime');
  assert.equal(covered,inventory.pins.length);
  const actionSelection=await require('./action-update-selection.cjs').regression({config,source,moduleAt});
  const documentationUpdates=await require('./generated-docs-updates.cjs')({config,source,moduleAt,python});
  return {passed:true,actionSelection,documentationUpdates,coveredPins:covered,replacements,nodeVersionFileCovered:true,inputExceptions:Object.fromEntries(skipped)};
};
