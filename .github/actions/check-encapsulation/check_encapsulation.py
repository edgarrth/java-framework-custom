"""Falla ante hallazgos de encapsulación de cualquier prioridad reportada y ante exclusiones amplias de detectores."""
from pathlib import Path
import sys
import os
from defusedxml import ElementTree as ET

PATTERNS = {'EI_EXPOSE_REP', 'EI_EXPOSE_REP2', 'CT_CONSTRUCTOR_THROW'}

# Metadatos opcionales con retención de clase procedentes de bytecode de terceros. No contienen ningún
# contrato ejecutable y no se requieren para el análisis de flujo de control y de datos. Sin excepción por paquete.
OPTIONAL_ANNOTATIONS = {
    'lombok.Generated',
    'org.checkerframework.checker.initialization.qual.Initialized',
    'org.checkerframework.checker.nullness.qual.NonNull',
    'org.checkerframework.checker.nullness.qual.Nullable',
    'org.checkerframework.checker.nullness.qual.UnknownKeyFor',
}


def production_modules(root):
    result = {}
    for directory, children, _ in os.walk(root):
        children[:] = [name for name in children if not name.startswith(('.', '_'))
                       and name not in {'target', 'src', 'node_modules', 'dist', 'build'}]
        module = Path(directory)
        source = module / 'src/main/java'
        if (module / 'pom.xml').is_file() and source.is_dir():
            java = [p for p in source.rglob('*.java') if p.name not in {'module-info.java', 'package-info.java'}]
            if java:
                result[module] = java
    return result


def analysis_errors(document):
    errors = []
    status = document.find('Errors')
    summary = document.find('FindBugsSummary')
    if document.tag != 'BugCollection' or status is None or summary is None:
        return ['Incomplete SpotBugs report: analysis status and summary are required']
    if status.get('errors') != '0' or status.findall('Error'):
        errors.append('SpotBugs/FindSecBugs reported an internal analysis failure')
    missing = status.findall('MissingClass')
    if status.get('missingClasses') != str(len(missing)):
        errors.append('SpotBugs missing-class inventory is inconsistent')
    for item in missing:
        if item.text not in OPTIONAL_ANNOTATIONS:
            errors.append(f'Unresolved analysis class: {item.text}')
    return errors


def filter_errors(path):
        # Configuración de build de confianza incluida en el repositorio, nunca XML del servicio o de la petición.
    # POM o reporte de build de confianza, tras ejecutar el mismo checkout; no se analiza XML de la petición.
    # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
    root = ET.parse(path).getroot()
    for node in root.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    errors = []
    for match in root.findall('Match'):
                # Solo está exento el contrato de colecciones mutables de los DTO generados marcados de forma exacta.
        children = list(match)
        annotation = match.find('Annotation')
        generated_bug = match.find('Bug')
        if len(children) == 2 and annotation is not None and generated_bug is not None:
            patterns = set(generated_bug.get('pattern', '').split(','))
            if (annotation.attrib == {'name': 'pe.axiz.core.commons.Generated'}
                    and set(generated_bug.attrib) == {'pattern'}
                    and patterns and patterns <= {'EI_EXPOSE_REP', 'EI_EXPOSE_REP2'}):
                continue
        bug = match.find('Bug')
        if bug is None or set(bug.attrib) != {'pattern'} or len(children) != 3:
            errors.append('exclusions must name bug patterns, an exact class and a member')
            continue
        cls = match.find('Class')
        member = match.find('Method')
        if member is None:
            member = match.find('Field')
        if (cls is None or member is None or not cls.get('name') or not member.get('name')
                or cls.get('name', '').startswith('~') or member.get('name', '').startswith('~')
                or any(n.tag not in {'Match', 'Class', 'Bug', 'Field', 'Method'} for n in match.iter())):
            errors.append('encapsulation exclusions require an exact class and member')
        if member is not None and member.tag == 'Method' and (
                'params' not in member.attrib or 'returns' not in member.attrib):
            errors.append('method exclusions require an exact signature, including parameters and return type')
        if 'CT_CONSTRUCTOR_THROW' in bug.get('pattern', '') and (
                member is None or member.tag != 'Method' or member.get('name') != '<init>'
                or 'params' not in member.attrib or member.get('returns') != 'void'):
            errors.append('constructor exclusions require an exact constructor signature')
    return errors


