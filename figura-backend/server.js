/**
 * Minimal Figura Backend v2 (Node.js)
 *
 * Mirrors the HTTP + WS API shapes consumed by `org.figuramc.figura.backend2.*`.
 *
 * Expected by client:
 * - HTTPS base: https://<host>[:port]/api
 * - WSS base:   wss://<host>[:port]/ws
 *
 * For local development you can run HTTP/WS (no TLS), but Figura's client code
 * hardcodes https/wss. To actually connect the mod to this server you must run
 * with a trusted TLS certificate (e.g. Let's Encrypt) or behind a TLS proxy and
 * set Configs.SERVER_IP accordingly.
 *
 * Env:
 * - HOST (default 0.0.0.0)
 * - PORT (default 443 if TLS enabled, else 8080)
 * - DATA_DIR (default ./figura-backend-data)
 * - SSL_KEY / SSL_CERT (enable HTTPS/WSS)
 * - SKIP_MOJANG_VERIFY=1 (dev mode: skip sessionserver hasJoined check)
 * - TOKEN_TTL_HOURS (default 24)
 * - ADMIN_KEY (enables POST /admin/toast and /admin/chat, sent as "admin-key" header)
 * - LOG_REQUESTS (default 1; 0 = off, all = also log avatar lookups/downloads)
 * - AVATAR_CACHE_MB (default 64; in-memory avatar cache size, 0 = off)
 *
 * Middleware (rate limits, real client IP, logging, headers, avatar cache) lives in ./middleware.
 *
 * Broadcasts (also available as console commands, type "help"):
 *   POST /admin/toast {"title": "...", "message": "...", "type": "default|warning|error|cheese", "uuids": [...]}
 *   POST /admin/chat  {"message": "text or JSON chat component", "uuids": [...]}
 *   GET  /admin/online
 * "uuids" is optional; without it the message goes to every connected player.
 */

const http = require("http");
const https = require("https");
const crypto = require("crypto");
const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const { WebSocketServer, WebSocket } = require("ws");
const {
  compose,
  clientIp,
  requestLogger,
  rateLimit,
  securityHeaders,
  createAvatarCache,
} = require("./middleware");
const { resolveClientIp } = require("./middleware/clientIp");

const HOST = process.env.HOST || "0.0.0.0";
const TLS_ENABLED = Boolean(process.env.SSL_KEY && process.env.SSL_CERT);
const PORT = Number(process.env.PORT || (TLS_ENABLED ? 443 : 8080));
const DATA_DIR = path.resolve(process.env.DATA_DIR || "./figura-backend-data");
const SKIP_MOJANG_VERIFY = process.env.SKIP_MOJANG_VERIFY === "1";
const ADMIN_KEY = process.env.ADMIN_KEY || "";
const LOG_REQUESTS = process.env.LOG_REQUESTS || "1";
const AVATAR_CACHE_MB = Number(process.env.AVATAR_CACHE_MB || 64);

const TOKEN_TTL_MS = Number(process.env.TOKEN_TTL_HOURS || 24) * 60 * 60 * 1000;
const PENDING_AUTH_TTL_MS = 2 * 60 * 1000;
const MAX_PENDING_AUTH = 10_000;
const MOJANG_TIMEOUT_MS = 10_000;
const WS_HEARTBEAT_MS = 30_000;
const WS_AUTH_TIMEOUT_MS = 10_000;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Avatar ids end up in file names, so only allow a safe charset (no dots / slashes).
const AVATAR_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

// ---- storage helpers ----

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function readJsonSync(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return fallback;
  }
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await fsp.readFile(file, "utf8"));
  } catch {
    return fallback;
  }
}

// Writes are serialized per file and done via temp file + rename, so a crash
// mid-write never leaves a truncated file behind.
const writeQueues = new Map();

function writeFileAtomic(file, data) {
  const prev = writeQueues.get(file) || Promise.resolve();
  const next = prev
    .catch(() => {})
    .then(async () => {
      await fsp.mkdir(path.dirname(file), { recursive: true });
      const tmp = `${file}.${crypto.randomBytes(6).toString("hex")}.tmp`;
      await fsp.writeFile(tmp, data);
      try {
        await fsp.rename(tmp, file);
      } catch (e) {
        await fsp.unlink(tmp).catch(() => {});
        throw e;
      }
    });
  writeQueues.set(file, next);
  next.finally(() => {
    if (writeQueues.get(file) === next) writeQueues.delete(file);
  }).catch(() => {});
  return next;
}

function writeJson(file, obj) {
  return writeFileAtomic(file, JSON.stringify(obj));
}

function sha256Hex(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return null;
  }
}

function uuidNoDashesToUuid(id32) {
  // Mojang sessionserver returns id as 32 hex chars (no dashes).
  if (typeof id32 !== "string" || !/^[0-9a-fA-F]{32}$/.test(id32)) return null;
  const s = id32.toLowerCase();
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}

function uuidToBytes(uuid) {
  // uuid string: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
  const hex = uuid.replace(/-/g, "").toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(hex)) throw new Error("Invalid UUID");
  return Buffer.from(hex, "hex");
}

