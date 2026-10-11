// Testes do servidor próprio: sobe o servidor.js e simula celulares por UDP.
//   node --test servidor/teste.mjs
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import dgram from 'node:dgram';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ADMIN = 'segredo-admin';
const PORT = 41000 + Math.floor(Math.random() * 5000);
const HTTP_PORT = PORT + 1;
const CODE = 'TESTE1';
let proc;

// Mesmo formato do Protocol.kt
function field(s) { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([b.length]), b]); }
function hello(name, code, key, admin = '') {
  const parts = [Buffer.from([1]), field(name), field(code), field(key), Buffer.from([0])];
  if (admin) parts.push(field(admin));
  return Buffer.concat(parts);
}
const adminProof = (code, secret = ADMIN) =>
  crypto.createHmac('sha256', secret).update(`comunicador/admin/${code}`).digest('hex').slice(0, 32);

function client() {
  const s = dgram.createSocket('udp4');
  const inbox = [];
  const waiters = [];
  s.on('message', (m) => { const w = waiters.shift(); if (w) w(m); else inbox.push(m); });
  return {
    send: (b) => new Promise((r) => s.send(b, PORT, '127.0.0.1', r)),
    next: (ms = 2000) => new Promise((res, rej) => {
      if (inbox.length) return res(inbox.shift());
      const t = setTimeout(() => rej(new Error('nada chegou')), ms);
      waiters.push((m) => { clearTimeout(t); res(m); });
    }),
    close: () => s.close(),
  };
}

before(async () => {
  proc = spawn(process.execPath, [fileURLToPath(new URL('./servidor.js', import.meta.url))], {
    env: { ...process.env, PORT: String(PORT), HTTP_PORT: String(HTTP_PORT), ADMIN_CODE: ADMIN, APP_URL: 'https://exemplo/app.apk' },
    stdio: 'ignore',
  });
  await new Promise((r) => setTimeout(r, 600));
});
after(() => proc?.kill());

test('canal novo exige código de administrador', async () => {
  const a = client();
  await a.send(hello('Sem admin', CODE, 'k1'));
  assert.deepEqual([...(await a.next())], [8, 2]);
  await a.send(hello('Admin errado', CODE, 'k1', adminProof(CODE, 'outro')));
  assert.deepEqual([...(await a.next())], [8, 2]);
  a.close();
});

test('entrar, senha errada, lista de pessoas e áudio repassado', async () => {
  const a = client(), b = client(), c = client();
  await a.send(hello('Direção', CODE, 'prova-certa', adminProof(CODE)));
  const r1 = await a.next();
  assert.equal(r1[0], 3); // ROSTER
  assert.equal(r1.readUInt16LE(1), 2);

  await b.send(hello('Câmera', CODE, 'prova-certa')); // convidado: sem admin
  const rb = await b.next();
  assert.equal(rb[0], 3);
  assert.equal(rb[3], 2, 'duas pessoas no canal');
  await a.next(); // a recebe a lista nova

  await c.send(hello('Intruso', CODE, 'prova-errada'));
  assert.deepEqual([...(await c.next())], [8, 1]);

  // Áudio (cifrado ou não, o servidor só repassa): troca o id e mantém o resto intacto.
  const payload = crypto.randomBytes(40);
  await a.send(Buffer.concat([Buffer.from([2, 0, 0, 5, 0, 0x82]), payload]));
  let got;
  do got = await b.next(); while (got[0] !== 2);
  assert.equal(got.readUInt16LE(1), 2, 'id de quem falou');
  assert.equal(got[5], 0x82);
  assert.deepEqual(got.subarray(6), payload);
  a.close(); b.close(); c.close();
});

test('páginas de convite e principal', async () => {
  const home = await (await fetch(`http://127.0.0.1:${HTTP_PORT}/`)).text();
  assert.match(home, /Comunicador/);
  assert.match(home, /exemplo\/app\.apk/);
  const inv = await (await fetch(`http://127.0.0.1:${HTTP_PORT}/c/${CODE}?k=abc`)).text();
  assert.match(inv, /comunicador:\/\/entrar\?s=/);
  assert.match(inv, /k=abc/);
  const priv = await fetch(`http://127.0.0.1:${HTTP_PORT}/privacidade`);
  assert.equal(priv.status, 200);
  assert.match(await priv.text(), /não grava conversas/);
});
