// React Native/Node 20 compatible: retain every existing JSON number verbatim.
// Root CAS must never round unrelated customer timestamps or metadata.
class RawNumber { constructor(text) { this.text=text;Object.freeze(this); } }
const isRecord=value=>value!==null&&typeof value==='object'&&!Array.isArray(value)&&!(value instanceof RawNumber);
function parseExact(text) {
 if(typeof text!=='string'||text.length>8*1024*1024)throw Error('DATA_INVALID');
 let at=0,nodes=0;
 const whitespace=()=>{while(/[\t\n\r ]/.test(text[at]||'!'))at++;};
 function string(){const start=at++;while(at<text.length){const c=text[at++];if(c==='"')return JSON.parse(text.slice(start,at));if(c==='\\')at++;}throw Error('DATA_INVALID');}
 function value(depth){
  if(depth>64||++nodes>250000)throw Error('DATA_INVALID');whitespace();const c=text[at];
  if(c==='"')return string();
  if(c==='{'){
   at++;const result=Object.create(null);whitespace();if(text[at]==='}'){at++;return result;}
   while(true){whitespace();if(text[at]!=='"')throw Error('DATA_INVALID');const key=string();
    if(Object.prototype.hasOwnProperty.call(result,key))throw Error('DATA_INVALID');whitespace();if(text[at++]!==':')throw Error('DATA_INVALID');result[key]=value(depth+1);whitespace();const sep=text[at++];if(sep==='}')return result;if(sep!==',')throw Error('DATA_INVALID');}
  }
  if(c==='['){at++;const result=[];whitespace();if(text[at]===']'){at++;return result;}while(true){result.push(value(depth+1));whitespace();const sep=text[at++];if(sep===']')return result;if(sep!==',')throw Error('DATA_INVALID');}}
  for(const [literal,result] of [['true',true],['false',false],['null',null]])if(text.startsWith(literal,at)){at+=literal.length;return result;}
  const match=/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(at));
  if(!match)throw Error('DATA_INVALID');at+=match[0].length;return new RawNumber(match[0]);
 }
 try{const result=value(0);whitespace();if(at!==text.length)throw Error();return result;}catch{throw Error('DATA_INVALID');}
}
function stringifyExact(value,sorted=false){
 if(value instanceof RawNumber)return value.text;
 if(value===null||typeof value==='string'||typeof value==='boolean')return JSON.stringify(value);
 if(typeof value==='number'){if(!Number.isSafeInteger(value))throw Error('DATA_INVALID');return String(value);}
 if(Array.isArray(value))return '['+value.map(item=>stringifyExact(item,sorted)).join(',')+']';
 if(isRecord(value)){const keys=Object.keys(value);if(sorted)keys.sort();return '{'+keys.map(key=>JSON.stringify(key)+':'+stringifyExact(value[key],sorted)).join(',')+'}';}
 throw Error('DATA_INVALID');
}
function integer(value){
 if(typeof value==='number'){if(!Number.isSafeInteger(value))throw Error('DATA_INVALID');return value;}
 if(!(value instanceof RawNumber))throw Error('DATA_INVALID');
 const m=/^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value.text);if(!m)throw Error('DATA_INVALID');
 let digits=(m[2]+(m[3]||'')).replace(/^0+/,'');if(!digits)return 0;
 const power=Number(m[4]||0)-(m[3]||'').length;if(!Number.isSafeInteger(power))throw Error('DATA_INVALID');
 if(power>=0){if(digits.length+power>16)throw Error('DATA_INVALID');digits+='0'.repeat(power);}
 else{const cut=-power;if(cut>=digits.length||!/^0+$/.test(digits.slice(-cut)))throw Error('DATA_INVALID');digits=digits.slice(0,-cut);}
 const result=Number((m[1]||'')+digits);if(!Number.isSafeInteger(result))throw Error('DATA_INVALID');return result;
}
function timestamp(value){
 if(typeof value==='string'){
  if(/^\d{1,16}$/.test(value))value=new RawNumber(value.replace(/^0+(?=\d)/,''));
  else{if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value))throw Error('DATA_INVALID');const n=Date.parse(value),canonical=value.includes('.')?value:value.replace('Z','.000Z');if(!Number.isFinite(n)||new Date(n).toISOString()!==canonical)throw Error('DATA_INVALID');return n;}
 }
 let n=integer(value);if(n<0)throw Error('DATA_INVALID');if(n>0&&n<10000000000)n*=1000;if(!Number.isSafeInteger(n)||n>8640000000000000)throw Error('DATA_INVALID');return n;
}
export {parseExact,stringifyExact,isRecord,integer,timestamp};
