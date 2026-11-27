#!/bin/bash
# Construye las bibliotecas del framework, las herramientas y la demo en orden de dependencias, desde la raíz
# del repositorio:
#   scripts/build.sh                 framework, herramientas y demo con pruebas y controles de calidad
#   scripts/build.sh --skip-tests    los mismos artefactos sin pruebas ni controles de calidad
#   scripts/build.sh --only <part>   una parte: framework, tools o demo (con pruebas salvo que
#                                    también se indique --skip-tests)
# Primero se instala el framework en ~/.m2; las herramientas y la demo lo resuelven desde allí. Las pruebas
# con contenedores requieren Docker en ejecución.
set -Eeuo pipefail
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
tests=1 only=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) tests=0 ;;
    --only) only=${2:?framework, tools or demo}; shift ;;
    *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
  esac
  shift
done
if [ "$tests" = 1 ]; then
  framework=(clean install -Pquality -Daxiz.it.requireDocker=true)
  tools=(clean install -Pquality)
  demo=(clean verify -Pquality -Daxiz.it.requireDocker=true)
else
  framework=(clean install -DskipTests -Dpit.skip=true)
  tools=(clean install -DskipTests -Dpit.skip=true)
  demo=(clean package -DskipTests -Dpit.skip=true)
fi
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
start=$SECONDS
if [ -z "$only" ] || [ "$only" = framework ]; then
  step "axiz-framework: ${framework[*]}"
  (cd "$root/axiz-framework" && ./mvnw --bootstrap -B -ntp "${framework[@]}" -T 4)
fi
if [ -z "$only" ] || [ "$only" = tools ]; then
  step "axiz-tools: ${tools[*]}"
  (cd "$root/axiz-tools" && ./mvnw -B -ntp "${tools[@]}")
fi
if [ -z "$only" ] || [ "$only" = demo ]; then
  step "axiz-demo: ${demo[*]}"
  (cd "$root/axiz-demo" && ./mvnw -B -ntp "${demo[@]}")
fi
printf '\n\033[32mbuild finished in %s s\033[0m\n' "$((SECONDS - start))"
