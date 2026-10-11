// Comunicador — servidor próprio (VPS, Oracle Cloud, Vultr, Hostinger…)
// Faz o papel da central para quem está na internet: recebe a voz de quem fala
// e repassa para os outros do mesmo canal. Não precisa de nenhuma biblioteca.
//
//   node servidor.js
//
// Portas: UDP 50505 (voz) e TCP 8080 (página de convite). Variáveis opcionais:
//   PORT, HTTP_PORT, PUBLIC_HOST (IP ou nome público), APP_URL (link para baixar o app),
//   ADMIN_CODE (se preenchido, só quem tem este código cria canais; quem entra por convite não precisa)

const dgram = require('dgram');
const crypto = require('crypto');
const http = require('http');
const { invitePage, homePage, privacyPage } = require('./convite');

const PORT = Number(process.env.PORT) || 50505;
const HTTP_PORT = Number(process.env.HTTP_PORT) || 8080;
const PUBLIC_HOST = process.env.PUBLIC_HOST || '';
const APP_URL = process.env.APP_URL || '';
const ADMIN_CODE = process.env.ADMIN_CODE || '';

const HELLO = 1, AUDIO = 2, ROSTER = 3, BYE = 4, TALK = 5, ERROR = 8;
const ERR_WRONG_KEY = 1, ERR_NEED_ADMIN = 2;
const KEY_TTL = 7 * 24 * 3600 * 1000; // a senha de um canal vazio é lembrada por 7 dias
const MEMBER_TIMEOUT = 8000;
const MAX_PER_ROOM = 60;
const MAX_ROOMS = 2000;
const CODE_RE = /^[A-Z0-9]{4,12}$/;

const rooms = new Map();   // código -> { code, members: Map(chave -> membro), nextId }
const byAddr = new Map();  // "ip:porta" -> { room, m }
const keys = new Map();    // código -> { key, last }: senha do canal, definida por quem o criou
const sock = dgram.createSocket('udp4');

const keyOf = (r) => `${r.address}:${r.port}`;

function cut(str, maxBytes) {
  let s = str;
  while (Buffer.byteLength(s, 'utf8') > maxBytes) s = s.slice(0, -1);
  return Buffer.from(s, 'utf8');
}

// HELLO: [1][tam][nome][tam][código][tam][prova de entrada][opções][tam][prova de admin]
// (opções: bit 1 = prioridade). A prova de entrada sai da senha do canal, que nunca viaja;
// o áudio vai cifrado de ponta a ponta e é só repassado. Detalhes em Protocol.kt e Seguranca.kt.
function parseHello(b) {
  if (b.length < 2) return null;
  const fields = [];
  let i = 1;
  for (let f = 0; f < 3 && i < b.length; f++) {
    const n = Math.min(b[i], b.length - i - 1);
    fields.push(b.toString('utf8', i + 1, i + 1 + n).trim());
    i += 1 + n;
  }
  const flags = i < b.length ? b[i] : 0;
  i += 1;
  let admin = '';
  if (i < b.length) {
    const n = Math.min(b[i], b.length - i - 1);
    admin = b.toString('utf8', i + 1, i + 1 + n);
  }
  return {
    name: fields[0] || 'Sem nome',
    code: (fields[1] || '').toUpperCase(),
    key: fields[2] || '',
    priority: (flags & 2) !== 0,
    admin,
  };
}

// Prova de administrador: HMAC-SHA256(código de admin, "comunicador/admin/CÓDIGO"), 32 primeiros hex.
function adminProof(code) {
  return crypto.createHmac('sha256', ADMIN_CODE).update(`comunicador/admin/${code}`).digest('hex').slice(0, 32);
}

function talkingNow(m, now) {
  return m.talking && (now - m.lastAudio < 1500 || now - m.talkSince < 1500);
}

function rosterPacket(room, yourId, now) {
  const parts = [];
  const list = [...room.members.values()].sort((a, b) => a.id - b.id).slice(0, 60);
  for (const m of list) {
    const name = cut(m.name, 20);
    const head = Buffer.alloc(4);
    head.writeUInt16LE(m.id, 0);
    head[2] = (talkingNow(m, now) ? 1 : 0) | (m.priority ? 2 : 0);
    head[3] = name.length;
    parts.push(head, name);
  }
  const top = Buffer.alloc(4);
  top[0] = ROSTER;
  top.writeUInt16LE(yourId, 1);
  top[3] = list.length;
  return Buffer.concat([top, ...parts]);
}

function sendTo(m, buf) {
  sock.send(buf, m.port, m.address);
}

function broadcastRoster(room) {
  const now = Date.now();
  for (const m of room.members.values()) sendTo(m, rosterPacket(room, m.id, now));
}

function removeMember(key) {
  const entry = byAddr.get(key);
  if (!entry) return;
  byAddr.delete(key);
  entry.room.members.delete(key);
  if (entry.room.members.size === 0) rooms.delete(entry.room.code);
  else broadcastRoster(entry.room);
}

