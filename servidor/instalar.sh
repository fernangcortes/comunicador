#!/usr/bin/env bash
# Instala o servidor do Comunicador num Ubuntu (Oracle Cloud, Vultr, Hostinger, qualquer VPS).
# Uso, dentro desta pasta:   sudo bash instalar.sh
set -e

if [ "$(id -u)" != "0" ]; then
  echo "Rode com sudo:  sudo bash instalar.sh"
  exit 1
fi

echo "1/4  Instalando o Node.js…"
apt-get update -y >/dev/null
apt-get install -y nodejs curl >/dev/null

echo "2/4  Copiando o servidor para /opt/comunicador…"
DIR=/opt/comunicador
mkdir -p "$DIR"
cp servidor.js convite.js package.json "$DIR/"
id -u comunicador >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin comunicador

PUBLIC_IP=$(curl -s --max-time 5 https://api.ipify.org || true)
APP_URL_VALUE="${APP_URL:-}"

cat > /etc/systemd/system/comunicador.service <<EOF
[Unit]
Description=Comunicador do set (central na internet)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/node $DIR/servidor.js
Environment=PUBLIC_HOST=$PUBLIC_IP
Environment=APP_URL=$APP_URL_VALUE
User=comunicador
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF

echo "3/4  Liberando as portas 50505/UDP (voz) e 8080/TCP (convites)…"
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow 50505/udp >/dev/null
  ufw allow 8080/tcp >/dev/null
fi
# As imagens Ubuntu da Oracle vêm com regras de iptables que bloqueiam quase tudo.
if iptables -L INPUT -n 2>/dev/null | grep -q REJECT; then
  iptables -C INPUT -p udp --dport 50505 -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport 50505 -j ACCEPT
  iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
  if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save >/dev/null 2>&1 || true; fi
fi

echo "4/4  Ligando o serviço…"
systemctl daemon-reload
systemctl enable --now comunicador >/dev/null
sleep 1
systemctl restart comunicador

echo
echo "Pronto. O servidor liga sozinho quando a máquina reinicia."
if [ -n "$PUBLIC_IP" ]; then
  echo "  Teste no navegador:      http://$PUBLIC_IP:8080"
  echo "  No app, campo Servidor:  $PUBLIC_IP"
fi
echo "  Ver o que está acontecendo:  journalctl -u comunicador -f"
echo
echo "Lembre de liberar também as portas 50505/UDP e 8080/TCP no painel do provedor"
echo "(na Oracle: Security List da rede da máquina)."
