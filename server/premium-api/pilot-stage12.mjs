// AdiXtream Premium API — Tahap 12: admin renewal MVP di D1 fiktif (PRODUCTION LOCKED)
// Login Admin tahap 4E tetap dipertahankan; /api/v1/admin/simulate HANYA menerima
// Firebase ID token sah dengan UID yang cocok dengan secret ADMIN_UID.
// Tidak ada request ke RTDB. POST uji klaim HANYA menulis ke TEST_DB D1 fiktif.
// JANGAN menggunakan hasil simulasi ini sebagai bukti transaksi atomik production.
const testLoginHtml = String.raw`<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>AdiXtream — Uji Login Admin</title>
  <style>
    :root{color-scheme:dark}
    body{background:#0c121e;color:#ecf2fb;font:16px system-ui,-apple-system,sans-serif;margin:0;padding:22px}
    main{max-width:490px;margin:45px auto;background:#172133;border:1px solid #29384b;padding:24px;border-radius:17px}
    h1{font-size:23px;margin:0 0 12px}p{color:#c1cbd9;line-height:1.55}
    button{background:#4d86f7;color:#fff;border:0;border-radius:9px;padding:13px 18px;font-weight:650;margin:7px 8px 7px 0}
    button:disabled{opacity:.55}button.secondary{background:#344457}
    .box{overflow-wrap:anywhere;white-space:pre-wrap;background:#0c1523;border-radius:10px;padding:14px;line-height:1.55;margin-top:12px;font-size:14px}
    small{display:block;color:#9eabba;margin-top:20px;line-height:1.55}
  </style>
</head>
<body>
  <main>
    <h1>AdiXtream — Uji Login Admin</h1>
    <p>Halaman pengujian Admin. Firebase pelanggan tidak diakses. Semua pengujian memakai data fiktif D1. Unlock dan Promo production tetap terkunci.</p>
    <button id="login">Masuk dengan Google</button>
    <button id="logout" class="secondary" disabled>Keluar</button>
    <div id="info" class="box">Menyiapkan Firebase Authentication...</div>
    <button id="copyuid" class="secondary" disabled>Salin UID saya</button>
    <button id="simulate" class="secondary" disabled>Jalankan simulasi Unlock + Promo</button>
    <div id="sim-result" class="box" hidden></div>
    <button id="checkdb" class="secondary" disabled>Periksa D1 (Baca Saja)</button>
    <div id="db-result" class="box" hidden></div>
    <button id="testclaim" class="secondary" disabled>Klaim TEST14 di D1 (data fiktif)</button>
    <div id="claim-result" class="box" hidden></div>
    <button id="testrace" class="secondary" disabled>Uji 2 klaim bersamaan — RACE14</button>
    <div id="race-result" class="box" hidden></div>
    <button id="testrollback" class="secondary" disabled>Uji Rollback ROLLBACK14 (data fiktif)</button>
    <div id="rollback-result" class="box" hidden></div>
    <button id="renewstaging" class="secondary" disabled>Perpanjang Premium Fiktif +30 Hari (Admin)</button>
    <div id="renew-result" class="box" hidden></div>
    <small>Jangan bagikan ID token, kredensial Firebase, atau screenshot data akun publik. Login Google tidak otomatis memberikan hak Admin.</small>
  </main>
  <script type="module">
    import {initializeApp} from 'https://www.gstatic.com/firebasejs/13.1.0/firebase-app.js';
    import {getAuth, GoogleAuthProvider, signInWithPopup, signOut, onAuthStateChanged, setPersistence, browserSessionPersistence} from 'https://www.gstatic.com/firebasejs/13.1.0/firebase-auth.js';
    const app=initializeApp({
      apiKey: 'AIzaSyDpPN-6gmJyG9C_oyZ3Xv8SmxIcLYPH210',
      authDomain: 'adixtream-premium.firebaseapp.com',
      projectId: 'adixtream-premium',
      appId: '1:414065837846:web:1ce28e3551fe840973237'
    });
    const auth=getAuth(app);
    const info=document.getElementById('info');
    const login=document.getElementById('login');
    const logout=document.getElementById('logout');
    const copyuid=document.getElementById('copyuid');
    const simulate=document.getElementById('simulate');
    const simResult=document.getElementById('sim-result');
    const checkdb=document.getElementById('checkdb');
    const dbResult=document.getElementById('db-result');
    const testclaim=document.getElementById('testclaim');
    const claimResult=document.getElementById('claim-result');
    const testrace=document.getElementById('testrace');
    const raceResult=document.getElementById('race-result');
    const testrollback=document.getElementById('testrollback');
    const rollbackResult=document.getElementById('rollback-result');
    const renewstaging=document.getElementById('renewstaging');
    const renewResult=document.getElementById('renew-result');
    let renewalRequestId=null;
    const show=(text)=>{info.textContent=text;};
    // Panggil signInWithPopup tanpa await terlebih dahulu di handler klik:
    // di browser seluler, jeda sebelum membuka popup dapat menghilangkan user activation.
    const ready=setPersistence(auth,browserSessionPersistence);
    let cspIssue='';
    window.addEventListener('securitypolicyviolation',(event)=>{
      const directive=String(event.effectiveDirective||'');
      let origin='';
      try{origin=new URL(event.blockedURI).origin;}catch(_){}
      cspIssue=directive+(origin?' ('+origin+')':'');
    });
    function safeError(error){
      const code=String(error?.code||'UNKNOWN_ERROR').slice(0,90);
      const message=String(error?.message||'Tidak ada detail tambahan')
        .replace(/https?:\/\/[^\s\"'<>]+/g,'[URL DISEMBUNYIKAN]')
        .replace(/(?:[A-Za-z0-9_-]+\.){2}[A-Za-z0-9_-]{8,}/g,'[TOKEN DISEMBUNYIKAN]')
        .slice(0,210);
      return 'Login gagal: '+code+'\nDetail: '+message+(cspIssue?'\nCSP: '+cspIssue:'');
    }
    login.onclick=async()=>{
      login.disabled=true;
      cspIssue='';
      show('Membuka login Google...');
      try{
        await signInWithPopup(auth,new GoogleAuthProvider());
      }catch(error){show(safeError(error));}
      finally{login.disabled=false;}
    };
    logout.onclick=async()=>{
      simulate.disabled=true;
      simResult.hidden=true;
      checkdb.disabled=true;
      dbResult.hidden=true;
      testclaim.disabled=true;
      claimResult.hidden=true;
      testrace.disabled=true;
      raceResult.hidden=true;
      testrollback.disabled=true;
      rollbackResult.hidden=true;
      renewstaging.disabled=true;
      renewResult.hidden=true;
      renewalRequestId=null;
      await signOut(auth);
      show('Sudah keluar.');
    };
    simulate.onclick=async()=>{
      if(!auth.currentUser || simulate.disabled)return;
      simulate.disabled=true;
      simResult.hidden=false;
      simResult.textContent='Menjalankan pengujian data fiktif...';
      try{
        const token=await auth.currentUser.getIdToken();
        const resp=await fetch('/api/v1/admin/simulate',{
          method:'POST',
          headers:{'Authorization':'Bearer '+token},
          cache:'no-store'
        });
        const obj=await resp.json();
        if(!resp.ok){
          simResult.textContent='Simulasi ditolak: '+String(obj.error||resp.status);
        }else{
          const rows=(obj.tests||[]).map((t)=>
            (t.passed?'LULUS':'GAGAL')+' — '+t.name+' — '+t.result
          );
          simResult.textContent='SIMULASI SAJA — TIDAK ADA DATA FIREBASE DIUBAH\n'+
            'Lulus: '+obj.passed+'/'+obj.total+'\n'+rows.join('\n');
        }
      }catch(e){simResult.textContent='Simulasi gagal dijalankan. Periksa koneksi.';}
      finally{simulate.disabled=false;}
    };
    checkdb.onclick=async()=>{
      if(!auth.currentUser || checkdb.disabled)return;
      checkdb.disabled=true;
      dbResult.hidden=false;
      dbResult.textContent='Membaca data fiktif di Cloudflare D1...';
      try{
        const token=await auth.currentUser.getIdToken();
        const response=await fetch('/api/v1/admin/test-db',{
          method:'GET',headers:{'Authorization':'Bearer '+token},cache:'no-store'
        });
        const data=await response.json();
        if(!response.ok){
          dbResult.textContent='D1: '+String(data.error||response.status);
        }else{
          dbResult.textContent='D1 TERHUBUNG — BACA SAJA\n'+
            'Firebase production: TIDAK TERHUBUNG\n'+
            'Pelanggan fiktif: '+(data.customer_found?'DITEMUKAN':'BELUM ADA')+'\n'+
            'Promo TEST14: '+(data.promo_found?'DITEMUKAN':'BELUM ADA')+'\n'+
            (data.promo_found?'Bonus: '+data.bonus_days+' hari\nKuota: '+
            data.used_count+'/'+data.max_quota+'\nStatus: '+data.status+'\n':'')+
            'Perubahan data: TIDAK ADA';
        }
      }catch(e){dbResult.textContent='Tidak berhasil membaca D1. Periksa koneksi.';}
      finally{checkdb.disabled=false;}
    };
    testclaim.onclick=async()=>{
      if(!auth.currentUser || testclaim.disabled)return;
      testclaim.disabled=true;
      claimResult.hidden=false;
      claimResult.textContent='Mengklaim TEST14 pada pelanggan fiktif...';
      try{
        const token=await auth.currentUser.getIdToken();
        const response=await fetch('/api/v1/admin/test-claim',{
          method:'POST',
          headers:{'Authorization':'Bearer '+token},
          cache:'no-store'
        });
        const data=await response.json();
        if(!response.ok){
          claimResult.textContent='KLAIM TIDAK DIBERIKAN: '+String(data.error||response.status)+
            '\nJika sudah diklaim, ini adalah hasil yang diharapkan untuk percobaan kedua.';
        }else{
          claimResult.textContent='KLAIM D1 FIKTIF BERHASIL\n'+
            'Kode: TEST14\nBonus: '+data.bonus_days+' hari\n'+
            'Kuota: '+data.used_count+'/'+data.max_quota+'\n'+
            'Expiry sebelum: '+new Date(data.expiry_before_ms).toISOString()+'\n'+
            'Expiry sesudah: '+new Date(data.expiry_after_ms).toISOString()+'\n'+
            'Firebase production: TIDAK DISENTUH';
        }
      }catch(e){claimResult.textContent='Klaim percobaan gagal. Periksa koneksi.';}
      finally{testclaim.disabled=false;}
    };
    testrace.onclick=async()=>{
      if(!auth.currentUser || testrace.disabled)return;
      testrace.disabled=true;
      raceResult.hidden=false;
      raceResult.textContent='Mengirim dua HTTP POST paralel ke Worker untuk dua pelanggan fiktif...';
      try{
        const token=await auth.currentUser.getIdToken();
        const send=(customer)=>fetch('/api/v1/admin/test-race-claim',{
          method:'POST',
          headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'},
          body:JSON.stringify({customer}),cache:'no-store'
        }).then(async r=>({http_status:r.status,data:await r.json()}));
        const results=await Promise.allSettled([send('A'),send('B')]);
        const statusResp=await fetch('/api/v1/admin/test-race-state',{
          headers:{'Authorization':'Bearer '+token},cache:'no-store'
        });
        const state=await statusResp.json();
        const lines=results.map((r,i)=>{
          const label=i===0?'A':'B';
          if(r.status!=='fulfilled')return label+': NETWORK_ERROR';
          return label+': '+(r.value.data.granted===true?'GRANTED':String(r.value.data.error||r.value.http_status));
        });
        if(!statusResp.ok){
          raceResult.textContent='Status D1 tidak dapat dibaca: '+String(state.error||statusResp.status)+'\n'+lines.join('\n');
        }else{
          const firstSuccess=results.filter(r=>r.status==='fulfilled' && r.value.data.granted===true).length;
          const firstRejected=results.filter(r=>r.status==='fulfilled' && r.value.http_status===409).length;
          const pass=firstSuccess===1 && firstRejected===1 && state.consistent===true;
          raceResult.textContent=(pass?'LULUS — DUA KLAIM BERSAMAAN':'PERLU PEMERIKSAAN — HASIL BELUM SESUAI')+'\n'+
            lines.join('\n')+'\nPromo: RACE14\nKuota terpakai: '+state.used_count+'/'+state.max_quota+
            '\nRiwayat klaim: '+state.total_claims+'\nCatatan transaksi: '+state.total_events+
            '\nPemenang: '+String(state.winner||'belum ada')+
            '\nSatu bonus +14 hari: '+(state.exactly_one_bonus?'YA':'BELUM TERBUKTI')+
            '\nFirebase pelanggan: TIDAK DIAKSES';
        }
      }catch(e){raceResult.textContent='Tes race gagal. Periksa koneksi dan data pengujian.';}
      finally{testrace.disabled=false;}
    };
    testrollback.onclick=async()=>{
      if(!auth.currentUser || testrollback.disabled)return;
      testrollback.disabled=true;
      rollbackResult.hidden=false;
      rollbackResult.textContent='Menguji rollback D1 pada Promo dan pelanggan fiktif...';
      try{
        const token=await auth.currentUser.getIdToken();
        const response=await fetch('/api/v1/admin/test-rollback',{
          method:'POST',
          headers:{'Authorization':'Bearer '+token},
          cache:'no-store'
        });
        const data=await response.json();
        const pass=response.ok && data.passed===true;
        rollbackResult.textContent=(pass?'LULUS — TRANSAKSI DIBATALKAN':'BELUM LULUS — PERLU PEMERIKSAAN')+'\n'+
          'Promo: ROLLBACK14\n'+
          'Kesalahan SQL disengaja: '+(data.intentional_error_triggered?'YA':'TIDAK/TAK TERBUKTI')+'\n'+
          'Kuota sebelum/sesudah: '+String(data.before?.used_count??'?')+'/'+String(data.after?.used_count??'?')+'\n'+
          'Expiry tetap: '+(data.expiry_unchanged?'YA':'BELUM')+'\n'+
          'Riwayat klaim sebelum/sesudah: '+String(data.before?.claims??'?')+'/'+String(data.after?.claims??'?')+'\n'+
          'Catatan transaksi sebelum/sesudah: '+String(data.before?.events??'?')+'/'+String(data.after?.events??'?')+'\n'+
          'Firebase pelanggan: TIDAK DIAKSES'+
          (data.error?'\nKeterangan: '+String(data.error):'');
      }catch(e){rollbackResult.textContent='Pengujian gagal dijalankan. Periksa koneksi.';}
      finally{testrollback.disabled=false;}
    };
    renewstaging.onclick=async()=>{
      if(!auth.currentUser || renewstaging.disabled)return;
      renewstaging.disabled=true;
      renewResult.hidden=false;
      renewResult.textContent='Memperpanjang pelanggan fiktif melalui D1...';
      // ID yang sama dipakai ulang jika jaringan terputus, mencegah bonus berulang.
      renewalRequestId=renewalRequestId||crypto.randomUUID();
      try{
        const token=await auth.currentUser.getIdToken();
        const resp=await fetch('/api/v1/admin/staging/renew',{
          method:'POST',
          headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'},
          body:JSON.stringify({request_id:renewalRequestId}),
          cache:'no-store'
        });
        const data=await resp.json();
        if(resp.ok && data.applied){
          renewResult.textContent='PERPANJANGAN PREMIUM FIKTIF BERHASIL\n'+
            'Pelanggan: TEST-CUSTOMER-001\n'+
            'Durasi: +30 hari\n'+
            'Expiry sebelum: '+new Date(data.expiry_before_ms).toISOString()+'\n'+
            'Expiry sesudah: '+new Date(data.expiry_after_ms).toISOString()+'\n'+
            'Idempotensi: '+(data.replayed?'Permintaan sudah pernah diterapkan':'Permintaan baru')+'\n'+
            'Firebase pelanggan: TIDAK DIAKSES';
          // Setelah hasil terkonfirmasi, klik berikutnya berarti operasi baru.
          renewalRequestId=null;
        }else{
          renewResult.textContent='PERPANJANGAN BELUM DITERAPKAN: '+String(data.error||resp.status)+
            '\nRequest ID dipertahankan untuk dicoba ulang, jika aman.';
        }
      }catch(e){renewResult.textContent='Jaringan gagal. Permintaan tidak dikirim ulang otomatis; klik sekali lagi dengan ID yang sama.';}
      finally{renewstaging.disabled=false;}
    };
    copyuid.onclick=async()=>{
      if(!auth.currentUser)return;
      try{await navigator.clipboard.writeText(auth.currentUser.uid);show('UID berhasil disalin. Simpan hanya di pengaturan Cloudflare.');}
      catch(e){show('Gagal menyalin otomatis; UID tersedia dalam informasi login di atas.');}
    };
    onAuthStateChanged(auth,async(user)=>{
      logout.disabled=!user;
      copyuid.disabled=!user;
      simulate.disabled=true;
      simResult.hidden=true;
      checkdb.disabled=true;
      dbResult.hidden=true;
      testclaim.disabled=true;
      claimResult.hidden=true;
      testrace.disabled=true;
      raceResult.hidden=true;
      testrollback.disabled=true;
      rollbackResult.hidden=true;
      renewstaging.disabled=true;
      renewResult.hidden=true;
      if(!user){renewalRequestId=null;show('Belum masuk. Tekan Masuk dengan Google.');return;}
      show('Login Firebase berhasil.\nMemeriksa izin Admin di backend...');
      try{
        const token=await user.getIdToken();
        const response=await fetch('/api/v1/admin/whoami',{
          method:'GET',headers:{'Authorization':'Bearer '+token},cache:'no-store'
        });
        const data=await response.json();
        let note='';
        if(data.error==='ADMIN_NOT_CONFIGURED') note='\nHak Admin belum dikonfigurasi — ini normal pada tahap ini.';
        else if(data.error==='FORBIDDEN') note='\nAkun ini tidak diizinkan sebagai Admin.';
        else if(data.authenticated===true){
          note='\nAdmin terverifikasi. Operasi Premium masih terkunci.';
          simulate.disabled=false;
          checkdb.disabled=false;
          testclaim.disabled=false;
          testrace.disabled=false;
          testrollback.disabled=false;
          renewstaging.disabled=false;
        }
        else note='\nBackend: '+String(data.error||response.status);
        show('Login Firebase berhasil.'+note);
      }catch(e){show('Login Firebase berhasil.\nPemeriksaan backend belum berhasil.');}
    });
    ready.catch((e)=>show('Inisialisasi login gagal: '+String(e.code||e.message||e)));
  </script>
</body>
</html>`;
function loginPage() {
  return new Response(testLoginHtml, {status:200, headers:{
    'content-type':'text/html; charset=utf-8',
    'cache-control':'no-store',
    'x-content-type-options':'nosniff',
    'x-frame-options':'DENY',
    'cross-origin-opener-policy':'same-origin-allow-popups',
    'referrer-policy':'no-referrer',
    'content-security-policy':"default-src 'none'; script-src https://www.gstatic.com https://apis.google.com https://accounts.google.com 'unsafe-inline'; connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com https://apis.google.com https://*.firebaseapp.com https://*.googleapis.com https://accounts.google.com; frame-src https://*.firebaseapp.com https://accounts.google.com https://*.google.com; style-src 'unsafe-inline'; img-src 'self' data: https:; base-uri 'none'; form-action 'none'"
  }});
}
const jwksUrl = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
let keyCache = null;
let keyCacheUntil = 0;

