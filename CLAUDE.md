# Comunicador de set: contexto para o Claude Code

Intercom de voz para equipe de filmagem, rodando no celular. Projeto do Fernando (documentarista; fala português). Escreva em português do Brasil: textos da interface, comentários, mensagens de commit e respostas. Ele não é desenvolvedor Android: explique o que fez em linguagem simples e peça confirmação antes de qualquer coisa que gaste dinheiro, publique algo ou mexa em contas dele.

O projeto foi escrito numa conversa no claude.ai, **sem compilar com o Android Gradle Plugin de verdade**. O Kotlin foi checado com `kotlinc` contra o `android.jar` (API 34) e contra uma réplica feita à mão da interface do `org.webrtc`. Então a primeira compilação real pode acusar erros pequenos, principalmente em `RtcVoice.kt` (API do WebRTC) e `OpusCodec.kt`. Corrija mantendo o comportamento.

## Estrutura

- `app/`: app Android (Kotlin, sem AndroidX, `minSdk 26`, `targetSdk 34`). Pacote `br.comunicador.set`.
  - `IntercomService.kt`: serviço em primeiro plano (tipo microfone) que mantém tudo com a tela travada. Captura, envio, mixagem, botão do fone (MediaSession), notificação, modo WebRTC, ponte e reserva.
  - `Protocol.kt`: protocolo binário (HELLO, AUDIO, ROSTER, BYE, TALK, RTC_PUB, RTC_LIST, ERROR, RTC_UNPUB). Comentário no topo descreve cada mensagem.
  - `Links.kt`: transporte UDP (Wi-Fi local e servidor próprio) e WebSocket feito à mão (Cloudflare).
  - `RtcVoice.kt`: voz por WebRTC via Cloudflare Realtime (SFU). Uma conexão para publicar, outra para receber.
  - `OpusCodec.kt` (MediaCodec, Android 10+), `ImaAdpcm.kt` (reserva), `Mixer.kt` (buffer, mistura, prioridade, bipes).
  - `PttButton.kt`: botão PTT BLE compatível com Zello (serviço FFE0/FFE1, "+PTT=P"/"+PTT=R").
  - `AudioTest.kt`, `Discovery.kt` (mDNS no Wi-Fi local), `MainActivity.kt` (interface feita em código, sem XML).
  - `app/comunicador-teste.keystore`: chave de assinatura de TESTE, de propósito no repositório (senha `comunicador`), para as versões instalarem por cima. Trocar antes da Google Play.
- `servidor/`: central para VPS/Oracle (Node.js, sem dependências). UDP 50505 para voz, HTTP 8080 para convites. `instalar.sh` instala como serviço.
- `cloudflare/`: Worker + Durable Object (`Canal`). Rotas `/ws/:código` (WebSocket), `/rtc/:código/*` (proxy para o Realtime SFU, exige a senha do canal no cabeçalho `X-Chave`), `/c/:código` (página de convite).
- `marca/`: logo, ícones e `gerar_marca.py`, que gera também os ícones em `app/src/main/res`.
- `.github/workflows/gerar-apk.yml`: compila o APK no GitHub Actions (Gradle 8.7, Java 17). Em tags `v*`, publica uma Release com `comunicador.apk`.

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

## Próximos passos (nesta ordem; ver também a seção "Roteiro" do LEIA-ME/site)

1. **GitHub:** `git init`, criar repositório **público** (o link de download do APK precisa ser aberto pela equipe sem login), enviar, acompanhar o Actions e corrigir erros de compilação até ficar verde. Não versionar `cloudflare/node_modules` nem `.wrangler`.
2. **Release `v0.7`**, para ter o link fixo `…/releases/latest/download/comunicador.apk`.
3. **Cloudflare:** `cd cloudflare && npm install && npx wrangler deploy`. Criar o app em Realtime → Serverless SFU (painel), gravar `SFU_APP_ID` e `SFU_TOKEN` com `npx wrangler secret put`, e `APP_URL` = link da Release. Confirmar com o Fernando antes.
4. **Servidor embutido:** preencher `Protocol.DEFAULT_SERVER` com `webrtc://comunicador.<subdomínio>.workers.dev`, para a equipe nunca ver o campo de servidor.
5. **Teste de campo** com 3 ou 4 celulares, seguindo a lista do `LEIA-ME.md`.

Depois disso, o roteiro segue: chave de assinatura definitiva, senha do servidor, criptografia no Wi-Fi local, grupos, central reserva, Google Play, iPhone.

## Cuidados

- Nunca colocar chaves da Cloudflare no repositório nem no app (ficam como segredos do Worker).
- Mudanças no protocolo precisam ir para os três lados: `Protocol.kt`, `servidor/servidor.js` e `cloudflare/src/index.js`.
- Ao mexer na marca, rodar `marca/gerar_marca.py` e copiar os PNGs para `servidor/publico/` e `cloudflare/src/publico/`.