function bytesToUuid(buf16) {
  const hex = Buffer.from(buf16).toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// ---- auth/session ----

// serverId -> { username, createdAt }
// also written to DATA_DIR/pending, for when /auth/id and /auth/verify are served by different processes
const pendingAuth = new Map();
const pendingAuthFile = (serverId) => path.join(DATA_DIR, "pending", `${serverId}.json`);
// sha256(token) -> { uuid, username, createdAt }
// Only token hashes are kept, so the tokens file is useless if leaked.
const tokens = new Map();
const tokensFile = () => path.join(DATA_DIR, "tokens.json");

function loadTokens() {
  const obj = readJsonSync(tokensFile(), {});
  const now = Date.now();
  for (const [k, v] of Object.entries(obj)) {
    if (v && v.uuid && now - v.createdAt < TOKEN_TTL_MS) tokens.set(k, v);
  }
}

// Another process (or an earlier run) may have written tokens we don't know yet, so merge the
// file into memory before saving, and re-read it when a token is unknown.
// Expired tokens are dropped from both, so a merge never brings them back.
let tokensReloadedAt = 0;
function reloadTokens(force = false) {
  const now = Date.now();
  if (!force && now - tokensReloadedAt < 500) return;
  tokensReloadedAt = now;
  loadTokens();
}

let tokensSaveTimer = null;
function saveTokensNow() {
  reloadTokens(true);
  return writeJson(tokensFile(), Object.fromEntries(tokens)).catch((e) => console.error("[tokens] save failed:", e));
}
function scheduleTokensSave() {
  if (tokensSaveTimer) return;
  tokensSaveTimer = setTimeout(() => {
    tokensSaveTimer = null;
    saveTokensNow();
  }, 1000);
}

function getSession(token) {
  if (!token) return null;
  const key = sha256Hex(token);
  let s = tokens.get(key);
  if (!s) {
    reloadTokens();
    s = tokens.get(key);
  }
  if (!s) return null;
  if (Date.now() - s.createdAt > TOKEN_TTL_MS) {
    tokens.delete(key);
    scheduleTokensSave();
    return null;
  }
  return s;
}

// ---- avatar cache (see ./middleware) ----

const avatarCache = createAvatarCache({ maxBytes: AVATAR_CACHE_MB * 1024 * 1024 });

// saved before the token is handed out, so every process knows it by the time the client uses it
async function createToken(uuid, username) {
  const token = crypto.randomBytes(32).toString("base64url");
  tokens.set(sha256Hex(token), { uuid, username, createdAt: Date.now() });
  await saveTokensNow();
  return token;
}

setInterval(() => {
  const now = Date.now();
  for (const [k, v] of pendingAuth) if (now - v.createdAt > PENDING_AUTH_TTL_MS) pendingAuth.delete(k);
  fsp.readdir(path.join(DATA_DIR, "pending")).then(async (names) => {
    for (const name of names) {
      const file = path.join(DATA_DIR, "pending", name);
      const st = await fsp.stat(file).catch(() => null);
      if (st && now - st.mtimeMs > PENDING_AUTH_TTL_MS) await fsp.unlink(file).catch(() => {});
    }
  }).catch(() => {});
  let changed = false;
  for (const [k, v] of tokens) {
    if (now - v.createdAt > TOKEN_TTL_MS) {
      tokens.delete(k);
      changed = true;
    }
  }
  if (changed) scheduleTokensSave();
}, 60_000).unref();

// ---- persisted user data (all users are kept in memory) ----

// uuid -> user
const users = new Map();

function userFile(uuid) {
  return path.join(DATA_DIR, "users", uuid, "user.json");
}
function avatarFile(ownerUuid, avatarId) {
  return path.join(DATA_DIR, "avatars", ownerUuid, `${avatarId}.bin`);
}
function avatarMetaFile(ownerUuid, avatarId) {
  return path.join(DATA_DIR, "avatars", ownerUuid, `${avatarId}.json`);
}

function newUser(uuid) {
  return {
    uuid,
    equipped: [], // [{ owner, id, hash }]
    equippedBadges: {
      pride: [],
      special: [],
    },
    // optional: temp badge (client uses /temp_badges but doesn't fetch it here)
    tempBadge: null,
  };
}

function loadAllUsers() {
  const dir = path.join(DATA_DIR, "users");
  ensureDir(dir);
  for (const name of fs.readdirSync(dir)) {
    if (!UUID_RE.test(name)) continue;
    const u = readJsonSync(userFile(name), null);
    if (u) users.set(name.toLowerCase(), { ...newUser(name.toLowerCase()), ...u });
  }
}

function saveUser(user) {
  writeJson(userFile(user.uuid), user).catch((e) => console.error(`[users] save ${user.uuid} failed:`, e));
}

// user.json is the source of truth: another process (or a restart) may have changed it since we
// loaded it, and saving a stale copy would undo that change
function refreshUser(uuid) {
  const u = readJsonSync(userFile(uuid), null);
  if (u) users.set(uuid, { ...newUser(uuid), ...u });
  return users.get(uuid);
}

function getOrCreateUser(uuid, username) {
  let u = refreshUser(uuid);
  let dirty = false;
  if (!u) {
    u = newUser(uuid);
    users.set(uuid, u);
    dirty = true;
  }
  if (username && u.username !== username) {
    u.username = username;
    dirty = true;
  }
  if (dirty) saveUser(u);
  return u;
}

function getAuthToken(req) {
  const t = req.headers["token"];
  if (!t || typeof t !== "string") return null;
  return t;
}

function requireAuth(req, res) {
  const token = getAuthToken(req);
  if (!token) {
    sendText(res, 401, "Missing token");
    return null;
  }
  const session = getSession(token);
  if (!session) {
    sendText(res, 401, "Invalid token");
    return null;
  }
  return session;
}

// ---- request parsing / responses ----

function sendText(res, status, body, headers = {}) {
  if (res.headersSent || res.destroyed) return;
  const b = body == null ? "" : String(body);
  res.writeHead(status, {
    "content-type": "text/plain; charset=utf-8",
    "content-length": Buffer.byteLength(b),
    ...headers,
  });
  res.end(b);
}

function sendJson(res, status, obj, headers = {}) {
  if (res.headersSent || res.destroyed) return;
  const b = JSON.stringify(obj);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(b),
    ...headers,
  });
  res.end(b);
}

