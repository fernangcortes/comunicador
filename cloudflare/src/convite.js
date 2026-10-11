// Páginas web do Comunicador (as mesmas no servidor próprio e na Cloudflare):
// - invitePage: página de convite. Quem toca no link do WhatsApp cai aqui e o botão
//   abre o app já com o canal preenchido.
// - homePage: página principal (endereço raiz), com o link para baixar o app.
// As metatags fazem o WhatsApp mostrar uma prévia com a marca.
// Este arquivo é igual em servidor/convite.js; só muda a última linha (export).
const FAVICON = "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A//www.w3.org/2000/svg%22%20viewBox%3D%220%200%20512%20512%22%20role%3D%22img%22%20aria-label%3D%22Comunicador%22%3E%20%3Crect%20width%3D%22512%22%20height%3D%22512%22%20rx%3D%22112%22%20fill%3D%22%2322262B%22/%3E%20%3Ccircle%20cx%3D%22174.00%22%20cy%3D%22256.00%22%20r%3D%2278.00%22%20fill%3D%22%23E5323F%22/%3E%20%3Cpath%20d%3D%22M280.07%2C149.93%20A150.0%2C150.0%200%200%201%20280.07%2C362.07%22%20fill%3D%22none%22%20stroke%3D%22%23F2A516%22%20stroke-width%3D%2240.00%22%20stroke-linecap%3D%22round%22/%3E%20%3Cpath%20d%3D%22M330.98%2C99.02%20A222.0%2C222.0%200%200%201%20330.98%2C412.98%22%20fill%3D%22none%22%20stroke%3D%22%23F2A516%22%20stroke-width%3D%2240.00%22%20stroke-linecap%3D%22round%22/%3E%3C/svg%3E";

// ---------------------------------------------------------------------------
// Logo animada (a mesma de marca/logo-marca.svg, separada em partes)
// - cm-botao: o círculo vermelho (botão de falar) + uma sombra interna que só
//   aparece quando ele está "pressionado".
// - cm-o1 / cm-o2: as ondas sonoras, de dentro para fora.
// Tudo gira em torno do centro do círculo (174, 256), para as ondas crescerem
// "saindo" do botão.
//
// Ao abrir: o botão assenta e as ondas aparecem em sequência.
// Depois, num ciclo de 16 s, o botão é "apertado" duas vezes (no segundo 0 e no 7,
// ou seja, a cada 7 ou 9 s), por ~1,8 s; enquanto isso as ondas pulsam para fora.
// Com "reduzir movimento" ligado no celular, a logo fica parada.
// ---------------------------------------------------------------------------
function logoAnimada(tamanho) {
  return `<svg class="marca" width="${tamanho}" height="${tamanho}" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" role="img" aria-label="Comunicador">
<defs><radialGradient id="cm-sombra" cx="50%" cy="60%" r="50%"><stop offset=".68" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".42"/></radialGradient></defs>
<rect width="512" height="512" rx="112" fill="#22262B"/>
<g class="cm-botao"><circle class="cm-circ" cx="174" cy="256" r="78" fill="#E5323F"/><circle class="cm-sombra" cx="174" cy="256" r="78" fill="url(#cm-sombra)"/></g>
<path class="cm-onda cm-o1" d="M280.07,149.93 A150.0,150.0 0 0 1 280.07,362.07" fill="none" stroke="#F2A516" stroke-width="40" stroke-linecap="round"/>
<path class="cm-onda cm-o2" d="M330.98,99.02 A222.0,222.0 0 0 1 330.98,412.98" fill="none" stroke="#F2A516" stroke-width="40" stroke-linecap="round"/>
</svg>`;
}

const LOGO_CSS = `
.marca{display:block}
.marca .cm-botao,.marca .cm-onda{transform-box:view-box;transform-origin:174px 256px}
.marca .cm-sombra{opacity:0}
@media (prefers-reduced-motion:no-preference){
.marca .cm-botao{animation:cm-assenta .9s cubic-bezier(.2,.7,.2,1) both,cm-aperta 16s ease-in-out 3s infinite}
.marca .cm-circ{animation:cm-cor 16s ease-in-out 3s infinite}
.marca .cm-sombra{animation:cm-sombra 16s ease-in-out 3s infinite}
.marca .cm-o1{animation:cm-entra .8s cubic-bezier(.2,.7,.2,1) .35s both,cm-transmite 16s ease-in-out 3s infinite}
.marca .cm-o2{animation:cm-entra .8s cubic-bezier(.2,.7,.2,1) .6s both,cm-transmite 16s ease-in-out 3.18s infinite}
}
@keyframes cm-assenta{from{opacity:0;transform:scale(.94)}to{opacity:1;transform:scale(1)}}
@keyframes cm-entra{from{opacity:0;transform:scale(.9)}to{opacity:1;transform:scale(1)}}
@keyframes cm-aperta{0%,13%,43.75%,56.75%,100%{transform:scale(1)}1.5%,11.5%,45.25%,55.25%{transform:scale(.93)}}
@keyframes cm-cor{0%,13%,43.75%,56.75%,100%{fill:#E5323F}1.5%,11.5%,45.25%,55.25%{fill:#C72A36}}
@keyframes cm-sombra{0%,13%,43.75%,56.75%,100%{opacity:0}1.5%,11.5%,45.25%,55.25%{opacity:1}}
@keyframes cm-transmite{
0%,1.5%,6.8%,12%,43.75%,45.25%,50.55%,55.75%,100%{opacity:1;transform:scale(1)}
4.2%,9.4%,47.95%,53.15%{opacity:.5;transform:scale(1.05)}}
`;

