<%@ page pageEncoding="UTF-8" session="false" %><!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Acesso negado</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b1324'/%3E%3Ccircle cx='16' cy='16' r='7' fill='%23f5c518'/%3E%3C/svg%3E">
<script>
(function(){
  var t=null; try{ t=localStorage.getItem('superpf1.tema'); }catch(e){}
  document.documentElement.setAttribute('data-tema', t==='escuro'?'escuro':'claro');
})();
</script>
<style>
:root{
  --fundo:#F6F0DF; --papel:#FDFAF0; --tinta:#1C2735; --texto:#2A3850;
  --texto-suave:rgba(28,39,53,.62); --rule:rgba(28,39,53,.18);
  --gold:#8F6B00; --gold-bg:#F5C518; --sobre-acento:#0E1822; --wine:#8B3A3A;
}
html[data-tema="escuro"]{
  --fundo:#0E1822; --papel:#131F2E; --tinta:#F2E9D2;
  --texto:rgba(242,233,210,.84); --texto-suave:rgba(242,233,210,.58);
  --rule:rgba(242,233,210,.18); --gold:#F5C518; --wine:#B0584A;
}
body{margin:0;background:var(--fundo);color:var(--texto);
  font-family:'IBM Plex Sans',system-ui,sans-serif;display:grid;place-items:center;
  min-height:100vh;padding:16px;box-sizing:border-box}
.cartao{background:var(--papel);border:1px solid var(--rule);border-radius:10px;
  max-width:480px;padding:40px;text-align:center}
h1{font-family:Georgia,'Times New Roman',serif;color:var(--tinta);margin:0 0 12px}
p{color:var(--texto-suave);line-height:1.6}
.selo{display:inline-grid;place-items:center;width:56px;height:56px;border-radius:8px;
  background:var(--gold-bg);color:var(--sobre-acento);font-weight:800;font-size:22px;
  margin-bottom:16px;font-family:Georgia,serif}
</style>
</head>
<body>
  <div class="cartao">
    <span class="selo">BB</span>
    <h1>Acesso negado</h1>
    <p>Não foi possível identificar o seu usuário no SSO, ou a sua matrícula está
       bloqueada nesta ferramenta.</p>
    <p>Acesse pelo endereço oficial
       (<strong>super-pf1.intranet.bb.com.br/atlasestilo</strong>) e, se o problema
       continuar, procure um administrador da SUPER PF1.</p>
  </div>
</body>
</html>
