# Comunicador – versão 0.7

Um app só, com quatro caminhos para a voz. A equipe não escolhe: o convite já leva o caminho.

| Caminho | Endereço do servidor no app | Quando usar | O que precisa |
|---|---|---|---|
| **Wi-Fi local** | (nenhum) | Locação sem sinal | Um celular vira a central (hotspot ou roteador) |
| **Servidor próprio** | `203.0.113.10` (IP) | Com internet; Oracle (grátis) para testar, VPS para usar | Pasta `servidor/` |
| **Cloudflare** | `wss://comunicador.sua-conta.workers.dev` | Com internet, sem máquina para manter | Pasta `cloudflare/` |
| **Cloudflare WebRTC** | `webrtc://comunicador.sua-conta.workers.dev` | Com internet, melhor qualidade e menos dados | Pasta `cloudflare/` + app do Realtime (ver o LEIA-ME de lá) |

Marca: o app ganhou ícone próprio (luz de tally com ondas de voz), também na notificação, e a página de convite passou a ter logo e prévia no WhatsApp. Os arquivos estão em `marca/`.

Novidade da 0.7: **volta automática do WebRTC para o WebSocket**. No modo `webrtc://`, se o WebRTC não conectar em 20 segundos (rede que bloqueia, por exemplo) ou se o servidor não tiver o WebRTC configurado, a voz daquele celular passa sozinha para o WebSocket do mesmo servidor, sem sair do canal. A tela mostra "Voz pela reserva" e o motivo. Quem continua no WebRTC manda uma cópia da própria voz pelo WebSocket enquanto houver alguém na reserva, então todo mundo continua se ouvindo. Isso também permite misturar no mesmo canal gente entrando por `webrtc://` e por `wss://`. A reserva vale até a pessoa sair e entrar de novo no canal.

Novidade da 0.6: **teste de áudio**. Na tela de entrada, "Testar áudio (3 segundos)" grava você e toca de volta pelo mesmo caminho do comunicador (mesmo microfone, mesmo fone). No fim, diz qual microfone e qual saída foram usados e avisa se não chegou som.

Novidades da 0.5:
- **Opus nos quatro caminhos.** Celulares com Android 10 ou mais novo mandam a voz em Opus (cerca de 25 MB por diária com 2 h de fala no canal); os mais antigos continuam em ADPCM e todos se entendem. A tela do canal mostra qual está em uso.
- **Senha do canal.** Quem cria o canal gera uma senha que vai escondida no link do convite. Quem tentar entrar com o código e sem a senha recebe "Senha do canal errada". No modo WebRTC, o servidor só libera o Cloudflare Realtime para quem tem a senha de um canal com gente dentro.
- **Prioridade.** Na tela de entrada, marque "Tenho prioridade" no celular da direção e da produção. Quando alguém com prioridade fala, as outras vozes caem para 25% em todos os celulares.
- **APK gerado no GitHub**, sem Android Studio (ver abaixo).

No modo WebRTC, a voz vai pelo Cloudflare Realtime (Opus, ritmo adaptativo da rede, criptografia) e o Durable Object cuida só da lista de pessoas. Botão, modo por voz, botão PTT Bluetooth e bipes funcionam igual nos quatro.

O protocolo é o mesmo nos três. A voz agora vai comprimida (ADPCM, 4 vezes menor que antes): cerca de 80 kbit/s com os cabeçalhos de rede, o que dá uns 70 MB de dados móveis numa diária com 2 horas de fala no canal (antes seriam uns 230 MB).

## Pela internet: como a equipe entra

1. A produção abre o app, toca em **Criar canal novo e convidar a equipe** e manda o convite pelo WhatsApp.
2. Cada pessoa toca no link, toca em **Abrir no Comunicador**, digita o nome e entra.

O link abre uma página do servidor com o botão que abre o app já com o canal preenchido. Quem não tiver o link pode digitar o código do canal.

---

# App Android

Comunicador de voz para a equipe de set. Funciona de celular para celular, sem notebook e sem internet, **com a tela desligada**.

- Um celular toca em **Criar canal** e vira a central.
- Os outros abrem o app e tocam no canal que aparece na lista.
- Para falar, há dois modos, escolhidos no app: **Botão** (pelo botão do fone, pela notificação ou pela tela) ou **Por voz** (transmite sozinho quando você fala).

## 1. Gerar o aplicativo