// Visual comum às duas páginas (cores e fontes da marca)
const BASE_CSS = `
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#22262b;color:#eef1f3;
font:17px/1.5 system-ui,-apple-system,Roboto,sans-serif;padding:24px;box-sizing:border-box}
main{max-width:380px;width:100%}h1{font-size:30px;line-height:1.1;margin:14px 0 6px}
a{display:block;text-align:center;text-decoration:none;border-radius:12px;padding:16px;font-weight:700;margin-top:12px}
.pri{background:#eef1f3;color:#22262b}.sec{border:1px solid #3e454d;color:#eef1f3}
.muted{color:#98a2ad;font-size:15px}
a.link{display:inline;padding:0;margin:0;border-radius:0;font-weight:inherit;text-align:inherit;color:#f2a516;text-decoration:underline}
${LOGO_CSS}`;

const attr = (s) => String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");

function cabecalho(titulo, descricao, origin, extra = "") {
  const imagem = origin ? `<meta property="og:image" content="${origin}/og-imagem.png">
<meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
<meta name="twitter:image" content="${origin}/og-imagem.png">` : "";
  return `<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${titulo}</title>
<meta name="description" content="${descricao}">
<meta name="theme-color" content="#22262b">
${extra}<meta property="og:type" content="website">
<meta property="og:locale" content="pt_BR">
<meta property="og:site_name" content="Comunicador">
<meta property="og:title" content="${titulo}">
<meta property="og:description" content="${descricao}">
<meta name="twitter:card" content="summary_large_image">
${imagem}
<link rel="icon" type="image/svg+xml" href="${FAVICON}">
${origin ? `<link rel="apple-touch-icon" href="${origin}/icone-192.png">` : ""}`;
}

function invitePage(code, serverUrl, appUrl, key = "", origin = "") {
  const k = key ? `&k=${encodeURIComponent(key)}` : "";
  const deep = `comunicador://entrar?s=${encodeURIComponent(serverUrl)}&c=${encodeURIComponent(code)}${k}`;
  const install = appUrl
    ? `<a class="sec" href="${attr(appUrl)}">Baixar o app</a>`
    : `<p class="muted">Ainda não tem o app? Peça o arquivo de instalação para a produção.</p>`;
  const titulo = `Canal ${code} · Comunicador`;
  const descricao = "Você foi convidado para o canal do set. Toque para entrar pelo Comunicador.";
  return `<!doctype html><html lang="pt-BR"><head>
${cabecalho(titulo, descricao, origin, `<meta name="robots" content="noindex">\n`)}
<style>${BASE_CSS}
.code{font-size:44px;font-weight:800;letter-spacing:.08em;margin:18px 0;color:#f2a516}
</style></head><body><main>
${logoAnimada(64)}
<h1>Comunicador do set</h1>
<p class="muted">Você foi convidado para o canal</p>
<div class="code">${code}</div>
<a class="pri" href="${deep}">Abrir no Comunicador</a>
${install}
<p class="muted">Depois de abrir, digite seu nome e toque em Entrar no canal.</p>
</main></body></html>`;
}

