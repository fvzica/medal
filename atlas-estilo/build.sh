#!/usr/bin/env bash
# Compila o Atlas Estilo para bytecode Java 8 (Tomcat 8.5 / JRE 1.8 x86 do
# servidor super-pf1), roda o SelfTest e gera dist/atlasestilo.war.
#
# Modo dev:  ./build.sh dev  -> gera dist/atlasestilo-dev.war SEM o filtro de
# SSO do BB e com atlas.dev.simular=true (login simulado como Master). Use só
# para testar fora do servidor — NUNCA em produção.
set -euo pipefail
cd "$(dirname "$0")"

MODO="${1:-prod}"
CP="../lib/javax.servlet-api-3.1.0.jar:lib/sqlite-jdbc-3.36.0.3.jar"

rm -rf build
mkdir -p build/classes build/test-classes build/war dist

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP" -d build/classes \
    $(find src/br -name '*.java')

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$CP:build/classes" \
    -d build/test-classes src/test/SelfTest.java
java -cp "build/classes:build/test-classes:lib/sqlite-jdbc-3.36.0.3.jar:../lib/javax.servlet-api-3.1.0.jar" SelfTest

# monta a árvore do WAR
cp -r WebContent/. build/war/
mkdir -p build/war/WEB-INF/classes build/war/WEB-INF/lib
cp -r build/classes/. build/war/WEB-INF/classes/
cp lib/sqlite-jdbc-3.36.0.3.jar build/war/WEB-INF/lib/

# SSO do BB. O WAR já leva a implementação própria do FilterOauth2 e do Usuario
# (src/br/com/bb/sso, compilada acima). Se os binários OFICIAIS do BB estiverem em
# terceiros/sso/ (FilterOauth2.class, Usuario.class, json-*.jar), eles
# sobrescrevem a implementação própria. O oauth.properties com o client_secret
# real fica em terceiros/sso/oauth.properties (ignorado pelo git); sem ele o WAR
# sai com o modelo (segredo TROQUE_AQUI…) e o filtro explica isso na tela.
SSO_SEGREDO=0
if [ -f terceiros/sso/br/com/bb/sso/filter/FilterOauth2.class ]; then
  cp -r terceiros/sso/br/com/bb/sso/. build/war/WEB-INF/classes/br/com/bb/sso/
  JSON_JAR="$(ls terceiros/sso/json-*.jar 2>/dev/null | head -1 || true)"
  [ -n "$JSON_JAR" ] && cp "$JSON_JAR" build/war/WEB-INF/lib/
  echo "SSO: usando os binários OFICIAIS do BB de terceiros/sso/"
fi
if [ "$MODO" != "dev" ]; then
  if [ -f terceiros/sso/oauth.properties ]; then
    cp terceiros/sso/oauth.properties build/war/WEB-INF/classes/oauth.properties
    grep -q "^redirect_uri=https://super-pf1.intranet.bb.com.br/atlasestilo$" terceiros/sso/oauth.properties \
      || echo "AVISO: redirect_uri do oauth.properties não é https://super-pf1.intranet.bb.com.br/atlasestilo"
    grep -q "^client_secret=TROQUE_AQUI" terceiros/sso/oauth.properties || SSO_SEGREDO=1
  else
    cp terceiros/sso/oauth.properties.exemplo build/war/WEB-INF/classes/oauth.properties
  fi
fi

if [ "$MODO" = "dev" ]; then
  # remove o filtro de SSO e liga o usuário simulado
  sed -i.bak '/<!-- SSO-INICIO/,/SSO-FIM -->/d' build/war/WEB-INF/web.xml
  sed -i.bak 's|<param-name>atlas.dev.simular</param-name>\n*|&|' build/war/WEB-INF/web.xml
  # troca o valor de atlas.dev.simular para true (linha seguinte ao param-name)
  awk '{
    print;
  }' build/war/WEB-INF/web.xml > /dev/null
  python3 - "$PWD/build/war/WEB-INF/web.xml" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = re.sub(r'(<param-name>atlas\.dev\.simular</param-name>\s*<param-value>)false(</param-value>)',
           r'\g<1>true\g<2>', s)
open(p, 'w', encoding='utf-8').write(s)
EOF
  rm -f build/war/WEB-INF/web.xml.bak
  (cd build/war && jar cf ../../dist/atlasestilo-dev.war .)
  echo "OK: dist/atlasestilo-dev.war (DEV — sem SSO, usuário simulado)"
else
  # cópia de referência do schema
  cp WebContent/WEB-INF/sql/schema.sql sql/schema.sql
  rm -f dist/atlasestilo.war dist/atlasestilo-sem-sso.war
  (cd build/war && jar cf ../../dist/atlasestilo.war .)
  for e in WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class \
           WEB-INF/classes/br/com/bb/sso/bean/Usuario.class WEB-INF/classes/oauth.properties; do
    jar tf dist/atlasestilo.war | grep -q "^$e$" || { echo "ERRO: WAR sem $e"; exit 1; }
  done
  if [ "$SSO_SEGREDO" = "1" ]; then
    echo "OK: dist/atlasestilo.war (produção, SSO do BB, oauth.properties real — NÃO commitar este WAR)"
  else
    echo "OK: dist/atlasestilo.war (produção, SSO do BB)"
    echo "    AVISO: oauth.properties com o segredo de EXEMPLO. Para o WAR pronto, crie"
    echo "    terceiros/sso/oauth.properties (modelo: oauth.properties.exemplo) com o client_secret"
    echo "    real e rode ./build.sh de novo — ou defina OAUTH_CLIENT_SECRET no serviço do Tomcat."
  fi
fi