function reply(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
    },
  });
}

function decodePart(part) {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
  const binary = atob(padded);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

async function getPublicKeys() {
  if (keyCache && Date.now() < keyCacheUntil) return keyCache;
  const response = await fetch(jwksUrl);
  if (!response.ok) throw new Error('KEYS_UNAVAILABLE');
  const data = await response.json();
  if (!Array.isArray(data.keys) || data.keys.length === 0) {
    throw new Error('KEYS_UNAVAILABLE');
  }
  const maxAge = Number(response.headers.get('cache-control')?.match(/max-age=(\d+)/)?.[1] ?? 300);
  keyCache = data.keys;
  keyCacheUntil = Date.now() + Math.min(Math.max(maxAge, 60), 3600) * 1000;
  return keyCache;
}

async function verifyFirebaseToken(token, projectId) {
  if (!projectId || token.length > 8192) throw new Error('INVALID_TOKEN');
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('INVALID_TOKEN');
  const header = JSON.parse(new TextDecoder().decode(decodePart(parts[0])));
  const claims = JSON.parse(new TextDecoder().decode(decodePart(parts[1])));
  if (header.alg !== 'RS256' || typeof header.kid !== 'string' || !header.kid) {
    throw new Error('INVALID_TOKEN');
  }
  const now = Math.floor(Date.now() / 1000);
  if (claims.aud !== projectId ||
      claims.iss !== `https://securetoken.google.com/${projectId}` ||
      typeof claims.sub !== 'string' || !claims.sub || claims.sub.length > 128 ||
      !Number.isInteger(claims.exp) || claims.exp <= now ||
      !Number.isInteger(claims.iat) || claims.iat > now ||
      !Number.isInteger(claims.auth_time) || claims.auth_time > now) {
    throw new Error('INVALID_TOKEN');
  }
  const keys = await getPublicKeys();
  const jwk = keys.find(k => k.kid === header.kid && k.kty === 'RSA' && k.alg === 'RS256');
  if (!jwk) throw new Error('INVALID_TOKEN');
  const key = await crypto.subtle.importKey(
    'jwk', jwk,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false, ['verify']
  );
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const ok = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5', key, decodePart(parts[2]), signed
  );
  if (!ok) throw new Error('INVALID_TOKEN');
  return claims;
}