sock.on('message', (b, rinfo) => {
  if (b.length < 1 || b.length > 1400) return;
  const key = keyOf(rinfo);
  const now = Date.now();
  const entry = byAddr.get(key);

  switch (b[0]) {
    case HELLO: {
      const h = parseHello(b);
      if (!h || !CODE_RE.test(h.code)) return;
      // Senha do canal: a primeira pessoa define; as outras precisam da mesma (vem no convite).
      const saved = keys.get(h.code);
      // Canal novo: com ADMIN_CODE configurado, só quem tem o código de administrador cria.
      if (!saved && ADMIN_CODE && h.admin !== adminProof(h.code)) {
        sock.send(Buffer.from([ERROR, ERR_NEED_ADMIN]), rinfo.port, rinfo.address);
        return;
      }
      if (saved && saved.key !== h.key) {
        sock.send(Buffer.from([ERROR, ERR_WRONG_KEY]), rinfo.port, rinfo.address);
        return;
      }
      keys.set(h.code, { key: saved ? saved.key : h.key, last: now });
      if (entry && entry.room.code !== h.code) removeMember(key); // trocou de canal
      let room = rooms.get(h.code);
      if (!room) {
        if (rooms.size >= MAX_ROOMS) return;
        room = { code: h.code, members: new Map(), nextId: 2 };
        rooms.set(h.code, room);
      }
      let m = room.members.get(key);
      let changed = false;
      if (!m) {
        if (room.members.size >= MAX_PER_ROOM) return;
        m = { id: room.nextId, address: rinfo.address, port: rinfo.port, name: h.name, priority: h.priority,
              lastSeen: now, lastAudio: 0, talking: false, talkSince: 0 };
        room.nextId = room.nextId >= 65000 ? 2 : room.nextId + 1;
        room.members.set(key, m);
        byAddr.set(key, { room, m });
        changed = true;
      } else if (m.name !== h.name || m.priority !== h.priority) {
        m.name = h.name;
        m.priority = h.priority;
        changed = true;
      }
      m.lastSeen = now;
      if (changed) broadcastRoster(room);
      else sendTo(m, rosterPacket(room, m.id, now)); // resposta ao "estou aqui"
      return;
    }
    case TALK: {
      if (!entry) return;
      const m = entry.m;
      m.lastSeen = now;
      m.talking = b.length > 1 && b[1] !== 0;
      if (m.talking) m.talkSince = now;
      broadcastRoster(entry.room);
      return;
    }
    case AUDIO: {
      if (!entry || b.length <= 6) return;
      const m = entry.m;
      m.lastSeen = now;
      m.lastAudio = now;
      const out = Buffer.from(b);
      out.writeUInt16LE(m.id, 1);
      for (const other of entry.room.members.values()) if (other !== m) sendTo(other, out);
      return;
    }
    case BYE:
      removeMember(key);
      return;
  }
});

// Limpeza: quem sumiu sai do canal; fala "presa" é encerrada.
setInterval(() => {
  const now = Date.now();
  for (const [key, { m, room }] of byAddr) {
    if (now - m.lastSeen > MEMBER_TIMEOUT) removeMember(key);
    else if (m.talking && !talkingNow(m, now)) { m.talking = false; broadcastRoster(room); }
  }
  for (const [code, k] of keys) if (!rooms.has(code) && now - k.last > KEY_TTL) keys.delete(code);
}, 2000);

sock.on('error', (e) => { console.error('Erro na porta de voz:', e.message); process.exit(1); });
sock.bind(PORT, () => console.log(`Voz (UDP) na porta ${PORT}`));

// ---- Página de convite e verificação ----
http.createServer((req, res) => {
  const path = (req.url || '/').split('?')[0];
  const query = new URLSearchParams((req.url || '').split('?')[1] || '');
  const m = path.match(/^\/c\/([A-Za-z0-9]{4,12})$/);
  if (m) {
    const host = PUBLIC_HOST || (req.headers.host || '').split(':')[0];
    const origin = `http://${host}:${HTTP_PORT}`;
    const html = invitePage(m[1].toUpperCase(), `udp://${host}:${PORT}`, APP_URL, (query.get('k') || '').slice(0, 40), origin);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(html);
  }
  // Imagens da marca (prévia do WhatsApp, ícone, favicon)
  const asset = { '/og-imagem.png': 'og-imagem.png', '/icone-192.png': 'icone-192.png', '/favicon.ico': 'favicon-32.png' }[path];
  if (asset) {
    try {
      const data = require('fs').readFileSync(require('path').join(__dirname, 'publico', asset));
      res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' });
      return res.end(data);
    } catch { /* cai no 404 */ }
  }
  if (path === '/privacidade') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(privacyPage());
  }
  if (path === '/') {
    let people = 0;
    for (const r of rooms.values()) people += r.members.size;
    // Página principal: apresenta o app; no rodapé, o estado do servidor.
    const host = PUBLIC_HOST || (req.headers.host || '').split(':')[0];
    const html = homePage(APP_URL, `http://${host}:${HTTP_PORT}`,
      `Servidor no ar. Canais ativos: ${rooms.size}. Pessoas: ${people}.`);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(html);
  }
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Não encontrado\n');
}).listen(HTTP_PORT, () => console.log(`Convites (HTTP) na porta ${HTTP_PORT}`));