function sendError(res, e) {
  if (e && e.code === "TOO_LARGE") return sendText(res, 413, "Payload too large", { connection: "close" });
  console.error("[http] error:", e);
  sendText(res, 500, "Internal error");
}

// Bodies over the limit are drained (not buffered) so the client still receives
// the 413; anything beyond this hard cap gets the connection cut instead.
const DRAIN_CAP = 8 * 1024 * 1024;

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const tooLarge = () => Object.assign(new Error("Payload too large"), { code: "TOO_LARGE" });
    // Reject right away when the client announces an absurdly big body.
    const declared = Number(req.headers["content-length"]);
    if (Number.isFinite(declared) && declared > DRAIN_CAP) {
      reject(tooLarge());
      return;
    }
    const chunks = [];
    let size = 0;
    let over = false;
    let done = false;
    req.on("data", (c) => {
      if (done) return;
      size += c.length;
      if (size > DRAIN_CAP) {
        done = true;
        reject(tooLarge());
        return;
      }
      if (size > maxBytes) {
        over = true;
        chunks.length = 0;
      }
      if (!over) chunks.push(c);
    });
    req.on("end", () => {
      if (done) return;
      done = true;
      if (over) reject(tooLarge());
      else resolve(Buffer.concat(chunks));
    });
    req.on("error", (e) => {
      if (!done) reject(e);
    });
  });
}

// ---- Figura-ish defaults ----

const LIMITS = {
  rate: {
    upload: 2_000_000, // bytes/s-ish (client treats as token bucket; it's ok)
    download: 4_000_000,
    pingRate: 32, // pings per second per connection
    pingSize: 1024,
  },
  limits: {
    maxAvatarSize: 1200 * 1024,
  },
};

const VERSION = {
  release: "0.0.0-local",
  prerelease: "0.0.0-local",
};

const MOTD = JSON.stringify({
  text: "",
  extra: [
    { text: "Figura Backend By §9§lOffKunG_\n\n\n" },
    { text: "§e§lติดต่องานได้ที่ \n" },
    { text: "§dDiscord : §f@sakurajimamaioffkung \n" },
    { text: "§bGithub : §f@Yumeko-1234 \n\n" },
    {
      text: "§a§nคลิ้กลิ้งค์นี้เพิ้อโดเนท",
      underlined: true,
      clickEvent: { action: "open_url", value: "https://ezdn.app/offkunggames_" },
      hoverEvent: { action: "show_text", contents: { text: "คลิ๊กเพื่อเปิด Link" } }
    }
  ]
});

// ---- websocket: routing + subscriptions ----

// ownerUuid -> Set<ws>
const subsByTarget = new Map();

function addSubscription(ws, targetUuid) {
  let set = subsByTarget.get(targetUuid);
  if (!set) {
    set = new Set();
    subsByTarget.set(targetUuid, set);
  }
  set.add(ws);
  ws._figuraSubs.add(targetUuid);
}

function removeSubscription(ws, targetUuid) {
  const set = subsByTarget.get(targetUuid);
  if (set) {
    set.delete(ws);
    if (set.size === 0) subsByTarget.delete(targetUuid);
  }
  ws._figuraSubs.delete(targetUuid);
}

function clearSubscriptions(ws) {
  if (!ws._figuraSubs) return;
  for (const u of ws._figuraSubs) removeSubscription(ws, u);
}

function wsSendAuthOk(ws) {
  ws.send(Buffer.from([0])); // S2C AUTH
}

// upload + equip both fire an event, each one makes every subscriber reload the avatar,
// so coalesce events per uuid and send one after things settle
const EVENT_DEBOUNCE_MS = 1000;
const pendingEvents = new Map();

function wsSendEvent(targetUuid) {
  clearTimeout(pendingEvents.get(targetUuid));
  pendingEvents.set(targetUuid, setTimeout(() => {
    pendingEvents.delete(targetUuid);
    wsSendEventNow(targetUuid);
  }, EVENT_DEBOUNCE_MS));
}

function wsSendEventNow(targetUuid) {
  const set = subsByTarget.get(targetUuid);
  if (!set || set.size === 0) return;
  const msg = Buffer.concat([Buffer.from([2]), uuidToBytes(targetUuid)]); // S2C EVENT + uuid
  for (const ws of set) {
    if (ws.readyState === WebSocket.OPEN) ws.send(msg);
  }
}

function wsSendPing(fromUuid, id, syncByte, data, excludingWs) {
  const set = subsByTarget.get(fromUuid);
  if (!set || set.size === 0) return;
  const head = Buffer.alloc(1 + 16 + 4 + 1);
  head.writeUInt8(1, 0); // S2C PING
  uuidToBytes(fromUuid).copy(head, 1);
  head.writeInt32BE(id, 17);
  head.writeUInt8(syncByte ? 1 : 0, 21);
  const msg = Buffer.concat([head, data]);
  for (const ws of set) {
    if (ws === excludingWs) continue;
    if (ws.readyState === WebSocket.OPEN) ws.send(msg);
  }
}

