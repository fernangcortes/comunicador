// Página de convite (a mesma usada pelo servidor próprio e pela Cloudflare).
// Quem toca no link do WhatsApp cai aqui e o botão abre o app já com o canal preenchido.
// As metatags fazem o WhatsApp mostrar uma prévia com a marca e o código do canal.
const FAVICON = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20viewBox%3D%220%200%20512%20512%22%20role%3D%22img%22%20aria-label%3D%22Comunicador%22%3E%20%3Crect%20width%3D%22512%22%20height%3D%22512%22%20rx%3D%22112%22%20fill%3D%22%2322262B%22/%3E%20%3Ccircle%20cx%3D%22174.00%22%20cy%3D%22256.00%22%20r%3D%2278.00%22%20fill%3D%22%23E5323F%22/%3E%20%3Cpath%20d%3D%22M280.07%2C149.93%20A150.0%2C150.0%200%200%201%20280.07%2C362.07%22%20fill%3D%22none%22%20stroke%3D%22%23F2A516%22%20stroke-width%3D%2240.00%22%20stroke-linecap%3D%22round%22/%3E%20%3Cpath%20d%3D%22M330.98%2C99.02%20A222.0%2C222.0%200%200%201%20330.98%2C412.98%22%20fill%3D%22none%22%20stroke%3D%22%23F2A516%22%20stroke-width%3D%2240.00%22%20stroke-linecap%3D%22round%22/%3E%3C/svg%3E";
const MARCA = `<svg width="64" height="64" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" role="img" aria-label="Comunicador"> <rect width="512" height="512" rx="112" fill="#22262B"/> <circle cx="174.00" cy="256.00" r="78.00" fill="#E5323F"/> <path d="M280.07,149.93 A150.0,150.0 0 0 1 280.07,362.07" fill="none" stroke="#F2A516" stroke-width="40.00" stroke-linecap="round"/> <path d="M330.98,99.02 A222.0,222.0 0 0 1 330.98,412.98" fill="none" stroke="#F2A516" stroke-width="40.00" stroke-linecap="round"/></svg>`;

function invitePage(code, serverUrl, appUrl, key = "", origin = "") {
  const k = key ? `&k=${encodeURIComponent(key)}` : "";
  const deep = `comunicador://entrar?s=${encodeURIComponent(serverUrl)}&c=${encodeURIComponent(code)}${k}`;
  const install = appUrl
    ? `<a class="sec" href="${appUrl}">Baixar o app</a>`
    : `<p class="muted">Ainda não tem o app? Peça o arquivo de instalação para a produção.</p>`;
  const titulo = `Canal ${code} · Comunicador`;
  const descricao = "Você foi convidado para o canal do set. Toque para entrar pelo Comunicador.";
  const imagem = origin ? `<meta property="og:image" content="${origin}/og-imagem.png">
<meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
<meta name="twitter:image" content="${origin}/og-imagem.png">` : "";
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${titulo}</title>
<meta name="description" content="${descricao}">
<meta name="theme-color" content="#22262b">
<meta name="robots" content="noindex">
<meta property="og:type" content="website">
<meta property="og:locale" content="pt_BR">
<meta property="og:site_name" content="Comunicador">
<meta property="og:title" content="${titulo}">
<meta property="og:description" content="${descricao}">
<meta name="twitter:card" content="summary_large_image">
${imagem}
<link rel="icon" type="image/svg+xml" href="${FAVICON}">
${origin ? `<link rel="apple-touch-icon" href="${origin}/icone-192.png">` : ""}
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#22262b;color:#eef1f3;
font:17px/1.5 system-ui,-apple-system,Roboto,sans-serif;padding:24px;box-sizing:border-box}
main{max-width:380px;width:100%}h1{font-size:30px;line-height:1.1;margin:14px 0 6px}
.logo{width:64px;height:64px;display:block}
.code{font-size:44px;font-weight:800;letter-spacing:.08em;margin:18px 0;color:#f2a516}
a{display:block;text-align:center;text-decoration:none;border-radius:12px;padding:16px;font-weight:700;margin-top:12px}
.pri{background:#eef1f3;color:#22262b}.sec{border:1px solid #3e454d;color:#eef1f3}
.muted{color:#98a2ad;font-size:15px}
</style></head><body><main>
<span class="logo">${MARCA}</span>
<h1>Comunicador do set</h1>
<p class="muted">Você foi convidado para o canal</p>
<div class="code">${code}</div>
<a class="pri" href="${deep}">Abrir no Comunicador</a>
${install}
<p class="muted">Depois de abrir, digite seu nome e toque em Entrar no canal.</p>
</main></body></html>`;
}

module.exports = { invitePage };