// Semua objek berikut dibuat ulang tiap permintaan. Tidak ada storage persisten.
const DAY_MS = 86400000;
const MAX_PREMIUM_MS = 5 * 366 * DAY_MS;

function fixture(now) {
  return {
    owner: 'SIM_USER_A',
    user: { expiryMs: now + 3 * DAY_MS },
    promo: {
      code: 'SIMULASI14', status: 'aktif', bonusDays: 14,
      validUntilMs: now + DAY_MS, used: 0, maxQuota: 2
    },
    receipts: new Set()
  };
}

// Contoh aturan bisnis, BUKAN bukti transaction isolation atau anti-race database.
function simulateClaim(state, actor, now) {
  const promo = state.promo;
  if (actor !== state.owner) return 'NOT_OWNER';
  if (promo.status !== 'aktif') return 'PROMO_INACTIVE';
  if (!Number.isSafeInteger(promo.validUntilMs) || promo.validUntilMs <= now) {
    return 'PROMO_EXPIRED';
  }
  const key = state.owner + ':' + promo.code;
  if (state.receipts.has(key)) return 'ALREADY_REDEEMED';
  if (!Number.isInteger(promo.used) || !Number.isInteger(promo.maxQuota) ||
      promo.maxQuota < 1 || promo.used >= promo.maxQuota) return 'QUOTA_EXHAUSTED';
  if (!Number.isInteger(promo.bonusDays) || promo.bonusDays < 1 ||
      promo.bonusDays > 365) return 'INVALID_BONUS';
  const expiry = state.user.expiryMs;
  if (!Number.isSafeInteger(expiry) || expiry > now + MAX_PREMIUM_MS) {
    return 'EXPIRY_OUT_OF_RANGE';
  }
  const nextExpiry = Math.max(now, expiry) + promo.bonusDays * DAY_MS;
  if (!Number.isSafeInteger(nextExpiry) || nextExpiry > now + MAX_PREMIUM_MS) {
    return 'EXPIRY_OUT_OF_RANGE';
  }
  // Mutasi hanya dalam fixture request ini, bukan ke Firebase.
  state.user.expiryMs = nextExpiry;
  state.promo.used += 1;
  state.receipts.add(key);
  return 'GRANTED';
}

