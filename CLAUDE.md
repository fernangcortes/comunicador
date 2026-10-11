# Comunicador de set: contexto para o Claude Code

Intercom de voz para equipe de filmagem, rodando no celular. Projeto do Fernando (documentarista; fala português). Escreva em português do Brasil: textos da interface, comentários, mensagens de commit e respostas. Ele não é desenvolvedor Android: explique o que fez em linguagem simples e peça confirmação antes de qualquer coisa que gaste dinheiro, publique algo ou mexa em contas dele.

O projeto foi escrito numa conversa no claude.ai e compila de verdade desde 11/10/2026 (GitHub Actions e no computador do Fernando). **Ainda não rodou num celular.**

## Estrutura

- `app/`: app Android (Kotlin 2.2, AGP 8.13, Gradle 8.14 pelo wrapper `gradlew`, sem AndroidX, `minSdk 26`, `targetSdk 36`). Pacote `br.comunicador.set`.
  - `IntercomService.kt`: serviço em primeiro plano (tipo microfone) que mantém tudo com a tela travada. Captura, envio, mixagem, botão do fone (MediaSession), notificação, modo WebRTC, ponte e reserva.
  - `Protocol.kt`: protocolo binário (HELLO, AUDIO, ROSTER, BYE, TALK, RTC_PUB, RTC_LIST, ERROR, RTC_UNPUB). Comentário no topo descreve cada mensagem.
  - `Seguranca.kt`: a senha do canal nunca viaja; dela saem (PBKDF2) a prova de entrada (vai no HELLO e no X-Chave) e a chave da voz (AES-256-GCM, áudio cifrado de ponta a ponta, bit 0x80 no codec). Também a prova do código de administrador.
  - `app/src/test/`: testes JUnit (protocolo, ADPCM, criptografia, com valores de referência iguais aos de Node/Python).
  - `Links.kt`: transporte UDP (Wi-Fi local e servidor próprio) e WebSocket feito à mão (Cloudflare).
  - `RtcVoice.kt`: voz por WebRTC via Cloudflare Realtime (SFU). Uma conexão para publicar, outra para receber.
  - `OpusCodec.kt` (MediaCodec, Android 10+), `ImaAdpcm.kt` (reserva), `Mixer.kt` (buffer, mistura, prioridade, bipes).
  - `PttButton.kt`: botão PTT BLE compatível com Zello (serviço FFE0/FFE1, "+PTT=P"/"+PTT=R").
  - `AudioTest.kt`, `Discovery.kt` (mDNS no Wi-Fi local), `MainActivity.kt` (interface feita em código, sem XML).
  - `app/comunicador-teste.keystore`: chave de TESTE (senha `comunicador`), só para debug e para PRs sem segredos. A Release usa a **chave definitiva**, fora do repositório: segredos `ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD` no GitHub; no computador, `comunicadorKeystore`/`comunicadorKeystorePassword` em `C:\ferramentas-android\gradle-home\gradle.properties`; original e backup em `Documentos\Comunicador - chave de assinatura`.
- `servidor/`: central para VPS/Oracle (Node.js, sem dependências). UDP 50505 para voz, HTTP 8080 para convites. `instalar.sh` instala como serviço. `teste.mjs`: testes (`node --test`).
- `cloudflare/`: Worker + Durable Object (`Canal`), publicado em `comunicador.escrevaprofernando.workers.dev`. Rotas `/` (página principal), `/ws/:código` (WebSocket), `/rtc/:código/*` (proxy para o Realtime SFU, exige a prova de entrada no cabeçalho `X-Chave`), `/c/:código` (convite), `/privacidade`. Segredos: `SFU_APP_ID`, `SFU_TOKEN` (gravados), `ADMIN_CODE` (opcional: só quem tem cria canal). `teste.mjs`: testes contra o `wrangler dev`.
- `docs/`: esquemas SVG usados no `README.md` (página do GitHub). `LEIA-ME.md` é o manual de uso.
- `marca/`: logo, ícones e `gerar_marca.py`, que gera também os ícones em `app/src/main/res`.
- `.github/workflows/gerar-apk.yml`: roda os testes (servidores e app) e compila o APK assinado (Java 17, `./gradlew`). Em tags `v*`, publica uma Release com `comunicador.apk` (recusa sem a chave definitiva).

## Modos de conexão (definidos pelo endereço do servidor)

| Endereço | Caminho da voz |
|---|---|
| vazio | Wi-Fi local: um celular é a central |
| `203.0.113.10` | servidor próprio (UDP) |
| `wss://host` | Cloudflare, voz pelo WebSocket |
| `webrtc://host` | Cloudflare, voz pelo WebRTC; se não conectar em 20 s, cai para o WebSocket ("reserva") e quem fica no WebRTC faz ponte |

Convite: `https://host/c/CÓDIGO?k=SENHA[&voz=webrtc]` → abre `comunicador://entrar?s=…&c=…&k=…`.

## O que já foi testado (fora de um celular)

- Código de rede real do app (Kotlin no JVM) contra os dois servidores: lista de pessoas, áudio, senha errada barrada, prioridade, pacotes Opus repassados.
- Worker no `wrangler dev`: rotas do WebRTC contra um SFU simulado (formato conferido com a documentação da Cloudflare), lista de publicações, aviso de reserva, imagens e prévia do convite.
- Nada foi executado num celular ainda.

## Estado e próximos passos

Feito em 11/10/2026: repositório público `fernangcortes/comunicador`, Releases v0.7 e v0.8, Worker publicado com o Realtime configurado, `DEFAULT_SERVER` embutido, página principal e logo animada, plano em https://claude.ai/artifact/XnRjpYjXvij4Ny3qVjVvAK. Depois da v0.8: chave definitiva, ferramentas atualizadas (API 36), opções avançadas escondidas (7 toques no título), código de administrador, voz cifrada de ponta a ponta, senha na central do Wi-Fi local, testes automáticos, política de privacidade, README com esquemas.

Próximos: Fernando gravar `ADMIN_CODE` no Worker e publicar a v0.9; teste de campo com 3 ou 4 celulares (lista no `LEIA-ME.md`); depois grupos (com modo "só ouvir"), central reserva, Google Play (cadastro de desenvolvedor Android obrigatório a partir de 2027), iPhone.

No Windows, rode os testes do app a partir de `C:\ferramentas-android\projeto` (junção sem acento para esta pasta): com "Programação" no caminho, o JUnit não acha as classes.

## Cuidados

- Nunca colocar chaves da Cloudflare no repositório nem no app (ficam como segredos do Worker).
- Mudanças no protocolo precisam ir para os três lados: `Protocol.kt`, `servidor/servidor.js` e `cloudflare/src/index.js`.
- Ao mexer na marca, rodar `marca/gerar_marca.py` e copiar os PNGs para `servidor/publico/` e `cloudflare/src/publico/`.
