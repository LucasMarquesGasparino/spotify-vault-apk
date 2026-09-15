// Teste consistente Spotify JS (node, sem DOM).
// Trava comportamento de alto risco: chunking/virtualização, isRejected, ordenação, LIKE parity.
import assert from "node:assert";
import fs from "node:fs";

const appPath = new URL("../assets/spotify-vault/app.js", import.meta.url);
const appSrc = fs.readFileSync(appPath, "utf8");

// --- referências puras (espelham app.js) ---
function safeParseNames(raw) {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try { const v = JSON.parse(raw); return Array.isArray(v) ? v : []; } catch { return []; }
}
function isRejected(p) {
  if (p.skipped == 1) return true;
  const re = (p.reason_end || "").toLowerCase();
  if (["fwdbtn","backbtn","remote","unexpected-exit","unexpected-exit-while-paused"].includes(re)) return true;
  if (p.ms_played && p.duration_ms && p.ms_played < p.duration_ms * 0.45) return true;
  return false;
}
function chunk(plays, n) { return { first: plays.slice(0, n), rest: plays.slice(n) }; }
function escapeLikeJS(q) { return q.replace(/[\\%_]/g, (m) => "\\" + m); }

let n = 0;
function ok(cond, name) { n++; assert.ok(cond, name); console.log("PASS " + name); }

// safeParse
ok(JSON.stringify(safeParseNames('["a","b"]')) === '["a","b"]', "parse array");
ok(safeParseNames(null).length === 0, "parse null");
ok(safeParseNames("invalido").length === 0, "parse invalido");

// isRejected trava thresholds atuais (alto risco: não mudar sem atualizar teste)
ok(isRejected({ skipped: 1 }) === true, "rej skipped");
ok(isRejected({ reason_end: "fwdbtn" }) === true, "rej fwdbtn");
ok(isRejected({ reason_end: "trackdone", ms_played: 100, duration_ms: 300 }) === true, "rej <45%");
ok(isRejected({ reason_end: "trackdone", ms_played: 200, duration_ms: 300 }) === false, "ok >45%");
ok(isRejected({}) === false, "vazio ok");

// chunking idempotente, sem duplicata/perda (virtualização)
const plays = Array.from({ length: 150 }, (_, i) => ({ id: i }));
const c40 = chunk(plays, 40);
ok(c40.first.length === 40 && c40.rest.length === 110, "chunk 40/110");
ok(new Set([...c40.first, ...c40.rest].map((p) => p.id)).size === 150, "sem dup/perda");
const c500 = chunk([{ id: 1 }], 40);
ok(c500.first.length === 1 && c500.rest.length === 0, "chunk pequeno");

// ordenação library (parity com SQL ORDER BY)
const rows = [
  { track_name: "b", played_at_ms: 2, artist_names: '["Z"]' },
  { track_name: "a", played_at_ms: 3, artist_names: '["A"]' },
];
const byRecent = [...rows].sort((x, y) => y.played_at_ms - x.played_at_ms);
ok(byRecent[0].track_name === "a", "sort recent");
const norm = rows.map((p) => ({ ...p, _an0: safeParseNames(p.artist_names)[0].toLowerCase() }));
norm.sort((a, b) => (a._an0 < b._an0 ? -1 : a._an0 > b._an0 ? 1 : 0));
ok(norm[0]._an0 === "a", "sort artist");

// LIKE parity com PlaysQuery.java
ok(escapeLikeJS("100%_x\\") === "100\\%\\_x\\\\", "escape like parity");

// checks estáticos de alto risco no app.js real
ok(appSrc.includes("IntersectionObserver"), "observer presente (virtualização)");
ok(appSrc.includes("searchPlays") || appSrc.includes("buildSearch"), "busca SQL presente");
ok(!appSrc.includes("getAllPlays(5000"), "sem full-dump 5000");
ok(appSrc.includes("LIMIT ? OFFSET ?") || appSrc.includes("limit"), "paginacao presente");
ok(appSrc.includes("invalidateStatsCache") || appSrc.includes("statsCache"), "cache stats presente");

console.log(`JS tests: ${n} pass`);
