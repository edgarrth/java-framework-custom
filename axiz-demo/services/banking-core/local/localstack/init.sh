#!/bin/bash
# Crea los recursos de AWS que banking-core usa en local (se ejecuta cuando LocalStack está listo).
# En los entornos reales estos recursos los crea Terraform, nunca el servicio.
set -euo pipefail
