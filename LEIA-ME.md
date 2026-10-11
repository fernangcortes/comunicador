# Comunicador: manual

Intercom de voz para a equipe de set, no celular. Este manual é para quem vai usar (equipe e produção). A visão geral do projeto está no [README](README.md).

**Baixar o app (Android 8 ou mais novo):**
https://github.com/fernangcortes/comunicador/releases/latest/download/comunicador.apk

Esse link sempre entrega a versão mais nova. Ao abrir o arquivo, o celular pede para "permitir apps desconhecidos": é normal para apps de fora da Google Play. As versões seguintes instalam por cima.

---

## 1. Em cada celular, uma vez

1. Abra o app e permita **microfone** e **notificações**.
2. Toque em **Permitir que funcione com a tela desligada** e confirme.
3. Ponha o fone e toque em **Testar áudio (3 segundos)**. Se você se ouvir com clareza, está pronto. Se o fone for Bluetooth e você quiser falar pelo microfone dele, marque **Usar o microfone do fone Bluetooth** antes de testar.
4. Em alguns aparelhos é preciso um ajuste a mais, senão o sistema fecha o app com a tela desligada:
   - **Samsung:** Configurações → Bateria → Limites de uso em segundo plano → não deixe o Comunicador em "apps em suspensão".
   - **Xiaomi / Redmi / Poco:** nas informações do app, Economia de bateria → **Sem restrições**, e ative **Início automático**.
   - **Motorola e outros:** nas informações do app, Bateria → **Sem restrições**.

## 2. Pela internet (o normal)

**A produção:**
1. Abre o app, digita o nome e marca **Tenho prioridade** (direção e produção).
2. Toca em **Criar canal novo e convidar a equipe** e manda o convite no grupo do WhatsApp.

**A equipe:**
1. Toca no link do convite e em **Abrir no Comunicador** (ou em **Baixar o app**, se ainda não tiver).
2. Digita o nome e toca em **Entrar no canal**.
3. Põe o fone e **trava o celular**. A notificação fica fixa e o comunicador continua funcionando.

O convite já leva o servidor, o código e a senha do canal. Ninguém precisa digitar nada disso.

## 3. Sem internet: Wi-Fi do set

Todos precisam estar na mesma rede Wi-Fi, que não precisa ter internet.

- **Hotspot do celular central (mais simples):** no celular que vai ser a central, ligue o **Ponto de acesso**. Na maioria dos aparelhos funciona mesmo sem dados móveis. Os outros se conectam nesse Wi-Fi. Deixe a central no power bank.
- **Roteador Wi-Fi:** um roteador de viagem num power bank alcança mais longe e aguenta mais gente. Use 2,4 GHz e confira que o "isolamento de clientes" (AP isolation) está desligado.

Depois:
1. A central digita o nome e toca em **Criar canal neste celular**. A tela do canal mostra o endereço e a **senha** da central.
2. Os outros tocam em **Entrar no canal de…** (ou digitam o endereço da central) e digitam a senha que aparece na tela da central.

A senha da central é sempre a mesma naquele celular, então dá para combinar uma vez com a equipe.

## 4. Falando

**Modo Botão**
- No fone: um toque liga o microfone (bipe agudo), outro desliga (bipe grave).
- Na tela de bloqueio: botão **Falar / Parar de falar** na notificação.
- Com o app aberto: segure o botão grande.

**Modo Por voz**
- Transmite quando você fala e corta 0,7 s depois que você para.
- Ajuste a **sensibilidade**: falando normalmente, a barra deve passar da metade. Se transmitir o barulho do set, diminua.
- O botão do fone e a notificação silenciam e abrem o microfone.

**Botão PTT Bluetooth (segurar para falar)**
- Funciona com os botões "compatíveis com Zello" (ex.: Focket PTT-Z01, Pryme BT-PTT-Z), que usam o perfil BLE FFE0/FFE1.
- Antes de entrar no canal: em **Botão PTT Bluetooth**, toque em **Procurar botão**, segure o botão apertado até ele aparecer e toque em **Usar**. Não pareie o botão nas configurações do celular; o app cuida disso.
- Segurou, falou; soltou, parou (em qualquer modo). Se o botão perder a conexão no meio da fala, o app para de transmitir.
- No Android 11 ou mais antigo, a localização precisa estar ligada para procurar o botão.

**Prioridade:** quando alguém com prioridade fala, as outras vozes caem para 25% em todos os celulares.

## 5. Opções avançadas (produção)

Toque **7 vezes** no título "Comunicador" na tela de entrada. Aparecem:
- **Servidor:** o app já vem com a central da Cloudflare. Só mude para usar um servidor próprio. "Voltar ao servidor padrão" desfaz.
- **Código de administrador:** se a central estiver configurada com um código, só quem o tem cria canais novos. Quem entra por convite não precisa dele.

Toque 7 vezes de novo para esconder.