### Opção A: no GitHub, sem instalar nada (recomendado)

1. Crie uma conta em github.com e um repositório novo (botão **New**). Para a equipe conseguir baixar o APK pelo link, ele precisa ser **público**. Não há senhas no código: as chaves da Cloudflare ficam na Cloudflare.
2. Na página do repositório, **Add file → Upload files** e arraste todo o conteúdo da pasta `comunicador-android`. Confira se a pasta `.github` foi junto; alguns computadores escondem pastas que começam com ponto. Se ela não aparecer, crie o arquivo pelo site: **Add file → Create new file**, nome `.github/workflows/gerar-apk.yml`, e cole o conteúdo do arquivo de mesmo nome.
3. Clique em **Commit changes**. O GitHub começa a compilar sozinho: acompanhe na aba **Actions** (leva uns 5 minutos).
4. Quando ficar verde, abra a execução e baixe **comunicador-apk** em *Artifacts*. Dentro está o `comunicador.apk`.
5. Para ter um **link fixo** de download (bom para mandar no WhatsApp e para a página de convite): na aba **Releases → Create a new release**, crie a tag `v0.5` e publique. O GitHub gera o APK e o link fica:
   `https://github.com/SEU_USUARIO/SEU_REPOSITORIO/releases/latest/download/comunicador.apk`

Se a compilação ficar vermelha, abra a execução, copie o erro e me mande.

O APK é assinado com uma chave de teste guardada no projeto (`app/comunicador-teste.keystore`), para que cada versão nova instale por cima da anterior. Antes de publicar na Google Play, troque por uma chave guardada fora do repositório.

### Opção B: no computador, com o Android Studio

1. Instale o **Android Studio** (developer.android.com/studio).
2. Abra esta pasta (`comunicador-android`) no Android Studio. Na primeira vez ele baixa o que falta (Gradle e Android SDK 34); aceite o que ele pedir e espere a sincronização terminar.
3. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. O arquivo sai em `app/build/outputs/apk/debug/app-debug.apk`.
5. Mande esse APK para os celulares da equipe (WhatsApp, Drive, cabo). No celular, abra o arquivo e permita "instalar apps desconhecidos" quando ele pedir.

Alternativa: ligue o celular no computador com a "Depuração USB" ativada e clique em **Run** (▶) no Android Studio.

## 2. Montar a rede do set

Todos precisam estar na mesma rede Wi-Fi. Não precisa de internet.

**Opção A – hotspot do celular central (mais simples).** No celular de quem vai ser a central, ligue o **Ponto de acesso / Roteador Wi-Fi** nas configurações. Na maioria dos aparelhos isso funciona mesmo sem dados móveis. Os outros celulares se conectam nesse Wi-Fi. Deixe a central no power bank: ela gasta mais bateria.

**Opção B – roteador Wi-Fi.** Ligue um roteador qualquer (um de viagem roda num power bank) e conecte todos nele. Alcança mais longe e aguenta mais gente que um hotspot de celular. Confira que o "isolamento de clientes" (AP isolation) está desligado.

## 3. Em cada celular (primeira vez)

1. Abra o app e permita **microfone** e **notificações**.
2. Toque em **Permitir que funcione com a tela desligada** e confirme.
3. Ponha o fone e toque em **Testar áudio**. Se você se ouvir com clareza, o celular está pronto. Se o fone for Bluetooth e você quiser falar por ele, marque "Usar o microfone do fone Bluetooth" antes de testar.
4. Em alguns aparelhos é preciso um ajuste a mais, senão o sistema fecha o app com a tela desligada:
   - **Samsung:** Configurações → Bateria → Limites de uso em segundo plano → não deixe o Comunicador em "apps em suspensão".
   - **Xiaomi / Redmi / Poco:** nas informações do app, Economia de bateria → **Sem restrições**, e ative **Início automático**.
   - **Motorola e outros:** nas informações do app, Bateria → **Sem restrições**.

## 4. No set

1. A central digita o nome e toca em **Criar canal neste celular**. A tela mostra o endereço dela (ex.: `192.168.43.1`).
2. Os outros digitam o nome e tocam em **Entrar no canal de…**. Se o canal não aparecer na lista, digitem o endereço da central em "Ou entre pelo endereço da central".
3. Ponham o fone e **travem o celular**. A notificação fica fixa e o comunicador continua funcionando.

