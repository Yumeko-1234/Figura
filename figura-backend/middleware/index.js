/**
 * Tiny middleware pipeline for Node's http server (no Express).
 *
 * A middleware is `(req, res, next) => void`. Call `next()` to continue,
 * or answer the request yourself and don't call it. Errors thrown (sync or
 * from a returned promise) are passed to `onError`.
 */

const clientIp = require("./clientIp");
const requestLogger = require("./requestLogger");
const rateLimit = require("./rateLimit");
const securityHeaders = require("./securityHeaders");
const createAvatarCache = require("./avatarCache");

function compose(middlewares, handler, onError) {
  return function run(req, res) {
    let i = 0;
    const next = () => {
      const fn = i < middlewares.length ? middlewares[i++] : handler;
      try {
        const r = fn(req, res, next);
        if (r && typeof r.catch === "function") r.catch((e) => onError(req, res, e));
      } catch (e) {
        onError(req, res, e);
      }
    };
    next();
  };
}

module.exports = {
  compose,
  clientIp,
  requestLogger,
  rateLimit,
  securityHeaders,
  createAvatarCache,
};
