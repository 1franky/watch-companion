#!/usr/bin/env bash
# Copia el contrato OpenAPI de un backend fijado a un commit (WC-ADR-009 del companion, mismo esquema que velora-android):
# los clientes se generan de esta copia, así el build no depende de la red y un cambio de contrato es explícito.
#   scripts/update-contract.sh velora <sha>     fija el commit y descarga el contrato
#   scripts/update-contract.sh --check velora   comprueba que la copia corresponde al commit fijado
set -euo pipefail
cd "$(dirname "$0")/.."

source_of() {
  case "$1" in
    velora) echo "1franky/velora-api openapi/openapi-v1.yaml" ;;
    nexora) echo "1franky/nexora-api openapi/openapi-v1.yaml" ;;
    *) echo "Backend desconocido: $1 (velora, nexora)" >&2; exit 2 ;;
  esac
}

if [[ "${1:-}" == "--check" ]]; then
  backend="${2:?Uso: scripts/update-contract.sh --check <backend>}"
  read -r repo path <<<"$(source_of "$backend")"
  ref="$(tr -d '[:space:]' < "contract/$backend/REF")"
  if diff -q <(curl -fsSL "https://raw.githubusercontent.com/$repo/$ref/$path") "contract/$backend/openapi-v1.yaml" >/dev/null; then
    echo "Contrato de $backend al día con $repo@$ref."
  else
    echo "contract/$backend/openapi-v1.yaml no coincide con $repo@$ref" >&2
    exit 1
  fi
  exit 0
fi

backend="${1:?Uso: scripts/update-contract.sh <backend> <sha>}"
ref="${2:?Uso: scripts/update-contract.sh <backend> <sha>}"
read -r repo path <<<"$(source_of "$backend")"
mkdir -p "contract/$backend"
curl -fsSL "https://raw.githubusercontent.com/$repo/$ref/$path" -o "contract/$backend/openapi-v1.yaml"
echo "$ref" > "contract/$backend/REF"
echo "Contrato de $backend copiado de $repo@$ref."
