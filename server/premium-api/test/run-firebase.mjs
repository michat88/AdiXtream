import {spawn} from 'node:child_process';
const jar=process.env.FIREBASE_EMULATOR_JAR;
if(!jar||!jar.endsWith('.jar'))throw Error('Set FIREBASE_EMULATOR_JAR to the local Firebase Database Emulator jar. No production credentials are used.');
const java=spawn('java',['-jar',jar,'--host','127.0.0.1','--port','19185'],{stdio:['ignore','ignore','pipe']});
let failed=false;java.on('error',()=>{failed=true;});java.stderr.resume();
try{
 let ready=false;for(let i=0;i<150&&!failed&&java.exitCode===null;i++){try{const r=await fetch('http://127.0.0.1:19185/.json?ns=demo-central-ready',{signal:AbortSignal.timeout(300)});if(r.status){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
 if(!ready)throw Error('LOCAL_EMULATOR_START_FAILED');
 const child=spawn(process.execPath,['--test',new URL('./firebase.integration.mjs',import.meta.url).pathname],{stdio:'inherit',env:{...process.env,TEST_FIREBASE_ORIGIN:'http://127.0.0.1:19185'}});
 process.exitCode=await new Promise(resolve=>{child.on('exit',code=>resolve(code??1));child.on('error',()=>resolve(1));});
}finally{java.kill('SIGTERM');}