function simulateAdminRenewal(state, days, now) {
  if (!Number.isInteger(days) || days < 1 || days > 365) {
    return 'INVALID_DURATION';
  }
  const expiry = state.user.expiryMs;
  if (!Number.isSafeInteger(expiry) || expiry > now + MAX_PREMIUM_MS) {
    return 'EXPIRY_OUT_OF_RANGE';
  }
  const next = Math.max(now, expiry) + days * DAY_MS;
  if (!Number.isSafeInteger(next) || next > now + MAX_PREMIUM_MS) {
    return 'EXPIRY_OUT_OF_RANGE';
  }
  state.user.expiryMs = next;
  return 'RENEWED';
}

function runSimulations() {
  const now = Date.now();
  const tests = [];
  const check = (name, actual, expected) => {
    tests.push({ name, result: actual, passed: actual === expected });
  };

  let s = fixture(now);
  check('Promo valid +14 hari', simulateClaim(s, 'SIM_USER_A', now), 'GRANTED');
  check('Tambahan 14 hari benar',
    Math.round((s.user.expiryMs - now) / DAY_MS), 17);
  check('Klaim ulang kode sama', simulateClaim(s, 'SIM_USER_A', now), 'ALREADY_REDEEMED');
  check('Kuota tidak bertambah setelah duplikat', s.promo.used, 1);

  s = fixture(now);
  s.promo.used = s.promo.maxQuota;
  check('Kuota sudah habis', simulateClaim(s, 'SIM_USER_A', now), 'QUOTA_EXHAUSTED');

  s = fixture(now);
  s.promo.validUntilMs = now - DAY_MS;
  check('Promo kedaluwarsa', simulateClaim(s, 'SIM_USER_A', now), 'PROMO_EXPIRED');

  s = fixture(now);
  s.promo.status = 'pause';
  check('Promo tidak aktif', simulateClaim(s, 'SIM_USER_A', now), 'PROMO_INACTIVE');

  s = fixture(now);
  check('Akun bukan pemilik', simulateClaim(s, 'SIM_USER_B', now), 'NOT_OWNER');

  s = fixture(now);
  s.user.expiryMs = Number.MAX_SAFE_INTEGER;
  check('Expiry tidak masuk akal', simulateClaim(s, 'SIM_USER_A', now), 'EXPIRY_OUT_OF_RANGE');

  s = fixture(now);
  check('Perpanjangan Unlock Admin +30 hari', simulateAdminRenewal(s, 30, now), 'RENEWED');
  check('Perpanjangan menghasilkan +33 hari',
    Math.round((s.user.expiryMs - now) / DAY_MS), 33);
  check('Durasi Unlock di luar batas', simulateAdminRenewal(s, 999999, now), 'INVALID_DURATION');

  const passed = tests.filter(t => t.passed).length;
  return {
    mode: 'SIMULATION_ONLY', firebase_connected: false,
    production_data_changed: false, persistent_writes: false,
    passed, total: tests.length, tests,
    limitations: 'Tidak menguji transaksi atomik, race condition, atau pemilik akun pelanggan sesungguhnya.'
  };
}

