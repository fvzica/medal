<#
.SYNOPSIS
  Completa o atlasestilo-sem-sso.war (ou atlasestilo.war) com o SSO do BB copiado
  de uma ferramenta já implantada no servidor (boaspraticas) — sem precisar de JDK.

.DESCRIPTION
  O WAR de produção sai do build com o FilterOauth2 declarado no web.xml, mas sem
  os quatro artefatos do SSO, que não ficam no repositório:
    WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class
    WEB-INF/classes/br/com/bb/sso/bean/Usuario.class
    WEB-INF/lib/json-*.jar
    WEB-INF/classes/oauth.properties   (com o client_secret; redirect_uri trocado para /atlasestilo)
  Este script os lê da webapp de origem e grava dentro do WAR, salvando o resultado
  como atlasestilo.war ao lado. Usa só .NET (System.IO.Compression): Windows Server
  2012+ / PowerShell 3+.

.PARAMETER War
  WAR a completar. Padrão: atlasestilo-sem-sso.war ou atlasestilo.war na pasta atual ou em dist\.

.PARAMETER Origem
  Pasta da webapp de onde copiar o SSO. Padrão: <Tomcat>\webapps\boaspraticas (ou dashjunho),
  descoberto por CATALINA_BASE/CATALINA_HOME ou pelo caminho padrão do servidor.

.PARAMETER RedirectUri
  redirect_uri a gravar no oauth.properties. Padrão: https://super-pf1.intranet.bb.com.br/atlasestilo

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File injetar-sso.ps1
  powershell -ExecutionPolicy Bypass -File injetar-sso.ps1 -War C:\temp\atlasestilo-sem-sso.war -Origem "C:\Program Files (x86)\Apache Software Foundation\Tomcat 8.5\webapps\boaspraticas"
#>
param(
  [string]$War = "",
  [string]$Origem = "",
  [string]$RedirectUri = "https://super-pf1.intranet.bb.com.br/atlasestilo"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Achar-War {
  $candidatos = @($War, ".\atlasestilo-sem-sso.war", ".\atlasestilo.war", ".\dist\atlasestilo-sem-sso.war", ".\dist\atlasestilo.war",
                  "..\..\dist\atlasestilo-sem-sso.war", "..\..\dist\atlasestilo.war")
  foreach ($c in $candidatos) { if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path } }
  throw "Nao achei o WAR. Informe -War <caminho\atlasestilo-sem-sso.war>."
}

function Achar-Origem {
  if ($Origem) { if (Test-Path $Origem) { return (Resolve-Path $Origem).Path } else { throw "Pasta de origem nao existe: $Origem" } }
  $bases = @($env:CATALINA_BASE, $env:CATALINA_HOME,
             "C:\Program Files (x86)\Apache Software Foundation\Tomcat 8.5",
             "C:\Program Files\Apache Software Foundation\Tomcat 8.5") | Where-Object { $_ }
  foreach ($b in $bases) {
    foreach ($app in @("boaspraticas", "dashjunho")) {
      $p = Join-Path $b "webapps\$app"
      if (Test-Path (Join-Path $p "WEB-INF\classes\br\com\bb\sso\filter\FilterOauth2.class")) { return $p }
    }
  }
  throw "Nao achei uma webapp com o SSO (boaspraticas/dashjunho). Informe -Origem <pasta da webapp>."
}

$warPath = Achar-War
$origem  = Achar-Origem
Write-Host "WAR:    $warPath"
Write-Host "Origem: $origem"

$classes = Join-Path $origem "WEB-INF\classes"
$itens = @(
  @{ entrada = "WEB-INF/classes/br/com/bb/sso/filter/FilterOauth2.class"; arquivo = (Join-Path $classes "br\com\bb\sso\filter\FilterOauth2.class") },
  @{ entrada = "WEB-INF/classes/br/com/bb/sso/bean/Usuario.class";        arquivo = (Join-Path $classes "br\com\bb\sso\bean\Usuario.class") }
)
# demais classes do pacote sso, se houver (versoes do filtro com classes auxiliares)
Get-ChildItem (Join-Path $classes "br\com\bb\sso") -Recurse -Filter "*.class" | ForEach-Object {
  $rel = $_.FullName.Substring($classes.Length + 1).Replace("\", "/")
  if (-not ($itens | Where-Object { $_.entrada -eq "WEB-INF/classes/$rel" })) { $itens += @{ entrada = "WEB-INF/classes/$rel"; arquivo = $_.FullName } }
}
$json = Get-ChildItem (Join-Path $origem "WEB-INF\lib") -Filter "json-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $json) { throw "Nao achei WEB-INF\lib\json-*.jar em $origem" }
$itens += @{ entrada = "WEB-INF/lib/$($json.Name)"; arquivo = $json.FullName }
foreach ($i in $itens) { if (-not (Test-Path $i.arquivo)) { throw "Arquivo ausente na origem: $($i.arquivo)" } }

# oauth.properties: o da origem, com o redirect_uri desta ferramenta
$oauthOrigem = Join-Path $classes "oauth.properties"
if (-not (Test-Path $oauthOrigem)) { throw "Nao achei $oauthOrigem (precisa do client_secret)" }
$linhas = Get-Content $oauthOrigem
$temRedirect = $false
$linhas = $linhas | ForEach-Object {
  if ($_ -match "^\s*redirect_uri\s*=") { $temRedirect = $true; "redirect_uri=$RedirectUri" } else { $_ }
}
if (-not $temRedirect) { $linhas += "redirect_uri=$RedirectUri" }
$oauthTemp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllLines($oauthTemp, $linhas, (New-Object System.Text.UTF8Encoding($false)))

# destino: sempre atlasestilo.war (se a entrada era -sem-sso, fica uma copia pronta ao lado)
$destino = Join-Path (Split-Path $warPath) "atlasestilo.war"
if ($destino -ne $warPath) { Copy-Item $warPath $destino -Force }
else { Copy-Item $warPath "$warPath.sem-sso.bak" -Force; Write-Host "Backup: $warPath.sem-sso.bak" }

$zip = [System.IO.Compression.ZipFile]::Open($destino, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $web = $zip.GetEntry("WEB-INF/web.xml")
  if (-not $web) { throw "WAR sem WEB-INF/web.xml" }
  $sr = New-Object System.IO.StreamReader($web.Open()); $xml = $sr.ReadToEnd(); $sr.Close()
  if ($xml -notmatch "br\.com\.bb\.sso\.filter\.FilterOauth2") { throw "O web.xml deste WAR nao declara o FilterOauth2 (e o WAR dev?). Use o atlasestilo-sem-sso.war de producao." }
  foreach ($i in $itens) {
    $antiga = $zip.GetEntry($i.entrada); if ($antiga) { $antiga.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $i.arquivo, $i.entrada, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    Write-Host ("  + " + $i.entrada)
  }
  $antiga = $zip.GetEntry("WEB-INF/classes/oauth.properties"); if ($antiga) { $antiga.Delete() }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $oauthTemp, "WEB-INF/classes/oauth.properties", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  Write-Host "  + WEB-INF/classes/oauth.properties (redirect_uri=$RedirectUri)"
} finally {
  $zip.Dispose()
  Remove-Item $oauthTemp -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Pronto: $destino tem o SSO. Copie para <Tomcat>\webapps\ e deixe o .war la."
Write-Host "Lembre: $RedirectUri precisa estar registrado para o client_id SUPERPF1 (senao: redirect_uri_mismatch)."
