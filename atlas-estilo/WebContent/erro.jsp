<%@ page pageEncoding="UTF-8" session="false" isErrorPage="true" %><%
  // Página de erro standalone (sem includes nem dados): substitui a página do
  // Tomcat, que exporia stack trace e versão. Para /api/* devolve JSON.
  Integer codigo = (Integer) request.getAttribute("javax.servlet.error.status_code");
  int status = codigo == null ? 500 : codigo;
  String uri = (String) request.getAttribute("javax.servlet.error.request_uri");
  boolean api = uri != null && uri.contains("/api/");
  response.setStatus(status);
  if (api) {
      response.setContentType("application/json;charset=UTF-8");
      out.print("{\"erro\":\"" + (status == 404 ? "Rota não encontrada." : "Erro interno.") + "\"}");
      return;
  }
%><!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><%= status == 404 ? "Página não encontrada" : "Erro inesperado" %> · Atlas Estilo</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b1324'/%3E%3Ccircle cx='16' cy='16' r='7' fill='%23f5c518'/%3E%3C/svg%3E">
<script>
(function(){
  var t=null; try{ t=localStorage.getItem('superpf1.tema'); }catch(e){}
  document.documentElement.setAttribute('data-tema', t==='escuro'?'escuro':'claro');
})();
</script>
<style>
:root{--fundo:#f3f5fa;--papel:#ffffff;--tinta:#0b1324;--texto:#2a3850;--texto-suave:rgba(28,39,53,.62);
  --rule:rgba(28,39,53,.14);--gold:#8a6a00;--gold-bg:#f5c518;--sobre-acento:#0e1822}
html[data-tema="escuro"]{--fundo:#0b1324;--papel:#121c30;--tinta:#f2e9d2;--texto:rgba(242,233,210,.84);
  --texto-suave:rgba(242,233,210,.58);--rule:rgba(242,233,210,.16);--gold:#f5c518}
body{margin:0;background:var(--fundo);color:var(--texto);font-family:Inter,'IBM Plex Sans',system-ui,sans-serif;
  display:grid;place-items:center;min-height:100vh;padding:16px;box-sizing:border-box}
.cartao{background:var(--papel);border:1px solid var(--rule);border-radius:14px;max-width:480px;padding:40px;text-align:center}
h1{color:var(--tinta);margin:0 0 12px;font-size:22px}
p{color:var(--texto-suave);line-height:1.6}
.selo{display:inline-grid;place-items:center;width:56px;height:56px;border-radius:12px;background:var(--gold-bg);
  color:var(--sobre-acento);font-weight:800;font-size:22px;margin-bottom:16px}
a{color:var(--gold);font-weight:600}
</style>
</head>
<body>
  <div class="cartao">
    <span class="selo">BB</span>
    <% if (status == 404) { %>
      <h1>Página não encontrada</h1>
      <p>O endereço não existe nesta ferramenta.</p>
    <% } else { %>
      <h1>Algo deu errado por aqui</h1>
      <p>Ocorreu um erro inesperado ao atender o seu pedido. Nada do que você
         registrou antes foi perdido. Tente de novo em instantes.</p>
    <% } %>
    <p><a href="<%= request.getContextPath() %>/">Voltar ao Atlas</a> · se persistir, avise um
       administrador da SUPER PF1 (horário: <%= new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date()) %>).</p>
  </div>
</body>
</html>