// S2C NOTICE: 0 = ping too big, 1 = ping rate exceeded. Throttled to 1/s per connection.
function wsSendNotice(ws, type) {
  const now = Date.now();
  if (now - ws._figuraLastNotice < 1000) return;
  ws._figuraLastNotice = now;
  if (ws.readyState === WebSocket.OPEN) ws.send(Buffer.from([5, type]));
}

// ---- broadcasts ----

// Order matches FiguraToast.ToastType; the client indexes values() with this byte.
const TOAST_TYPES = ["default", "warning", "error", "cheese"];

// Sends to every authed connection, or only to those of the given player uuids.
function wsBroadcast(msg, uuids) {
  const only = Array.isArray(uuids) && uuids.length ? new Set(uuids.map((u) => String(u).toLowerCase())) : null;
  let sent = 0;
  for (const ws of wss.clients) {
    if (!ws._figuraAuthed || ws.readyState !== WebSocket.OPEN) continue;
    if (only && !only.has(ws._figuraUuid)) continue;
    ws.send(msg);
    sent++;
  }
  return sent;
}

// S2C TOAST: 3 + byte(type) + utf8("title\0message")
function broadcastToast(title, message, type, uuids) {
  let t = typeof type === "number" ? type : TOAST_TYPES.indexOf(String(type || "default").toLowerCase());
  if (!Number.isInteger(t) || t < 0 || t >= TOAST_TYPES.length) t = 0;
  const text = String(title || "") + (message ? `\0${message}` : "");
  return wsBroadcast(Buffer.concat([Buffer.from([3, t]), Buffer.from(text, "utf8")]), uuids);
}

// S2C CHAT: 4 + utf8(text or JSON chat component)
function broadcastChat(message, uuids) {
  const text = typeof message === "string" ? message : JSON.stringify(message);
  return wsBroadcast(Buffer.concat([Buffer.from([4]), Buffer.from(text, "utf8")]), uuids);
}

function onlinePlayers() {
  const list = [];
  for (const ws of wss.clients) {
    if (!ws._figuraAuthed) continue;
    const u = users.get(ws._figuraUuid);
    list.push({ uuid: ws._figuraUuid, username: (u && u.username) || null });
  }
  return list;
}

// token bucket: refills pingRate tokens per second, holds at most pingRate
function wsTakePingToken(ws) {
  const now = Date.now();
  const rate = LIMITS.rate.pingRate;
  ws._figuraPingTokens = Math.min(rate, ws._figuraPingTokens + ((now - ws._figuraPingRefill) / 1000) * rate);
  ws._figuraPingRefill = now;
  if (ws._figuraPingTokens < 1) return false;
  ws._figuraPingTokens -= 1;
  return true;
}

// ---- http routing ----

function parseUrl(req) {
  // full URL isn't available here; use dummy origin
  return new URL(req.url, "http://localhost");
}

