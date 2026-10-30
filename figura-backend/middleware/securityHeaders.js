/**
 * Adds basic security headers to every response. The API is only consumed by
 * the Figura Java client, so nothing should be cached, sniffed or framed.
 */

const HEADERS = {
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "no-referrer",
  "cache-control": "no-store",
};

function securityHeaders() {
  return (req, res, next) => {
    for (const [k, v] of Object.entries(HEADERS)) res.setHeader(k, v);
    next();
  };
}

module.exports = securityHeaders;
