#!/usr/bin/env python3
"""spring.factories contiene solo lo que Spring Boot no puede cargar desde AutoConfiguration.imports.

Las autoconfiguraciones se declaran en META-INF/spring/...AutoConfiguration.imports; spring.factories
se reserva para las extensiones SPI de Boot (environment, config data, filtros de importación y ciclo de vida). Esta verificación
falla si un spring.factories declara otra clave (Boot 4 ignoraría una autoconfiguración declarada ahí)
o nombra una clase que no existe en el módulo o que no implementa la interfaz.

Uso: check-spring-factories.py <repo-root>
"""
import os
import re
import sys

ALLOWED = {
    'org.springframework.boot.EnvironmentPostProcessor': 'EnvironmentPostProcessor',
    'org.springframework.boot.autoconfigure.AutoConfigurationImportFilter': 'AutoConfigurationImportFilter',
    'org.springframework.boot.context.config.ConfigDataLocationResolver': 'ConfigDataLocationResolver',
    'org.springframework.boot.context.config.ConfigDataLoader': 'ConfigDataLoader',
    'org.springframework.boot.SpringApplicationRunListener': 'SpringApplicationRunListener',
}


def entries(path):
    text = open(path, encoding='utf-8').read().replace('\\\n', '')
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, _, values = line.partition('=')
        yield key.strip(), [v.strip() for v in values.split(',') if v.strip()]


def main(root):
    errors = []
    count = 0
    for dirpath, dirnames, files in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('target', '.git', 'node_modules')]
        if 'spring.factories' not in files or not dirpath.endswith('src/main/resources/META-INF'):
            continue
        path = os.path.join(dirpath, 'spring.factories')
        java = os.path.join(os.path.dirname(os.path.dirname(dirpath)), 'java')
        for key, classes in entries(path):
            count += 1
            if key not in ALLOWED:
                errors.append(f'{path}: key {key} is not allowed (auto-configurations go to AutoConfiguration.imports)')
                continue
            for cls in classes:
                src = os.path.join(java, *cls.split('.')) + '.java'
                if not os.path.isfile(src):
                    errors.append(f'{path}: {cls} is not a class of this module')
                elif not re.search(r'implements\s+[^{]*\b' + ALLOWED[key] + r'\b', open(src, encoding='utf-8').read()):
                    errors.append(f'{path}: {cls} does not implement {ALLOWED[key]}')
    for e in errors:
        print('::error::' + e)
    print(f'spring.factories: {count} entries checked, {len(errors)} problems')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else '.'))
