// Testes do Worker: sobe o `wrangler dev` localmente e simula celulares por WebSocket.
//   cd cloudflare && node --test teste.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ADMIN = 'segredo-admin';
const PORT = 47000 + Math.floor(Math.random() * 2000);
const BASE = `http://127.0.0.1:${PORT}`;
const CODE = 'T' + crypto.randomBytes(3).toString('hex').toUpperCase();
let proc;

const enc = new TextEncoder();
const field = (s) => { const b = enc.encode(s); return [b.length, ...b]; };
const hello = (name, code, key, admin = '') =>
  new Uint8Array([1, ...field(name), ...field(code), ...field(key), 0, ...(admin ? field(admin) : [])]);
const adminProof = (code, secret = ADMIN) =>
  crypto.createHmac('sha256', secret).update(`comunicador/admin/${code}`).digest('hex').slice(0, 32);

function client(code) {
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws/${code}`);
  ws.binaryType = 'arraybuffer';
  const inbox = [], waiters = [];
  ws.onmessage = (e) => { const m = new Uint8Array(e.data); const w = waiters.shift(); if (w) w(m); else inbox.push(m); };
  const open = new Promise((r, j) => { ws.onopen = r; ws.onerror = () => j(new Error('WebSocket falhou')); });
  return {
    send: async (b) => { await open; ws.send(b); },
    next: (ms = 4000) => new Promise((res, rej) => {
      if (inbox.length) return res(inbox.shift());
      const t = setTimeout(() => rej(new Error('nada chegou')), ms);
      waiters.push((m) => { clearTimeout(t); res(m); });
    }),
    // Próxima mensagem de um tipo (ignora listas de pessoas e de WebRTC no meio).
    async nextOf(type) { for (;;) { const m = await this.next(); if (m[0] === type) return m; } },
    close: () => ws.close(),
  };
}

before(async () => {
  const dir = fileURLToPath(new URL('.', import.meta.url));
  proc = spawn('npx', ['wrangler', 'dev', '--port', String(PORT), '--ip', '127.0.0.1', '--var', `ADMIN_CODE:${ADMIN}`],
    { cwd: dir, shell: true, stdio: 'ignore' });
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(BASE + '/')).ok) return; } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('wrangler dev não subiu');
});
after(() => {
  if (!proc) return;
  if (process.platform === 'win32') spawn('taskkill', ['/pid', String(proc.pid), '/t', '/f']);
  else proc.kill();
});

test('canal novo exige código de administrador', async () => {
  const a = client(CODE);
  await a.send(hello('Sem admin', CODE, 'k'));
  assert.deepEqual([...(await a.nextOf(8))], [8, 2]);
  a.close();
});

test('entrar, senha errada, áudio repassado e WebRTC protegido', async () => {
  const a = client(CODE), b = client(CODE), c = client(CODE);
  await a.send(hello('Direção', CODE, 'prova', adminProof(CODE)));
  const r = await a.nextOf(3);
  assert.equal(r[3], 1);

  await b.send(hello('Câmera', CODE, 'prova'));
  assert.equal((await b.nextOf(3))[3], 2, 'duas pessoas');

  await c.send(hello('Intruso', CODE, 'errada'));
  assert.deepEqual([...(await c.nextOf(8))], [8, 1]);

  const payload = crypto.randomBytes(30);
  await a.send(new Uint8Array([2, 0, 0, 1, 0, 0x82, ...payload]));
  const got = await b.nextOf(2);
  assert.equal(got[5], 0x82);
  assert.deepEqual([...got.subarray(6)], [...payload]);

  // Rotas do WebRTC: senha errada barrada; com a senha certa passa (aqui sem SFU configurado: 501).
  const errada = await fetch(`${BASE}/rtc/${CODE}/session`, { method: 'POST', headers: { 'X-Chave': 'errada' }, body: '{}' });
  assert.equal(errada.status, 403);
  const certa = await fetch(`${BASE}/rtc/${CODE}/session`, { method: 'POST', headers: { 'X-Chave': 'prova' }, body: '{}' });
  assert.notEqual(certa.status, 403);
  a.close(); b.close(); c.close();
});

test('páginas', async () => {
  assert.match(await (await fetch(BASE + '/')).text(), /Comunicador/);
  const inv = await (await fetch(`${BASE}/c/${CODE}?k=abc&voz=webrtc`)).text();
  assert.match(inv, /s=webrtc%3A%2F%2F/);
  assert.equal((await fetch(BASE + '/og-imagem.png')).status, 200);
  assert.equal((await fetch(BASE + '/nada')).status, 404);
  assert.match(await (await fetch(BASE + '/privacidade')).text(), /não grava conversas/);
});
