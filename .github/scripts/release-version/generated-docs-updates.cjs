"use strict";
// Real manager extraction/replacement: registry source + generated YAML, and one Node version file.
module.exports=async function documentationUpdates({config,source,moduleAt,python}) {
  const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
  const {execFileSync,spawnSync}=require('node:child_process');
  const {matchRegexOrGlobList}=moduleAt('util/string-match.js');
  const {applyPackageRules}=moduleAt('util/package-rules/index.js');
  const {doAutoReplace}=moduleAt('workers/repository/update/branch/auto-replace.js');
  const {lookup}=require('./action-update-selection.cjs');
  const regex=moduleAt('modules/manager/custom/regex/index.js'),actions=moduleAt('modules/manager/github-actions/index.js');
  const registryFile='.github/policies/action-pins-v2.json',workflowFile='.github/workflows/docs-docs.yaml';
  const originalRegistry=fs.readFileSync(path.join(source,registryFile),'utf8'),registry=JSON.parse(originalRegistry);
  const originalWorkflow=fs.readFileSync(path.join(source,workflowFile),'utf8');
  const managers=config.customManagers.filter(m=>m.customType==='regex'&&matchRegexOrGlobList(registryFile,m.managerFilePatterns));
  assert.equal(managers.length,1,'The registry source must have exactly one effective manager');
  const manager=managers[0];assert.notEqual(manager.enabled,false);
  const deps=regex.extractPackageFile(originalRegistry,registryFile,manager)?.deps||[];
  assert.equal(deps.length,registry.pins.length,'Every registry source must be maintained');
  const native=(await actions.extractPackageFile(originalWorkflow,workflowFile,config))?.deps||[];
  for(const dep of deps) {
    assert(!dep.skipReason);
    const expected=registry.pins.filter(p=>p.repository===dep.depName&&p.sha===dep.currentDigest&&p.tag===dep.currentValue);
    assert.equal(expected.length,1,'Each registry record needs one complete SHA/version extraction');
    const selected=await applyPackageRules({...manager,...dep,manager:'regex',packageFile:registryFile,packageName:dep.depName,packageRules:config.packageRules});
    assert.notEqual(selected.enabled,false);assert.equal(selected.dependencyDashboardApproval,true);assert.equal(selected.automerge,false);
    const group=dep.depName==='googleapis/release-please-action'?'release planner toolchain':'github actions';
    assert.equal(selected.groupName,group);
  }
  const temp=fs.mkdtempSync(path.join(os.tmpdir(),'axiz-docs-update-'));
  try {
    const resources=[registryFile,workflowFile,'.github/scripts/render_docs_controls.py','.github/scripts/docs_publish_control.py','.github/policies/required-checks-v2.json','.github/scripts/release-version/.node-version'];
    for(const file of resources){const target=path.join(temp,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(source,file),target);}
    const targetWorkflow=path.join(temp,workflowFile),targetRegistry=path.join(temp,registryFile),script=path.join(temp,'.github/scripts/render_docs_controls.py');
    const check=()=>spawnSync(python,[script,'--check'],{encoding:'utf8'});
    assert.equal(check().status,0);
    let exercised=0;
    for(const [depIndex,dep] of deps.entries()) {
      const nativeIndex=native.findIndex(row=>row.depName===dep.depName&&row.currentDigest===dep.currentDigest);
      if(nativeIndex<0)continue;
      const nativeDep=native[nativeIndex];
      const selected=await applyPackageRules({...nativeDep,manager:'github-actions',packageFile:workflowFile,packageName:nativeDep.depName,packageRules:config.packageRules});
      assert.equal(selected.groupName,'github actions','Source and output must update in one PR group');
      const major=Number(/^v(\d+)\./.exec(dep.currentValue)[1])+1;
      const next=`v${major}.0.0`;
      const lookedUp=await lookup({config,moduleAt},[
        {dep,manager:'regex',packageFile:registryFile,custom:manager},
        {dep:nativeDep,manager:'github-actions',packageFile:workflowFile},
      ],[dep.currentValue,next,`v${major}`,`v${major}.0`]);
      for(const result of lookedUp)assert.deepEqual(result.updates.map(update=>update.newValue),[next]);
      const sourceCandidate=lookedUp[0].updates[0],workflowCandidate=lookedUp[1].updates[0];
      const sourceUpdate={...manager,...dep,...sourceCandidate,manager:'regex',packageFile:registryFile,packageFileConfig:manager,depIndex,autoReplaceGlobalMatch:true};
      const workflowUpdate={...nativeDep,...workflowCandidate,manager:'github-actions',packageFile:workflowFile,depIndex:nativeIndex,autoReplaceGlobalMatch:true};
      const updatedRegistry=await doAutoReplace(sourceUpdate,originalRegistry,false);
      let updatedWorkflow=originalWorkflow;
      for(const [index,entry] of native.entries()) {
        if(entry.depName!==dep.depName||entry.currentDigest!==dep.currentDigest)continue;
        const nextContent=await doAutoReplace({...workflowUpdate,...entry,...workflowCandidate,depIndex:index},updatedWorkflow,false);
        assert(nextContent,`Native replacement failed for occurrence ${index} of ${dep.depName}`);
        updatedWorkflow=nextContent;
      }
      assert(updatedRegistry&&updatedRegistry!==originalRegistry);assert(updatedWorkflow&&updatedWorkflow!==originalWorkflow);
      fs.writeFileSync(targetRegistry,originalRegistry);fs.writeFileSync(targetWorkflow,updatedWorkflow);
      assert.notEqual(check().status,0,'An output-only update must be rejected');
      fs.writeFileSync(targetRegistry,updatedRegistry);
      const coordinated=check();
      if(coordinated.status!==0) {
        execFileSync(python,[script]);
        const rendered=fs.readFileSync(targetWorkflow,'utf8').split('\n'),expected=updatedWorkflow.split('\n');
        const line=rendered.findIndex((value,index)=>value!==expected[index]);
        throw new Error(`Coordinated update differs for ${dep.depName} at line ${line+1}: expected ${expected[line]}, rendered ${rendered[line]}`);
      }
      execFileSync(python,[script]);assert.equal(fs.readFileSync(targetWorkflow,'utf8'),updatedWorkflow,'Regeneration must preserve the new version');
      exercised++;
    }
    assert(exercised>0);fs.writeFileSync(targetRegistry,originalRegistry);fs.writeFileSync(targetWorkflow,originalWorkflow);
    const nodeFile='.github/scripts/release-version/.node-version',nodenv=moduleAt('modules/manager/nodenv/index.js');
    const currentNode=fs.readFileSync(path.join(source,nodeFile),'utf8'),nodeDep=(await nodenv.extractPackageFile(currentNode))?.deps[0];
    const nextNode=nodeDep.currentValue.replace(/(\d+)$/,value=>String(Number(value)+1));
    const updatedNode=await doAutoReplace({...nodeDep,manager:'nodenv',packageFile:nodeFile,depIndex:0,newValue:nextNode,newVersion:nextNode},currentNode,false);
    fs.writeFileSync(path.join(temp,nodeFile),updatedNode);
    assert.equal(check().status,0,'Updating the single Node version file must not require changing generated YAML');
    assert.equal(fs.readFileSync(targetWorkflow,'utf8'),originalWorkflow);
    return {passed:true,registryRecords:deps.length,coordinatedActionUpdateCases:exercised,nodeHasOneSource:true,outputOnlyUpdateRejected:true};
  } finally {fs.rmSync(temp,{recursive:true,force:true});}
};
