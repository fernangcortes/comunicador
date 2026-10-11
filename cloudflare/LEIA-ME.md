# Central na Cloudflare (Worker + Durable Object)

A mesma central do Comunicador, rodando na rede da Cloudflare. Não tem máquina para manter: cada canal é um "Durable Object", um pequeno servidor que existe só enquanto alguém está no canal e fica perto de quem está usando.

Os celulares se conectam por WebSocket seguro (`wss://`), que passa por qualquer rede: 4G, Wi-Fi de hotel, rede corporativa.

## Publicar (uma vez, num computador com Node.js)

1. Crie uma conta gratuita na Cloudflare (dash.cloudflare.com).
2. Nesta pasta:
   ```
   npm install
   npx wrangler login       # abre o navegador para autorizar
   npx wrangler deploy
   ```
3. No fim aparece o endereço, algo como `https://comunicador.sua-conta.workers.dev`.
4. No app, campo **Servidor**: o mesmo endereço trocando `https://` por `wss://`:
   `wss://comunicador.sua-conta.workers.dev`

Para a equipe nem ver esse campo, coloque o endereço em `Protocol.DEFAULT_SERVER` no código do app antes de gerar o APK.

Opcional: um link para baixar o app na página de convite. Em `wrangler.toml`, preencha `APP_URL` e rode `npx wrangler deploy` de novo.

## Testar sem publicar

```
npx wrangler dev
```
Roda a central no seu computador (`ws://localhost:8787`), com o mesmo motor da Cloudflare.

## Custo

O plano gratuito da Cloudflare inclui Durable Objects:
- 100 mil "requisições" por dia. Mensagens que chegam por WebSocket contam 1 a cada 20; mensagens que saem não contam.
- 13 mil GB-segundo de tempo ativo por dia. Com a hibernação, o canal sai da memória quando ninguém está falando.

Estimativa: uma diária de 10 horas com alguém falando 2 horas no total gera umas 18 mil requisições. O plano grátis cobre algumas diárias por dia. Passando disso, o plano pago da Cloudflare custa US$ 5/mês e inclui 1 milhão de requisições.

Os limites e preços são os publicados pela Cloudflare em 2026; confira no painel antes de usar em produção.

## Voz por WebRTC (opcional, recomendado)

Com isto, a voz passa a ir pelo **Cloudflare Realtime** (WebRTC): codec Opus, ritmo adaptativo da rede, menos travadas com sinal fraco e uns 3 vezes menos dados. O Durable Object continua no papel de "recepcionista" do canal: lista de pessoas, quem está falando e onde ouvir cada um. É o mesmo desenho que a Cloudflare usa nos exemplos dela.

1. No painel da Cloudflare: **Realtime → Serverless SFU → Create**. Guarde o **App ID** e o **App Secret**.
2. Nesta pasta, grave os dois como segredos (eles ficam só no servidor, nunca vão para o celular):
   ```
   npx wrangler secret put SFU_APP_ID
   npx wrangler secret put SFU_TOKEN
   ```
3. Publique de novo: `npx wrangler deploy`.
4. No app, campo **Servidor**: `webrtc://comunicador.sua-conta.workers.dev`. Os convites gerados a partir daí já levam a equipe para o modo WebRTC.

Os dois modos convivem no mesmo servidor e até no mesmo canal: `wss://…` usa a voz pelo WebSocket, `webrtc://…` usa a voz pelo WebRTC. Se o WebRTC não conectar em 20 segundos, ou se os segredos não estiverem configurados, o app passa sozinho para o WebSocket (a "reserva") e avisa na tela; quem está no WebRTC faz a ponte para quem está na reserva.

**Custo do WebRTC:** o Realtime cobra US$ 0,05 por GB enviado aos celulares, com os primeiros 1.000 GB de cada mês grátis. Com Opus, uma pessoa gasta dezenas de MB por diária; na prática, custo zero. O Durable Object, nesse modo, quase não trabalha (só a lista de pessoas), então também fica bem dentro do plano grátis.

**Senha do canal:** cada canal tem uma senha, criada pelo app de quem abriu o canal e levada no convite. Quem não tem a senha não entra, não ouve, e o Worker não libera o Realtime para ele. Uma pessoa que estude o app ainda conseguiria abrir um canal próprio no seu servidor; se isso um dia importar, o próximo passo é uma senha do servidor.
