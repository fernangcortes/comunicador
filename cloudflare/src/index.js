// Comunicador — central na Cloudflare.
// Cada canal é um Durable Object: um "pequeno servidor" que existe só enquanto
// alguém está no canal. Os celulares conectam por WebSocket seguro em /ws/CÓDIGO.
// O protocolo é o mesmo do servidor próprio e da central no celular.

import { DurableObject } from "cloudflare:workers";
import { invitePage, homePage, privacyPage } from "./convite.js";
import OG_IMAGEM from "./publico/og-imagem.png";
import ICONE_192 from "./publico/icone-192.png";
import FAVICON_32 from "./publico/favicon-32.png";

// Imagens da marca (prévia do WhatsApp, ícone, favicon)
const ASSETS = { "/og-imagem.png": OG_IMAGEM, "/icone-192.png": ICONE_192, "/favicon.ico": FAVICON_32 };

const HELLO = 1, AUDIO = 2, ROSTER = 3, BYE = 4, TALK = 5, RTC_PUB = 6, RTC_LIST = 7, ERROR = 8, RTC_UNPUB = 9;
const ERR_WRONG_KEY = 1, ERR_NEED_ADMIN = 2;
const CODE = "([A-Za-z0-9]{4,12})";
const MAX_PER_ROOM = 60;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    let m = url.pathname.match(new RegExp(`^/ws/${CODE}$`));
    if (m) {
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Esperado WebSocket", { status: 426 });
      }
      const stub = env.CANAL.get(env.CANAL.idFromName(m[1].toUpperCase()));
      return stub.fetch(request);
    }

    m = url.pathname.match(new RegExp(`^/rtc/${CODE}/(publish|session|subscribe|answer|close)$`));
    if (m) {
      if (request.method !== "POST") return json({ erro: "Use POST" }, 405);
      // Só usa o SFU quem tem a senha de um canal ativo.
      const stub = env.CANAL.get(env.CANAL.idFromName(m[1].toUpperCase()));
      const ok = await stub.fetch("https://canal/verify", { headers: { "X-Chave": request.headers.get("X-Chave") || "" } });
      if (ok.status !== 200) return json({ erro: "Senha do canal errada ou canal vazio" }, 403);
      return rtc(env, m[2], request);
    }

    m = url.pathname.match(new RegExp(`^/c/${CODE}$`));
    if (m) {
      const scheme = url.searchParams.get("voz") === "webrtc" ? "webrtc" : "wss";
      const key = (url.searchParams.get("k") || "").slice(0, 40);
      const html = invitePage(m[1].toUpperCase(), `${scheme}://${url.host}`, env.APP_URL || "", key, url.origin);
      return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8" } });
    }

    if (ASSETS[url.pathname]) {
      return new Response(ASSETS[url.pathname], { headers: { "Content-Type": "image/png", "Cache-Control": "public, max-age=86400" } });
    }

    if (url.pathname === "/privacidade") {
      return new Response(privacyPage(url.origin), { headers: { "Content-Type": "text/html; charset=utf-8" } });
    }
    if (url.pathname === "/") {
      // Página principal: apresenta o app e o link para baixar.
      return new Response(homePage(env.APP_URL || "", url.origin), { headers: { "Content-Type": "text/html; charset=utf-8" } });
    }
    return new Response("Não encontrado\n", { status: 404 });
  },
};

// ---------------------------------------------------------------------------
// Voz por WebRTC (Cloudflare Realtime SFU)
// O Worker guarda a chave do SFU e faz as chamadas em nome dos celulares.
// Segredos (wrangler secret put): SFU_APP_ID e SFU_TOKEN.
// ---------------------------------------------------------------------------

const SID_RE = /^[A-Za-z0-9_-]{8,128}$/;

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