## 6. Segurança

- Cada canal pela internet tem uma senha longa, escondida no convite. Sem ela, ninguém entra nem ouve.
- A senha nunca sai do celular. A central recebe só uma "prova" calculada a partir dela.
- No Wi-Fi local, no servidor próprio e na reserva pelo WebSocket, a voz vai **cifrada de ponta a ponta** (AES-256-GCM): a central só repassa e não consegue ouvir. No WebRTC, a voz é cifrada pelo próprio WebRTC.
- Cifrar não atrasa o som.
- Detalhes: [privacidade](https://comunicador.escrevaprofernando.workers.dev/privacidade).

## 7. Os quatro caminhos da voz

| Caminho | Servidor (opções avançadas) | Quando |
|---|---|---|
| **Cloudflare WebRTC** (padrão) | `webrtc://comunicador.escrevaprofernando.workers.dev` | Com internet. Melhor com sinal fraco. Se o WebRTC não conectar em 20 s, a voz passa sozinha para o WebSocket ("Voz pela reserva"). |
| **Cloudflare** | `wss://comunicador.escrevaprofernando.workers.dev` | Redes que bloqueiam o WebRTC. |
| **Servidor próprio** | o IP do servidor | Quem tiver um VPS. Ver [`servidor/LEIA-ME.md`](servidor/LEIA-ME.md). |
| **Wi-Fi local** | (nenhum) | Sem internet: um celular é a central. |

## 8. Lista do teste de campo

O app ainda não rodou em celular de verdade. Num teste com 3 ou 4 aparelhos (de preferência incluindo um Samsung e um Xiaomi):

- [ ] O teste de áudio passa em todos os celulares, com o fone de cada um.
- [ ] Todos entram no canal pelo convite e se ouvem.
- [ ] **Com a tela desligada por 10 minutos**, todos continuam ouvindo e falando.
- [ ] O botão de cada modelo de fone liga e desliga o microfone com a tela travada.
- [ ] Atraso aceitável (em torno de 0,1 a 0,2 s no Wi-Fi; fone Bluetooth soma um pouco).
- [ ] Alcance: andar pela locação até a voz falhar.
- [ ] Bateria em 1 hora, inclusive a central do Wi-Fi local.
- [ ] O modo por voz não dispara com o barulho do set.
- [ ] O botão PTT conecta, e segurar e soltar funciona com a tela travada.
- [ ] Reserva: numa rede que bloqueie o WebRTC, em até 20 s aparece "Voz pela reserva" e todos continuam se ouvindo.
- [ ] Wi-Fi local: entrar com a senha certa funciona; com a errada, aparece "Senha errada".
- [ ] Microfone do fone ou do celular: comparar a voz captada pelos dois.

## Limites desta versão

- No Wi-Fi local, se a central sair ou ficar sem bateria, o canal cai para todos.
- Um canal só. Não há grupos.
- Se duas pessoas falarem ao mesmo tempo, as vozes se misturam.
- Só Android.

---

## Para quem for mexer no código

- `IntercomService.kt`: serviço em primeiro plano (tipo microfone) que mantém tudo com a tela travada: captura, envio, mixagem, botão do fone, notificação, WebRTC, ponte e reserva.
- `Protocol.kt`: o protocolo binário, igual nas três centrais (descrito no topo do arquivo).
- `Seguranca.kt`: senha do canal, prova de entrada, voz cifrada e código de administrador.
- `Links.kt`: UDP e WebSocket. `RtcVoice.kt`: WebRTC via Cloudflare Realtime.
- `OpusCodec.kt`, `ImaAdpcm.kt`, `Mixer.kt`: compressão, mistura, prioridade e bipes.
- `PttButton.kt`: botão PTT BLE. `Discovery.kt`: encontra a central no Wi-Fi (mDNS). `MainActivity.kt`: a tela.

**Compilar no computador** (Java 17 e Android SDK):

```bash
./gradlew testReleaseUnitTest assembleRelease
```

No Windows, se a pasta do projeto tiver acento no caminho (ex.: `C:\Users\Programação`), os testes do app não encontram as classes. Rode a partir de um caminho sem acento (uma junção de pasta resolve).

**Testes dos servidores:**

```bash
node --test servidor/teste.mjs
```

```bash
cd cloudflare && npm ci && node --test teste.mjs
```

**Assinatura:** o APK de release usa a chave definitiva, que fica fora do repositório: nos segredos `ANDROID_KEYSTORE_BASE64` e `ANDROID_KEYSTORE_PASSWORD` do GitHub e, no computador, em `comunicadorKeystore` / `comunicadorKeystorePassword` no `gradle.properties` do usuário. Sem ela, sai com a chave de teste (`app/comunicador-teste.keystore`).

**Mudanças no protocolo** precisam ir para os três lados: `Protocol.kt`, `servidor/servidor.js` e `cloudflare/src/index.js`.
