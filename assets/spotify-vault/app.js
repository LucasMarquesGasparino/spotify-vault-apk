/* Spotify Vault - SPA autocontida v1.2
 * - Auth PKCE + refresh (sem secret)
 * - Sync 50 últimas a cada 8h com dedup + foreground notif
 * - Extended History ZIP/JSON com ms_played/skipped/reason_end
 * - Timeline com rejeição + hyperlink Spotify + WebView UA fix
 */
const SCOPES = "user-read-recently-played user-read-currently-playing user-read-playback-state user-top-read user-library-read playlist-read-private";
const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

let state = {
  clientId: "",
  redirectUri: "spotifyvault://callback",
  accessToken: "",
  refreshToken: "",
  expiresAt: 0,
  autoSync: true,
  libPage: 0,
  libLimit: 20,
};

// ---------- perf layer (fluidez v1.3) ----------
// Cache curto de stats para evitar ~20 queries SQLite a cada troca de aba
let _statsCache = null, _statsAt = 0;
function bridgeGetStatsCached(ttlMs){
  ttlMs = ttlMs || 15000;
  const now = Date.now();
  if(_statsCache && (now - _statsAt) < ttlMs) return _statsCache;
  _statsCache = bridgeGetStats();
  _statsAt = now;
  return _statsCache;
}
function invalidateStatsCache(){ _statsCache = null; _statsAt = 0; }
// Formatters reutilizados (Intl com timeZone é ~50-100x mais caro que getHours)
const _fmtTimeBR = new Intl.DateTimeFormat("pt-BR", {hour:"2-digit", minute:"2-digit", timeZone:"America/Sao_Paulo"});
const _fmtHourBR = new Intl.DateTimeFormat("pt-BR", {hour:"2-digit", hour12:false, timeZone:"America/Sao_Paulo"});
const _fmtNumBR = new Intl.NumberFormat("pt-BR");
function fmtTimeBR(ms){ try{ return _fmtTimeBR.format(new Date(ms)); }catch(e){ return ""; } }
function fmtHourBR(ms){ try{ return _fmtHourBR.format(new Date(ms)).slice(0,2); }catch(e){ return ""; } }
function safeParseNames(raw){
  if(!raw) return [];
  if(Array.isArray(raw)) return raw;
  try{ const v = JSON.parse(raw); return Array.isArray(v)? v : []; }catch(e){ return []; }
}
// rAF para charts (evita 4 layouts seguidos)
let _chartRaf = 0, _chartPending = null;
function scheduleCharts(stats){
  _chartPending = stats;
  if(_chartRaf) return;
  _chartRaf = requestAnimationFrame(()=>{
    _chartRaf = 0;
    const s = _chartPending; _chartPending = null;
    if(!s) return;
    try{ drawDaypartChart([s.daypart?.madrugada||0, s.daypart?.manha||0, s.daypart?.tarde||0, s.daypart?.noite||0]); }catch(e){}
    try{ drawMonthlyChart(s.monthly||[]); }catch(e){}
    try{ drawDailyChart(s.daily||[]); }catch(e){}
    try{ drawHourlyChart(s.hourly||[]); }catch(e){}
  });
}
function setupCanvas(sel, hCss){
  const c = typeof sel==="string"? $(sel) : sel;
  if(!c) return null;
  const dpr = window.devicePixelRatio||1;
  const wCss = c.clientWidth || (c.parentElement? c.parentElement.clientWidth-14 : 300) || 300;
  if(wCss<=0) return null;
  c.width = Math.round(wCss*dpr);
  c.height = Math.round(hCss*dpr);
  const ctx = c.getContext("2d");
  ctx.setTransform(dpr,0,0,dpr,0,0);
  return {c, ctx, W:wCss, H:hCss, dpr};
}

// ---------- helpers ----------
function toast(msg, ms=2600){
  const t=$("#toast");
  t.textContent=msg;
  t.classList.add("show");
  setTimeout(()=>t.classList.remove("show"), ms);
}
function copyText(txt){
  if(navigator.clipboard) navigator.clipboard.writeText(txt).then(()=>toast("Copiado: "+txt));
  else { prompt("Copie:", txt); }
}
function openExternal(url){
  try {
    if(typeof Android !== "undefined" && Android.openExternal){
      Android.openExternal(url);
      return;
    }
  } catch(e){}
  // fallback
  try { window.open(url,"_blank"); } catch(e){ location.href=url; }
}
function isAndroid(){ return typeof Android !== "undefined" && Android.getConfig; }
function bridgeGet(k,def=""){ try{ return isAndroid()? Android.getConfig(k): (localStorage.getItem(k)||def); }catch(e){return def;} }
function bridgePut(k,v){ try{ if(isAndroid()) Android.saveConfig(k,v); else localStorage.setItem(k,v);}catch(e){} }
function bridgeSavePlays(arr){
  const json = JSON.stringify(arr);
  if(isAndroid()) return Android.savePlays(json);
  try{
    let existing = JSON.parse(localStorage.getItem("mock_plays")||"[]");
    const map = new Set(existing.map(p=>p.played_at));
    let inserted=0;
    for(const p of arr){ if(!map.has(p.played_at)){ existing.push(p); inserted++; } }
    existing.sort((a,b)=> new Date(a.played_at)-new Date(b.played_at));
    localStorage.setItem("mock_plays", JSON.stringify(existing));
    localStorage.setItem("last_sync_ms", String(Date.now()));
    return inserted;
  }catch(e){ return -1; }
}
function bridgeGetPlaysByDate(d){
  if(isAndroid()) return JSON.parse(Android.getPlaysByDate(d)||"[]");
  try{
    const all = JSON.parse(localStorage.getItem("mock_plays")||"[]");
    // mais recente primeiro (Brasília)
    return all.filter(p=> {
      // filtra por data local Brasília: compara date local
      const local = new Date(p.played_at).toLocaleDateString("en-CA", {timeZone:"America/Sao_Paulo"});
      return local===d;
    }).sort((a,b)=> new Date(b.played_at)-new Date(a.played_at));
  }catch(e){ return []; }
}
function bridgeGetStats(){
  if(isAndroid()) return JSON.parse(Android.getStats()||"{}");
  try{
    const all = JSON.parse(localStorage.getItem("mock_plays")||"[]");
    let total = all.length;
    let totalMs = all.reduce((s,p)=> s+(p.duration_ms||0),0);
    let skipped = all.filter(p=>p.skipped==1).length;
    const dailyMap={};
    all.forEach(p=>{ const d=p.played_at.slice(0,10); dailyMap[d]=(dailyMap[d]||0)+1; });
    const daily = Object.keys(dailyMap).sort().slice(-30).map(d=>({date:d, plays:dailyMap[d]}));
    const hourly = Array(24).fill(0);
    all.forEach(p=>{ const h=new Date(p.played_at).getHours(); if(h>=0&&h<24) hourly[h]++; });
    const trackMap={};
    all.forEach(p=>{ const k=p.track_id||p.track_name; if(!trackMap[k]) trackMap[k]={track_id:p.track_id,track_name:p.track_name,artist_names:p.artist_names,album_image:p.album_image,plays:0}; trackMap[k].plays++; });
    const topTracks = Object.values(trackMap).sort((a,b)=>b.plays-a.plays).slice(0,20);
    const artistMap={};
    all.forEach(p=>{
      let names=[];
      try{ names= typeof p.artist_names==="string"? JSON.parse(p.artist_names): p.artist_names; }catch(e){ names=[]; }
      const key = JSON.stringify(names);
      if(!artistMap[key]) artistMap[key]={artist_names:names,plays:0};
      artistMap[key].plays++;
    });
    const topArtists = Object.values(artistMap).sort((a,b)=>b.plays-a.plays).slice(0,20);
    const skipMap={};
    all.filter(p=>p.skipped==1).forEach(p=>{
      const k=p.track_id||p.track_name;
      if(!skipMap[k]) skipMap[k]={track_id:p.track_id,track_name:p.track_name,artist_names:p.artist_names,skipped:0};
      skipMap[k].skipped++;
    });
    const topSkipped=Object.values(skipMap).sort((a,b)=>b.skipped-a.skipped).slice(0,10);
    return {total_plays:total, total_duration_ms:totalMs, total_minutes: Math.floor(totalMs/60000), total_hours: (totalMs/3600000).toFixed(1), daily, hourly, top_tracks:topTracks, top_artists:topArtists, skipped, skip_rate: total? Math.round(skipped*1000/total)/10:0, top_skipped:topSkipped, last_sync_human: localStorage.getItem("last_sync_human")||"" };
  }catch(e){ return {}; }
}
function bridgeGetDates(){ if(isAndroid()) return JSON.parse(Android.getDatesWithPlays()||"[]"); try{ const all=JSON.parse(localStorage.getItem("mock_plays")||"[]"); return [...new Set(all.map(p=> new Date(p.played_at).toLocaleDateString("en-CA", {timeZone:"America/Sao_Paulo"})))].sort().reverse(); }catch(e){return [];} }
function bridgeGetLastMs(){ if(isAndroid()) return Number(Android.getLastPlayedAtMs()||0); try{ const all=JSON.parse(localStorage.getItem("mock_plays")||"[]"); if(!all.length) return 0; return Math.max(...all.map(p=> new Date(p.played_at).getTime())); }catch(e){return 0;} }
function bridgeClear(){ if(isAndroid()) Android.clearAllPlays(); else localStorage.removeItem("mock_plays"); }
function formatMs(ms){ if(!ms||ms<=0) return "—"; const m=Math.floor(ms/60000); const s=Math.floor((ms%60000)/1000); return m+":"+String(s).padStart(2,"0"); }
function toISODate(d){ const dt = new Date(d); const y=dt.getFullYear(); const m=String(dt.getMonth()+1).padStart(2,"0"); const day=String(dt.getDate()).padStart(2,"0"); return `${y}-${m}-${day}`; }

// ---------- tabs ----------
function initTabs(){
  $$(".tab").forEach(b=> b.addEventListener("click", ()=>{
    _enrichAbort++; // cancela enrich pendente ao trocar de aba
    $$(".tab").forEach(x=>x.classList.remove("active"));
    b.classList.add("active");
    $$(".panel").forEach(p=>p.classList.remove("active"));
    $("#tab-"+b.dataset.tab).classList.add("active");
    if(b.dataset.tab==="insights") renderInsights();
    if(b.dataset.tab==="timeline") renderTimeline();
    if(b.dataset.tab==="library") renderLibrary();
  }));
}
function switchTab(name){
  $$(".tab").forEach(x=> x.classList.toggle("active", x.dataset.tab===name));
  $$(".panel").forEach(p=> p.classList.toggle("active", p.id==="tab-"+name));
}

// ---------- PKCE ----------
function randString(len){
  const chars="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let s=""; const arr = new Uint8Array(len);
  crypto.getRandomValues(arr);
  for(let i=0;i<len;i++) s+= chars[arr[i]%chars.length];
  return s;
}
async function sha256(str){
  const data = new TextEncoder().encode(str);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return hash;
}
function base64url(buf){
  const bytes = new Uint8Array(buf);
  let s=""; for(let i=0;i<bytes.length;i++) s+= String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
}

