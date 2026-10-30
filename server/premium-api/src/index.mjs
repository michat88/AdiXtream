import pilot from '../pilot-stage12.mjs';
import {authenticate,checkRevocation,rateLimit,ApiError,fail} from './auth.mjs';
import {createService,PRODUCTION_FIREBASE_ENABLED,validDevice} from './service.mjs';
import {createFirebaseTransport} from './firebase.mjs';
const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...(status===429?{'Retry-After':'60'}:{})}});
async function body(request){if(request.headers.get('content-type')?.split(';')[0]!=='application/json')fail('UNSUPPORTED_MEDIA_TYPE',415);if(Number(request.headers.get('content-length')||0)>4096)fail('PAYLOAD_TOO_LARGE',413);const reader=request.body?.getReader();if(!reader)fail('REQUEST_INVALID',400);let size=0,chunks=[];try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>4096)fail('PAYLOAD_TOO_LARGE',413);chunks.push(value);}const bytes=new Uint8Array(size);let at=0;for(const c of chunks){bytes.set(c,at);at+=c.length;}return JSON.parse(new TextDecoder().decode(bytes));}catch(e){if(e instanceof ApiError)throw e;fail('REQUEST_INVALID',400);}finally{await reader.cancel().catch(()=>{});}}
export function createHandler({verifier,firebaseFactory=createFirebaseTransport}={}){
 return async(request,env)=>{
  const url=new URL(request.url),path=url.pathname;if(!path.startsWith('/api/v2/'))return pilot.fetch(request,env);
  try{
   if(url.search||!['GET','POST'].includes(request.method))fail('REQUEST_INVALID',400);
   const p=await authenticate(request,env,verifier);if(path.startsWith('/api/v2/admin/')&&!p.admin)fail('FORBIDDEN',403);
   if(!env.TEST_DB)fail('SERVICE_NOT_READY',503);
   const db=env.TEST_DB.withSession?env.TEST_DB.withSession('first-primary'):env.TEST_DB;
   await checkRevocation(db,p);await rateLimit({...env,TEST_DB:db},p.uid);
   const synthetic=env.PREMIUM_DATA_SCOPE==='synthetic';
   if(path==='/api/v2/admin/capabilities'&&request.method==='GET')return reply({contractVersion:2,authenticated:true,role:'admin',productionWritesEnabled:PRODUCTION_FIREBASE_ENABLED,syntheticOperationsEnabled:synthetic&&env.PREMIUM_SYNTHETIC_MUTATIONS==='enabled',ownerEnrollmentEnabled:false});
   const writes=request.method==='POST';
   if(!synthetic||writes&&env.PREMIUM_SYNTHETIC_MUTATIONS!=='enabled'||!writes&&env.PREMIUM_SYNTHETIC_READS!=='enabled')fail('PRODUCTION_LOCKED',503);
   const firebase=firebaseFactory(env),service=createService({db,firebase});
   let m;
   if((m=/^\/api\/v2\/admin\/customers\/([0-9-]+)$/.exec(path))&&request.method==='GET')return reply(await service.customer(p,m[1]));
   if((m=/^\/api\/v2\/admin\/history\/([0-9-]+)$/.exec(path))&&request.method==='GET')return reply(await service.history(p,m[1]));
   if(path==='/api/v2/admin/promos'&&request.method==='GET')return reply(await service.promos(p));
   if(path==='/api/v2/admin/promos'&&request.method==='POST')return reply(await service.savePromo(p,await body(request)));
   if((m=/^\/api\/v2\/(?:admin|premium)\/operations\/([a-f0-9-]+)$/.exec(path))&&request.method==='GET')return reply(await service.lookup(p,m[1]));
   if((m=/^\/api\/v2\/(?:admin|premium)\/operations\/([a-f0-9-]+)\/retry$/.exec(path))&&request.method==='POST'){const b=await body(request);if(!b||Object.keys(b).length)fail('REQUEST_INVALID',400);return reply(await service.lookup(p,m[1],true));}
   if(path==='/api/v2/premium/entitlement'&&request.method==='POST'){const b=await body(request);if(!b||Object.keys(b).length!==1||!validDevice(b.device_id))fail('REQUEST_INVALID',400);return reply({contractVersion:2,currentEntitlement:await service.current(p,b.device_id),serverTime:Date.now()});}
   const kind={'/api/v2/admin/renew':'ADMIN_RENEWAL','/api/v2/admin/unlock':'UNLOCK_CREATE','/api/v2/premium/unlock':'UNLOCK_ACTIVATE','/api/v2/premium/promo':'PROMO_CLAIM'}[path];
   if(kind&&request.method==='POST')return reply(await service.mutate(p,kind,await body(request)));
   fail('NOT_FOUND',404);
  }catch(e){const code=e instanceof ApiError?e.code:'TEMPORARY_UNAVAILABLE',status=e instanceof ApiError?e.status:503;return reply({contractVersion:2,error:{code,retryable:[429,503].includes(status)},productionWritesEnabled:false},status);}
 };
}
export default {fetch:createHandler()};
