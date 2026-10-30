/**
 * Resolves the real client IP into `req.clientIp`.
 *
 * Proxy headers (CF-Connecting-IP, X-Real-IP, X-Forwarded-For) are only trusted
 * when the TCP peer is a local/private address, i.e. our own reverse proxy
 * (Plesk nginx). Direct connections from the internet use the socket address.
 *
 * Note: if the origin is reachable directly (not only through Cloudflare), the
 * local proxy will forward whatever CF-Connecting-IP a client sends. Restrict
 * the origin to Cloudflare IP ranges to make the header trustworthy.
 */

const net = require("net");

function normalize(ip) {
  if (!ip) return "";
  ip = String(ip).trim();
  if (ip.startsWith("::ffff:")) ip = ip.slice(7);
  return ip;
}

function isPrivate(ip) {
  if (!ip) return false;
  if (ip === "127.0.0.1" || ip === "::1" || ip.startsWith("127.")) return true;
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split(".").map(Number);
    return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  }
  return /^f[cd]/i.test(ip) || /^fe80:/i.test(ip);
}

function resolveClientIp(req) {
  const peer = normalize(req.socket && req.socket.remoteAddress);
  // No peer address means a unix socket (e.g. Phusion Passenger), which is local too.
  if (peer && !isPrivate(peer)) return peer;

  const h = req.headers;
  const candidates = [
    h["cf-connecting-ip"],
    h["x-real-ip"],
    typeof h["x-forwarded-for"] === "string" ? h["x-forwarded-for"].split(",")[0] : null,
  ];
  for (const c of candidates) {
    const ip = normalize(c);
    if (net.isIP(ip)) return ip;
  }
  return peer;
}

function clientIp() {
  return (req, res, next) => {
    req.clientIp = resolveClientIp(req);
    next();
  };
}

module.exports = clientIp;
module.exports.resolveClientIp = resolveClientIp;