function mojangHasJoined(username, serverId) {
  const u = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined");
  u.searchParams.set("username", username);
  u.searchParams.set("serverId", serverId);

  return new Promise((resolve, reject) => {
    const req = https.get(u, { timeout: MOJANG_TIMEOUT_MS }, (r) => {
      let data = "";
      r.setEncoding("utf8");
      r.on("data", (c) => (data += c));
      r.on("end", () => {
        if (r.statusCode !== 200) {
          resolve(null);
          return;
        }
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
      r.on("error", reject);
    });
    req.on("timeout", () => req.destroy(new Error("Mojang sessionserver timed out")));
    req.on("error", reject);
  });
}

async function handleAuthVerify(res, serverId, pending) {
  let profile = null;
  if (!SKIP_MOJANG_VERIFY) {
    profile = await mojangHasJoined(pending.username, serverId);
    if (!profile || !profile.id) return sendText(res, 401, "Not joined");
  } else {
    // Dev mode: synthesize a stable UUID from username
    const hash = crypto.createHash("sha256").update(`figura-dev:${pending.username}`).digest("hex").slice(0, 32);
    profile = { id: hash, name: pending.username };
  }

  const uuid = uuidNoDashesToUuid(profile.id);
  if (!uuid) return sendText(res, 500, "Invalid profile id");

  const username = profile.name || pending.username;
  const token = await createToken(uuid, username);
  getOrCreateUser(uuid, username);
  sendText(res, 200, token);
}

async function handleEquip(req, res, session) {
  const buf = await readBody(req, 256 * 1024);
  let arr;
  try {
    arr = JSON.parse(buf.toString("utf8"));
  } catch {
    return sendText(res, 400, "Invalid JSON");
  }
  if (!Array.isArray(arr)) return sendText(res, 400, "Expected array");

  const user = getOrCreateUser(session.uuid, session.username);
  const equipped = [];
  for (const item of arr.slice(0, 16)) {
    if (!item || typeof item !== "object") continue;
    const owner = String(item.owner || "").toLowerCase();
    const id = String(item.id || "");
    if (!UUID_RE.test(owner) || !AVATAR_ID_RE.test(id)) continue;
    const meta = await readJson(avatarMetaFile(owner, id), null);
    if (!meta || !meta.hash) continue;
    equipped.push({ owner, id, hash: meta.hash });
  }
  user.equipped = equipped;
  saveUser(user);

  // Notify subscribers that this player changed avatar state
  wsSendEvent(session.uuid);
  sendText(res, 200, "OK");
}

async function handleTempBadge(req, res, session) {
  const buf = await readBody(req, 16 * 1024);
  let parsed;
  try {
    parsed = JSON.parse(buf.toString("utf8").trim());
  } catch {
    return sendText(res, 400, "Invalid JSON");
  }
  const user = getOrCreateUser(session.uuid, session.username);
  user.tempBadge = parsed === null ? null : Number(parsed);
  saveUser(user);
  sendText(res, 200, "OK");
}

async function handleAvatarDownload(res, owner, id) {
  let buf;
  try {
    buf = await avatarCache.get(`${owner}/${id}`, () => fsp.readFile(avatarFile(owner, id)));
  } catch (e) {
    if (e.code === "ENOENT") return sendText(res, 404, "Avatar not found");
    throw e;
  }
  if (res.headersSent || res.destroyed) return;
  res.writeHead(200, {
    "content-type": "application/octet-stream",
    "content-length": buf.length,
  });
  res.end(buf);
}

async function handleAvatarDelete(res, session, id) {
  const owner = session.uuid;
  const user = getOrCreateUser(owner, session.username);
  const isThis = (e) => e && e.owner === owner && e.id === id;
  const wasEquipped = Array.isArray(user.equipped) && user.equipped.some(isThis);

  let existed = true;
  avatarCache.delete(`${owner}/${id}`);
  try {
    await fsp.unlink(avatarFile(owner, id));
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
    existed = false;
  }
  await fsp.unlink(avatarMetaFile(owner, id)).catch(() => {});

  // If the avatar file is already missing but the user still has it equipped,
  // treat DELETE as a successful cleanup.
  if (!existed && !wasEquipped) return sendText(res, 404, "Avatar not found");

  if (wasEquipped) {
    user.equipped = user.equipped.filter((e) => !isThis(e));
    saveUser(user);
  }
  wsSendEvent(owner);
  sendText(res, 200, "OK");
}

async function handleAvatarUpload(req, res, session, id) {
  const owner = session.uuid;
  const buf = await readBody(req, LIMITS.limits.maxAvatarSize);
  const hash = sha256Hex(buf);
  await writeFileAtomic(avatarFile(owner, id), buf);
  await writeJson(avatarMetaFile(owner, id), { owner, id, hash, size: buf.length, updatedAt: Date.now() });
  avatarCache.set(`${owner}/${id}`, buf);

  const user = getOrCreateUser(owner, session.username);
  if (!Array.isArray(user.equipped) || user.equipped.length === 0) {
    // If user doesn't have anything equipped yet, auto-equip this avatar as a convenience.
    user.equipped = [{ owner, id, hash }];
    saveUser(user);
  } else {
    // Keep the hash of an already equipped copy of this avatar up to date.
    const entry = user.equipped.find((e) => e && e.owner === owner && e.id === id);
    if (entry && entry.hash !== hash) {
      entry.hash = hash;
      saveUser(user);
    }
  }

  wsSendEvent(owner);
  sendText(res, 200, "OK");
}

function routeApi(req, res, url) {
  // everything below is relative to /api
  let p = url.pathname;
  if (p === "/api") {
    p = "/";
  } else if (p.startsWith("/api/")) {
    // strip "/api" prefix but keep the rest (including any extra slashes)
    p = p.slice(4); // removes "/api"
  } else {
    return sendText(res, 404, "Not found");
  }

  // Normalize multiple slashes (Figura sometimes calls "/api//auth/..." due to URI concatenation)
  p = p.replace(/\/+/g, "/");
  if (!p.startsWith("/")) p = "/" + p;

  // auth endpoints do NOT require token
  if (p === "/auth/id" && req.method === "GET") {
    const username = url.searchParams.get("username");
    if (!username || username.length > 32) return sendText(res, 400, "Invalid username");
    if (pendingAuth.size >= MAX_PENDING_AUTH) return sendText(res, 429, "Too many pending logins");
    const serverId = crypto.randomBytes(16).toString("hex");
    const pending = { username, createdAt: Date.now() };
    pendingAuth.set(serverId, pending);
    return writeJson(pendingAuthFile(serverId), pending)
      .then(() => sendText(res, 200, serverId))
      .catch((e) => {
        console.error("[auth] pending save failed:", e);
        pendingAuth.delete(serverId);
        sendText(res, 500, "Login failed");
      });
  }

  if (p === "/auth/verify" && req.method === "GET") {
    const serverId = url.searchParams.get("id");
    if (!serverId) return sendText(res, 400, "Missing id");
    if (!/^[0-9a-f]{32}$/.test(serverId)) return sendText(res, 400, "Unknown id");
    // the file is the authority, the process that deletes it claims the id (one-time use across processes)
    const file = pendingAuthFile(serverId);
    const pending = readJsonSync(file, null);
    pendingAuth.delete(serverId);
    if (!pending) return sendText(res, 400, "Unknown id");
    return fsp.unlink(file).then(
      () => {
        if (Date.now() - pending.createdAt > PENDING_AUTH_TTL_MS) return sendText(res, 401, "Expired");
        return handleAuthVerify(res, serverId, pending);
      },
      () => sendText(res, 400, "Unknown id")
    );
  }

  // assets (optional) - the client requests these without a token
  if (p.startsWith("/assets/") && req.method === "GET") {
    // For now serve empty resources list and 404 for actual assets.
    const parts = p.split("/").filter(Boolean); // ["assets", version, maybe resource]
    if (parts.length === 2) return sendText(res, 200, "");
    return sendText(res, 404, "Not found");
  }

  // require auth for everything else
  const session = requireAuth(req, res);
  if (!session) return;

  // checkAuth: GET /api or GET /api/
  if (p === "/" && req.method === "GET") return sendText(res, 200, "OK");

  if (p === "/version" && req.method === "GET") return sendJson(res, 200, VERSION);
  if (p === "/limits" && req.method === "GET") return sendJson(res, 200, LIMITS);
  // Client expects a JSON chat component string.
  if (p === "/motd" && req.method === "GET") return sendText(res, 200, MOTD, { "content-type": "application/json; charset=utf-8" });
  if (p === "/equip" && req.method === "POST") return handleEquip(req, res, session);
  if (p === "/temp_badges" && req.method === "POST") return handleTempBadge(req, res, session);

  const segs = p.split("/").filter(Boolean).map(safeDecode);
  if (segs.includes(null)) return sendText(res, 400, "Bad path");

  // user lookup: GET /api/<uuid>
  if (segs.length === 1 && UUID_RE.test(segs[0]) && req.method === "GET") {
    const uuid = segs[0].toLowerCase();
    // read from disk, the in-memory copy may be stale (see refreshUser)
    return readJson(userFile(uuid), null).then((saved) => {
      const u = saved ? { ...newUser(uuid), ...saved } : users.get(uuid);
      if (saved) users.set(uuid, u);
      // Shape expected by `NetworkStuff.getUserFromBackend`:
      // - equipped: [{ owner, id, hash }]
      // - equippedBadges: { pride: int[], special: int[] }
      sendJson(res, 200, {
        equipped: u ? u.equipped : [],
        equippedBadges: (u && u.equippedBadges) || { pride: [], special: [] },
      });
    });
  }

  // avatar download: GET /api/<ownerUuid>/<id>
  if (segs.length === 2 && req.method === "GET") {
    if (!UUID_RE.test(segs[0]) || !AVATAR_ID_RE.test(segs[1])) return sendText(res, 404, "Avatar not found");
    return handleAvatarDownload(res, segs[0].toLowerCase(), segs[1]);
  }

  // avatar upload/delete: PUT|DELETE /api/<id> (uses token to identify owner)
  if (segs.length === 1 && (req.method === "PUT" || req.method === "DELETE")) {
    const id = segs[0];
    if (!AVATAR_ID_RE.test(id)) return sendText(res, 400, "Invalid avatar id");
    return req.method === "DELETE" ? handleAvatarDelete(res, session, id) : handleAvatarUpload(req, res, session, id);
  }

  sendText(res, 404, "Not found");
}

function checkAdminKey(req) {
  const k = req.headers["admin-key"];
  if (!ADMIN_KEY || typeof k !== "string") return false;
  const a = Buffer.from(sha256Hex(k), "hex");
  const b = Buffer.from(sha256Hex(ADMIN_KEY), "hex");
  return crypto.timingSafeEqual(a, b);
}

async function routeAdmin(req, res, url) {
  if (!ADMIN_KEY) return sendText(res, 404, "Not found");
  if (!checkAdminKey(req)) return sendText(res, 401, "Invalid admin key");

  const p = url.pathname;
  if (p === "/admin/online" && req.method === "GET") {
    return sendJson(res, 200, onlinePlayers());
  }

  if (p === "/admin/stats" && req.method === "GET") {
    return sendJson(res, 200, {
      online: onlinePlayers().length,
      users: users.size,
      sessions: tokens.size,
      avatarCache: avatarCache.stats(),
    });
  }

  if ((p === "/admin/toast" || p === "/admin/chat") && req.method === "POST") {
    let body;
    try {
      body = JSON.parse((await readBody(req, 64 * 1024)).toString("utf8"));
    } catch (e) {
      if (e && e.code === "TOO_LARGE") throw e;
      return sendText(res, 400, "Invalid JSON");
    }
    if (!body || typeof body !== "object") return sendText(res, 400, "Expected object");
    if (body.uuids != null && !Array.isArray(body.uuids)) return sendText(res, 400, "uuids must be an array");

    if (p === "/admin/toast") {
      if (!body.title) return sendText(res, 400, "Missing title");
      return sendJson(res, 200, { sent: broadcastToast(body.title, body.message, body.type, body.uuids) });
    }
    if (body.message == null || body.message === "") return sendText(res, 400, "Missing message");
    return sendJson(res, 200, { sent: broadcastChat(body.message, body.uuids) });
  }

  sendText(res, 404, "Not found");
}

// ---- middleware ----

const isApi = (req) => req.url.startsWith("/api");
const apiPath = (req) => req.url.split("?")[0].replace(/\/+/g, "/");
// Per player lookups and avatar downloads happen constantly; only log them on errors
// unless LOG_REQUESTS=all.
const NOISY_RE = /^\/api\/[0-9a-f-]{36}(\/[^/]+)?$/i;

// a login is 2 auth requests and many players can share one ip (CGNAT, cafes), and they all
// reconnect at once after a restart; 10 per minute locked them out of the cloud for 5 minutes
const authLimiter = rateLimit({ name: "auth", capacity: 30, perSecond: 1, match: (req) => apiPath(req).startsWith("/api/auth/") });
const adminLimiter = rateLimit({ name: "admin", capacity: 30, perSecond: 1 / 2, match:(req) => req.url.startsWith("/admin/") });
const writeLimiter = rateLimit({ name: "write", capacity: 20, perSecond: 1 / 3, match: (req) => isApi(req) && req.method !== "GET" });
// joining a busy server looks up and downloads every player at once; a 429 there makes the
// client give up on that player, so these get their own, much larger bucket
const isLookup = (req) => req.method === "GET" && NOISY_RE.test(apiPath(req));
const lookupLimiter = rateLimit({ name: "lookup", capacity: 2000, perSecond: 100, match: isLookup });
const apiLimiter = rateLimit({ name: "api", capacity: 300, perSecond: 20, match: (req) => isApi(req) && !isLookup(req) });
const wsLimiter = rateLimit({ name: "ws", capacity: 30, perSecond: 1 / 2 });

const middlewares = [
  clientIp(),
  requestLogger({
    enabled: LOG_REQUESTS !== "0",
    skip: (req, res) =>
      LOG_REQUESTS !== "all" &&
      (req.url === "/health" || (req.method === "GET" && res.statusCode < 500 && NOISY_RE.test(apiPath(req)))),
  }),
  securityHeaders(),
  authLimiter,
  adminLimiter,
  writeLimiter,
  lookupLimiter,
  apiLimiter,
];

function handleRequest(req, res) {
  // Discard the rest of the request when we answered before reading it (e.g. 413).
  res.on("finish", () => {
    if (!req.complete) req.destroy();
  });
  try {
    const url = parseUrl(req);
    if (url.pathname === "/health" && req.method === "GET") {
      return sendJson(res, 200, { ok: true, tls: TLS_ENABLED, port: PORT });
    }
    if (url.pathname === "/ws") {
      // upgrade handled elsewhere
      return sendText(res, 426, "Upgrade Required");
    }
    if (url.pathname === "/api" || url.pathname.startsWith("/api/")) {
      const r = routeApi(req, res, url);
      if (r && typeof r.catch === "function") r.catch((e) => sendError(res, e));
      return;
    }
    if (url.pathname.startsWith("/admin/")) {
      return routeAdmin(req, res, url).catch((e) => sendError(res, e));
    }
    return sendText(res, 404, "Not found");
  } catch (e) {
    sendError(res, e);
  }
}

const requestListener = compose(middlewares, handleRequest, (req, res, e) => sendError(res, e));

// ---- boot ----

ensureDir(DATA_DIR);
loadAllUsers();
loadTokens();

const server = TLS_ENABLED
  ? https.createServer(
      {
        key: fs.readFileSync(process.env.SSL_KEY),
        cert: fs.readFileSync(process.env.SSL_CERT),
      },
      requestListener
    )
  : http.createServer(requestListener);

server.on("error", (e) => {
  console.error("[figura-backend-node] server error:", e);
  process.exit(1);
});

server.on("clientError", (err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
  else socket.destroy();
});

// C2S messages are small: token or ping header (6 bytes) + ping data. Oversized pings
// are rejected per message (NOTICE 0) in handleWsMessage; maxPayload only guards against
// abuse, since exceeding it closes the whole connection (1009) and the client never
// checks ping size before sending.
const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

wss.on("connection", (ws) => {
  ws._figuraAuthed = false;
  ws._figuraUuid = null;
  ws._figuraSubs = new Set();
  ws._figuraAlive = true;
  ws._figuraPingTokens = LIMITS.rate.pingRate;
  ws._figuraPingRefill = Date.now();
  ws._figuraLastNotice = 0;

  const authTimer = setTimeout(() => {
    if (!ws._figuraAuthed) ws.close(1008, "Auth timeout");
  }, WS_AUTH_TIMEOUT_MS);

  ws.on("pong", () => {
    ws._figuraAlive = true;
  });

  ws.on("error", (e) => {
    console.warn("[ws] error:", e.message);
    ws.terminate();
  });

  ws.on("message", (data) => {
    try {
      handleWsMessage(ws, Buffer.isBuffer(data) ? data : Buffer.from(data));
    } catch (e) {
      console.error("[ws] message handling failed:", e);
    }
  });

  ws.on("close", () => {
    clearTimeout(authTimer);
    clearSubscriptions(ws);
  });
});

function handleWsMessage(ws, buf) {
  if (buf.length < 1) return;
  const type = buf.readUInt8(0);

  // First message MUST be TOKEN (0) with raw UTF-8 token bytes.
  if (!ws._figuraAuthed) {
    if (type !== 0) {
      ws.close(1002, "Protocol error");
      return;
    }
    const session = getSession(buf.subarray(1).toString("utf8"));
    if (!session) {
      // 4000 makes the client re-authenticate instead of staying disconnected
      ws.close(4000, "Re-auth");
      return;
    }
    ws._figuraAuthed = true;
    ws._figuraUuid = session.uuid.toLowerCase();
    // auto-subscribe to self so multi-client setups (and sync pings) reach the sender too
    addSubscription(ws, ws._figuraUuid);
    wsSendAuthOk(ws);
    return;
  }

  // After auth:
  // C2S PING: 1 + int32(id) + bool(sync) + bytes(data)
  if (type === 1) {
    if (buf.length < 1 + 4 + 1) return;
    const id = buf.readInt32BE(1);
    const sync = buf.readUInt8(5) !== 0;
    const payload = buf.subarray(6);
    if (payload.length > LIMITS.rate.pingSize) return wsSendNotice(ws, 0);
    if (!wsTakePingToken(ws)) return wsSendNotice(ws, 1);
    // Sync pings are not run locally by the client; it waits for the echo.
    wsSendPing(ws._figuraUuid, id, sync, payload, sync ? null : ws);
    return;
  }

  // C2S SUB: 2 + uuid(16 bytes)
  if (type === 2) {
    if (buf.length < 1 + 16) return;
    addSubscription(ws, bytesToUuid(buf.subarray(1, 17)));
    return;
  }

  // C2S UNSUB: 3 + uuid(16 bytes)
  if (type === 3) {
    if (buf.length < 1 + 16) return;
    removeSubscription(ws, bytesToUuid(buf.subarray(1, 17)));
    return;
  }
}

// Heartbeat: keeps connections alive through Cloudflare (~100s idle timeout)
// and drops sockets that stopped answering.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws._figuraAlive) {
      ws.terminate();
      continue;
    }
    ws._figuraAlive = false;
    ws.ping();
  }
}, WS_HEARTBEAT_MS).unref();

