import {fail,ApiError} from './auth.mjs';
import {parseExact,stringifyExact,isRecord,integer,timestamp} from './exact-json.mjs';
export const PRODUCTION_FIREBASE_ENABLED=false;
const DAY=86400000,MAX=4102444800000;
const own=(x,k)=>isRecord(x)&&Object.hasOwn(x,k)?x[k]:undefined;
export const validDevice=id=>typeof id==='string'&&/^(?:\d{1,8}|-2147483)$/.test(id);
const uuid=id=>typeof id==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(id);
const promoCode=x=>typeof x==='string'&&/^[A-Z0-9_-]{1,64}$/.test(x);
export async function digest(text){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))].map(b=>b.toString(16).padStart(2,'0')).join('');}
function exactKeys(body,keys){if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).length!==keys.length||keys.some(k=>!Object.hasOwn(body,k)))fail('REQUEST_INVALID',400);}
export function canonical(kind,b){
 const keys=kind==='PROMO_CLAIM'?['request_id','device_id','promo_code']:kind==='UNLOCK_ACTIVATE'?['request_id','device_id','unlock_code']:['request_id','device_id','days','order_ref'];exactKeys(b,keys);
 if(!uuid(b.request_id)||!validDevice(b.device_id))fail('REQUEST_INVALID',400);
 if(kind==='PROMO_CLAIM'&&!promoCode(b.promo_code))fail('REQUEST_INVALID',400);
 if(kind==='UNLOCK_ACTIVATE'&&(typeof b.unlock_code!=='string'||!/^[-A-Z0-9]{1,64}$/.test(b.unlock_code)))fail('REQUEST_INVALID',400);
 if(!['PROMO_CLAIM','UNLOCK_ACTIVATE'].includes(kind)&&(!Number.isInteger(b.days)||b.days<1||b.days>36500||typeof b.order_ref!=='string'||!/^[-A-Za-z0-9_:]{1,100}$/.test(b.order_ref)))fail('REQUEST_INVALID',400);
 return b;
}
function state(user,now){
 if(!isRecord(user))return {state:'UNKNOWN',expiresAt:null,revision:null};
 let expiry=null,revision=null;try{expiry=timestamp(user.expired_at);revision=user.revision===undefined?0:integer(user.revision);}catch{}
 const active=user.status==='aktif'||user.status===undefined&&Object.values(user.redeemed_promos||{}).some(x=>x===true);
 return {state:user.status==='banned'?'BANNED':!active||expiry===null?'UNKNOWN':expiry>now?'PREMIUM':expiry>0?'EXPIRED':'FREE',expiresAt:expiry,revision};
}
export function createService({db,firebase,clock=Date.now}){
 const one=(sql,...args)=>db.prepare(sql).bind(...args).first();
 const run=(sql,...args)=>db.prepare(sql).bind(...args).run();
 const admin=p=>{if(!p.admin)fail('FORBIDDEN',403);};
 async function owner(p,id){if(p.admin)return;const b=await one('SELECT owner_uid,status FROM premium_v1_bindings WHERE device_id=?1',id);if(!b||b.owner_uid!==p.uid||b.status!=='VERIFIED_OWNER')fail('OWNERSHIP_REQUIRED',403);}
 async function current(p,id){await owner(p,id);const snapshot=await firebase.read(id);return state(parseExact(snapshot.text),clock());}
 async function operation(p,id){if(!uuid(id))fail('REQUEST_INVALID',400);const op=await one('SELECT * FROM premium_v1_operations WHERE request_id=?1',id);if(!op)fail('OPERATION_NOT_FOUND',404);if(!p.admin&&op.actor_uid!==p.uid)fail('FORBIDDEN',403);if(!p.admin)await owner(p,op.device_id);return op;}
 async function describe(p,op){return {contractVersion:2,requestId:op.request_id,outcome:op.state==='COMPLETED'?'APPLIED':'PENDING',retryable:op.state!=='COMPLETED',receipt:op.state==='COMPLETED'?JSON.parse(op.result_json):null,currentEntitlement:op.device_id?await current(p,op.device_id):null,serverTime:clock()};}
 async function reserve(p,kind,b){
  await owner(p,b.device_id);const fingerprint=await digest(JSON.stringify({kind,body:Object.fromEntries(Object.entries(b).filter(([k])=>k!=='request_id').sort(([a],[c])=>a.localeCompare(c)))}));
  let prior=await one('SELECT * FROM premium_v1_operations WHERE request_id=?1',b.request_id);
  if(prior){if(prior.actor_uid!==p.uid||prior.kind!==kind||prior.fingerprint!==fingerprint)fail('IDEMPOTENCY_CONFLICT',409);return prior;}
  const now=clock();let days=b.days||0;
  if(kind==='PROMO_CLAIM'){
   const claimed=await one('SELECT request_id FROM premium_v1_claims WHERE promo_code=?1 AND device_id=?2',b.promo_code,b.device_id);if(claimed)fail('CLAIM_ALREADY_RECORDED',409);
   const promo=await one('SELECT * FROM premium_v1_promos WHERE code=?1',b.promo_code);if(!promo)fail('PROMO_NOT_FOUND',404);if(promo.status!=='aktif'||promo.valid_until<=now||promo.used_count>=promo.max_quota)fail('PROMO_UNAVAILABLE',409);days=promo.days;
  }
  // Read-only business preflight before reserving quota; revalidated again during projection.
  const user=parseExact((await firebase.read(b.device_id)).text);
  if(user?.status==='banned')fail('BANNED',403);
  if(kind==='PROMO_CLAIM'){if(!firebase.legacyEvidence)fail('LEGACY_EVIDENCE_UNAVAILABLE',503);const e=await firebase.legacyEvidence(b.device_id,b.promo_code);if(e.repairExists||e.claimExists)fail('LEGACY_RECONCILIATION_REQUIRED',409);}
  if(kind==='PROMO_CLAIM'&&own(user?.redeemed_promos,b.promo_code)!==undefined)fail('LEGACY_RECONCILIATION_REQUIRED',409);
  if(kind==='UNLOCK_CREATE'&&user?.code)fail('CODE_ALREADY_EXISTS',409);
  if(kind==='UNLOCK_ACTIVATE'){
   if(typeof user?.code!=='string'||await digest(user.code)!==await digest(b.unlock_code))fail('UNLOCK_INVALID',403);
   if(state(user,now).state!=='PREMIUM')fail('ENTITLEMENT_INACTIVE',409);
  }
  if(kind!=='UNLOCK_CREATE'&&!isRecord(user))fail('CUSTOMER_NOT_FOUND',404);
  if(isRecord(user)&&user.expired_at!==undefined){let before;try{before=timestamp(user.expired_at);}catch{fail('DATA_RECONCILIATION_REQUIRED',409);}const after=kind==='UNLOCK_ACTIVATE'?before:Math.max(before,now)+days*DAY;if(!Number.isSafeInteger(after)||after>MAX||after>now+5*366*DAY)fail('DATA_RECONCILIATION_REQUIRED',409);}
  const statements=[db.prepare(`INSERT INTO premium_v1_operations(request_id,actor_uid,actor_role,fingerprint,kind,device_id,promo_code,days,state,order_ref,created_at,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,'PENDING',?9,?10,?10)`).bind(b.request_id,p.uid,p.admin?'admin':'customer',fingerprint,kind,b.device_id,b.promo_code||null,days,b.order_ref||null,now),db.prepare('INSERT INTO premium_v1_account_locks(device_id,request_id) VALUES(?1,?2)').bind(b.device_id,b.request_id)];
  if(kind==='PROMO_CLAIM')statements.push(db.prepare(`UPDATE premium_v1_promos SET used_count=used_count+1,revision=revision+1 WHERE code=?1 AND status='aktif' AND valid_until>?2 AND days=?3 AND used_count<max_quota`).bind(b.promo_code,now,days),db.prepare(`INSERT INTO premium_v1_claims(promo_code,device_id,request_id) VALUES(?1,?2,CASE WHEN changes()=1 THEN ?3 ELSE NULL END)`).bind(b.promo_code,b.device_id,b.request_id));
  try{await db.batch(statements);}catch{
   prior=await one('SELECT * FROM premium_v1_operations WHERE request_id=?1',b.request_id);
   if(prior){if(prior.actor_uid!==p.uid||prior.fingerprint!==fingerprint)fail('IDEMPOTENCY_CONFLICT',409);return prior;}
   fail('CONCURRENT_OPERATION_OR_QUOTA',409);
  }
  return one('SELECT * FROM premium_v1_operations WHERE request_id=?1',b.request_id);
 }
 async function reconcile(p,op,depth=0){
  if(depth>3)fail('UNKNOWN_RESULT',503);
  await owner(p,op.device_id);if(op.state==='COMPLETED')return describe(p,op);
  const lock=await one('SELECT request_id FROM premium_v1_account_locks WHERE device_id=?1',op.device_id);if(lock?.request_id!==op.request_id)fail('RECONCILIATION_REQUIRED',409);
  const markerKey=await digest(op.request_id),snap=await firebase.read(op.device_id),raw=parseExact(snap.text),marker=own(raw?._premium_api_operations,markerKey);
  if(marker!==undefined){
   if(!isRecord(marker)||marker.fingerprint!==op.fingerprint)fail('RECONCILIATION_REQUIRED',409);
   let receipt;try{receipt={requestId:op.request_id,kind:op.kind,days:op.days,expiryBefore:timestamp(marker.expiryBefore),expiresAt:timestamp(marker.expiryAfter),revision:integer(marker.revision),appliedAt:timestamp(marker.appliedAt),actorUid:op.actor_uid};}catch{fail('RECONCILIATION_REQUIRED',409);}
   await complete(op,receipt);return describe(p,await operation(p,op.request_id));
  }
  if(raw?.status==='banned')fail('BANNED',403);
  if(op.kind==='PROMO_CLAIM'){if(!firebase.legacyEvidence)fail('LEGACY_EVIDENCE_UNAVAILABLE',503);const e=await firebase.legacyEvidence(op.device_id,op.promo_code);if(e.repairExists||e.claimExists)fail('LEGACY_RECONCILIATION_REQUIRED',409);const promo=await one('SELECT status,valid_until,days FROM premium_v1_promos WHERE code=?1',op.promo_code);if(!promo||promo.status!=='aktif'||promo.valid_until<=clock()||promo.days!==op.days)fail('PROMO_REVIEW_REQUIRED',409);}
  if(op.state==='RECONCILIATION_REQUIRED')fail('RECONCILIATION_REQUIRED',409);
  if(op.state==='PREPARED'&&snap.etag!==op.firebase_etag){await run("UPDATE premium_v1_operations SET state='RECONCILIATION_REQUIRED',last_error='AMBIGUOUS_FIREBASE_CHANGE',updated_at=?2 WHERE request_id=?1 AND state<>'COMPLETED'",op.request_id,clock());fail('RECONCILIATION_REQUIRED',409);}
  if(op.state==='PENDING'){
   const now=clock();let user=raw;
   if(user===null&&op.kind==='UNLOCK_CREATE')user={status:'aktif',created_at:new Date(now).toISOString(),extend_count:0};
   if(!isRecord(user))fail('CUSTOMER_NOT_FOUND',404);
   const active=state(user,now);if(op.kind!=='UNLOCK_CREATE'&&active.state==='UNKNOWN')fail('DATA_RECONCILIATION_REQUIRED',409);
   if(op.kind==='UNLOCK_CREATE'&&user.code)fail('CODE_ALREADY_EXISTS',409);
   if(op.kind==='PROMO_CLAIM'&&own(user.redeemed_promos,op.promo_code)!==undefined)fail('LEGACY_RECONCILIATION_REQUIRED',409);
   const before=user.expired_at===undefined?0:timestamp(user.expired_at),after=op.kind==='UNLOCK_ACTIVATE'?before:Math.max(before,now)+op.days*DAY;
   if(!Number.isSafeInteger(after)||after>MAX||after>now+5*366*DAY||before<0)fail('DATA_RECONCILIATION_REQUIRED',409);
   const revision=(user.revision===undefined?0:integer(user.revision))+1;if(!Number.isSafeInteger(revision))fail('DATA_RECONCILIATION_REQUIRED',409);
   const updated={...user,expired_at:after,revision,last_update:new Date(now).toISOString(),_premium_api_operations:{...(user._premium_api_operations||{}),[markerKey]:{fingerprint:op.fingerprint,expiryBefore:before,expiryAfter:after,revision,appliedAt:now}}};
   if(op.kind==='PROMO_CLAIM')updated.redeemed_promos={...(user.redeemed_promos||{}),[op.promo_code]:true};
   if(op.kind==='ADMIN_RENEWAL')updated.extend_count=(user.extend_count===undefined?0:integer(user.extend_count))+1;
   if(op.kind==='UNLOCK_CREATE'){const bytes=crypto.getRandomValues(new Uint8Array(12));updated.code=Array.from(bytes,b=>(b%36).toString(36)).join('').toUpperCase();updated.status='aktif';updated.account_type=user.account_type||'vip';}
   const payload=stringifyExact(updated);
   await run("UPDATE premium_v1_operations SET state='PREPARED',firebase_etag=?2,firebase_before=?3,firebase_after=?4,firebase_payload=?5,updated_at=?6 WHERE request_id=?1 AND state='PENDING'",op.request_id,snap.etag,String(before),String(after),payload,now);
   op=await operation(p,op.request_id);
  }
  // Immutable prepared CAS. Never recompute a delta after an ambiguous network result.
  let status;try{status=await firebase.compareAndSet(op.device_id,op.firebase_etag,op.firebase_payload);}catch{await run("UPDATE premium_v1_operations SET last_error='UNKNOWN_FIREBASE_RESULT',updated_at=?2 WHERE request_id=?1 AND state<>'COMPLETED'",op.request_id,clock());fail('UNKNOWN_RESULT',503);}
  if(status!==200){
   const latest=parseExact((await firebase.read(op.device_id)).text);
   if(own(latest?._premium_api_operations,markerKey)!==undefined)return reconcile(p,op,depth+1);
   // Explicit CAS rejection proves this attempt did not apply, but not another in-flight attempt.
   // Fail closed for operator review rather than risking a repeated grant.
   await run("UPDATE premium_v1_operations SET state='RECONCILIATION_REQUIRED',last_error=?2,updated_at=?3 WHERE request_id=?1 AND state<>'COMPLETED'",op.request_id,status===412?'FIREBASE_CONFLICT':'FIREBASE_REJECTED',clock());fail('RECONCILIATION_REQUIRED',409);
  }
  return reconcile(p,op,depth+1); // Confirm durable Firebase marker before reporting success.
 }
 async function complete(op,receipt){
  // Audit actor/order are private in D1. Firebase marker contains no actor credential/code.
  await db.batch([db.prepare("UPDATE premium_v1_operations SET state='COMPLETED',result_json=?2,firebase_payload=NULL,last_error=NULL,updated_at=?3 WHERE request_id=?1 AND state<>'COMPLETED'").bind(op.request_id,JSON.stringify(receipt),clock()),db.prepare('DELETE FROM premium_v1_account_locks WHERE device_id=?1 AND request_id=?2').bind(op.device_id,op.request_id)]);
 }
 return {
  current,
  async customer(p,id){admin(p);if(!validDevice(id))fail('REQUEST_INVALID',400);const user=parseExact((await firebase.read(id)).text);return {contractVersion:2,deviceId:id,currentEntitlement:state(user,clock()),unlockCode:typeof user?.code==='string'?user.code:null};},
  async mutate(p,kind,b){if(['ADMIN_RENEWAL','UNLOCK_CREATE'].includes(kind))admin(p);canonical(kind,b);return reconcile(p,await reserve(p,kind,b));},
  async lookup(p,id,retry=false){const op=await operation(p,id);return retry?reconcile(p,op):describe(p,op);},
  async history(p,id){admin(p);if(!validDevice(id))fail('REQUEST_INVALID',400);const r=await db.prepare('SELECT request_id,kind,state,days,actor_uid,order_ref,created_at,updated_at,last_error FROM premium_v1_operations WHERE device_id=?1 ORDER BY created_at DESC LIMIT 50').bind(id).all();return {contractVersion:2,operations:r.results};},
  async promos(p){admin(p);const r=await db.prepare('SELECT * FROM premium_v1_promos ORDER BY code LIMIT 100').all();return {contractVersion:2,promos:r.results};},
  async savePromo(p,b){admin(p);exactKeys(b,['request_id','code','days','max_quota','valid_until','status','expected_revision','order_ref']);if(!uuid(b.request_id)||!promoCode(b.code)||!Number.isInteger(b.days)||b.days<1||b.days>36500||!Number.isInteger(b.max_quota)||b.max_quota<0||b.max_quota>1000000||!Number.isSafeInteger(b.valid_until)||b.valid_until<=clock()||b.valid_until>MAX||!['aktif','paused'].includes(b.status)||!Number.isSafeInteger(b.expected_revision)||b.expected_revision<0||typeof b.order_ref!=='string'||!/^[-A-Za-z0-9_:]{1,100}$/.test(b.order_ref))fail('REQUEST_INVALID',400);
   const fingerprint=await digest(JSON.stringify(b));const old=await one('SELECT * FROM premium_v1_operations WHERE request_id=?1',b.request_id);if(old){if(old.actor_uid!==p.uid||old.fingerprint!==fingerprint)fail('IDEMPOTENCY_CONFLICT',409);return {contractVersion:2,requestId:b.request_id,outcome:'REPLAYED'};}
   const statement=b.expected_revision===0?db.prepare('INSERT INTO premium_v1_promos(code,days,max_quota,valid_until,status) VALUES(?1,?2,?3,?4,?5)').bind(b.code,b.days,b.max_quota,b.valid_until,b.status):db.prepare('UPDATE premium_v1_promos SET days=?2,max_quota=?3,valid_until=?4,status=?5,revision=revision+1 WHERE code=?1 AND revision=?6 AND used_count<=?3').bind(b.code,b.days,b.max_quota,b.valid_until,b.status,b.expected_revision);
   try{await db.batch([statement,db.prepare(`INSERT INTO premium_v1_operations(request_id,actor_uid,actor_role,fingerprint,kind,device_id,days,state,order_ref,created_at,updated_at,result_json) VALUES(CASE WHEN changes()=1 THEN ?1 ELSE NULL END,?2,'admin',?3,'PROMO_UPSERT','',0,'COMPLETED',?4,?5,?5,'{}')`).bind(b.request_id,p.uid,fingerprint,b.order_ref,clock())]);}catch{const replay=await one('SELECT fingerprint,actor_uid FROM premium_v1_operations WHERE request_id=?1',b.request_id);if(replay?.fingerprint===fingerprint&&replay.actor_uid===p.uid)return {contractVersion:2,requestId:b.request_id,outcome:'REPLAYED'};fail('CONFLICT',409);}return {contractVersion:2,requestId:b.request_id,outcome:'APPLIED'};
  }
 };
}