// ---------- auth ----------
async function startLogin(){
  const cid = $("#clientId").value.trim();
  const red = $("#redirectUri").value;
  if(!cid){ toast("Cole o Client ID"); $("#clientId").focus(); return; }
  if(cid.length<20){ toast("Client ID parece inválido"); }
  bridgePut("client_id", cid);
  bridgePut("redirect_uri", red);
  state.clientId=cid; state.redirectUri=red;

  const verifier = randString(64);
  bridgePut("code_verifier", verifier);
  const hashed = await sha256(verifier);
  const challenge = base64url(hashed);
  const scopeEnc = encodeURIComponent(SCOPES);
  const stateStr = randString(12);
  bridgePut("oauth_state", stateStr);

  const authUrl = `https://accounts.spotify.com/authorize?response_type=code&client_id=${encodeURIComponent(cid)}&scope=${scopeEnc}&redirect_uri=${encodeURIComponent(red)}&state=${stateStr}&code_challenge_method=S256&code_challenge=${challenge}`;
  $("#authStatus").className="status warn";
  $("#authStatus").textContent="Abrindo login Spotify... confirme o acesso e aguarde o retorno.";
  if(isAndroid() && typeof Android.openAuth === "function"){
    Android.openAuth(authUrl);
  } else {
    window.location.href = authUrl;
  }
}
async function handleCallback(code, error, stateRet){
  const log = $("#authStatus");
  if(error){
    log.className="status err";
    log.textContent="Erro Spotify: "+error;
    toast("Falha no login: "+error, 3500);
    return;
  }
  if(!code){ log.className="status err"; log.textContent="Código não recebido"; return; }
  log.className="status warn";
  log.textContent="Código recebido, trocando por token...";
  try{
    const verifier = bridgeGet("code_verifier");
    const cid = bridgeGet("client_id");
    const red = bridgeGet("redirect_uri");
    const body = new URLSearchParams({
      grant_type: "authorization_code",
      code: code,
      redirect_uri: red,
      client_id: cid,
      code_verifier: verifier
    });
    const resp = await fetch("https://accounts.spotify.com/api/token", {
      method:"POST",
      headers:{ "Content-Type":"application/x-www-form-urlencoded" },
      body: body.toString()
    });
    const data = await resp.json();
    if(!resp.ok){
      throw new Error(data.error_description||data.error||"falha token");
    }
    const now = Date.now();
    const expiresAt = now + (data.expires_in*1000);
    bridgePut("access_token", data.access_token);
    bridgePut("refresh_token", data.refresh_token|| bridgeGet("refresh_token"));
    bridgePut("expires_at", String(expiresAt));
    bridgePut("scope", data.scope||SCOPES);
    bridgePut("token_type", data.token_type||"Bearer");
    state.accessToken=data.access_token;
    state.refreshToken=data.refresh_token||state.refreshToken;
    state.expiresAt=expiresAt;
    log.className="status ok";
    log.textContent="Login OK! Token válido por "+Math.floor(data.expires_in/60)+" min. Fazendo primeiro sync...";
    toast("Login Spotify OK!");
    updateAuthUI();
    setTimeout(doSync, 600);
  }catch(e){
    log.className="status err";
    log.textContent="Erro ao trocar token: "+e.message;
    toast("Erro token: "+e.message, 4000);
  }
}
window.onSpotifyCallback = handleCallback;

async function refreshTokenIfNeeded(){
  const expiresAt = Number(bridgeGet("expires_at","0"));
  const now = Date.now();
  if(now + 60000 < expiresAt) return bridgeGet("access_token");
  const refresh = bridgeGet("refresh_token");
  const cid = bridgeGet("client_id");
  if(!refresh || !cid) throw new Error("sem refresh_token/client_id");
  const body = new URLSearchParams({ grant_type:"refresh_token", refresh_token: refresh, client_id: cid });
  const resp = await fetch("https://accounts.spotify.com/api/token", { method:"POST", headers:{ "Content-Type":"application/x-www-form-urlencoded"}, body: body.toString()});
  const data = await resp.json();
  if(!resp.ok) throw new Error(data.error_description||data.error||"refresh falhou");
  const newExpires = Date.now()+ data.expires_in*1000;
  bridgePut("access_token", data.access_token);
  bridgePut("expires_at", String(newExpires));
  if(data.refresh_token) bridgePut("refresh_token", data.refresh_token);
  if(data.scope) bridgePut("scope", data.scope);
  state.accessToken=data.access_token;
  state.expiresAt=newExpires;
  if(data.refresh_token) state.refreshToken=data.refresh_token;
  return data.access_token;
}

function updateAuthUI(){
  const cid = bridgeGet("client_id");
  const red = bridgeGet("redirect_uri", state.redirectUri);
  const at = bridgeGet("access_token");
  const rt = bridgeGet("refresh_token");
  const exp = bridgeGet("expires_at");
  $("#clientId").value = cid;
  $("#redirectUri").value = red;
  state.clientId=cid; state.redirectUri=red;
  state.accessToken=at; state.refreshToken=rt; state.expiresAt= Number(exp||0);
  const info = $("#tokenInfo");
  const status = $("#authStatus");
  if(at){
    const expDate = state.expiresAt? new Date(state.expiresAt).toLocaleString("pt-BR"): "—";
    const hoursLeft = state.expiresAt? Math.max(0, Math.floor((state.expiresAt-Date.now())/60000)) : 0;
    info.textContent = `access_token: ${at.slice(0,18)}... (${hoursLeft} min restantes)\nrefresh_token: ${rt? rt.slice(0,12)+"...": "—"}\nexpires: ${expDate}\nclient_id: ${cid.slice(0,8)}...`;
    status.className="status ok";
    status.textContent="Autenticado ✓ - pode sincronizar. Token expira em "+hoursLeft+" min (auto-refresh ativo).";
    $("#syncStatus").textContent="logado";
    $("#syncStatus").style.background="rgba(29,185,84,.15)";
    $("#syncStatus").style.color="#b7f0c3";
  }else{
    info.textContent = cid? "Client ID configurado, mas ainda não logado. Clique em Entrar com Spotify.":"Cole o Client ID do Dashboard";
    status.className="status warn";
    status.textContent = cid? "Aguardando login":"Configure o Client ID";
    $("#syncStatus").textContent="deslogado";
    $("#syncStatus").style.background="#1d1d1d";
    $("#syncStatus").style.color="#bdbdbd";
  }
  const last = bridgeGet("last_sync_human") || (isAndroid() && Android.getLastSyncHuman? Android.getLastSyncHuman():"");
  $("#lastSyncLabel").textContent = "último: "+(last||"nunca");
  const emptyEl = $("#emptyLastSync");
  if(emptyEl) emptyEl.textContent = last||"nunca";
  const auto = bridgeGet("auto_sync_enabled","true");
  $("#autoSync").checked = auto==="true";
  const nextAl = bridgeGet("next_alarm_human","");
  const nextEl = $("#nextAlarmLabel");
  if(nextEl) nextEl.textContent = nextAl ? "Próximo agendado: "+nextAl : "";
}

function logout(){
  if(!confirm("Sair e apagar tokens? O banco de plays permanece.")) return;
  bridgePut("access_token","");
  bridgePut("refresh_token","");
  bridgePut("expires_at","");
  toast("Logout feito");
  updateAuthUI();
}

// ---------- sync ----------
let syncing=false;
async function doSync(){
  if(syncing) return;
  const cid = bridgeGet("client_id");
  let token = bridgeGet("access_token");
  if(!cid || !token){
    toast("Faça login primeiro no Config");
    switchTab("config");
    return;
  }
  syncing=true;
  $("#btnSync").disabled=true; $("#btnDoSync").disabled=true;
  $("#btnSync").textContent="… sync";
  const logEl = $("#syncLog");
  const appendLog = (m)=>{ const ts=new Date().toLocaleTimeString("pt-BR"); logEl.textContent = `[${ts}] ${m}\n` + logEl.textContent; };
  try{
    appendLog("verificando token...");
    token = await refreshTokenIfNeeded();
    const lastMs = bridgeGetLastMs();
    appendLog("lastMs="+ (lastMs? new Date(lastMs).toISOString():"0 (full)"));
    let cursorAfter = lastMs>0? String(lastMs): null;
    let totalInserted=0;
    let totalFetched=0;
    let loops=0;
    while(loops<5){
      let url = "https://api.spotify.com/v1/me/player/recently-played?limit=50";
      if(cursorAfter) url+= "&after="+encodeURIComponent(cursorAfter);
      appendLog("GET "+url);
      const resp = await fetch(url, { headers:{ Authorization:"Bearer "+token }});
      if(resp.status===401){
        appendLog("401, tentando refresh...");
        token = await refreshTokenIfNeeded();
        continue;
      }
      if(resp.status===429){
        const retry = resp.headers.get("Retry-After")||"5";
        appendLog("429 rate limit, aguardando "+retry+"s");
        await new Promise(r=>setTimeout(r, Number(retry)*1000));
        continue;
      }
      if(!resp.ok){
        const txt = await resp.text();
        throw new Error("HTTP "+resp.status+" "+txt.slice(0,180));
      }
      const data = await resp.json();
      const items = data.items||[];
      totalFetched += items.length;
      appendLog(`recebidos ${items.length} itens`);
      if(items.length===0) break;
      const flatArr = items.map(it=>{
        const track = it.track;
        const artists = track.artists||[];
        const album = track.album||{};
        const imgs = album.images||[];
        let playedAt = it.played_at;
        let ms = 0;
        try{ ms = new Date(playedAt).getTime(); }catch(e){}
        return {
          played_at: playedAt,
          played_at_ms: ms,
          track_id: track.id||"",
          track_name: track.name||"",
          artist_ids: JSON.stringify(artists.map(a=>a.id)),
          artist_names: JSON.stringify(artists.map(a=>a.name)),
          album_id: album.id||"",
          album_name: album.name||"",
          album_image: imgs[0]? imgs[0].url : "",
          duration_ms: track.duration_ms||0,
          explicit: track.explicit?1:0,
          context_type: it.context? it.context.type : "",
          context_uri: it.context? it.context.uri : "",
          ms_played: track.duration_ms||0,
          skipped: 0,
          reason_start: "",
          reason_end: "trackdone",
          shuffle: 0,
          offline: 0,
          platform: "",
          raw_json: JSON.stringify(it)
        };
      });
      const inserted = bridgeSavePlays(flatArr);
      appendLog(`inseridos ${inserted}/${flatArr.length} (dedup por played_at)`);
      totalInserted+= inserted;
      const cursors = data.cursors;
      const nextAfter = cursors && cursors.after;
      if(nextAfter && nextAfter!==cursorAfter && items.length===50){
        cursorAfter = nextAfter;
      } else if(items.length===50){
        const lastItem = items[items.length-1];
        const lastMs2 = new Date(lastItem.played_at).getTime();
        if(lastMs2 && String(lastMs2)!==cursorAfter) cursorAfter = String(lastMs2);
        else break;
      } else {
        break;
      }
      loops++;
      if(inserted===0 && loops>1) {
        appendLog("só duplicatas, parando");
        break;
      }
      await new Promise(r=>setTimeout(r, 250));
    }
    bridgePut("last_sync_ms", String(Date.now()));
    const human = new Date().toLocaleString("pt-BR");
    bridgePut("last_sync_human", human);
    appendLog(`✓ Sync concluído: ${totalInserted} novos de ${totalFetched} fetched. ${human}`);
    toast(`Sync OK: +${totalInserted} faixas`);
    invalidateStatsCache();
    updateAuthUI();
    // Só re-renderiza aba visível (fluidez)
    if($("#tab-timeline").classList.contains("active")) renderTimeline();
    if($("#tab-insights").classList.contains("active")) renderInsights();
    if($("#tab-library").classList.contains("active")) renderLibrary();
    else if(totalInserted>0 && !$("#tab-insights").classList.contains("active") && !$("#tab-timeline").classList.contains("active")) renderLibrary();
    $("#syncStatus").textContent = `+${totalInserted}`;
    setTimeout(()=>{ $("#btnSync").textContent="↻ Sync"; }, 1200);
  }catch(e){
    appendLog("ERRO: "+e.message);
    toast("Erro sync: "+e.message, 4000);
    $("#btnSync").textContent="↻ Sync (erro)";
  }finally{
    syncing=false;
    $("#btnSync").disabled=false; $("#btnDoSync").disabled=false;
  }
}

