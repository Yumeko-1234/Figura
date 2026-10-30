/**
 * Per-key token bucket rate limiter.
 *
 *   rateLimit({ name, capacity, perSecond, match, key })
 *
 * - capacity:  burst size (max requests at once)
 * - perSecond: refill rate
 * - match(req): only requests where this returns true are counted (default: all)
 * - key(req):   bucket key (default: req.clientIp)
 *
 * Over the limit the request gets 429 with a Retry-After header.
 * The returned middleware also has `.take(key)` for non-HTTP use (WS upgrades).
 */

function rateLimit({ name = "default", capacity, perSecond, match = null, key = (req) => req.clientIp || "" }) {
  // key -> { tokens, last }
  const buckets = new Map();

  function take(k) {
    const now = Date.now();
    let b = buckets.get(k);
    if (!b) {
      b = { tokens: capacity, last: now };
      buckets.set(k, b);
    }
    b.tokens = Math.min(capacity, b.tokens + ((now - b.last) / 1000) * perSecond);
    b.last = now;
    if (b.tokens < 1) return Math.ceil((1 - b.tokens) / perSecond);
    b.tokens -= 1;
    return 0;
  }

  // Drop buckets that have refilled completely; they hold no state.
  const fullAfterMs = (capacity / perSecond) * 1000;
  setInterval(() => {
    const now = Date.now();
    for (const [k, b] of buckets) if (now - b.last > fullAfterMs) buckets.delete(k);
  }, Math.max(10_000, fullAfterMs)).unref();

  function middleware(req, res, next) {
    if (match && !match(req)) return next();
    const retryAfter = take(key(req));
    if (!retryAfter) return next();

    console.warn(`[rate-limit] ${name}: ${req.clientIp || "-"} ${req.method} ${(req.url || "").split("?")[0]}`);
    const body = "Too many requests";
    res.writeHead(429, {
      "content-type": "text/plain; charset=utf-8",
      "content-length": Buffer.byteLength(body),
      "retry-after": String(retryAfter),
    });
    res.end(body);
  }

  middleware.take = take;
  return middleware;
}

module.exports = rateLimit;