server.on("upgrade", (req, socket, head) => {
  socket.on("error", () => socket.destroy());
  try {
    const url = parseUrl(req);
    // Be tolerant to proxies that append a trailing slash.
    if (url.pathname !== "/ws" && url.pathname !== "/ws/") {
      socket.destroy();
      return;
    }
    const ip = resolveClientIp(req);
    if (wsLimiter.take(ip)) {
      console.warn(`[rate-limit] ws: ${ip}`);
      socket.end("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 10\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req);
    });
  } catch {
    socket.destroy();
  }
});

// ---- console commands ----

const CONSOLE_HELP = [
  "commands:",
  "  toast [default|warning|error|cheese] <title> | <message>",
  "  chat <text or JSON chat component>",
  "  online",
  "  stats",
].join("\n");

function handleConsoleLine(line) {
  const trimmed = line.trim();
  if (!trimmed) return;
  const [cmd] = trimmed.split(/\s+/, 1);
  let rest = trimmed.slice(cmd.length).trim();

  switch (cmd.toLowerCase()) {
    case "toast": {
      let type = "default";
      const first = rest.split(/\s+/, 1)[0].toLowerCase();
      if (TOAST_TYPES.includes(first)) {
        type = first;
        rest = rest.slice(first.length).trim();
      }
      const [title, ...msg] = rest.split("|");
      if (!title.trim()) return console.log("usage: toast [type] <title> | <message>");
      console.log(`[admin] toast sent to ${broadcastToast(title.trim(), msg.join("|").trim(), type)} connection(s)`);
      return;
    }
    case "chat":
      if (!rest) return console.log("usage: chat <message>");
      console.log(`[admin] chat sent to ${broadcastChat(rest)} connection(s)`);
      return;
    case "online": {
      const list = onlinePlayers();
      console.log(`[admin] ${list.length} online: ${list.map((p) => p.username || p.uuid).join(", ")}`);
      return;
    }
    case "stats": {
      const c = avatarCache.stats();
      console.log(
        `[admin] online ${onlinePlayers().length}, users ${users.size}, sessions ${tokens.size}; ` +
          `avatar cache ${c.entries} entries, ${(c.bytes / 1048576).toFixed(1)}/${(c.maxBytes / 1048576).toFixed(0)} MB, ${c.hits} hits / ${c.misses} misses`
      );
      return;
    }
    default:
      console.log(CONSOLE_HELP);
  }
}