async function testApi(){
  try{
    const tok = await refreshTokenIfNeeded();
    const r = await fetch("https://api.spotify.com/v1/me", { headers:{ Authorization:"Bearer "+tok }});
    const j = await r.json();
    if(!r.ok) throw new Error(JSON.stringify(j));
    $("#syncLog").textContent = "ME OK: "+JSON.stringify(j,null,2).slice(0,800) + "\n\n...token válido";
    toast("API OK: "+ (j.display_name||j.id));
  }catch(e){
    $("#syncLog").textContent = "ME ERRO: "+e.message;
    toast("Teste falhou: "+e.message, 4000);
  }
}

// ---------- timeline ----------
function isRejected(p){
  // Extended history: skipped==1 ou reason_end fwdbtn/backbtn/remote ou ms_played < 45% duration
  if(p.skipped==1) return true;
  const re = (p.reason_end||"").toLowerCase();
  if(re==="fwdbtn"||re==="backbtn"||re==="remote"||re==="unexpected-exit"||re==="unexpected-exit-while-paused") return true;
  if(p.ms_played && p.duration_ms && p.ms_played < p.duration_ms*0.45) return true;
  return false;
}
function renderTimeline(){
  const picker = $("#datePicker");
  if(!picker.value){
    const today = toISODate(new Date());
    picker.value = today;
  }
  const date = picker.value;
  const dates = bridgeGetDates();
  const chipsEl = $("#datesChips");
  chipsEl.innerHTML="";
  if(dates.length){
    dates.slice(0,14).forEach(d=>{
      const b=document.createElement("button");
      b.className="chip"+(d===date?" active":"");
      b.textContent=d + (d===toISODate(new Date())?" (hoje)":"");
      b.onclick=()=>{ picker.value=d; renderTimeline(); };
      chipsEl.appendChild(b);
    });
    if(dates.length>14){
      const more=document.createElement("span");
      more.className="hint";
      more.textContent=`+${dates.length-14} dias`;
      chipsEl.appendChild(more);
    }
  } else {
    chipsEl.innerHTML='<span class="hint">Nenhum dia ainda. Faça sync.</span>';
  }

  const plays = bridgeGetPlaysByDate(date);
  const list = $("#timelineList");
  const empty = $("#timelineEmpty");
  const summary = $("#daySummary");
  if(list._tlObserver){ try{ list._tlObserver.disconnect(); }catch(e){} list._tlObserver = null; }
  list.innerHTML="";
  if(!plays.length){
    list.style.display="none";
    empty.classList.remove("hidden");
    summary.innerHTML="";
    return;
  }
  empty.classList.add("hidden");
  list.style.display="flex";
  // Pré-computa 1x por item: ms, nomes, rejeição (evita 3x isRejected + 2x JSON.parse + 3x Intl)
  let totalMs = 0;
  const uniqArtists = new Set();
  let rejected = 0;
  let minMs = Infinity, maxMs = -Infinity;
  for(const p of plays){
    let ms = p._ms;
    if(!ms){ try{ ms = new Date(p.played_at).getTime(); }catch(e){ ms=0; } p._ms = ms; }
    if(ms){ if(ms<minMs) minMs=ms; if(ms>maxMs) maxMs=ms; }
    totalMs += (p.duration_ms||0);
    if(!p._names) p._names = safeParseNames(p.artist_names);
    for(const n of p._names) uniqArtists.add(n);
    if(p._rej===undefined) p._rej = isRejected(p);
    if(p._rej) rejected++;
  }
  const totalMin = Math.floor(totalMs/60000);
  const first = isFinite(minMs)? fmtTimeBR(minMs) : "—";
  const last = isFinite(maxMs)? fmtTimeBR(maxMs) : "—";
  summary.innerHTML = `<span>${plays.length} faixas</span><span>${totalMin} min</span><span>${uniqArtists.size} artistas</span><span>${first} → ${last} (Brasília)</span>` + (rejected? `<span style="background:rgba(255,59,48,.15);border-color:rgba(255,59,48,.4);color:#ff9a93">🚫 ${rejected} puladas</span>` : "") + `<span class="hint" style="border:none;background:none">ordem: mais recente primeiro</span>`;
  let lastHour="";
  const CHUNK = 40;
  const renderItems = plays.length>CHUNK? plays.slice(0, CHUNK) : plays;
  function buildItem(p){
    const ms = p._ms || 0;
    const hour = ms? fmtHourBR(ms) : "";
    let sep = null;
    if(hour && hour!==lastHour){
      lastHour=hour;
      sep=document.createElement("div");
      sep.className="hint";
      sep.style.margin="6px 2px 2px";
      sep.textContent=`— ${hour}:00 (Brasília) —`;
    }
    const names = p._names || [];
    const time = ms? fmtTimeBR(ms) : "—";
    const rej = p._rej;
    const img = p.album_image? `<img src="${p.album_image}" loading="lazy" decoding="async" width="56" height="56" alt="">` : `<div style="width:56px;height:56px;border-radius:8px;background:#222;display:grid;place-items:center">♪</div>`;
    const dur = p.duration_ms? formatMs(p.duration_ms): "";
    const msPlayed = p.ms_played && p.ms_played!=p.duration_ms ? `<span title="tocado" style="font-size:11px;color:#9a9a9a">▶ ${formatMs(p.ms_played)}</span>` : "";
    const rejectedBadge = rej ? `<span style="background:rgba(255,59,48,.15);color:#ff453a;border:1px solid rgba(255,59,48,.35);padding:2px 6px;border-radius:20px;font-size:10px">pulada${p.reason_end? ' • '+escapeHtml(p.reason_end):''}</span>` : `<span style="color:#1DB954;border:1px solid rgba(29,185,84,.3);padding:2px 6px;border-radius:20px;font-size:10px">ok${p.ms_played? ' '+formatMs(p.ms_played):''}</span>`;
    const spotifyLink = p.track_id ? `<a href="#" data-spotify="${escapeHtml(p.track_id)}" style="font-size:11px;color:#1DB954;text-decoration:none;border:1px solid rgba(29,185,84,.3);padding:2px 6px;border-radius:20px">↗ Spotify</a>` : "";
    const item = document.createElement("div");
    item.className="t-item";
    if(p.track_id) item.dataset.tid = p.track_id;
    item.style.opacity = rej ? "0.78" : "1";
    if(rej) item.style.borderColor = "rgba(255,59,48,.25)";
    item.innerHTML = `
      ${img}
      <div class="t-info">
        <div class="t-title">${escapeHtml(p.track_name||"—")}</div>
        <div class="t-artist">${escapeHtml(names.join(", "))} • ${escapeHtml(p.album_name||"")}</div>
        <div class="t-meta"><span>${dur}</span> ${msPlayed} ${rejectedBadge} ${spotifyLink}${p.explicit?' <span style="color:#ff3b30;border:1px solid #ff3b30;padding:0 4px;border-radius:4px;font-size:10px">E</span>':''}</div>
      </div>
      <div class="t-time">${time}</div>
    `;
    if(p.context_type || p.platform) item.title = [p.context_type, p.platform].filter(Boolean).join(" • ");
    return {sep, item};
  }
  const frag = document.createDocumentFragment();
  // Delegação: 1 listener no container em vez de N por item
  if(!list._delegated){
    list._delegated = true;
    list.addEventListener("click", (e)=>{
      if(e.target.closest && e.target.closest("[data-spotify]")) return;
      if(e.target.tagName==="A") return;
      const el = e.target.closest(".t-item");
      if(!el || !el.dataset.tid) return;
      openExternal("https://open.spotify.com/track/"+el.dataset.tid);
    });
  }
  for(const p of renderItems){
    const {sep, item} = buildItem(p);
    if(sep) frag.appendChild(sep);
    frag.appendChild(item);
  }
  list.appendChild(frag);
  if(plays.length>CHUNK){
    // Alto risco: virtualização com IntersectionObserver + fallback botão (sem dup)
    let loaded = CHUNK;
    let loadingMore = false;
    const sentinel = document.createElement("div");
    sentinel.className = "hint";
    sentinel.style.textAlign = "center";
    sentinel.textContent = `+${plays.length-loaded} restantes…`;
    const loadMore = ()=>{
      if(loadingMore || loaded>=plays.length) return;
      loadingMore = true;
      const frag2 = document.createDocumentFragment();
      const end = Math.min(plays.length, loaded+CHUNK);
      for(let i=loaded;i<end;i++){
        const {sep, item} = buildItem(plays[i]);
        if(sep) frag2.appendChild(sep);
        frag2.appendChild(item);
      }
      loaded = end;
      if(sentinel.parentNode===list) list.insertBefore(frag2, sentinel);
      else list.appendChild(frag2);
      if(loaded>=plays.length){
        if(tlObserver) tlObserver.disconnect();
        if(sentinel.parentNode===list) list.removeChild(sentinel);
        if(moreBtn.parentNode===list) list.removeChild(moreBtn);
      } else {
        sentinel.textContent = `+${plays.length-loaded} restantes…`;
      }
      loadingMore = false;
    };
    const moreBtn = document.createElement("button");
    moreBtn.className = "btn ghost small";
    moreBtn.style.marginTop = "8px";
    moreBtn.textContent = `Ver +${plays.length-CHUNK} faixas`;
    moreBtn.onclick = ()=>{ loadMore(); if(loaded<plays.length) loadMore(); };
    list.appendChild(sentinel);
    list.appendChild(moreBtn);
    let tlObserver = null;
    // desconecta observer anterior da mesma lista (troca de dia)
    if(list._tlObserver){ try{ list._tlObserver.disconnect(); }catch(e){} }
    if(typeof IntersectionObserver !== "undefined"){
      tlObserver = new IntersectionObserver((entries)=>{
        for(const en of entries){ if(en.isIntersecting) loadMore(); }
      }, {rootMargin: "600px"});
      tlObserver.observe(sentinel);
    }
    list._tlObserver = tlObserver;
  }
}
function escapeHtml(s){ return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;"); }

// ---------- insights ----------
async function renderInsights(){
  // skeleton enquanto bridge bloqueia (dataviz loading)
  try{
    ["#chartDaily","#chartHourly","#chartMonthly","#chartDaypart"].forEach(s=>{
      const c=$(s); if(c&&c.parentElement) c.parentElement.classList.add("skel");
    });
  }catch(e){}
  const stats = bridgeGetStatsCached(15000);
  try{
    ["#chartDaily","#chartHourly","#chartMonthly","#chartDaypart"].forEach(s=>{
      const c=$(s); if(c&&c.parentElement) c.parentElement.classList.remove("skel");
    });
  }catch(e){}
  $("#statPlays").textContent = _fmtNumBR.format(stats.total_plays||0);
  $("#statHours").textContent = (stats.total_hours||0)+"h";
  $("#statTracks").textContent = _fmtNumBR.format(stats.unique_tracks||0);
  const uniqArtists = stats.unique_artists ?? stats.unique_artists_approx ?? (stats.top_artists? stats.top_artists.length : 0);
  $("#statArtists").textContent = uniqArtists;

  // --- Favoritos (Dia/Semana/Mês/Ano/Vida) - artista em tempo, música em execuções - topo ---
  try {
    const fav = stats.favorites||{};
    const grid = $("#favoritesGrid");
    if(grid){
      grid.innerHTML="";
      const periods = [
        {key:"day", label:"Hoje", icon:"📅"},
        {key:"week", label:"Semana", icon:"🗓️"},
        {key:"month", label:"Mês", icon:"🗓️"},
        {key:"year", label:"Ano", icon:"📆"},
        {key:"all", label:"Vida", icon:"♾️"}
      ];
      periods.forEach(p=>{
        const d = fav[p.key]||{};
        let aNames=[];
        try{ aNames = Array.isArray(d.top_artist_names)? d.top_artist_names : JSON.parse(d.top_artist_names||"[]"); }catch(e){}
        const aName = aNames[0]||"—";
        const aTime = d.top_artist_time_ms ? formatMs(d.top_artist_time_ms) : "—";
        const aId = d.top_artist_id||"";
        let tNames=[];
        try{ tNames = Array.isArray(d.top_track_artists)? d.top_track_artists : JSON.parse(d.top_track_artists||"[]"); }catch(e){}
        const tName = d.top_track_name||"—";
        const tPlays = d.top_track_plays||0;
        const tId = d.top_track_id||"";
        const tImg = d.top_track_image||"";
        const card = document.createElement("div");
        card.className="fav-card";
        card.innerHTML = `<h4>${p.icon} ${p.label}</h4>
          <div style="display:flex;gap:8px;align-items:center" data-artist-id-fav="${aId}" class="fav-artist-row"><div style="width:28px;height:28px;border-radius:14px;background:#1a1a1a;display:grid;place-items:center;border:1px solid #222;overflow:hidden;flex-shrink:0" class="fav-artist-img">${aId?`<span style="font-size:12px">👤</span>`:`<span style="font-size:12px">—</span>`}</div><div style="min-width:0;flex:1"><div class="fav-artist">${escapeHtml(aName)}</div><div class="fav-meta">${aTime} • ${d.plays||0} plays</div></div><a href="#" onclick="openExternal('https://open.spotify.com/artist/${aId}');return false;" style="font-size:11px;color:#1DB954">${aId?"↗":""}</a></div>
          <div style="display:flex;gap:8px;align-items:center;margin-top:6px">${tImg?`<img src="${tImg}" style="width:28px;height:28px;border-radius:6px;object-fit:cover;flex-shrink:0">`:`<div style="width:28px;height:28px;border-radius:6px;background:#222;display:grid;place-items:center;flex-shrink:0">♫</div>`}<div style="min-width:0;flex:1"><div class="fav-track">${escapeHtml(tName)}</div><div class="fav-meta">${escapeHtml(tNames.join(", "))} • ${tPlays} execuções</div></div><a href="#" onclick="openExternal('https://open.spotify.com/track/${tId}');return false;" style="font-size:11px;color:#1DB954">${tId?"↗":""}</a></div>`;
        // click whole card opens artist/track
        card.querySelector(".fav-artist-row")?.addEventListener("click", ()=>{ if(aId) openExternal("https://open.spotify.com/artist/"+aId); });
        grid.appendChild(card);
      });
      // enrich fav artist images
      setTimeout(()=>{
        const favArtists = periods.map(pp=> fav[pp.key]).filter(Boolean).map(d=> ({artist_id: d.top_artist_id, artist_names: d.top_artist_names})).filter(a=>a.artist_id);
        if(favArtists.length) enrichArtistsWithImages(favArtists);
        // also patch fav artist rows with cached images if already cached
        favArtists.forEach(a=>{
          const cached = localStorage.getItem("artist_img_"+a.artist_id);
          if(cached){
            document.querySelectorAll(`[data-artist-id-fav="${CSS.escape(a.artist_id)}"] .fav-artist-img`).forEach(el=>{
              el.innerHTML=`<img src="${cached}" style="width:28px;height:28px;border-radius:14px;object-fit:cover">`;
            });
          }
        });
      }, 600);
    }
  } catch(e){ console.error("fav",e); }

  // --- Personalidade musical (Brasília) inspirada Wrapped / YT Recap ---
  try {
    const total = stats.total_plays||0;
    const skipped = stats.skipped||0;
    const skipRate = stats.skip_rate||0;
    const dp = stats.daypart||{};
    const peakPeriod = stats.peak_period||"noite";
    const hours = stats.hourly||[];
    let maxH=-1, peakHour=-1;
    hours.forEach((v,i)=>{ if(v>maxH){maxH=v; peakHour=i;}});
    const discovery = total? Math.round((stats.unique_tracks||0)*100/total):0;
    const dayparts = [dp.madrugada||0, dp.manha||0, dp.tarde||0, dp.noite||0];
    const maxDp = Math.max(...dayparts);
    let personality="", desc="", badges=[];
    // determina período
    const periodNames={madrugada:"Coruja 🌙", manha:"Madrugador ☀️", tarde:"Vespertino 🌤️", noite:"Notívago 🌃"};
    const period = periodNames[peakPeriod]||"Notívago";
    // diversidade
    let archetype="";
    if(discovery>60) archetype="Explorador";
    else if(discovery>35) archetype="Eclético";
    else if(skipRate>25) archetype="Seletivo";
    else archetype="Fiel";
    // lealdade
    let loyalty="";
    if(stats.longest_streak && stats.longest_streak>=7) loyalty="Maratonista";
    else if(stats.current_streak && stats.current_streak>=3) loyalty="Constante";
    else loyalty="Casual";
    personality = `${period} • ${archetype}`;
    if(peakHour>=22 || peakHour<5) desc = `Você vive de música na madrugada (pico ${String(peakHour).padStart(2,"0")}:00 Brasília). ${skipRate>20?"Seletivo, pula o que não te prende.":"Fiel, deixa tocar até o fim."} ${discovery>50?"Adora descobrir faixas novas.":"Revisita seus clássicos."}`;
    else if(peakHour>=6 && peakHour<12) desc = `Manhãs com trilha sonora. Pico às ${String(peakHour).padStart(2,"0")}:00. ${archetype} que equilibra novidade e repetição.`;
    else if(peakHour>=12 && peakHour<18) desc = `Tarde é seu palco. Pico ${String(peakHour).padStart(2,"0")}:00. ${loyalty} com ${stats.active_days||0} dias ativos.`;
    else desc = `Noites intensas. Pico ${String(peakHour).padStart(2,"0")}:00. ${archetype} com ${discovery}% de descoberta.`;
    // badges
    badges = [period, archetype, loyalty, `${skipRate}% skip`, `${discovery}% descoberta`, peakPeriod];
    if(stats.active_days) badges.push(`${stats.active_days} dias ativos`);
    if(stats.longest_streak) badges.push(`streak ${stats.longest_streak}d`);
    $("#personalityCard").style.display="block";
    $("#personalityDesc").textContent = desc;
    $("#personalityBadges").innerHTML = badges.map(b=>`<span class="chip" style="background:#1d1d1d;border-color:#2a2a2a">${b}</span>`).join("");
    $("#personalitySub").textContent = `Baseado em ${total} plays em Horário de Brasília (America/Sao_Paulo) • ${stats.active_days||0} dias • ${stats.longest_streak||0}d streak máximo`;
  } catch(e){
    console.error("personality",e);
  }

  // --- Peak Day Year (Brasília) ---
  try {
    const peak = stats.peak_day_year;
    const card = $("#peakDayCard");
    if(peak && peak.date){
      card.style.display="block";
      const dur = peak.duration_ms ? formatMs(peak.duration_ms) : "";
      const weekday = peak.weekday ? ` • ${peak.weekday}` : "";
      document.getElementById("peakDayContent").innerHTML = `<div class="t-item" style="border-color:rgba(29,185,84,.3)"><div style="width:48px;height:48px;border-radius:10px;background:rgba(29,185,84,.15);display:grid;place-items:center;border:1px solid rgba(29,185,84,.3)">📅</div><div class="t-info"><div class="t-title">${peak.date}${weekday}</div><div class="t-artist">${peak.plays} plays • ${dur} de música • Horário de Brasília</div><div class="t-meta">Ano corrente • toque para ver dia</div></div><span class="badge">${peak.plays} faixas</span></div>`;
      document.getElementById("peakDayContent").querySelector(".t-item").addEventListener("click", ()=>{ $("#datePicker").value=peak.date; switchTab("timeline"); renderTimeline(); });
    } else if(card){
      card.style.display="none";
    }
  } catch(e){ console.error("peak",e); }

  // --- Top-3 single day (mais plays em 24h) ---
  try {
    const top3 = stats.top3_single_day||[];
    const card = $("#topSingleDayCard");
    if(card && top3.length){
      card.style.display="block";
      const list = $("#topSingleDayList");
      list.innerHTML="";
      top3.forEach((t,i)=>{
        let names=[];
        try{ names = Array.isArray(t.artist_names)? t.artist_names : JSON.parse(t.artist_names||"[]"); }catch(e){ try{ names=JSON.parse(t.artist_names||"[]"); }catch(e2){} }
        const img = t.album_image? `<img src="${t.album_image}" style="width:48px;height:48px;border-radius:6px;object-fit:cover">` : `<div style="width:48px;height:48px;border-radius:6px;background:#222;display:grid;place-items:center">♫</div>`;
        const date = t.date||"—";
        const plays = t.plays||0;
        const div=document.createElement("div");
        div.className="t-item";
        div.style.borderColor = i===0 ? "rgba(29,185,84,.4)" : "var(--line)";
        div.innerHTML=`<span style="font-weight:900;color:var(--accent);width:18px">${i+1}</span> ${img}<div class="t-info"><div class="t-title" style="font-size:13px">${escapeHtml(t.track_name)}</div><div class="t-artist">${escapeHtml(names.join(", "))}</div><div class="t-meta">${date} • ${plays} plays em 24h (Brasília)</div></div><a href="#" onclick="openExternal('https://open.spotify.com/track/${t.track_id}');return false;" style="font-size:11px;color:#1DB954">↗</a> <span class="badge">${plays}×</span>`;
        div.addEventListener("click", (e)=>{ if(e.target.tagName!=="A" && t.track_id) openExternal("https://open.spotify.com/track/"+t.track_id); else if(e.target.tagName!=="A" && t.track_id) {} });
        // click no card leva ao dia
        div.addEventListener("click", (e)=>{
          if(e.target.tagName==="A") return;
          $("#datePicker").value = t.date;
          switchTab("timeline");
          renderTimeline();
          toast(`Indo para ${t.date} • ${plays} plays`);
        });
        list.appendChild(div);
      });
    } else if(card){
      card.style.display="none";
    }
  } catch(e){ console.error("topSingleDay",e); }

  // --- Daypart + Monthly: só legendas aqui, charts vão via rAF (1 layout) ---
  try {
    const dp = stats.daypart||{madrugada:0,manha:0,tarde:0,noite:0};
    const totalDp = (dp.madrugada||0)+(dp.manha||0)+(dp.tarde||0)+(dp.noite||0);
    const fmt = (v)=> totalDp? Math.round(v*100/totalDp):0;
    $("#daypartLegend").innerHTML = `<span class="chip">🌙 ${fmt(dp.madrugada)}% madrugada</span><span class="chip">☀️ ${fmt(dp.manha)}% manhã</span><span class="chip">🌤️ ${fmt(dp.tarde)}% tarde</span><span class="chip">🌃 ${fmt(dp.noite)}% noite</span>`;
    $("#peakPeriod").textContent = stats.peak_period ? `${stats.peak_period} (${_fmtNumBR.format(Math.max(dp.madrugada,dp.manha,dp.tarde,dp.noite))} plays)` : "—";
  } catch(e){}

  // --- Monthly evolution ---
  try {
    const topM = (stats.monthly||[])[0];
    const el = $("#monthlyTop");
    if(topM){
      el.innerHTML = `<span class="chip" style="background:rgba(29,185,84,.15);border-color:rgba(29,185,84,.4)">${topM.month} top: ${_fmtNumBR.format(topM.plays)} plays</span>`;
    } else {
      el.innerHTML = `<span class="hint">Sem histórico mensal ainda</span>`;
    }
  } catch(e){}

  // --- Streaks & marcos ---
  try {
    const sEl = $("#streakInfo");
    const mEl = $("#milestones");
    sEl.innerHTML = `<span>🔥 atual: ${stats.current_streak||0}d</span><span>🏆 recorde: ${stats.longest_streak||0}d</span><span>📅 ${stats.active_days||0} dias ativos</span>`;
    let miles=[];
    if(stats.total_plays) miles.push(`Primeiro play em ${stats.daily && stats.daily.length ? stats.daily[stats.daily.length-1].date : "—"}`);
    if(stats.total_plays>=100) miles.push(`100 plays alcançados`);
    if(stats.total_plays>=1000) miles.push(`1000 plays`);
    if(stats.unique_tracks) miles.push(`${stats.unique_tracks} músicas únicas`);
    if(stats.avg_ms_played) miles.push(`Média tocada ${Math.round(stats.avg_ms_played/1000)}s`);
    mEl.innerHTML = miles.map(x=>`<div class="t-item" style="padding:8px"><span style="color:var(--accent)">•</span> <span style="font-size:12px">${x}</span></div>`).join("") || '<span class="hint">Sem marcos</span>';
  } catch(e){}

  scheduleCharts(stats);
  const topTracksEl = $("#topTracks");
  topTracksEl.innerHTML="";
  const _fragTop = document.createDocumentFragment();
  (stats.top_tracks||[]).slice(0,10).forEach((t,i)=>{
    const names = safeParseNames(t.artist_names);
    const img = t.album_image? `<img src="${t.album_image}" loading="lazy" decoding="async" width="40" height="40" style="width:40px;height:40px;border-radius:6px;object-fit:cover">` : `<div style="width:40px;height:40px;border-radius:6px;background:#222;display:grid;place-items:center">♫</div>`;
    const div=document.createElement("div");
    div.className="t-item";
    div.style.cursor="pointer";
    if(t.track_id) div.dataset.tid = t.track_id;
    div.innerHTML=`<span style="font-weight:900;color:var(--accent);width:18px">${i+1}</span> ${img} <div class="t-info"><div class="t-title" style="font-size:13px">${escapeHtml(t.track_name)}</div><div class="t-artist">${escapeHtml(names.join(", "))}</div></div><span class="badge">${_fmtNumBR.format(t.plays)}×</span> ${t.track_id?`<span style="font-size:11px;color:#1DB954">↗</span>`:""}`;
    _fragTop.appendChild(div);
  });
  topTracksEl.appendChild(_fragTop);
  if(!topTracksEl.children.length) topTracksEl.innerHTML='<span class="hint">Sem dados</span>';
  else if(!topTracksEl._delegated){ topTracksEl._delegated=true; topTracksEl.addEventListener("click",(e)=>{ const el=e.target.closest(".t-item"); if(el&&el.dataset.tid) openExternal("https://open.spotify.com/track/"+el.dataset.tid); }); }

  const topArtistsEl = $("#topArtists");
  topArtistsEl.innerHTML="";
  const artists = (stats.top_artists||[]).slice(0,10);
  const _fragArt = document.createDocumentFragment();
  artists.forEach((a,i)=>{
    const names = safeParseNames(a.artist_names);
    const artistId = a.artist_id||"";
    const displayName = names.join(", ")||"—";
    const placeholder = `<div data-artist-id="${escapeHtml(artistId)}" style="width:40px;height:40px;border-radius:20px;background:#111;display:grid;place-items:center;border:1px solid #222;overflow:hidden" class="artist-img"><span>👤</span></div>`;
    const div=document.createElement("div");
    div.className="t-item";
    div.style.cursor="pointer";
    if(artistId) div.dataset.aid = artistId; else div.dataset.aname = displayName;
    div.innerHTML=`<span style="font-weight:900;color:var(--accent);width:18px">${i+1}</span> ${placeholder} <div class="t-info"><div class="t-title" style="font-size:13px">${escapeHtml(displayName)}</div><div class="t-artist">${_fmtNumBR.format(a.plays)} plays</div></div> ${artistId?'<span style="font-size:11px;color:#1DB954">↗</span>':""}`;
    _fragArt.appendChild(div);
  });
  topArtistsEl.appendChild(_fragArt);
  if(!topArtistsEl.children.length) topArtistsEl.innerHTML='<span class="hint">Sem dados</span>';
  else if(!topArtistsEl._delegated){ topArtistsEl._delegated=true; topArtistsEl.addEventListener("click",(e)=>{ const el=e.target.closest(".t-item"); if(!el) return; if(el.dataset.aid) openExternal("https://open.spotify.com/artist/"+el.dataset.aid); else if(el.dataset.aname) openExternal("https://open.spotify.com/search/"+encodeURIComponent(el.dataset.aname)); }); }
  // fetch images async (throttled, só se aba visível)
  if(document.visibilityState==="visible") enrichArtistsWithImages(artists);

  // top skipped
  const cardRejected = $("#cardRejected");
  const topSkippedEl = $("#topSkipped");
  if(stats.skipped && stats.skipped>0){
    cardRejected.style.display="block";
    topSkippedEl.innerHTML="";
    const _fragSkip = document.createDocumentFragment();
    (stats.top_skipped||[]).slice(0,8).forEach((t,i)=>{
      const names = safeParseNames(t.artist_names);
      const div=document.createElement("div");
      div.className="t-item";
      div.style.borderColor="rgba(255,59,48,.25)";
      if(t.track_id) div.dataset.tid = t.track_id;
      div.innerHTML=`<span style="font-weight:900;color:#ff453a;width:18px">${i+1}</span> <div style="width:40px;height:40px;border-radius:6px;background:#221111;display:grid;place-items:center">🚫</div> <div class="t-info"><div class="t-title" style="font-size:13px">${escapeHtml(t.track_name)}</div><div class="t-artist">${escapeHtml(names.join(", "))}</div></div><span class="badge" style="background:rgba(255,59,48,.15);color:#ff9a93;border:1px solid rgba(255,59,48,.3)">${_fmtNumBR.format(t.skipped)} pulos</span>`;
      _fragSkip.appendChild(div);
    });
    topSkippedEl.appendChild(_fragSkip);
    if(!topSkippedEl._delegated){ topSkippedEl._delegated=true; topSkippedEl.addEventListener("click",(e)=>{ const el=e.target.closest(".t-item"); if(el&&el.dataset.tid) openExternal("https://open.spotify.com/track/"+el.dataset.tid); }); }
  } else {
    cardRejected.style.display="none";
  }

  const sessEl = $("#sessionInfo");
  const hintEl = $("#skipHint");
  if(stats.total_plays){
    try{
      // Sessões a partir de daily (sem getAllPlays pesado): aproxima sessões = dias ativos * 1.8
      const daily = stats.daily||[];
      const activeDays = stats.active_days || daily.length || 1;
      const total = stats.total_plays||0;
      const avgPerDay = total/Math.max(1,activeDays);
      // sessões ≈ total / max(4, avgPerDay/2) — heurística leve, sem parse de 500 plays
      const sessions = Math.max(1, Math.round(total/Math.max(4, avgPerDay/1.5)));
      const avgPerSession = total/sessions;
      const skipped = stats.skipped||0;
      const rate = stats.skip_rate||0;
      sessEl.innerHTML=`<span>${_fmtNumBR.format(sessions)} sessões</span><span>${avgPerSession.toFixed(1)} faixas/sessão</span><span>${_fmtNumBR.format(skipped)} puladas</span><span>skip ${rate}%</span><span>${_fmtNumBR.format(activeDays)} dias ativos</span>`;
      if(skipped>0){
        hintEl.textContent = `Rejeição real via Extended History: ${skipped} faixas marcadas skipped=1 ou reason_end=fwdbtn/backbtn ou <45% tocado.`;
        hintEl.style.color="#ff9a93";
      } else {
        hintEl.textContent = `Rejeição estimada por gaps curtos (API só conta >30s). Importe o Extended History (ZIP) no Config para dado preciso com ms_played.`;
        hintEl.style.color="var(--muted)";
      }
      const hourly = stats.hourly||[];
      let max=0, idx=-1;
      hourly.forEach((v,i)=>{ if(v>max){max=v; idx=i;} });
      $("#peakHour").textContent = idx>=0? `${String(idx).padStart(2,"0")}:00 (Brasília, ${_fmtNumBR.format(max)} plays)`: "—";
    }catch(e){
      sessEl.innerHTML=`<span class="hint">erro sessões: ${e.message}</span>`;
    }
  } else {
    sessEl.innerHTML='<span class="hint">Sem dados para sessões</span>';
    hintEl.textContent="";
  }
  const heat = $("#heatmap");
  heat.innerHTML="";
  const daily = stats.daily||[];
  const map = {};
  daily.forEach(d=> map[d.date]=d.plays);
  // dataviz: legenda + escala por quantil (não quebra p/ heavy users)
  const valsSorted = daily.map(d=>d.plays).sort((a,b)=>a-b);
  const qAt = (p)=> valsSorted.length? valsSorted[Math.min(valsSorted.length-1, Math.floor(valsSorted.length*p))] : 1;
  const t4 = Math.max(12, qAt(0.85)), t3 = Math.max(7, qAt(0.6)), t2 = Math.max(3, qAt(0.3));
  const head = document.createElement("div");
  head.className = "hint";
  head.style.cssText = "grid-column:1/-1;display:flex;justify-content:space-between";
  head.innerHTML = `<span>D S T Q Q S S — 30 dias</span><span>Menos <i class="heat-cell" style="display:inline-block;width:10px;height:10px"></i> <i class="heat-cell lv2" style="display:inline-block;width:10px;height:10px"></i> <i class="heat-cell lv4" style="display:inline-block;width:10px;height:10px"></i> Mais</span>`;
  heat.appendChild(head);
  const _fragHeat = document.createDocumentFragment();
  const today = new Date();
  for(let i=29;i>=0;i--){
    const d=new Date(today); d.setDate(today.getDate()-i);
    const iso=toISODate(d);
    const cnt=map[iso]||0;
    const cell=document.createElement("div");
    let cls="heat-cell";
    if(cnt>=t4) cls+=" lv4";
    else if(cnt>=t3) cls+=" lv3";
    else if(cnt>=t2) cls+=" lv2";
    else if(cnt>=1) cls+=" lv1";
    cell.className=cls;
    cell.title=`${iso}: ${cnt} plays (Brasília)`;
    cell.textContent = String(d.getDate());
    _fragHeat.appendChild(cell);
  }
  heat.appendChild(_fragHeat);
}
let _enrichAbort = 0;
async function enrichArtistsWithImages(artists){
  // Throttled: máx 6 artistas, 3 workers paralelos, TTL 30d, sem sleep artificial
  const myRun = ++_enrichAbort;
  let token=null;
  try{ token = await refreshTokenIfNeeded(); }catch(e){ return; }
  if(!token || myRun!==_enrichAbort) return;
  const seen = new Set();
  const ids = [];
  for(const a of (artists||[])){
    const id = a && a.artist_id;
    if(id && !seen.has(id)){ seen.add(id); ids.push({id}); }
    if(ids.length>=6) break;
  }
  // 1) aplica cache com TTL antes de rede (sem reflow pingado: batch)
  const pending = [];
  const now = Date.now(), TTL = 30*24*3600*1000;
  for(const e of ids){
    try{
      const raw = localStorage.getItem("artist_img_"+e.id);
      if(raw){
        try{
          const o = JSON.parse(raw);
          if(o && o.url && (now-(o.ts||0))<TTL){ updateArtistImgById(e.id, o.url); continue; }
        }catch(_){ updateArtistImgById(e.id, raw); continue; }
      }
    }catch(_){}
    pending.push(e);
  }
  if(!pending.length) return;
  const queue = pending.slice();
  async function fetchOne(e){
    if(myRun!==_enrichAbort) return;
    try{
      const r = await fetch(`https://api.spotify.com/v1/artists/${encodeURIComponent(e.id)}`, {headers:{Authorization:"Bearer "+token}});
      if(r.status===429){
        const retry = parseInt(r.headers.get("Retry-After")||"2",10);
        await new Promise(res=>setTimeout(res, Math.min(8,retry)*1000));
        return;
      }
      if(!r.ok) return;
      const j = await r.json();
      const img = j.images && j.images[0] ? j.images[0].url : "";
      if(img && myRun===_enrichAbort){
        try{ localStorage.setItem("artist_img_"+e.id, JSON.stringify({url:img, ts:Date.now()})); }catch(_){}
        updateArtistImgById(e.id, img);
      }
    }catch(_){}
  }
  const workers = [0,1,2].map(async ()=>{
    while(queue.length && myRun===_enrichAbort){
      const e = queue.shift();
      if(e) await fetchOne(e);
    }
  });
  await Promise.all(workers);
}
function updateArtistImgById(id, url){
  document.querySelectorAll(`[data-artist-id="${CSS.escape(id)}"]`).forEach(el=>{
    el.innerHTML = `<img src="${url}" style="width:40px;height:40px;border-radius:20px;object-fit:cover">`;
  });
}
function updateArtistImg(name, url){
  // fallback por nome - atualiza primeiro correspondente sem id
  document.querySelectorAll(".artist-img").forEach(el=>{
    const parent = el.closest(".t-item");
    if(parent && parent.textContent.includes(name) && !el.querySelector("img")){
      el.innerHTML = `<img src="${url}" style="width:40px;height:40px;border-radius:20px;object-fit:cover">`;
    }
  });
}
function drawDaypartChart(vals){
  const s = setupCanvas("#chartDaypart", 140);
  if(!s) return;
  const {ctx, W, H} = s;
  ctx.clearRect(0,0,W,H);
  const total = vals.reduce((a,b)=>a+b,0);
  if(!total){
    ctx.fillStyle="#c9c9c9"; ctx.font=`12px system-ui`;
    ctx.fillText("Sem dados — faça sync", 10, H/2);
    return;
  }
  const colors=["#8b5cf6","#1DB954","#f59e0b","#3b82f6"]; // madrugada contrastante (dataviz)
  // donut
  const cx=W/2, cy=H/2, r=Math.min(W,H)*0.33, r2=r*0.62;
  let ang=-Math.PI/2;
  vals.forEach((v,i)=>{
    const slice = v/total*2*Math.PI;
    ctx.beginPath();
    ctx.moveTo(cx,cy);
    ctx.arc(cx,cy,r,ang,ang+slice);
    ctx.closePath();
    ctx.fillStyle=colors[i];
    ctx.fill();
    ang+=slice;
  });
  // hole
  ctx.beginPath();
  ctx.arc(cx,cy,r2,0,2*Math.PI);
  ctx.fillStyle="#0f0f0f";
  ctx.fill();
  // center text
  ctx.fillStyle="#fff";
  ctx.font=`bold 14px system-ui`;
  ctx.textAlign="center";
  ctx.fillText(_fmtNumBR.format(total)+" plays", cx, cy+4);
  ctx.textAlign="left";
}
function drawMonthlyChart(monthly){
  const s = setupCanvas("#chartMonthly", 120);
  if(!s) return;
  const {ctx, W, H} = s;
  ctx.clearRect(0,0,W,H);
  if(!monthly || !monthly.length){
    ctx.fillStyle="#c9c9c9"; ctx.font=`12px system-ui`;
    ctx.fillText("Sem histórico mensal — importe ZIP em Config", 10, H/2);
    return;
  }
  // monthly está DESC, inverter para cronológico
  const data = [...monthly].reverse();
  let max=1; for(const d of data) if(d.plays>max) max=d.plays;
  const pad = 20;
  const barW = (W - pad*2)/data.length;
  data.forEach((d,i)=>{
    const h = (d.plays/max)*(H - pad*2);
    const x = pad + i*barW + barW*0.15;
    const y = H - pad - h;
    const w = barW*0.7;
    ctx.fillStyle = i===data.length-1 ? "#1DB954" : "#3a3a3a";
    roundRect(ctx, x, y, w, h, 4);
    ctx.fill();
  });
  ctx.fillStyle="#c9c9c9";
  ctx.font=`11px system-ui`;
  const mesesPt = { "01":"jan","02":"fev","03":"mar","04":"abr","05":"mai","06":"jun","07":"jul","08":"ago","09":"set","10":"out","11":"nov","12":"dez" };
  data.forEach((d,i)=>{
    if(i%2===0 || i===data.length-1){
      const x = pad + i*barW + barW/2;
      const mm = String(d.month||"").slice(5);
      ctx.fillText(mesesPt[mm]||mm, x-10, H-4);
    }
  });
  // click tooltip
  const c = s.c;
  if(!c._tip){ c._tip=true; c.style.cursor="pointer"; c.onclick=(e)=>{
    const r=c.getBoundingClientRect(); const i=Math.max(0,Math.min(data.length-1, Math.floor((e.clientX-r.left-pad)/barW)));
    const d=data[i]; if(d) toast(`${d.month}: ${_fmtNumBR.format(d.plays)} plays`);
  }; }
}

function drawDailyChart(daily){
  const s = setupCanvas("#chartDaily", 120);
  if(!s) return;
  const {ctx, W, H} = s;
  ctx.clearRect(0,0,W,H);
  if(!daily.length){
    ctx.fillStyle="#c9c9c9"; ctx.font=`12px system-ui`;
    ctx.fillText("Sem dados — faça sync", 10, H/2);
    return;
  }
  let max=1; for(const d of daily) if(d.plays>max) max=d.plays;
  const pad = 28;
  // eixo Y leve (3 linhas pt-BR)
  ctx.fillStyle="#c9c9c9"; ctx.font=`10px system-ui`; ctx.strokeStyle="#2e2e2e"; ctx.lineWidth=1;
  for(let g=0; g<=2; g++){
    const v = Math.round(max*g/2);
    const y = H-pad-(v/max)*(H-pad*2);
    ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(W-6,y); ctx.stroke();
    ctx.fillText(_fmtNumBR.format(v), 2, y+3);
  }
  const barW = (W - pad-6)/daily.length;
  daily.forEach((d,i)=>{
    const h = (d.plays/max)*(H - pad*2);
    const x = pad + i*barW + barW*0.15;
    const y = H - pad - h;
    const w = barW*0.7;
    ctx.fillStyle = i===daily.length-1? "#1DB954" : "#3a3a3a";
    roundRect(ctx, x, y, w, h, 4);
    ctx.fill();
  });
  ctx.fillStyle="#c9c9c9";
  ctx.font=`10px system-ui`;
  daily.forEach((d,i)=>{
    if(i%5===0 || i===daily.length-1){
      const x = pad + i*barW + barW/2;
      ctx.fillText(String(d.date||"").slice(8,10)+"/"+String(d.date||"").slice(5,7), x-12, H-4);
    }
  });
  const c = s.c;
  if(!c._tip){ c._tip=true; c.style.cursor="pointer"; c.onclick=(e)=>{
    const r=c.getBoundingClientRect(); const i=Math.max(0,Math.min(daily.length-1, Math.floor((e.clientX-r.left-pad)/barW)));
    const d=daily[i]; if(d) toast(`${String(d.date).slice(8,10)}/${String(d.date).slice(5,7)}: ${_fmtNumBR.format(d.plays)} plays`);
  }; }
}
function drawHourlyChart(hourly){
  const s = setupCanvas("#chartHourly", 120);
  if(!s) return;
  const {ctx, W, H} = s;
  ctx.clearRect(0,0,W,H);
  if(!hourly.length || hourly.every(v=>v===0)){
    ctx.fillStyle="#c9c9c9"; ctx.font=`12px system-ui`;
    ctx.fillText("Sem dados", 10, H/2);
    return;
  }
  let max=1; for(const v of hourly) if(v>max) max=v;
  const pad = 28;
  ctx.fillStyle="#c9c9c9"; ctx.font=`10px system-ui`; ctx.strokeStyle="#2e2e2e"; ctx.lineWidth=1;
  for(let g=0; g<=2; g++){
    const v = Math.round(max*g/2);
    const y = H-pad-(v/max)*(H-pad*2);
    ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(W-6,y); ctx.stroke();
    ctx.fillText(_fmtNumBR.format(v), 2, y+3);
  }
  const barW = (W - pad-6)/24;
  hourly.forEach((v,i)=>{
    const h = (v/max)*(H - pad*2);
    const x = pad + i*barW + barW*0.1;
    const y = H - pad - h;
    const w = barW*0.8;
    ctx.fillStyle = v===max? "#1DB954" : "#3a3a3a";
    roundRect(ctx, x, y, w, h, 3);
    ctx.fill();
  });
  ctx.fillStyle="#c9c9c9";
  ctx.font=`10px system-ui`;
  for(let i=0;i<24;i+=3){
    const x = pad + i*barW + barW/2;
    ctx.fillText(String(i).padStart(2,"0")+":00", x-10, H-4);
  }
  const c = s.c;
  if(!c._tip){ c._tip=true; c.style.cursor="pointer"; c.onclick=(e)=>{
    const r=c.getBoundingClientRect(); const i=Math.max(0,Math.min(23, Math.floor((e.clientX-r.left-pad)/barW)));
    toast(`${String(i).padStart(2,"0")}:00 — ${_fmtNumBR.format(hourly[i]||0)} plays`);
  }; }
}
function roundRect(ctx,x,y,w,h,r){
  ctx.beginPath();
  ctx.moveTo(x+r, y);
  ctx.lineTo(x+w-r, y);
  ctx.quadraticCurveTo(x+w, y, x+w, y+r);
  ctx.lineTo(x+w, y+h);
  ctx.lineTo(x, y+h);
  ctx.lineTo(x, y+r);
  ctx.quadraticCurveTo(x, y, x+r, y);
  ctx.closePath();
}

// ---------- library (alto risco: busca/ordem no SQL, paginado) ----------
function bridgeSearchPlays(q, sort, limit, offset){
  if(isAndroid() && typeof Android.searchPlays === "function"){
    try{ return JSON.parse(Android.searchPlays(q||"", sort||"recent", limit, offset)||"[]"); }catch(e){ return []; }
  }
  return null; // fallback JS (browser ou APK antigo)
}
function bridgeCountSearch(q){
  if(isAndroid() && typeof Android.countSearchPlays === "function"){
    try{ return Number(Android.countSearchPlays(q||""))||0; }catch(e){ return 0; }
  }
  return -1;
}
function renderLibrary(){
  const qRaw = $("#searchLib").value.trim();
  const q = qRaw.toLowerCase();
  const sort = $("#sortLib").value;
  const page = state.libPage;
  const limit = state.libLimit;
  const stats = bridgeGetStatsCached(15000);
  const totalPlays = stats.total_plays||0;
  const useSql = isAndroid() && typeof Android.searchPlays === "function";
  let all=[];
  let totalForPager = totalPlays;
  if(useSql && (sort==="recent" || sort==="artist")){
    // Caminho SQL: filtro LIKE + ORDER BY + LIMIT/OFFSET no SQLite (sem full-dump)
    totalForPager = q ? bridgeCountSearch(qRaw) : totalPlays;
    if(totalForPager < 0) totalForPager = totalPlays;
    const offset = page*limit;
    all = bridgeSearchPlays(qRaw, sort, limit, offset) || [];
    for(const p of all){
      if(p._ms===undefined){ try{ p._ms = p.played_at_ms || new Date(p.played_at).getTime()||0; }catch(e){ p._ms=0; } }
      if(p._names===undefined) p._names = safeParseNames(p.artist_names);
    }
  } else if(useSql && (sort==="plays" || sort==="skipped")){
    // Agregação continua no JS, mas sobre conjunto filtrado limitado a 200 (antes: 2000)
    const filtered = bridgeSearchPlays(qRaw, "recent", 200, 0) || [];
    for(const p of filtered){
      if(p._ms===undefined){ try{ p._ms = p.played_at_ms || new Date(p.played_at).getTime()||0; }catch(e){ p._ms=0; } }
      if(p._names===undefined) p._names = safeParseNames(p.artist_names);
      if(p._an0===undefined) p._an0 = (p._names[0]||"").toLowerCase();
      if(p._tn===undefined) p._tn = (p.track_name||"").toLowerCase();
      if(p._aln===undefined) p._aln = (p.album_name||"").toLowerCase();
    }
    all = filtered;
  } else if(totalPlays<2500){
    all = isAndroid()? JSON.parse(Android.getAllPlays(2000,0)||"[]") : JSON.parse(localStorage.getItem("mock_plays")||"[]");
    // normaliza 1x: ms + nomes (evita Date/JSON.parse no sort/filter)
    for(const p of all){
      if(p._ms===undefined){ try{ p._ms = new Date(p.played_at).getTime()||0; }catch(e){ p._ms=0; } }
      if(p._names===undefined) p._names = safeParseNames(p.artist_names);
      if(p._an0===undefined) p._an0 = (p._names[0]||"").toLowerCase();
      if(p._tn===undefined) p._tn = (p.track_name||"").toLowerCase();
      if(p._aln===undefined) p._aln = (p.album_name||"").toLowerCase();
    }
    if(sort==="recent") all.sort((a,b)=> b._ms-a._ms);
  } else {
    const offset = page*limit;
    all = JSON.parse(isAndroid()? Android.getAllPlays(limit, offset) : "[]");
    for(const p of all){ if(p._names===undefined) p._names = safeParseNames(p.artist_names); }
  }
  if(!useSql && q){
    all = all.filter(p=> p._tn.includes(q) || (p._namesJoined || (p._namesJoined = p._names.join(", ").toLowerCase())).includes(q) || p._aln.includes(q));
  }
  if(sort==="plays" && (!useSql || totalPlays<2500)){
    const map={};
    const src = useSql ? all : all;
    src.forEach(p=>{
      const k=p.track_id||p.track_name;
      if(!map[k]) map[k]={...p, _cnt:0, _names: p._names};
      map[k]._cnt++;
    });
    all = Object.values(map).sort((a,b)=> b._cnt-a._cnt);
  } else if(sort==="artist" && !useSql){
    all.sort((a,b)=> (a._an0<b._an0?-1:a._an0>b._an0?1:0));
  } else if(sort==="skipped"){
    for(const p of all){ if(p._rej===undefined) p._rej = isRejected(p); }
    all.sort((a,b)=> (b._rej?1:0)-(a._rej?1:0) || b._ms-a._ms);
  }
  let pageSlice = all;
  let totalPages = 1;
  const sqlPaged = useSql && (sort==="recent" || sort==="artist");
  if(sqlPaged){
    totalPages = Math.max(1, Math.ceil(totalForPager/limit));
    if(page>=totalPages) state.libPage = totalPages-1;
    pageSlice = all; // já é a página do SQL
  } else if(totalPlays<2500 || (useSql && (sort==="plays" || sort==="skipped"))){
    totalPages = Math.max(1, Math.ceil(all.length/limit));
    if(page>=totalPages) state.libPage= totalPages-1;
    pageSlice = all.slice(state.libPage*limit, state.libPage*limit+limit);
  } else {
    totalPages = Math.ceil(totalPlays/limit);
    pageSlice = all;
  }
  const shownTotal = sqlPaged ? totalForPager : all.length;
  $("#libCount").textContent = `${_fmtNumBR.format(shownTotal)} registros ${q?`(filtrado de ${_fmtNumBR.format(totalPlays)})`: `(${_fmtNumBR.format(totalPlays)} total)`}`;
  $("#libPageInfo").textContent = `pág ${state.libPage+1}/${totalPages}`;
  $("#libPrev").disabled = state.libPage===0;
  $("#libNext").disabled = state.libPage>=totalPages-1;
  const list = $("#libraryList");
  list.innerHTML="";
  if(!pageSlice.length){
    list.innerHTML='<div class="empty"><div class="empty-icon">∅</div><p>Nenhum registro</p></div>';
    return;
  }
  const _fragLib = document.createDocumentFragment();
  pageSlice.forEach(p=>{
    const names = p._names || safeParseNames(p.artist_names);
    const img = p.album_image? `<img src="${p.album_image}" loading="lazy" decoding="async" width="48" height="48" style="width:48px;height:48px;border-radius:6px;object-fit:cover">` : `<div style="width:48px;height:48px;border-radius:6px;background:#222;display:grid;place-items:center">♫</div>`;
    const badge = p._cnt? `<span class="badge">${_fmtNumBR.format(p._cnt)}×</span>` : `<span class="badge">${formatMs(p.duration_ms||0)}</span>`;
    const rej = (p._rej!==undefined? p._rej : isRejected(p)) ? `<span style="background:rgba(255,59,48,.15);color:#ff9a93;border:1px solid rgba(255,59,48,.3);padding:2px 6px;border-radius:20px;font-size:10px">pulada</span>` : "";
    const item=document.createElement("div");
    item.className="t-item";
    if(p.track_id) item.dataset.tid = p.track_id;
    if(p._rej) item.style.opacity ="0.85";
    item.innerHTML=`${img}<div class="t-info"><div class="t-title">${escapeHtml(p.track_name)}</div><div class="t-artist">${escapeHtml(names.join(", "))} • ${escapeHtml(p.album_name||"")}</div><div class="t-meta">${escapeHtml(p.played_at? new Date(p.played_at).toLocaleString("pt-BR"): "")} ${badge} ${rej}</div></div><span style="font-size:11px;color:#1DB954">↗</span>`;
    _fragLib.appendChild(item);
  });
  list.appendChild(_fragLib);
  if(!list._delegated){ list._delegated=true; list.addEventListener("click",(e)=>{ const el=e.target.closest(".t-item"); if(el&&el.dataset.tid) openExternal("https://open.spotify.com/track/"+el.dataset.tid); }); }
}

// ---------- import extended history (ZIP + JSON) ----------
async function handleImportFiles(e){
  const files = Array.from(e.target.files||[]);
  if(!files.length) return;
  const logEl=$("#importLog");
  const sumEl=$("#importSummary");
  logEl.textContent="";
  if(sumEl) sumEl.innerHTML="";
  let totalInserted=0;
  let totalParsed=0;
  let totalSkipped=0;

  // helper to process array of entries
  async function processEntries(entries, sourceName){
    if(!entries || !entries.length) return;
    totalParsed+=entries.length;
    logEl.textContent+=`  → ${entries.length} entradas em ${sourceName}\n`;
    const flatArr=[];
    for(const en of entries){
      const trackName = en.master_metadata_track_name || en.trackName || en.track_name || en.name || en.master_metadata_track_name;
      if(!trackName) continue;
      // pula podcasts sem track uri? mantém se for música
      const isMusic = en.spotify_track_uri && en.spotify_track_uri.includes("track");
      if(en.episode_name && !isMusic) continue;
      const artistName = en.master_metadata_album_artist_name || en.artistName || "";
      const albumName = en.master_metadata_album_album_name || en.albumName || "";
      const uri = en.spotify_track_uri || en.spotifyTrackUri || en.uri || "";
      let trackId = "";
      if(uri && uri.includes(":")) trackId = uri.split(":").pop();
      else if(en.track_id) trackId = en.track_id;
      else trackId = "ext_"+Math.random().toString(36).slice(2,9);
      let ts = en.ts || en.endTime || en.played_at || en.date || en.offline_timestamp;
      if(ts == null) continue;
      // ts pode ser "2024-03-12 14:22:05" ou ISO ou número
      let ms=0;
      if(typeof ts === "number"){
        // offline_timestamp é segundos? endsong usa ms?
        ms = ts > 20000000000 ? ts : ts*1000;
        // se for offline_timestamp em ms já
        if(en.offline_timestamp && typeof en.offline_timestamp==="number") ms = en.offline_timestamp;
      } else {
        let tstr = String(ts);
        if(tstr.length===19 && !tstr.includes("T")) tstr = tstr.replace(" ", "T")+"Z";
        if(tstr.length===10 && tstr.includes("-")) tstr += "T12:00:00Z";
        try{ ms = new Date(tstr).getTime(); if(isNaN(ms)) ms=0; }catch(e){ms=0;}
      }
      if(!ms || isNaN(ms)) continue;
      const iso = new Date(ms).toISOString();
      const msPlayed = Number(en.ms_played ?? en.msPlayed ?? 0);
      const duration = Number(en.duration_ms ?? msPlayed ?? 0);
      const skippedVal = en.skipped ? 1 : 0;
      if(skippedVal) totalSkipped++;
      const reasonStart = en.reason_start || en.reasonStart || "";
      const reasonEnd = en.reason_end || en.reasonEnd || (skippedVal ? "fwdbtn" : "trackdone");
      const shuffle = en.shuffle ? 1 : 0;
      const offline = en.offline ? 1 : 0;
      const platform = en.platform || en.conn_country || "";
      const incognito = en.incognito_mode ? 1 : 0;
      // se incognito, ainda importa mas marca
      flatArr.push({
        played_at: iso,
        played_at_ms: ms,
        track_id: trackId,
        track_name: trackName,
        artist_ids: JSON.stringify([]),
        artist_names: JSON.stringify(artistName? [artistName]: []),
        album_id: "",
        album_name: albumName,
        album_image: "",
        duration_ms: duration||msPlayed||0,
        explicit: 0,
        context_type: platform||"",
        context_uri: en.conn_country||"",
        ms_played: msPlayed||duration||0,
        skipped: skippedVal,
        reason_start: reasonStart,
        reason_end: reasonEnd,
        shuffle: shuffle,
        offline: offline,
        platform: platform,
        raw_json: JSON.stringify(en)
      });
      if(flatArr.length>=2000){
        const ins = bridgeSavePlays(flatArr);
        totalInserted+=ins;
        logEl.textContent+=`  inseridos ${ins}/${flatArr.length}\n`;
        // scroll log
        logEl.scrollTop = logEl.scrollHeight;
        flatArr.length=0;
        // yield
        await new Promise(r=>setTimeout(r, 10));
      }
    }
    if(flatArr.length){
      const ins = bridgeSavePlays(flatArr);
      totalInserted+=ins;
      logEl.textContent+=`  inseridos ${ins}/${flatArr.length}\n`;
    }
  }

  for(const file of files){
    logEl.textContent+=`Lendo ${file.name} (${(file.size/1024).toFixed(1)}KB)...\n`;
    try{
      if(file.name.toLowerCase().endsWith(".zip")){
        if(typeof JSZip === "undefined"){
          logEl.textContent+=`  ERRO: JSZip não carregado, não foi possível ler ZIP\n`;
          continue;
        }
        const zip = await JSZip.loadAsync(file);
        let jsonFiles = 0;
        for(const name in zip.files){
          const entry = zip.files[name];
          if(entry.dir) continue;
          if(!name.toLowerCase().endsWith(".json")) continue;
          jsonFiles++;
          logEl.textContent+=`  descompactando ${name}...\n`;
          const text = await entry.async("string");
          try{
            const json = JSON.parse(text);
            let entries = [];
            if(Array.isArray(json)) entries=json;
            else if(json.length!==undefined) entries=json;
            else entries=[];
            await processEntries(entries, name);
          }catch(err){
            logEl.textContent+=`  ERRO JSON ${name}: ${err.message}\n`;
          }
        }
        if(jsonFiles===0) logEl.textContent+=`  nenhum JSON no ZIP\n`;
      } else {
        const text = await file.text();
        const json = JSON.parse(text);
        let entries = [];
        if(Array.isArray(json)) entries=json;
        else if(json.length!==undefined) entries=json;
        else entries=[];
        await processEntries(entries, file.name);
      }
    }catch(err){
      logEl.textContent+=`  ERRO ${file.name}: ${err.message}\n`;
    }
  }
  logEl.textContent+=`\n✓ Import concluído: ${totalInserted} novos (de ${totalParsed} lidos, ${totalSkipped} puladas)\n`;
  if(sumEl){
    sumEl.innerHTML = `<span>${totalInserted} novas</span><span>${totalParsed} lidas</span><span style="background:rgba(255,59,48,.15);color:#ff9a93;border-color:rgba(255,59,48,.3)">${totalSkipped} puladas</span>`;
  }
  toast(`Import OK: +${totalInserted} faixas (${totalSkipped} puladas)`);
  invalidateStatsCache();
  if($("#tab-timeline").classList.contains("active")) renderTimeline();
  if($("#tab-insights").classList.contains("active")) renderInsights();
  if($("#tab-library").classList.contains("active")) renderLibrary();
  updateAuthUI();
  // limpa input para permitir re-selecionar mesmo arquivo
  e.target.value="";
}

// ---------- misc ----------
function clearAll(){
  if(!confirm("Apagar TODO o banco local? Isso não apaga no Spotify, só local. Continuar?")) return;
  bridgeClear();
  invalidateStatsCache();
  toast("Banco limpo");
  renderTimeline(); renderInsights(); renderLibrary(); updateAuthUI();
}
function exportJson(){
  let data="";
  if(isAndroid()) data = Android.exportJson();
  else {
    const all = JSON.parse(localStorage.getItem("mock_plays")||"[]");
    data = JSON.stringify({plays: all, exported_at: new Date().toISOString()}, null, 2);
  }
  const blob = new Blob([data], {type:"application/json"});
  const url = URL.createObjectURL(blob);
  const a=document.createElement("a");
  a.href=url; a.download="spotify_vault_export_"+toISODate(new Date())+".json";
  a.click();
  setTimeout(()=>URL.revokeObjectURL(url), 2000);
  toast("Export gerado");
}

// ---------- init ----------
document.addEventListener("DOMContentLoaded", ()=>{
  initTabs();
  state.clientId = bridgeGet("client_id","");
  state.redirectUri = bridgeGet("redirect_uri","spotifyvault://callback");
  state.accessToken = bridgeGet("access_token","");
  state.refreshToken = bridgeGet("refresh_token","");
  state.expiresAt = Number(bridgeGet("expires_at","0"));
  $("#clientId").value = state.clientId;
  $("#redirectUri").value = state.redirectUri;
  updateAuthUI();

  const today = toISODate(new Date());
  $("#datePicker").value = today;
  // Boot fluido: só timeline agora; insights/library sob demanda (lazy)
  renderTimeline();
  // links data-spotify (delegação global, sem inline onclick)
  document.addEventListener("click", (e)=>{
    const a = e.target.closest && e.target.closest("[data-spotify]");
    if(a){ e.preventDefault(); openExternal("https://open.spotify.com/track/"+a.dataset.spotify); }
  });
  // resize: reagenda charts com debounce (evita distorção ao girar)
  let _rzT=0;
  window.addEventListener("resize", ()=>{ clearTimeout(_rzT); _rzT=setTimeout(()=>{ if($("#tab-insights").classList.contains("active") && _statsCache) scheduleCharts(_statsCache); }, 250); });

  $("#btnLogin").addEventListener("click", startLogin);
  $("#btnLogout").addEventListener("click", logout);
  $("#btnDoSync").addEventListener("click", doSync);
  $("#btnSync").addEventListener("click", doSync);
  $("#btnTestApi").addEventListener("click", testApi);
  $("#btnToday").addEventListener("click", ()=>{ $("#datePicker").value=toISODate(new Date()); renderTimeline(); });
  $("#btnYesterday").addEventListener("click", ()=>{
    const d=new Date(); d.setDate(d.getDate()-1); $("#datePicker").value=toISODate(d); renderTimeline();
  });
  $("#datePicker").addEventListener("change", renderTimeline);
  let _libT=0;
  $("#searchLib").addEventListener("input", ()=>{ clearTimeout(_libT); _libT=setTimeout(()=>{ state.libPage=0; renderLibrary(); }, 280); });
  $("#sortLib").addEventListener("change", ()=>{ state.libPage=0; renderLibrary(); });
  $("#libPrev").addEventListener("click", ()=>{ if(state.libPage>0){ state.libPage--; renderLibrary(); }});
  $("#libNext").addEventListener("click", ()=>{ state.libPage++; renderLibrary(); });
  $("#fileImport").addEventListener("change", handleImportFiles);
  $("#autoSync").addEventListener("change", (e)=>{
    bridgePut("auto_sync_enabled", e.target.checked?"true":"false");
    if(isAndroid()){
      if(e.target.checked) Android.scheduleSync();
      else Android.cancelSync();
    }
    toast(e.target.checked?"Sync 9h,12h,15h,18h,21h30 Brasília ativado":"Sync desativado");
  });
  setInterval(()=>{
    if(document.hidden) return;
    const last = Number(bridgeGet("last_sync_ms","0"));
    if(Date.now() - last > 8*60*60*1000){
      if(bridgeGet("access_token")){
        doSync();
      }
    }
  }, 60*1000);
  document.addEventListener("visibilitychange", ()=>{
    if(!document.hidden){
      const last = Number(bridgeGet("last_sync_ms","0"));
      if(Date.now()-last > 60*60*1000){
        $("#syncStatus").textContent="sync pendente";
      }
    }
  });
  const last = Number(bridgeGet("last_sync_ms","0"));
  if(bridgeGet("access_token") && last===0){
    setTimeout(()=>{ $("#syncLog").textContent="Primeiro sync automático em 2s..."; doSync(); }, 1500);
  }
  window.doSync = doSync;
  window.copyText = copyText;
  window.clearAll = clearAll;
  window.exportJson = exportJson;
  window.openExternal = openExternal;
  // Ajusta topo para barra de status Android (evita cobertura)
  try {
    if(isAndroid()){
      document.documentElement.style.setProperty('--safe-top', '24px');
    } else if(window.visualViewport){
      // iOS safe area já via env()
    }
    // previne scroll lateral
    document.documentElement.style.overflowX = 'hidden';
    document.body.style.overflowX = 'hidden';
  } catch(e){}
  toast("Spotify Vault pronto");
});