def declared_filters(root):
    """Resuelve cada exclusión declarada; ninguna ruta arbitraria ni propiedad de Maven se ignora en silencio."""
    paths, errors = set(), []
    for pom in root.rglob('pom.xml'):
        if any(part in {'target', 'node_modules'} or part.startswith(('.', '_'))
               for part in pom.relative_to(root).parts):
            continue
        try:
            document = ET.parse(pom).getroot()
            for node in document.iter():
                node.tag = node.tag.rsplit('}', 1)[-1]
            for node in document.iter():
                if node.tag in {'omitVisitors', 'onlyAnalyze', 'visitors', 'includeFilterFile', 'excludeBugsFile'} and (node.text or '').strip():
                    errors.append(f'{pom}: partial detector/class selection is not permitted: {node.tag}')
                if node.tag in {'threshold', 'failThreshold'} and (node.text or '').strip().lower() not in {'low', 'medium', 'default', 'experimental'}:
                    errors.append(f'{pom}: analysis threshold can hide medium-priority findings')
                if node.tag in {'spotbugs.skip', 'spotbugs.skipMain'} and (node.text or '').strip().lower() != 'false':
                    errors.append(f'{pom}: production SpotBugs analysis cannot be skipped')
            for node in document.iter('excludeFilterFile'):
                value = (node.text or '').strip()
                value = value.replace('${project.basedir}', str(pom.parent.resolve()))
                if not value or '${' in value:
                    errors.append(f'{pom}: unresolved exclusion filter: {value}')
                    continue
                file = Path(value)
                if not file.is_absolute():
                    file = pom.parent / file
                if not file.is_file():
                                        # El parent usa un recurso del classpath incluido en axiz-build-rules.
                    candidates = list(root.glob('**/src/main/resources/' + value)) if not Path(value).is_absolute() else []
                    candidates = [p for p in candidates if 'target' not in p.parts]
                    if len(candidates) != 1:
                        errors.append(f'{pom}: exclusion filter is not a unique reviewed file: {value}')
                        continue
                    file = candidates[0]
                if not file.resolve().is_relative_to(root.resolve()):
                    errors.append(f'{pom}: exclusion filter must stay inside the reviewed root')
                    continue
                paths.add(file.resolve())
        except (ET.ParseError, OSError, ValueError) as failure:
            errors.append(f'{pom}: invalid exclusion configuration ({type(failure).__name__})')
    return paths, errors


def check(root):
    root = Path(root)
    failures = []
    modules = production_modules(root)
    files = sorted(root.glob('**/target/spotbugsXml.xml'))
    files = [p for p in files if not any(part.startswith(('.', '_')) for part in p.relative_to(root).parts)]
    if not files:
        failures.append('No SpotBugs reports found: run Maven with -Pquality first')
    for module in modules:
        if module / 'target/spotbugsXml.xml' not in files:
            failures.append(f'{module}: production Java has no SpotBugs report')
    for path in files:
        try:
            document = ET.parse(path).getroot()
        except (ET.ParseError, OSError, ValueError) as failure:
            failures.append(f'{path}: invalid analysis XML ({type(failure).__name__})')
            continue
        failures.extend(f'{path}: {error}' for error in analysis_errors(document))
        module = path.parent.parent
        if module in modules:
            compiled = {p.relative_to(module / 'target/classes').as_posix()[:-6].replace('/', '.')
                        for p in (module / 'target/classes').rglob('*.class')
                        if p.name not in {'module-info.class', 'package-info.class'}}
            analyzed = {node.get('class') for node in document.findall('.//ClassStats')}
            if not compiled or not compiled <= analyzed:
                failures.append(f'{path}: compiled production classes missing from analysis: {sorted(compiled - analyzed)}')
            if any(source.stat().st_mtime_ns > path.stat().st_mtime_ns for source in modules[module]):
                failures.append(f'{path}: report predates production source changes')
        for bug in document.findall('BugInstance'):
            if bug.get('type') in PATTERNS:
                cls = bug.find('Class')
                failures.append(f"{path}: {bug.get('type')} in {cls.get('classname') if cls is not None else 'unknown class'}")
    filters = list(root.glob('**/src/main/resources/axiz/spotbugs/*.xml'))
    filters += [p for p in root.glob('**/spotbugs-exclude.xml') if 'target' not in p.parts]
    declared, invalid = declared_filters(root)
    failures.extend(invalid)
    filters = {p.resolve() for p in filters} | declared
    for path in filters:
        failures.extend(f'{path}: {error}' for error in filter_errors(path))
    for failure in failures:
        print(f'::error::{failure}')
    print(f'Encapsulation: {len(files)} SpotBugs reports, {len(failures)} errors')
    return int(bool(failures))


if __name__ == '__main__':
    sys.exit(check(sys.argv[1]))