async function rtc(env, action, request) {
  if (!env.SFU_APP_ID || !env.SFU_TOKEN) {
    return json({ erro: "WebRTC não configurado neste servidor (faltam SFU_APP_ID e SFU_TOKEN)" }, 501);
  }
  // trim(): um espaço ou quebra de linha colado junto com a chave faz o SFU recusar o pedido.
  const appId = env.SFU_APP_ID.trim();
  const token = env.SFU_TOKEN.trim();
  const base = `${env.SFU_BASE || "https://rtc.live.cloudflare.com"}/v1/apps/${appId}`;
  const call = async (method, path, body) => {
    // Sem corpo, não anuncia JSON: o SFU recusa (400) um pedido JSON vazio.
    const headers = { Authorization: `Bearer ${token}` };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const r = await fetch(base + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await r.text();
    let data = {};
    try { data = JSON.parse(text); } catch {}
    if (!r.ok || data.errorCode) {
      throw new Error(data.errorDescription || data.errorCode || `SFU respondeu ${r.status}: ${text.slice(0, 200)}` +
        (/^[0-9a-f]{32}$/.test(appId) ? "" : " (o SFU_APP_ID não parece um App ID: confira se não foi colado o App Secret)"));
    }
    return data;
  };

  let b;
  try { b = await request.json(); } catch { return json({ erro: "Pedido inválido" }, 400); }
  const badSession = (sid) => typeof sid !== "string" || !SID_RE.test(sid);

  try {
    switch (action) {
      case "publish": {
        if (typeof b.sdp !== "string" || b.sdp.length > 20000 || typeof b.mid !== "string") return json({ erro: "Pedido inválido" }, 400);
        const s = await call("POST", "/sessions/new");
        const r = await call("POST", `/sessions/${s.sessionId}/tracks/new`, {
          sessionDescription: { type: "offer", sdp: b.sdp },
          tracks: [{ location: "local", mid: b.mid, trackName: "voz" }],
        });
        const t = r.tracks?.[0];
        if (t?.errorCode) throw new Error(t.errorDescription || t.errorCode);
        return json({ sessionId: s.sessionId, sdp: r.sessionDescription.sdp });
      }
      case "session": {
        const s = await call("POST", "/sessions/new");
        return json({ sessionId: s.sessionId });
      }
      case "subscribe": {
        if (badSession(b.sessionId) || !Array.isArray(b.tracks) || b.tracks.length === 0 || b.tracks.length > 60) {
          return json({ erro: "Pedido inválido" }, 400);
        }
        const wanted = b.tracks
          .filter((t) => !badSession(t.sessionId) && typeof t.trackName === "string")
          .map((t) => ({ location: "remote", sessionId: t.sessionId, trackName: t.trackName }));
        const r = await call("POST", `/sessions/${b.sessionId}/tracks/new`, { tracks: wanted });
        // A resposta vem na mesma ordem do pedido; devolve cada trilha com o seu "mid".
        const tracks = wanted.map((t, i) => {
          const res = r.tracks?.[i] || {};
          const out = { sessionId: t.sessionId, trackName: t.trackName };
          if (res.mid) out.mid = res.mid;
          if (res.errorCode) out.errorCode = res.errorCode;
          return out;
        });
        return json({
          requiresImmediateRenegotiation: !!r.requiresImmediateRenegotiation,
          sdp: r.sessionDescription?.sdp || "",
          tracks,
        });
      }
      case "answer": {
        if (badSession(b.sessionId) || typeof b.sdp !== "string" || b.sdp.length > 40000) return json({ erro: "Pedido inválido" }, 400);
        await call("PUT", `/sessions/${b.sessionId}/renegotiate`, { sessionDescription: { type: "answer", sdp: b.sdp } });
        return json({ ok: true });
      }
      case "close": {
        if (badSession(b.sessionId) || !Array.isArray(b.mids) || b.mids.length === 0 || b.mids.length > 60) {
          return json({ erro: "Pedido inválido" }, 400);
        }
        await call("PUT", `/sessions/${b.sessionId}/tracks/close`, { tracks: b.mids.map((mid) => ({ mid: String(mid) })), force: true });
        return json({ ok: true });
      }
    }
  } catch (e) {
    return json({ erro: e.message || "Falha no SFU" }, 502);
  }
  return json({ erro: "Não encontrado" }, 404);
}

// Prova de administrador: HMAC-SHA256(código de admin, "comunicador/admin/CÓDIGO"), 32 primeiros hex.
async function adminProof(adminCode, code) {
  const k = await crypto.subtle.importKey("raw", encoder.encode(adminCode), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", k, encoder.encode(`comunicador/admin/${code}`)));
  return [...sig].map((x) => x.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

const decoder = new TextDecoder();
const encoder = new TextEncoder();

function cut(str, maxBytes) {
  let s = str;
  while (encoder.encode(s).length > maxBytes) s = s.slice(0, -1);
  return encoder.encode(s);
}

export class Canal extends DurableObject {
  // Com a "hibernação" de WebSocket, o objeto sai da memória quando ninguém fala
  // e a Cloudflare não cobra esse tempo parado. Por isso o estado de cada pessoa
  // fica guardado no próprio WebSocket (serializeAttachment), não em variáveis.

  async fetch(request) {
    // Pergunta do Worker: esta senha vale para este canal, e tem gente nele?
    if (new URL(request.url).pathname === "/verify") {
      const saved = await this.ctx.storage.get("key");
      const someone = this.ctx.getWebSockets().some((w) => w.deserializeAttachment()?.joined);
      const ok = saved !== undefined && saved === (request.headers.get("X-Chave") || "") && someone;
      return new Response(null, { status: ok ? 200 : 403 });
    }
    const sockets = this.ctx.getWebSockets();
    if (sockets.length >= MAX_PER_ROOM) return new Response("Canal cheio", { status: 429 });
    const used = new Set(sockets.map((w) => w.deserializeAttachment()?.id));
    let id = 2;
    while (used.has(id)) id++;

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    const code = new URL(request.url).pathname.split("/").pop().toUpperCase(); // /ws/CÓDIGO
    server.serializeAttachment({ id, name: "", talking: false, joined: false, code });
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message === "string") return;
    const b = new Uint8Array(message);
    if (b.length < 1) return;
    const me = ws.deserializeAttachment() || {};

    switch (b[0]) {
      case HELLO: {
        // [1][tam][nome][tam][código][tam][prova de entrada][opções][tam][prova de admin]
        // A prova de entrada sai da senha do canal, que nunca viaja; o áudio vai cifrado
        // de ponta a ponta e só é repassado. Detalhes em Protocol.kt e Seguranca.kt.
        const fields = [];
        let i = 1;
        for (let f = 0; f < 3 && i < b.length; f++) {
          const n = Math.min(b[i], b.length - i - 1);
          fields.push(decoder.decode(b.subarray(i + 1, i + 1 + n)).trim());
          i += 1 + n;
        }
        const flags = i < b.length ? b[i] : 0;
        i += 1;
        const admin = i < b.length ? decoder.decode(b.subarray(i + 1, i + 1 + Math.min(b[i], b.length - i - 1))) : "";
        const key = fields[2] || "";
        // Senha do canal: a primeira pessoa define; quem chega depois precisa da mesma.
        const saved = await this.ctx.storage.get("key");
        if (saved === undefined) {
          // Canal novo: com o segredo ADMIN_CODE configurado, só quem tem o código de administrador cria.
          const code = me.code || "";
          if (this.env.ADMIN_CODE && admin !== (await adminProof(this.env.ADMIN_CODE.trim(), code))) {
            try { ws.send(new Uint8Array([ERROR, ERR_NEED_ADMIN])); } catch {}
            return;
          }
          await this.ctx.storage.put("key", key);
        }
        else if (saved !== key) {
          try { ws.send(new Uint8Array([ERROR, ERR_WRONG_KEY])); } catch {}
          return;
        }
        me.name = fields[0] || "Sem nome";
        me.priority = (flags & 2) !== 0;
        me.joined = true;
        ws.serializeAttachment(me);
        this.broadcastRoster();
        this.broadcastRtc();
        break;
      }
      case TALK: {
        me.talking = b.length > 1 && b[1] !== 0;
        ws.serializeAttachment(me);
        this.broadcastRoster();
        break;
      }
      case AUDIO: {
        if (!me.joined || b.length <= 6) return;
        const out = b.slice();
        out[1] = me.id & 0xff;
        out[2] = (me.id >> 8) & 0xff;
        for (const other of this.ctx.getWebSockets()) {
          if (other !== ws && other.deserializeAttachment()?.joined) {
            try { other.send(out); } catch {}
          }
        }
        break;
      }
      case RTC_PUB: {
        // Modo WebRTC: a pessoa avisa onde a voz dela está no SFU.
        let i = 1;
        const sl = b[i++] ?? 0;
        const sid = decoder.decode(b.subarray(i, i + sl)); i += sl;
        const tl = b[i++] ?? 0;
        const track = decoder.decode(b.subarray(i, i + tl));
        if (!SID_RE.test(sid) || !track || track.length > 60) return;
        me.pub = { s: sid, t: track };
        ws.serializeAttachment(me);
        this.broadcastRtc();
        break;
      }
      case RTC_UNPUB: {
        // A pessoa caiu para a reserva: a voz dela agora vem pelo WebSocket.
        if (!me.pub) return;
        delete me.pub;
        ws.serializeAttachment(me);
        this.broadcastRtc(undefined, true);
        break;
      }
      case BYE:
        try { ws.close(1000, "tchau"); } catch {}
        this.broadcastRoster(ws);
        break;
    }
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch {}
    this.broadcastRoster(ws);
    this.broadcastRtc(ws);
  }

  async webSocketError(ws) {
    this.broadcastRoster(ws);
    this.broadcastRtc(ws);
  }

  /** Lista "onde ouvir cada pessoa" no modo WebRTC. Só é enviada se alguém publicou. */
  broadcastRtc(leaving, force = false) {
    const sockets = this.ctx.getWebSockets().filter((w) => w !== leaving);
    const pubs = sockets
      .map((w) => w.deserializeAttachment())
      .filter((a) => a && a.joined && a.pub)
      .slice(0, 60);
    const parts = [];
    for (const a of pubs) {
      const sid = encoder.encode(a.pub.s);
      const tr = encoder.encode(a.pub.t);
      parts.push(new Uint8Array([a.id & 0xff, (a.id >> 8) & 0xff, sid.length]), sid, new Uint8Array([tr.length]), tr);
    }
    const size = 2 + parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(size);
    out[0] = RTC_LIST;
    out[1] = pubs.length;
    let i = 2;
    for (const p of parts) { out.set(p, i); i += p.length; }
    let leavingPub = false;
    try { leavingPub = !!leaving?.deserializeAttachment()?.pub; } catch {}
    if (pubs.length === 0 && !leavingPub && !force) return; // canal sem WebRTC: não manda nada
    for (const w of sockets) {
      const me = w.deserializeAttachment();
      if (!me || !me.joined) continue;
      try { w.send(out); } catch {}
    }
  }

  broadcastRoster(leaving) {
    const sockets = this.ctx.getWebSockets().filter((w) => w !== leaving);
    const people = sockets
      .map((w) => w.deserializeAttachment())
      .filter((a) => a && a.joined)
      .sort((x, y) => x.id - y.id)
      .slice(0, 60);
    for (const w of sockets) {
      const me = w.deserializeAttachment();
      if (!me || !me.joined) continue;
      try { w.send(rosterPacket(me.id, people)); } catch {}
    }
  }
}

function rosterPacket(yourId, people) {
  const parts = [];
  let size = 4;
  for (const p of people) {
    const name = cut(p.name, 20);
    parts.push([p, name]);
    size += 4 + name.length;
  }
  const out = new Uint8Array(size);
  out[0] = ROSTER;
  out[1] = yourId & 0xff;
  out[2] = (yourId >> 8) & 0xff;
  out[3] = people.length;
  let i = 4;
  for (const [p, name] of parts) {
    out[i++] = p.id & 0xff;
    out[i++] = (p.id >> 8) & 0xff;
    out[i++] = (p.talking ? 1 : 0) | (p.priority ? 2 : 0);
    out[i++] = name.length;
    out.set(name, i);
    i += name.length;
  }
  return out;
}