async function authenticateAdmin(request, env) {
  const auth = request.headers.get('authorization') || '';
  const found = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(auth);
  if (!found) return { error: 'UNAUTHORIZED', status: 401 };
  if (!env.FIREBASE_PROJECT_ID || !env.ADMIN_UID) {
    return { error: 'ADMIN_NOT_CONFIGURED', status: 503 };
  }
  try {
    const claims = await verifyFirebaseToken(found[1], env.FIREBASE_PROJECT_ID);
    if (claims.sub !== env.ADMIN_UID) return { error: 'FORBIDDEN', status: 403 };
    return { uid: claims.sub };
  } catch (error) {
    if (error.message === 'KEYS_UNAVAILABLE') {
      return { error: 'AUTH_UNAVAILABLE', status: 503 };
    }
    return { error: 'UNAUTHORIZED', status: 401 };
  }
}

// Pemeriksaan D1 percobaan: SELECT statis, tanpa akses/pengubahan data pelanggan.
// Endpoint ini tetap memerlukan Firebase ID token Admin yang tervalidasi.
async function readTestD1(env) {
  if (!env.TEST_DB || typeof env.TEST_DB.prepare !== 'function') {
    return {error:'TEST_DB_NOT_CONFIGURED',status:503};
  }
  try {
    const customer = await env.TEST_DB.prepare(
      "SELECT customer_id FROM test_customers WHERE customer_id = 'TEST-CUSTOMER-001' LIMIT 1"
    ).first();
    const promo = await env.TEST_DB.prepare(
      "SELECT promo_code, bonus_days, max_quota, used_count, status FROM test_promos WHERE promo_code = 'TEST14' LIMIT 1"
    ).first();
    return {mode:'READ_ONLY_TEST_D1', firebase_connected:false,
      persistent_writes:false, customer_found:!!customer, promo_found:!!promo,
      ...(promo?{bonus_days:promo.bonus_days,max_quota:promo.max_quota,
        used_count:promo.used_count,status:promo.status}:{}),
    };
  } catch(error) {
    // Jangan pernah mengembalikan SQL, struktur internal, atau detail exception.
    return {error:'TEST_DB_QUERY_FAILED',status:503};
  }
}

// Atomic claim on TEST_DB only. 4 steps executed with D1.batch transaction:
// claim UNIQUE -> quota +1 -> expiry update -> audit insert.
// All follow-up statements are guarded by a claim inserted at this exact timestamp.
// If any statement fails (including duplicate claim), D1 rolls back the entire batch.
// Not a production Promo endpoint: hard-coded synthetic customer/owner and Admin-only.
const TEST_CUSTOMER = 'TEST-CUSTOMER-001';
const TEST_PROMO = 'TEST14';
const TEST_OWNER = 'TEST-OWNER-001';
const D1_DAY_MS = 86400000;
const MAX_EXPIRES_MS = 4102444800000;

