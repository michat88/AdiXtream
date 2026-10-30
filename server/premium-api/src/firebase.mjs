import {fail} from './auth.mjs';
import {PRODUCTION_FIREBASE_ENABLED} from './service.mjs';
// A code review + separate production approval is required to change this compile-time lock.
const PRODUCTION_HOST='adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app';
const b64=bytes=>btoa(String.fromCharCode(...bytes)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
export function createFirebaseTransport(env,{fetchImpl=fetch,clock=Date.now}={}){
 const emulator=env.PREMIUM_DATA_SCOPE==='synthetic'&&env.PREMIUM_FIREBASE_MODE==='emulator';
 if(!emulator&&!PRODUCTION_FIREBASE_ENABLED)fail('PRODUCTION_LOCKED',503);
 const base=new URL(env.FIREBASE_DATABASE_URL||'http://invalid.invalid/');
 if(base.username||base.password||base.search||base.hash||base.pathname!=='/')fail('FIREBASE_CONFIG_INVALID',503);
 if(emulator){if(base.protocol!=='http:'||base.hostname!=='127.0.0.1'||!/^demo-[a-z0-9-]+$/.test(env.FIREBASE_EMULATOR_NAMESPACE||''))fail('FIREBASE_CONFIG_INVALID',503);}
 else if(base.protocol!=='https:'||base.hostname!==PRODUCTION_HOST||env.FIREBASE_PROJECT_ID!=='adixtream-premium')fail('FIREBASE_CONFIG_INVALID',503);
 let cachedToken=null,tokenUntil=0;
 async function accessToken(){
  if(clock()<tokenUntil&&cachedToken)return cachedToken;
  if(!PRODUCTION_FIREBASE_ENABLED)fail('PRODUCTION_LOCKED',503);
  if(!env.FIREBASE_CLIENT_EMAIL||!env.FIREBASE_PRIVATE_KEY)fail('FIREBASE_CREDENTIALS_MISSING',503);
  const now=Math.floor(clock()/1000),header=b64(new TextEncoder().encode(JSON.stringify({alg:'RS256',typ:'JWT'}))),claims=b64(new TextEncoder().encode(JSON.stringify({iss:env.FIREBASE_CLIENT_EMAIL,scope:'https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/userinfo.email',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600})));
  const pem=env.FIREBASE_PRIVATE_KEY.replace(/-----[^-]+-----|\s/g,'');const key=await crypto.subtle.importKey('pkcs8',Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  const signature=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(header+'.'+claims));
  const r=await fetchImpl('https://oauth2.googleapis.com/token',{method:'POST',redirect:'error',signal:AbortSignal.timeout(5000),headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:header+'.'+claims+'.'+b64(new Uint8Array(signature))})});
  if(!r.ok)fail('FIREBASE_AUTH_UNAVAILABLE',503);const d=await r.json();if(typeof d.access_token!=='string'||!Number.isInteger(d.expires_in))fail('FIREBASE_AUTH_UNAVAILABLE',503);cachedToken=d.access_token;tokenUntil=clock()+Math.max(0,d.expires_in-60)*1000;return cachedToken;
 }
 async function exchange(id,method,etag,payload){
  if(!/^(?:\d{1,8}|-2147483)$/.test(id))fail('REQUEST_INVALID',400);
  const url=new URL('users/'+id+'.json',base);if(emulator)url.searchParams.set('ns',env.FIREBASE_EMULATOR_NAMESPACE);
  const auth=emulator?'owner':await accessToken();
  const r=await fetchImpl(url,{method,redirect:'error',signal:AbortSignal.timeout(8000),headers:{Authorization:'Bearer '+auth,'Cache-Control':'no-cache',...(method==='GET'?{'X-Firebase-ETag':'true'}:{'Content-Type':'application/json','if-match':etag})},...(payload===undefined?{}:{body:payload})});
  const text=await r.text();if(text.length>1024*1024)fail('FIREBASE_UNAVAILABLE',503);
  if(method==='PUT')return r.status;
  if(r.status!==200||!r.headers.get('etag'))fail('FIREBASE_UNAVAILABLE',503);
  return {text,etag:r.headers.get('etag')};
 }
 async function legacyEvidence(id,code){
  if(!/^(?:\d{1,8}|-2147483)$/.test(id)||!/^[-A-Z0-9_]{1,64}$/.test(code))fail('REQUEST_INVALID',400);
  const found=[];for(const path of ['_adixtream_admin_promo_repairs_v1/'+id+'/'+code,'_adixtream_claims_v1/claims/'+id+'/'+code]){const url=new URL(path+'.json',base);if(emulator)url.searchParams.set('ns',env.FIREBASE_EMULATOR_NAMESPACE);const r=await fetchImpl(url,{redirect:'error',signal:AbortSignal.timeout(5000),headers:{Authorization:'Bearer '+(emulator?'owner':await accessToken()),'Cache-Control':'no-cache'}});if(!r.ok)fail('LEGACY_EVIDENCE_UNAVAILABLE',503);const text=await r.text();if(text.length>65536)fail('LEGACY_EVIDENCE_UNAVAILABLE',503);found.push(text.trim()!=='null');}
  return {repairExists:found[0],claimExists:found[1]};
 }
 return {legacyEvidence,read:id=>exchange(id,'GET'),compareAndSet:(id,etag,payload)=>{if(typeof etag!=='string'||/[\r\n]/.test(etag)||!etag||typeof payload!=='string'||payload.length>1024*1024)fail('REQUEST_INVALID',400);return exchange(id,'PUT',etag,payload);}};
}
