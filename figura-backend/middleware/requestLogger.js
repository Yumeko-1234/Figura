/**
 * Logs one line per request once the response is done:
 *   [http] 1.2.3.4 GET /api/motd 200 3ms
 *
 * Options:
 * - enabled (default true)
 * - skip(req, res) -> true to not log a request (e.g. noisy successful lookups)
 */

function requestLogger({ enabled = true, skip = null } = {}) {
  return (req, res, next) => {
    if (!enabled) return next();
    const start = process.hrtime.bigint();
    // Path only: query strings can carry login ids.
    const p = (req.url || "").split("?")[0];
    let logged = false;
    const log = (aborted) => {
      if (logged) return;
      logged = true;
      if (skip && skip(req, res)) return;
      const ms = Number(process.hrtime.bigint() - start) / 1e6;
      const status = aborted && !res.headersSent ? "aborted" : res.statusCode;
      console.log(`[http] ${req.clientIp || "-"} ${req.method} ${p} ${status} ${ms.toFixed(0)}ms`);
    };
    res.on("finish", () => log(false));
    res.on("close", () => log(true));
    next();
  };
}

module.exports = requestLogger;