// Perubahan Tahap 9: setiap klaim diikat ke request_id UUID unik.
// Tidak boleh menggunakan claimed_at_ms sebagai identitas suatu permintaan,
// karena dua HTTP request bisa tiba pada milidetik yang sama.
async function commitFictitiousClaim(env, adminUid, promoCode, customerId, ownerId) {
  const db=env.TEST_DB;
  if (!db || typeof db.batch !== 'function')
    return {error:'TEST_DB_NOT_CONFIGURED',status:503};
  const now=Date.now();
  const maxExpiry=Math.min(MAX_EXPIRES_MS,now+5*366*D1_DAY_MS);
  const reqId=crypto.randomUUID();
  const eventId=crypto.randomUUID();

  const insertClaim=db.prepare(`
    INSERT INTO test_promo_claims
      (promo_code,customer_id,bonus_days,claimed_at_ms,
       expiry_before_ms,expiry_after_ms,request_id)
    SELECT p.promo_code,c.customer_id,p.bonus_days,?1,
           c.expires_at_ms,MAX(c.expires_at_ms,?1)+p.bonus_days*86400000,?5
    FROM test_customers c JOIN test_promos p ON p.promo_code=?2
    WHERE c.customer_id=?3 AND c.owner_uid=?4
      AND p.status='active' AND p.used_count<p.max_quota
      AND p.valid_until_ms>?1
      AND c.expires_at_ms BETWEEN 0 AND ?6
      AND MAX(c.expires_at_ms,?1)+p.bonus_days*86400000<=?6
      AND NOT EXISTS (
        SELECT 1 FROM test_promo_claims old
        WHERE old.promo_code=p.promo_code AND old.customer_id=c.customer_id
      )
  `).bind(now,promoCode,customerId,ownerId,reqId,maxExpiry);

  const incQuota=db.prepare(`
    UPDATE test_promos SET used_count=used_count+1
    WHERE promo_code=?1 AND used_count<max_quota
    AND EXISTS(SELECT 1 FROM test_promo_claims
               WHERE request_id=?2 AND promo_code=?1 AND customer_id=?3)
  `).bind(promoCode,reqId,customerId);

  const extendExpiry=db.prepare(`
    UPDATE test_customers SET expires_at_ms=(
      SELECT expiry_after_ms FROM test_promo_claims WHERE request_id=?1
    )
    WHERE customer_id=?2 AND EXISTS (
      SELECT 1 FROM test_promo_claims
      WHERE request_id=?1 AND customer_id=?2
    )
  `).bind(reqId,customerId);

  const logEvent=db.prepare(`
    INSERT INTO test_premium_events
      (event_id,request_id,customer_id,event_type,promo_code,
       days_granted,expiry_before_ms,expiry_after_ms,actor_uid,created_at_ms)
    SELECT ?1,?2,c.customer_id,'PROMO_CLAIM',c.promo_code,
           c.bonus_days,c.expiry_before_ms,c.expiry_after_ms,?3,?4
    FROM test_promo_claims c WHERE c.request_id=?2
  `).bind(eventId,reqId,adminUid,now);

  try {
    const results=await db.batch([insertClaim,incQuota,extendExpiry,logEvent]);
    const changes=results.map(r=>Number(r?.meta?.changes??0));
    if(changes[0]===0 && changes.slice(1).every(v=>v===0))
      return {error:'CLAIM_NOT_ELIGIBLE',status:409};
    if(changes.some(v=>v!==1))
      return {error:'TRANSACTION_RESULT_UNCERTAIN',status:503};
    const record=await db.prepare(`
      SELECT c.bonus_days,c.expiry_before_ms,c.expiry_after_ms,
             p.used_count,p.max_quota
      FROM test_promo_claims c JOIN test_promos p ON p.promo_code=c.promo_code
      WHERE c.request_id=?1
    `).bind(reqId).first();
    if(!record)return {error:'POST_COMMIT_READ_FAILED',status:503};
    return {...record,granted:true,mode:'D1_TEST_ONLY',firebase_connected:false};
  }catch(_error){
    // Tanpa membocorkan SQL, jangan menganggap error sebagai transaksi sukses.
    try{
      const exists=await db.prepare(`
        SELECT 1 AS found FROM test_promo_claims
        WHERE promo_code=?1 AND customer_id=?2
      `).bind(promoCode,customerId).first();
      if(exists)return {error:'ALREADY_REDEEMED',status:409};
    }catch(_){}
    return {error:'TEST_D1_TRANSACTION_FAILED',status:503};
  }
}

async function claimTestD1(env,adminUid){
  return commitFictitiousClaim(env,adminUid,TEST_PROMO,TEST_CUSTOMER,TEST_OWNER);
}

const RACE_CUSTOMERS=Object.freeze({
  A:['RACE-CUSTOMER-A','RACE-OWNER-A'],
  B:['RACE-CUSTOMER-B','RACE-OWNER-B']
});

async function raceState(env){
  if(!env.TEST_DB)return {error:'TEST_DB_NOT_CONFIGURED',status:503};
  try{
    const db=env.TEST_DB;
    const p=await db.prepare(`
      SELECT used_count,max_quota,bonus_days,status,
        (SELECT COUNT(*) FROM test_promo_claims WHERE promo_code='RACE14') AS total_claims,
        (SELECT COUNT(*) FROM test_premium_events
         WHERE promo_code='RACE14' AND event_type='PROMO_CLAIM') AS total_events
      FROM test_promos WHERE promo_code='RACE14'
    `).first();
    if(!p)return {error:'RACE_PROMO_NOT_FOUND',status:404};
    const claims=await db.prepare(`
      SELECT customer_id,bonus_days,expiry_before_ms,expiry_after_ms
      FROM test_promo_claims WHERE promo_code='RACE14'
    `).all();
    const rows=claims.results||[];
    const exactlyOneBonus=rows.length===1 && rows[0].bonus_days===14 &&
      rows[0].expiry_after_ms-rows[0].expiry_before_ms===14*D1_DAY_MS;
    const consistent=p.used_count===1 && p.max_quota===1 &&
      p.total_claims===1 && p.total_events===1 && exactlyOneBonus;
    return {used_count:p.used_count,max_quota:p.max_quota,
      total_claims:p.total_claims,total_events:p.total_events,
      winner:rows.length===1?rows[0].customer_id:null,
      exactly_one_bonus:exactlyOneBonus,consistent};
  }catch(_e){return {error:'RACE_QUERY_FAILED',status:503};}
}

// Tahap 10 — hanya ROLLBACK14 dan pelanggan fiktif RACE-CUSTOMER-B.
// Rollback dipicu oleh CHECK event_type yang sengaja dilanggar pada
// pernyataan ke-4 dalam satu D1.batch(). Tidak ada SQL dari pengguna.
const ROLLBACK_PROMO='ROLLBACK14';
const ROLLBACK_CUSTOMER='RACE-CUSTOMER-B';

async function snapshotRollback(db){
  const state=await db.prepare(`
    SELECT p.used_count,p.max_quota,p.status,p.valid_until_ms,
      c.expires_at_ms AS customer_expiry,
      (SELECT COUNT(*) FROM test_promo_claims x
       WHERE x.promo_code='ROLLBACK14') AS claims,
      (SELECT COUNT(*) FROM test_premium_events e
       WHERE e.promo_code='ROLLBACK14') AS events
    FROM test_promos p JOIN test_customers c
      ON c.customer_id='RACE-CUSTOMER-B'
    WHERE p.promo_code='ROLLBACK14'
  `).first();
  return state && {
    used_count:Number(state.used_count),max_quota:Number(state.max_quota),
    status:String(state.status),valid_until_ms:Number(state.valid_until_ms),
    customer_expiry:Number(state.customer_expiry),
    claims:Number(state.claims),events:Number(state.events)
  };
}