// Página principal. "rodape" é uma linha opcional de estado (o servidor próprio
// mostra quantos canais e pessoas estão ativos).
function homePage(appUrl, origin = "", rodape = "Servidor no ar.") {
  const titulo = "Comunicador · intercom de voz para o set";
  const descricao = "Intercom de voz para a equipe de set, no celular. Aperte o botão, fale, e todo o canal ouve.";
  const baixar = appUrl
    ? `<a class="pri grande" href="${attr(appUrl)}">Baixar o app (Android)</a>`
    : `<p class="muted">Para instalar o app, peça o arquivo de instalação para a produção.</p>`;
  return `<!doctype html><html lang="pt-BR"><head>
${cabecalho(titulo, descricao, origin)}
<style>${BASE_CSS}
h1{font-size:38px;margin-top:18px}
.lead{font-size:19px;color:#d5dade;margin:8px 0 22px}
.grande{font-size:19px;padding:18px}
.passos{list-style:none;padding:0;margin:30px 0 0;counter-reset:p}
.passos li{counter-increment:p;display:flex;gap:12px;align-items:flex-start;margin:14px 0;color:#c9d0d6;font-size:16px}
.passos li::before{content:counter(p);flex:none;width:28px;height:28px;margin-top:-1px;border-radius:50%;border:1px solid #3e454d;
color:#f2a516;font-weight:800;font-size:14px;display:grid;place-items:center}
.passos b{color:#eef1f3}
.rodape{margin-top:32px;font-size:13px;color:#6f7983}
</style></head><body><main>
${logoAnimada(88)}
<h1>Comunicador</h1>
<p class="lead">Intercom de voz para a equipe de set, no celular. Aperte o botão, fale, e todo o canal ouve.</p>
${baixar}
<ol class="passos">
<li><span><b>Instale</b> o app no celular Android.</span></li>
<li><span><b>Crie um canal:</b> abra o app, digite seu nome e toque em “Criar canal novo e convidar a equipe”.</span></li>
<li><span><b>Mande o convite pelo WhatsApp.</b> Quem tocar no link entra direto no canal.</span></li>
</ol>
${rodape ? `<p class="rodape">${rodape}</p>` : ""}
<p class="rodape"><a class="link" href="/privacidade">Privacidade</a></p>
</main></body></html>`;
}

// Política de privacidade (a Google Play exige um endereço público para ela).
function privacyPage(origin = "") {
  const titulo = "Privacidade · Comunicador";
  const descricao = "O que o Comunicador usa, o que passa pela central e o que não é guardado.";
  return `<!doctype html><html lang="pt-BR"><head>
${cabecalho(titulo, descricao, origin)}
<style>${BASE_CSS}
body{place-items:start center}main{max-width:640px}
h2{font-size:20px;margin:28px 0 6px}p,li{color:#d5dade;font-size:16px}ul{padding-left:20px}
</style></head><body><main>
${logoAnimada(56)}
<h1>Privacidade</h1>
<p class="muted">Comunicador de set · atualizada em 11 de outubro de 2026</p>

<h2>Em resumo</h2>
<p>O Comunicador é um intercom de voz para equipes de filmagem. Ele não tem contas, anúncios nem ferramentas de análise, não grava conversas e não vende nem compartilha dados.</p>

<h2>O que o app usa no celular</h2>
<ul>
<li><b>Microfone:</b> só enquanto você está num canal, para transmitir a sua voz quando você fala (botão ou modo por voz). Com a tela travada, ele continua funcionando por um serviço em primeiro plano, sempre com um aviso fixo na notificação.</li>
<li><b>Bluetooth:</b> para o fone e para o botão PTT opcional.</li>
<li><b>Notificações:</b> para o aviso fixo enquanto o comunicador está ligado.</li>
<li><b>Rede:</b> para falar com a central (Wi-Fi local, servidor próprio ou Cloudflare).</li>
</ul>
<p>O teste de áudio grava 3 segundos, toca de volta e apaga. Essa gravação nunca sai do celular.</p>

<h2>O que passa pela central</h2>
<ul>
<li>O nome ou função que você digitou, o código do canal e se você está falando ou tem prioridade. Isso serve para mostrar a lista de pessoas do canal e some quando você sai.</li>
<li>Uma "prova de entrada" calculada a partir da senha do canal. A senha em si nunca sai do celular. A central guarda essa prova para conferir quem chega depois.</li>
<li><b>A sua voz, em tempo real, sem gravação.</b> No Wi-Fi local, no servidor próprio e no modo WebSocket da Cloudflare, ela vai cifrada de ponta a ponta: a central só repassa e não consegue ouvir. No modo WebRTC, ela passa pelo Cloudflare Realtime, cifrada no caminho e sem gravação.</li>
</ul>
<p>A central da Cloudflare funciona na infraestrutura da Cloudflare, que pode registrar dados técnicos de conexão, como o endereço IP, conforme a <a class="link" href="https://www.cloudflare.com/privacypolicy/">política de privacidade da Cloudflare</a>.</p>

<h2>O que fica no seu celular</h2>
<p>O seu nome, as preferências do app, a senha do canal em que você entrou e, se você for da produção, o código de administrador. Desinstalar o app apaga tudo.</p>

<h2>Contato</h2>
<p>Dúvidas ou pedidos: abra uma conversa em <a class="link" href="https://github.com/fernangcortes/comunicador/issues">github.com/fernangcortes/comunicador/issues</a>.</p>
<p><a class="sec" href="/">Voltar</a></p>
</main></body></html>`;
}

export { invitePage, homePage, privacyPage };
