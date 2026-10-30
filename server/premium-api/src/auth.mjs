const JWKS='https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
export class ApiError extends Error{constructor(code,status=409){super(code);this.code=code;this.status=status;}}
export const fail=(code,status)=>{throw new ApiError(code,status);};
const decode=s=>Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/').padEnd(Math.ceil(s.length/4)*4,'=')),c=>c.charCodeAt(0));
export function createTokenVerifier({fetchImpl=fetch,clock=Date.now}={}){
 let cache=null,until=0;
 return async(token,project)=>{
  try{
   if(!project||typeof token!=='string'||token.length>8192)fail('AUTH_REQUIRED',401);
   const parts=token.split('.');if(parts.length!==3)fail('AUTH_REQUIRED',401);
   const header=JSON.parse(new TextDecoder().decode(decode(parts[0]))),claims=JSON.parse(new TextDecoder().decode(decode(parts[1]))),now=Math.floor(clock()/1000);
   if(header.alg!=='RS256'||typeof header.kid!=='string'||header.kid.length>256||claims.aud!==project||claims.iss!==`https://securetoken.google.com/${project}`||typeof claims.sub!=='string'||!claims.sub||claims.sub.length>128||!Number.isInteger(claims.exp)||claims.exp<=now||!Number.isInteger(claims.iat)||claims.iat>now||!Number.isInteger(claims.auth_time)||claims.auth_time>now)fail('AUTH_REQUIRED',401);
   if(!cache||clock()>=until){const r=await fetchImpl(JWKS,{redirect:'error',signal:AbortSignal.timeout(5000)});if(!r.ok)fail('AUTH_UNAVAILABLE',503);const text=await r.text();if(text.length>65536)fail('AUTH_UNAVAILABLE',503);const d=JSON.parse(text);if(!Array.isArray(d.keys)||!d.keys.length)fail('AUTH_UNAVAILABLE',503);cache=d.keys;until=clock()+Math.min(3600,Math.max(60,Number(r.headers.get('cache-control')?.match(/max-age=(\d+)/)?.[1]||300)))*1000;}
   const jwk=cache.find(k=>k.kid===header.kid&&k.kty==='RSA'&&k.alg==='RS256');if(!jwk)fail('AUTH_REQUIRED',401);
   const key=await crypto.subtle.importKey('jwk',jwk,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
   if(!await crypto.subtle.verify('RSASSA-PKCS1-v1_5',key,decode(parts[2]),new TextEncoder().encode(parts[0]+'.'+parts[1])))fail('AUTH_REQUIRED',401);
   return claims;
  }catch(e){if(e instanceof ApiError)throw e;fail('AUTH_REQUIRED',401);}
 };
}
const verify=createTokenVerifier();
export async function authenticate(request,env,verifier=verify){const found=/^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(request.headers.get('authorization')||'');if(!found)fail('AUTH_REQUIRED',401);if(!env.FIREBASE_PROJECT_ID)fail('AUTH_UNAVAILABLE',503);const c=await verifier(found[1],env.FIREBASE_PROJECT_ID);return {uid:c.sub,admin:typeof env.ADMIN_UID==='string'&&env.ADMIN_UID.length>0&&c.sub===env.ADMIN_UID,authTime:c.auth_time};}
// Atomic per-UID minute quota; migrations and TEST_DB are required. No process-local limiter.
export async function rateLimit(env,uid,now=Date.now()){
 if(!env.TEST_DB)fail('SERVICE_NOT_READY',503);
 const row=await env.TEST_DB.prepare(`INSERT INTO premium_v1_rate_limits(uid,bucket,requests) VALUES(?1,?2,1)
 ON CONFLICT(uid,bucket) DO UPDATE SET requests=requests+1 WHERE requests<60 RETURNING requests`).bind(uid,Math.floor(now/60000)).first();
 if(!row)fail('RATE_LIMITED',429);
}
export async function checkRevocation(db,principal){
 const r=await db.prepare('SELECT revoked_before,disabled FROM premium_v1_revocations WHERE uid=?1').bind(principal.uid).first();
 if(r&&(r.disabled||principal.authTime<=r.revoked_before))fail('AUTH_REQUIRED',401);
}