async function testRollbackD1(env,adminUid){
  const db=env.TEST_DB;
  if(!db || typeof db.batch!=='function'){
    return {error:'TEST_DB_NOT_CONFIGURED',status:503};
  }
  let before;
  try{before=await snapshotRollback(db);}
  catch(_){return {error:'ROLLBACK_PREFLIGHT_QUERY_FAILED',status:503};}
  const now=Date.now();
  if(!before || before.status!=='active' || before.used_count!==0 ||
     before.max_quota!==1 || before.claims!==0 || before.events!==0 ||
     before.valid_until_ms<=now ||
     before.customer_expiry+14*D1_DAY_MS>MAX_EXPIRES_MS){
    return {error:'ROLLBACK_TEST_NOT_READY',status:409,before};
  }
  const id=crypto.randomUUID();
  const event=crypto.randomUUID();
  // Tiga pernyataan pertama seharusnya menulis data; yang keempat
  // gagal dengan CHECK constraint dan memaksa rollback seluruh batch.
  const statements=[
    db.prepare(`UPDATE test_promos SET used_count=used_count+1
      WHERE promo_code=?1 AND used_count=0 AND max_quota=1
        AND status='active' AND valid_until_ms>?2`).bind(ROLLBACK_PROMO,now),
    db.prepare(`UPDATE test_customers SET expires_at_ms=expires_at_ms+?1
      WHERE customer_id=?2 AND expires_at_ms=?3`).bind(14*D1_DAY_MS,ROLLBACK_CUSTOMER,before.customer_expiry),
    db.prepare(`INSERT INTO test_promo_claims
        (promo_code,customer_id,bonus_days,claimed_at_ms,
         expiry_before_ms,expiry_after_ms,request_id)
      VALUES (?1,?2,14,?3,?4,?5,?6)`)
      .bind(ROLLBACK_PROMO,ROLLBACK_CUSTOMER,now,before.customer_expiry,
            before.customer_expiry+14*D1_DAY_MS,id),
    db.prepare(`INSERT INTO test_premium_events
        (event_id,request_id,customer_id,event_type,promo_code,
         days_granted,expiry_before_ms,expiry_after_ms,actor_uid,created_at_ms)
      VALUES(?1,?2,?3,'INVALID_FOR_ROLLBACK_TEST',?4,14,?5,?6,?7,?8)`)
      .bind(event,id,ROLLBACK_CUSTOMER,ROLLBACK_PROMO,
            before.customer_expiry,before.customer_expiry+14*D1_DAY_MS,adminUid,now)
  ];
  let intentionalError=false;
  let errorCode=null;
  try{
    await db.batch(statements);
    // Bahaya: Jika batch tidak error, itu BUKAN rollback.
    errorCode='FAILURE_NOT_TRIGGERED';
  }catch(error){
    // Hanya akui bahwa kendala check terjadi jika tampak pada error.
    const message=String(error?.message||error);
    intentionalError=/CHECK constraint failed|constraint failed.*event_type/i.test(message);
    if(!intentionalError)errorCode='D1_ERROR_NOT_CONFIRMED_AS_CHECK';
  }
  let after;
  try{after=await snapshotRollback(db);}
  catch(_){return {error:'ROLLBACK_POSTCHECK_QUERY_FAILED',status:503};}
  const expiryUnchanged=!!(before && after &&
    before.customer_expiry===after.customer_expiry);
  const stateUnchanged=!!(before && after &&
    before.used_count===after.used_count &&
    expiryUnchanged && before.claims===after.claims &&
    before.events===after.events);
  const passed=intentionalError && stateUnchanged;
  return {
    passed,intentional_error_triggered:intentionalError,
    expiry_unchanged:expiryUnchanged,before,after,
    ...(errorCode?{error:errorCode}:{}),
    mode:'D1_TEST_ONLY',firebase_connected:false,
    status:passed?200:409
  };
}

// Tahap 12 — pilot implementasi perpanjangan oleh Admin di D1 data fiktif.
// TIDAK ADA credential Firebase, akses RTDB, atau data pelanggan nyata.
const STAGING_RENEW_CUSTOMER='TEST-CUSTOMER-001';
const STAGING_RENEW_OWNER='TEST-OWNER-001';
const STAGING_RENEW_DAYS=30;
const STAGING_RENEW_DELTA=STAGING_RENEW_DAYS*86400000;

