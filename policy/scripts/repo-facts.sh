#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Genera el input JSON de las políticas de policy/repo (REPO-01 y REPO-02):
#   - trivyignore: cada entrada de .trivyignore con su exp: y su justificación (el bloque de comentarios #
#     inmediatamente anterior, sin línea en blanco entre medio);
#   - tracked_files: los archivos VERSIONADOS (git ls-files), no los del disco: un .env local ignorado por
#     .gitignore no es un incumplimiento.
# Solo bash + awk + git (sin jq), para que corra igual en el runner y en Git Bash de Windows.
#
#   policy/scripts/repo-facts.sh > repo-facts.json
# -----------------------------------------------------------------------------
set -euo pipefail

json_escape() { sed -e 's/[\\]/\\\\/g' -e 's/"/\\"/g' -e 's/\t/ /g' -e 's/\r//g'; }

echo '{'
echo '  "trivyignore": ['
if [ -f .trivyignore ]; then
  tr -d '\r' < .trivyignore | awk '
    # Comillas dobles -> simples, barras invertidas -> "/" y tabs -> espacio: string JSON válido sin escapes.
    function esc(s) { gsub(/"/, "\047", s); gsub(/[\\]/, "/", s); gsub(/\t/, " ", s); return s }
    /^[[:space:]]*#/ { sub(/^[[:space:]]*#[[:space:]]?/, ""); just = (just == "" ? $0 : just " " $0); next }
    /^[[:space:]]*$/ { just = ""; next }
    {
      id = $1; vence = ""
      for (i = 2; i <= NF; i++) if ($i ~ /^exp:/) { vence = substr($i, 5) }
      printf "%s    {\"id\": \"%s\", \"exp\": \"%s\", \"line\": %d, \"justification\": \"%s\"}", sep, esc(id), esc(vence), NR, esc(just)
      sep = ",\n"; just = ""
    }
    END { if (sep != "") printf "\n" }'
fi
echo '  ],'
echo '  "tracked_files": ['
git ls-files | json_escape | awk '{ printf "%s    \"%s\"", sep, $0; sep = ",\n" } END { if (sep != "") printf "\n" }'
echo '  ]'
echo '}'
