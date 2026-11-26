export class StaleResponse extends Error {}
export class ResponseFormatError extends Error {}
// 局域网HTTP不一定提供randomUUID，使用安全随机字节生成意图键。
export function createIntentKey(api=globalThis.crypto){
 if(typeof api?.randomUUID==='function')return api.randomUUID();
 const b=api.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;
 const h=Array.from(b,n=>n.toString(16).padStart(2,'0')).join('');
 return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
export function createClient({base='/api',fetcher=fetch,onIdentityChange=()=>{},keyFactory=createIntentKey}={}){
 let csrf='',actor=null,epoch=0,permissions='';const inflight=new Set(),intents=new Map();
 function identity(user,token){csrf=token||'';const nextActor=user?.id??null;const nextPermissions=user?JSON.stringify([user.role??null,!!user.verified,!!user.restricted]):'';if(actor!==nextActor||permissions!==nextPermissions){actor=nextActor;permissions=nextPermissions;epoch++;intents.clear();for(const c of inflight)c.abort();inflight.clear();onIdentityChange();}}
 function invalidate(){csrf='';actor=null;permissions='';epoch++;intents.clear();for(const c of inflight)c.abort();inflight.clear();onIdentityChange();}
 async function request(path,{method='GET',body,key}={}){
  const generation=epoch,controller=new AbortController();inflight.add(controller);const timer=setTimeout(()=>controller.abort(),15000);
  const headers={};if(method!=='GET')headers['X-CSRF-Token']=csrf;if(key)headers['Idempotency-Key']=key;
  const form=typeof FormData!=='undefined'&&body instanceof FormData;if(body!==undefined&&!form)headers['Content-Type']='application/json';
  try{const r=await fetcher(base+path,{method,headers,credentials:'same-origin',signal:controller.signal,body:body===undefined?undefined:form?body:JSON.stringify(body)});
   let data;try{data=await r.json();}catch{if(r.ok)throw new ResponseFormatError('响应格式异常，结果尚未确认。请查询已有记录，勿直接重复提交。');data={message:`请求失败（HTTP ${r.status}）`};}if(generation!==epoch)throw new StaleResponse();
   if(!r.ok){const e=new Error(data?.message||'请求失败');e.status=r.status;throw e;}return data;
  }catch(e){if(generation!==epoch)throw new StaleResponse();if(e.status||e instanceof StaleResponse||e instanceof ResponseFormatError)throw e;throw new Error('网络中断或请求超时。请保留输入，查询结果或原样重试。');}
  finally{clearTimeout(timer);inflight.delete(controller);}
 }
 async function command(path,method,body){
  const signature=JSON.stringify([actor,path,method,body]);let key=intents.get(signature);if(!key){key=keyFactory();intents.set(signature,key);}
  const result=await request(path,{method,body,key});intents.delete(signature);return result;
 }
 return {request,command,identity,invalidate,get epoch(){return epoch},get actor(){return actor}};
}