**Modo Botão**
- No fone: um toque no botão liga o microfone (bipe agudo), outro toque desliga (bipe grave).
- Na tela de bloqueio: botão **Falar / Parar de falar** na notificação.
- Com o app aberto: segure o botão grande para falar.

**Modo Por voz**
- O app transmite quando você fala e corta 0,7 s depois que você para.
- Ajuste a **sensibilidade** com o app aberto: falando normalmente, a barra deve passar da metade. Se ele transmitir o barulho do set, diminua.
- O botão do fone e a notificação servem para **silenciar** e **abrir** o microfone.

**Botão PTT Bluetooth (segurar para falar)**
- Funciona com os botões "compatíveis com Zello" (ex.: Focket PTT-Z01, Pryme BT-PTT-Z e genéricos), que usam o perfil BLE FFE0/FFE1.
- Antes de entrar no canal: em "Botão PTT Bluetooth", toque em **Procurar botão**, segure o botão apertado até ele aparecer na lista e toque em **Usar**. Não pareie o botão nas configurações de Bluetooth do celular; o app cuida disso.
- No canal: enquanto o botão estiver apertado, você transmite (em qualquer modo); soltou, parou. Se o botão perder a conexão no meio de uma fala, o app para de transmitir.
- O botão dorme para economizar bateria. Se a tela mostrar "aperte uma vez para acordar", é só apertar.
- No Android 11 ou mais antigo, a localização do celular precisa estar ligada para procurar o botão.

Quando alguém começa e termina de falar, toca um bipe curto, como num rádio.

**Fone Bluetooth:** marque "Usar o microfone do fone Bluetooth" antes de entrar se você quer falar pelo microfone do fone. Nesse modo o som fica com qualidade de ligação telefônica. Sem marcar, o app usa o microfone do celular (ou o do fone com fio).

## 5. O que testar primeiro

Esta versão foi escrita e compilada, mas ainda não rodou em celular de verdade. Num teste com 3 ou 4 aparelhos, confira:

- [ ] Volta automática: num celular, entre com `webrtc://` numa rede que bloqueie o WebRTC (ou com o servidor sem as chaves do Realtime) e confira se, em até 20 s, aparece "Voz pela reserva" e todos continuam se ouvindo.
- [ ] O teste de áudio passa em todos os celulares, com o fone que cada um vai usar.
- [ ] Todos entram no canal e se ouvem.
- [ ] **Com a tela desligada por 10 minutos**, todos continuam ouvindo e falando.
- [ ] O botão do **seu** modelo de fone liga e desliga o microfone com a tela travada (isso varia de fone para fone).
- [ ] O atraso da voz é aceitável (deve ficar em torno de um décimo a dois décimos de segundo no Wi-Fi; fone Bluetooth soma mais um pouco).
- [ ] Alcance: ande pela locação até a voz começar a falhar.
- [ ] Bateria: quanto cada celular gasta em 1 hora, inclusive a central.
- [ ] O modo por voz não dispara com o barulho do set.
- [ ] O botão PTT conecta, e segurar/soltar funciona com a tela travada.

## Limites desta versão

- Se a central sair ou ficar sem bateria, o canal cai para todos.
- Um canal só. Não há grupos.
- Se duas pessoas falarem ao mesmo tempo, as vozes se misturam.
- O áudio não é criptografado: qualquer um com o app na mesma rede pode entrar. Use uma senha no Wi-Fi.
- O microfone fica capturando o tempo todo (só transmite quando deve), o que gasta um pouco de bateria a mais.

## Como funciona (para quem for mexer no código)

- `IntercomService.kt`: serviço em primeiro plano do tipo microfone, que mantém tudo rodando com a tela desligada. Segura a CPU e o Wi-Fi acordados, captura o microfone, envia e recebe a voz e atende o botão do fone.
- `Protocol.kt`: pacotes UDP na porta 50505. Voz em PCM de 16 kHz, blocos de 20 ms.
- `Mixer.kt`: segura 60 ms de áudio para absorver oscilações do Wi-Fi, mistura as vozes e toca os bipes.
- `PttButton.kt`: conexão com o botão PTT Bluetooth (BLE, serviço FFE0, mensagens "+PTT=P" / "+PTT=R").
- `Discovery.kt`: anuncia e encontra a central na rede (mDNS), sem precisar digitar endereço.
- `MainActivity.kt`: a tela.
