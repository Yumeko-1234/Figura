/**
 * In-memory LRU cache for avatar files, bounded by total bytes.
 *
 * Keys are "<ownerUuid>/<avatarId>". The server updates the cache on upload and
 * drops entries on delete, so cached bytes never go stale.
 *
 *   const cache = createAvatarCache({ maxBytes: 64 * 1024 * 1024 });
 *   const buf = await cache.get(key, () => fsp.readFile(file));
 */

function createAvatarCache({ maxBytes }) {
  // Map keeps insertion order: first entry = least recently used.
  const entries = new Map();
  // key -> Promise<Buffer>, so concurrent misses only read the file once
  const loading = new Map();
  let bytes = 0;
  let hits = 0;
  let misses = 0;

  function evict() {
    for (const [k, buf] of entries) {
      if (bytes <= maxBytes) break;
      entries.delete(k);
      bytes -= buf.length;
    }
  }

  function set(key, buf) {
    del(key);
    // Single entries bigger than the whole cache are not worth keeping.
    if (maxBytes <= 0 || buf.length > maxBytes) return;
    entries.set(key, buf);
    bytes += buf.length;
    evict();
  }

  function del(key) {
    const old = entries.get(key);
    if (old) {
      entries.delete(key);
      bytes -= old.length;
    }
    loading.delete(key);
  }

  // Returns the cached buffer, or calls `load()` on a miss and caches its result.
  // A load failure (e.g. ENOENT) is passed through and nothing is cached.
  async function get(key, load) {
    const buf = entries.get(key);
    if (buf) {
      // refresh LRU position
      entries.delete(key);
      entries.set(key, buf);
      hits++;
      return buf;
    }
    misses++;
    let p = loading.get(key);
    if (!p) {
      p = Promise.resolve()
        .then(load)
        .then((b) => {
          // Only cache if no upload/delete happened while loading.
          if (loading.get(key) === p) set(key, b);
          return b;
        })
        .finally(() => {
          if (loading.get(key) === p) loading.delete(key);
        });
      loading.set(key, p);
    }
    return p;
  }

  function stats() {
    return { entries: entries.size, bytes, maxBytes, hits, misses };
  }

  return { get, set, delete: del, stats };
}

module.exports = createAvatarCache;