// Only when attached to a terminal; hosted setups (Plesk, pm2) usually have no stdin.
if (process.stdin.isTTY) {
  require("readline").createInterface({ input: process.stdin }).on("line", (line) => {
    try {
      handleConsoleLine(line);
    } catch (e) {
      console.error("[admin] command failed:", e);
    }
  });
}

process.on("unhandledRejection",(e) => console.error("[process] unhandled rejection:", e));
process.on("uncaughtException", (e) => console.error("[process] uncaught exception:", e));

function shutdown() {
  console.log("[figura-backend-node] shutting down");
  clearTimeout(tokensSaveTimer);
  try {
    fs.writeFileSync(tokensFile(), JSON.stringify(Object.fromEntries(tokens)));
  } catch (e) {
    console.error("[tokens] save failed:", e);
  }
  for (const ws of wss.clients) ws.close(1012, "Service Restart");
  server.close();
  setTimeout(() => process.exit(0), 500).unref();
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

server.listen(PORT, HOST, () => {
  const scheme = TLS_ENABLED ? "https" : "http";
  const wsScheme = TLS_ENABLED ? "wss" : "ws";
  console.log(`[figura-backend-node] listening on ${scheme}://${HOST}:${PORT}`);
  console.log(`[figura-backend-node] api base: ${scheme}://${HOST}:${PORT}/api`);
  console.log(`[figura-backend-node] ws  base: ${wsScheme}://${HOST}:${PORT}/ws`);
  console.log(`[figura-backend-node] loaded ${users.size} users, ${tokens.size} sessions`);
  if (!TLS_ENABLED) {
    console.warn(
      "[figura-backend-node] WARNING: TLS is disabled. The Figura client expects https/wss; use SSL_KEY+SSL_CERT to enable TLS."
    );
  }
  if (SKIP_MOJANG_VERIFY) {
    console.warn("[figura-backend-node] WARNING: SKIP_MOJANG_VERIFY=1 enabled (dev auth).");
  }
});
