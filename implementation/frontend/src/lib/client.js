export class StaleResponse extends Error {}
export function createClient({base='/api',fetcher=fetch,onIdentityChange=()=>{},keyFactory=()=>crypto.randomUUID()}={}){
 let csrf='',actor=null,epoch=0;const inflight=new Set(),intents=new Map();
 function identity(user,token){csrf=token||'';if(actor!==user?.id){actor=user?.id??null;epoch++;intents.clear();for(const c of inflight)c.abort();inflight.clear();onIdentityChange();}}
 function invalidate(){csrf='';actor=null;epoch++;intents.clear();for(const c of inflight)c.abort();inflight.clear();onIdentityChange();}
 async function request(path,{method='GET',body,key}={}){
  const generation=epoch,controller=new AbortController();inflight.add(controller);const timer=setTimeout(()=>controller.abort(),15000);
  const headers={};if(method!=='GET')headers['X-CSRF-Token']=csrf;if(key)headers['Idempotency-Key']=key;
  const form=typeof FormData!=='undefined'&&body instanceof FormData;if(body!==undefined&&!form)headers['Content-Type']='application/json';
  try{const r=await fetcher(base+path,{method,headers,credentials:'same-origin',signal:controller.signal,body:body===undefined?undefined:form?body:JSON.stringify(body)});
   const data=await r.json().catch(()=>({message:'响应格式异常，请查询结果后再重试。'}));if(generation!==epoch)throw new StaleResponse();
   if(!r.ok){const e=new Error(data.message||'请求失败');e.status=r.status;throw e;}return data;
  }catch(e){if(generation!==epoch)throw new StaleResponse();if(e.status||e instanceof StaleResponse)throw e;throw new Error('网络中断或请求超时。请保留输入，查询结果或原样重试。');}
  finally{clearTimeout(timer);inflight.delete(controller);}
 }
 async function command(path,method,body){
  const signature=JSON.stringify([actor,path,method,body]);let key=intents.get(signature);if(!key){key=keyFactory();intents.set(signature,key);}
  const result=await request(path,{method,body,key});intents.delete(signature);return result;
 }
 return {request,command,identity,invalidate,get epoch(){return epoch},get actor(){return actor}};
}
