#!/usr/bin/env bash
# Completa o atlasestilo-sem-sso.war (ou atlasestilo.war) com o SSO do BB a partir
# de uma webapp já implantada (…/webapps/boaspraticas) ou de uma pasta com os
# arquivos soltos (br/com/bb/sso/…, json-*.jar, oauth.properties).
# Uso: terceiros/sso/injetar-sso.sh [war] [pasta-de-origem] [redirect_uri]
# Resultado: atlasestilo.war ao lado do WAR de entrada.
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
WAR="${1:-}"
ORIGEM="${2:-$AQUI}"
REDIRECT="${3:-https://super-pf1.intranet.bb.com.br/atlasestilo}"
if [ -z "$WAR" ]; then
  for c in "$AQUI/../../dist/atlasestilo-sem-sso.war" "$AQUI/../../dist/atlasestilo.war"; do [ -f "$c" ] && { WAR="$c"; break; }; done
fi
[ -n "$WAR" ] && [ -f "$WAR" ] || { echo "WAR não encontrado. Uso: $0 <atlasestilo-sem-sso.war> [origem]"; exit 1; }

# aceita layout de webapp (WEB-INF/classes/...) ou solto (br/..., json-*.jar, oauth.properties)
if [ -f "$ORIGEM/WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class" ]; then
  CLASSES="$ORIGEM/WEB-INF/classes"; LIB="$ORIGEM/WEB-INF/lib"
else
  CLASSES="$ORIGEM"; LIB="$ORIGEM"
fi
for f in "$CLASSES/br/com/bb/sso/filter/FilterOauth2.class" "$CLASSES/br/com/bb/sso/bean/Usuario.class" "$CLASSES/oauth.properties"; do
  [ -f "$f" ] || { echo "Ausente na origem: $f"; exit 1; }
done
JSON="$(ls "$LIB"/json-*.jar 2>/dev/null | head -1 || true)"
[ -n "$JSON" ] || { echo "Ausente: $LIB/json-*.jar"; exit 1; }

unzip -p "$WAR" WEB-INF/web.xml | grep -q "br.com.bb.sso.filter.FilterOauth2" \
  || { echo "O web.xml deste WAR não declara o FilterOauth2 (WAR dev?). Use dist/atlasestilo-sem-sso.war."; exit 1; }

DESTINO="$(dirname "$WAR")/atlasestilo.war"
if [ "$DESTINO" != "$WAR" ]; then cp "$WAR" "$DESTINO"; else cp "$WAR" "$WAR.sem-sso.bak"; fi

TMP="$(mktemp -d)"
mkdir -p "$TMP/WEB-INF/classes" "$TMP/WEB-INF/lib"
(cd "$CLASSES" && find br/com/bb/sso -type f -name '*.class' | while read -r c; do
   mkdir -p "$TMP/WEB-INF/classes/$(dirname "$c")"; cp "$c" "$TMP/WEB-INF/classes/$c"; done)
cp "$JSON" "$TMP/WEB-INF/lib/"
# oauth.properties da origem com o redirect_uri desta ferramenta
if grep -q '^[[:space:]]*redirect_uri[[:space:]]*=' "$CLASSES/oauth.properties"; then
  sed "s#^[[:space:]]*redirect_uri[[:space:]]*=.*#redirect_uri=$REDIRECT#" "$CLASSES/oauth.properties" > "$TMP/WEB-INF/classes/oauth.properties"
else
  { cat "$CLASSES/oauth.properties"; echo "redirect_uri=$REDIRECT"; } > "$TMP/WEB-INF/classes/oauth.properties"
fi
(cd "$TMP" && zip -q -r "$DESTINO" WEB-INF)
rm -rf "$TMP"
echo "Injetado em $DESTINO:"
unzip -l "$DESTINO" | grep -E "br/com/bb/sso|json-.*\.jar|oauth.properties" | awk '{print "  " $4}'
unzip -p "$DESTINO" WEB-INF/classes/oauth.properties | grep "^redirect_uri" | sed 's/^/  /'
