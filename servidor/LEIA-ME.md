# Servidor próprio (Oracle Cloud, Vultr, Hostinger…)

É a "central" do Comunicador morando na internet. Os celulares, cada um no seu 4G ou Wi-Fi, se conectam nele pelo código do canal. Um servidor atende várias equipes e vários canais ao mesmo tempo.

- Voz: UDP, porta **50505**
- Convites: HTTP, porta **8080** (a página que abre o app com o canal preenchido)
- Não precisa de banco de dados nem de bibliotecas. Só Node.js.

## Opção 1 — Oracle Cloud (grátis, bom para testar)

A Oracle tem um plano "Always Free" com datacenter em São Paulo e Vinhedo.

1. Crie a conta em oracle.com/cloud/free. Ela pede cartão só para verificação. **Escolha São Paulo (ou Vinhedo) como região principal**: os recursos gratuitos ficam na região principal.
2. Menu **Compute → Instances → Create instance**.
   - Imagem: **Ubuntu** (22.04 ou mais nova).
   - Formato: um marcado como **Always Free eligible**.
   - Baixe a chave SSH que ela oferece.
3. Libere as portas na rede da máquina: na página da instância, clique na **Subnet → Security List → Add Ingress Rules**:
   - Origem `0.0.0.0/0`, protocolo **UDP**, porta de destino **50505**
   - Origem `0.0.0.0/0`, protocolo **TCP**, porta de destino **8080**
4. Copie esta pasta para a máquina e instale:
   ```
   scp -i sua-chave.key -r servidor ubuntu@IP_DA_MAQUINA:~
   ssh -i sua-chave.key ubuntu@IP_DA_MAQUINA
   cd servidor && sudo bash instalar.sh
   ```
5. Abra `http://IP_DA_MAQUINA:8080` no navegador. Deve aparecer "servidor no ar".
6. No app, campo **Servidor**: o IP da máquina.

**Atenção:** a Oracle pode recuperar máquinas gratuitas que ficam quase sem uso por 7 dias (CPU, rede e memória abaixo de 20%). O comunicador gasta muito pouco, então isso pode acontecer. Para testar é ótimo; para depender dele numa filmagem, use a opção 2 ou a Cloudflare.

## Opção 2 — VPS pago (Vultr, Hostinger…), barato e estável

- Vultr: plano de 1 GB por cerca de US$ 5/mês, com datacenter em São Paulo.
- Hostinger: plano KVM 1 a partir de cerca de R$ 28/mês, cobrado em real.

Crie uma máquina **Ubuntu** em **São Paulo**, libere as portas 50505/UDP e 8080/TCP no painel (se houver firewall) e rode os mesmos passos 4 a 6.

## Opção 3 — Testar no seu computador

```
node servidor.js
```
Funciona na rede local. Para alguém de fora entrar, o computador precisa estar acessível pela internet, o que normalmente não é o caso. Use para testes.

## Manutenção

- Ver o que está acontecendo: `journalctl -u comunicador -f`
- Reiniciar: `sudo systemctl restart comunicador`
- Atualizar: copie a pasta nova e rode `sudo bash instalar.sh` de novo.
- Link para baixar o app na página de convite: `sudo APP_URL="https://seu-link" bash instalar.sh`

## Limites

60 pessoas por canal e 2.000 canais por servidor. Quem não dá sinal por 8 segundos sai da lista sozinho.

## Senha do canal

A primeira pessoa que entra num canal define a senha (o app gera e põe no convite). Quem chegar depois com outra senha recebe "senha errada" e não ouve nada. A senha de um canal vazio é lembrada por 7 dias.