async function applyStagingRenewal(env, adminUid, requestId){
  if(!env.TEST_DB || typeof env.TEST_DB.batch!=='function')
    return {error:'TEST_DB_NOT_CONFIGURED',status:503};
  if(typeof requestId!=='string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId))
    return {error:'INVALID_REQUEST_ID',status:400};
  const db=env.TEST_DB;
  const existing=async()=>db.prepare(`
    SELECT customer_id,actor_uid,days_granted,expiry_before_ms,expiry_after_ms
    FROM test_premium_events WHERE request_id=?1
  `).bind(requestId).first();
  try{
    let prior=await existing();
    if(prior){
      if(prior.customer_id!==STAGING_RENEW_CUSTOMER || prior.actor_uid!==adminUid ||
         prior.days_granted!==STAGING_RENEW_DAYS)
        return {error:'REQUEST_ID_CONFLICT',status:409};
      return {applied:true,replayed:true,expiry_before_ms:prior.expiry_before_ms,
        expiry_after_ms:prior.expiry_after_ms,days:STAGING_RENEW_DAYS,mode:'D1_TEST_ONLY'};
    }
    const customer=await db.prepare(`
      SELECT customer_id,owner_uid,expires_at_ms
      FROM test_customers WHERE customer_id=?1
    `).bind(STAGING_RENEW_CUSTOMER).first();
    if(!customer || customer.owner_uid!==STAGING_RENEW_OWNER)
      return {error:'TEST_CUSTOMER_NOT_FOUND',status:404};
    const now=Date.now();
    const oldExpiry=Number(customer.expires_at_ms);
    if(!Number.isSafeInteger(oldExpiry) || oldExpiry<0 || oldExpiry>4102444800000)
      return {error:'INVALID_EXISTING_EXPIRY',status:409};
    const nextExpiry=Math.max(now,oldExpiry)+STAGING_RENEW_DELTA;
    if(!Number.isSafeInteger(nextExpiry) || nextExpiry>4102444800000 || nextExpiry>now+5*366*86400000)
      return {error:'EXPIRY_LIMIT',status:409};
    const eventId=crypto.randomUUID();
    // batch = satu transaksi. Pernyataan INSERT memakai SQLite changes() untuk
    // MEMAKSA kesalahan NOT NULL jika UPDATE sebelumnya berubah 0 baris.
    // Kesalahan menggagalkan seluruh batch (termasuk UPDATE).
    try{
      await db.batch([
        db.prepare(`
          UPDATE test_customers SET expires_at_ms=?1
          WHERE customer_id=?2 AND owner_uid=?3 AND expires_at_ms=?4
        `).bind(nextExpiry,STAGING_RENEW_CUSTOMER,STAGING_RENEW_OWNER,oldExpiry),
        db.prepare(`
          INSERT INTO test_premium_events
          (event_id,request_id,customer_id,event_type,promo_code,days_granted,
           expiry_before_ms,expiry_after_ms,actor_uid,created_at_ms)
          VALUES (?1,CASE WHEN changes()=1 THEN ?2 ELSE NULL END,
           ?3,'ADMIN_RENEWAL',NULL,?4,?5,?6,?7,?8)
        `).bind(eventId,requestId,STAGING_RENEW_CUSTOMER,STAGING_RENEW_DAYS,
          oldExpiry,nextExpiry,adminUid,now)
      ]);
    }catch(_){
      // Permintaan yang sama mungkin SUDAH berhasil namun respons sebelumnya hilang.
      prior=await existing();
      if(prior && prior.customer_id===STAGING_RENEW_CUSTOMER &&
         prior.actor_uid===adminUid && prior.days_granted===STAGING_RENEW_DAYS)
        return {applied:true,replayed:true,expiry_before_ms:prior.expiry_before_ms,
          expiry_after_ms:prior.expiry_after_ms,days:STAGING_RENEW_DAYS,mode:'D1_TEST_ONLY'};
      return {error:'CONCURRENT_UPDATE_OR_WRITE_FAILED',status:409};
    }
    const committed=await existing();
    if(!committed)return {error:'COMMIT_OUTCOME_UNKNOWN',status:503};
    return {applied:true,replayed:false,expiry_before_ms:committed.expiry_before_ms,
      expiry_after_ms:committed.expiry_after_ms,days:STAGING_RENEW_DAYS,mode:'D1_TEST_ONLY'};
  }catch(_){return {error:'STAGING_D1_UNAVAILABLE',status:503};}
}

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    if (request.method === 'GET' && pathname === '/admin/login') return loginPage();
    if (request.method === 'GET' && (pathname === '/' || pathname === '/health')) {
      return reply({ service: 'AdiXtream Premium API', status: 'online',
        environment: 'development', security_mode: 'locked',
        firebase_connected: false, unlock_enabled: false,
        promo_enabled: false, admin_enabled: false });
    }
    if (pathname === '/api/v1/admin/whoami' ||
        pathname === '/api/v1/admin/simulate' ||
        pathname === '/api/v1/admin/test-db' ||
        pathname === '/api/v1/admin/test-claim' ||
        pathname === '/api/v1/admin/test-race-claim' ||
        pathname === '/api/v1/admin/test-race-state' ||
        pathname === '/api/v1/admin/test-rollback' ||
        pathname === '/api/v1/admin/staging/renew') {
      const isSim = pathname === '/api/v1/admin/simulate';
      const isRead = pathname === '/api/v1/admin/test-db';
      const isClaim = pathname === '/api/v1/admin/test-claim';
      const isRaceClaim=pathname === '/api/v1/admin/test-race-claim';
      const isRaceState=pathname === '/api/v1/admin/test-race-state';
      const isRollback=pathname === '/api/v1/admin/test-rollback';
      const isStagingRenew=pathname === '/api/v1/admin/staging/renew';
      if (request.method !== ((isSim || isClaim || isRaceClaim || isRollback || isStagingRenew) ? 'POST' : 'GET')) {
        return reply({ error: 'METHOD_NOT_ALLOWED' }, 405);
      }
      const auth = await authenticateAdmin(request, env);
      if (auth.error) return reply({ error: auth.error }, auth.status);
      if(isStagingRenew){
        if(Number(request.headers.get('content-length')||0)>256)
          return reply({error:'BAD_REQUEST'},400);
        let body;
        try{body=await request.json();}
        catch(_){return reply({error:'BAD_REQUEST'},400);}
        if(!body || typeof body!=='object' || Array.isArray(body) ||
           Object.keys(body).length!==1 || !Object.hasOwn(body,'request_id'))
          return reply({error:'BAD_REQUEST'},400);
        const result=await applyStagingRenewal(env,auth.uid,body.request_id);
        if(result.error)return reply({error:result.error},result.status);
        return reply(result);
      }
      if(isRollback){
        const result=await testRollbackD1(env,auth.uid);
        const httpStatus=result.status||200;
        const {status:_,...payload}=result;
        return reply(payload,httpStatus);
      }
      if(isRaceState){
        const result=await raceState(env);
        if(result.error)return reply({error:result.error},result.status);
        return reply(result);
      }
      if(isRaceClaim){
        let raw;
        try{
          if(Number(request.headers.get('content-length')||0)>256)
            return reply({error:'BAD_REQUEST'},400);
          raw=await request.json();
        }catch(_e){return reply({error:'BAD_REQUEST'},400);}
        const pair=raw && (raw.customer==='A'||raw.customer==='B')
          ? RACE_CUSTOMERS[raw.customer] : null;
        if(!pair)return reply({error:'BAD_REQUEST'},400);
        const result=await commitFictitiousClaim(env,auth.uid,'RACE14',pair[0],pair[1]);
        if(result.error)return reply({error:result.error},result.status);
        return reply(result);
      }
      if (isSim) return reply(runSimulations());
      if (isClaim) {
        const result=await claimTestD1(env,auth.uid);
        if(result.error) return reply({error:result.error},result.status);
        return reply(result);
      }
      if (isRead) {
        const result = await readTestD1(env);
        if (result.error) return reply({error:result.error}, result.status);
        return reply(result);
      }
      return reply({ authenticated: true, role: 'admin',
        premium_operations_enabled: false });
    }
    if (pathname === '/api' || pathname.startsWith('/api/')) {
      return reply({ error: 'SERVICE_NOT_READY', access: 'denied' }, 503);
    }
    return reply({ error: 'NOT_FOUND' }, 404);
  },
};
