"""Valida la cobertura completa y revisada de los veredictos de pull request."""
import json
from pathlib import Path

from workflow_sources import read_document

POLICY = Path('.github/policies/required-checks-v2.json')
PR_IF = "${{ always() && github.event_name == 'pull_request' }}"


def events(document):
    value = document.get('on', document.get(True, {})) or {}
    return {value: None} if isinstance(value, str) else dict.fromkeys(value) if isinstance(value, list) else value


def token_permissions(document, exceptions=()):
    errors = []
    for scope, value in [('workflow', document.get('permissions', {})),
                         *[(key, job.get('permissions', {})) for key, job in document.get('jobs', {}).items()]]:
        if value == 'write-all':
            errors.append(scope + ': write-all permissions are forbidden')
        elif isinstance(value, dict):
            for permission in ('checks', 'statuses'):
                if value.get(permission) == 'write' and (scope, permission) not in exceptions:
                    errors.append(scope + ': ' + permission + ': write can impersonate a required verdict')
    return errors


def check(root):
    root = Path(root)
    policy = json.loads((root / POLICY).read_text())
    documents = {p.name: read_document(p) for p in (root / '.github/workflows').glob('*') if p.suffix in {'.yaml', '.yml'}}
    selected = {name: doc for name, doc in documents.items() if 'pull_request' in events(doc)}
    errors = []
    if set(selected) != set(policy['workflows']):
        errors.append('Every pull_request workflow must have exactly one reviewed verdict')
    names = [v['verdict']['name'] for v in policy['workflows'].values()]
    if len(names) != len(set(names)):
        errors.append('Required verdict names must be unique')
    used = set()
    for file, document in documents.items():
        allowed = set()
        for number, entry in enumerate(policy['permissionExceptions']):
            if entry['path'] == '.github/workflows/' + file:
                scope = entry['scope']
                value = document.get('permissions', {}) if scope == 'workflow' else document.get('jobs', {}).get(scope, {}).get('permissions', {})
                if entry.get('reason') and isinstance(value, dict) and value.get(entry['permission']) == 'write':
                    allowed.add((scope, entry['permission'])); used.add(number)
        errors.extend(file + ': ' + error for error in token_permissions(document, allowed))
        for key, job in document.get('jobs', {}).items():
            if job.get('name') in names and (file not in policy['workflows'] or key != policy['workflows'][file]['verdict']['job']):
                errors.append(file + ': another job uses a required verdict name')
    if len(used) != len(policy['permissionExceptions']):
        errors.append('Unused permission exception')
    tolerated_used = set()
    for file, entry in policy['workflows'].items():
        if file not in selected:continue
        document = selected[file]; jobs = document['jobs']; verdict = entry['verdict']; declarations = entry['jobs']
        trigger = events(document)['pull_request'] or {}
        if not isinstance(trigger, dict) or set(trigger) - {'types'}:
            errors.append(file + ': required pull_request workflows cannot filter paths or branches')
        elif 'types' in trigger and not {'opened', 'reopened', 'synchronize'} <= set(trigger['types']):
            errors.append(file + ': pull_request types omit a required event')
        gate = jobs.get(verdict['job'])
        if gate is None or gate.get('name') != verdict['name'] or 'strategy' in gate:
            errors.append(file + ': missing, unstable or matrix verdict'); continue
        if set(declarations) != set(jobs) - {verdict['job']}:
            errors.append(file + ': every non-verdict job must be classified exactly once')
        for key, job in jobs.items():
            classification = declarations.get(key)
            if classification:
                if classification['class'] not in {'required', 'skipped-in-pr', 'non-blocking'}:
                    errors.append(file + ': unknown job classification')
                if job.get('if') != classification.get('if'):
                    errors.append(file + ': unreviewed condition for ' + key)
                if classification['class'] != 'required' and not classification.get('reason'):
                    errors.append(file + ': exception needs an explicit reason')
            if key == verdict['job'] or classification and classification['class'] != 'non-blocking':
                if job.get('continue-on-error', False) is not False:
                    errors.append(file + ': required job tolerates errors: ' + key)
                for index, step in enumerate(job.get('steps', [])):
                    if step.get('continue-on-error', False) is not False:
                        match = {'path': '.github/workflows/' + file, 'job': key, 'step': index, 'value': step['continue-on-error']}
                        found = False
                        for number, exception in enumerate(policy['toleratedSteps']):
                            if exception.get('reason') and all(exception.get(k) == v for k, v in match.items()):
                                tolerated_used.add(number); found = True
                        if not found:errors.append(file + ': required step tolerates errors without review')
        if verdict['mode'] == 'single':
            if len(jobs) != 1 or gate.get('needs') or gate.get('if'):
                errors.append(file + ': single-job verdict no longer covers every job')
            continue
        required = {key for key, value in declarations.items() if value['class'] != 'non-blocking'}
        skippable = sorted(key for key, value in declarations.items() if value['class'] == 'skipped-in-pr')
        needs = gate.get('needs', [])
        if not isinstance(needs, list) or set(needs) != required or len(needs) != len(required):
            errors.append(file + ': verdict needs must cover all required and skippable jobs')
        if gate.get('if') != PR_IF or gate.get('permissions') != {} or gate.get('runs-on') != 'ubuntu-24.04' or gate.get('timeout-minutes') != 2:
            errors.append(file + ': verdict must run always with no privileges')
        steps = gate.get('steps', [])
        if (len(steps) != 1 or steps[0].get('shell') != 'bash' or steps[0].get('run') != policy['verdictScript']
                or steps[0].get('env') != {'NEEDS': '${{ toJSON(needs) }}', 'SKIPPABLE': json.dumps(skippable, separators=(',', ':'))}):
            errors.append(file + ': verdict implementation differs from its canonical policy')
    if len(tolerated_used) != len(policy['toleratedSteps']):
        errors.append('Unused tolerated-step exception')
    table_text = (root / 'RELEASING.md').read_text().partition('## Checks requeridos\n')[2].split('\n## ', 1)[0]
    table = [line.split('`')[1] for line in table_text.splitlines() if line.startswith('| `axiz-')]
    if sorted(table) != sorted(names):errors.append('RELEASING.md must list exactly the required verdicts')
    return errors
