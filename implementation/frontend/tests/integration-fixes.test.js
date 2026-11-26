import test from 'node:test';
import assert from 'node:assert/strict';
import {createClient, createIntentKey, ResponseFormatError} from '../src/lib/client.js';

test('匿名会话轮询不清理输入；真实退出仍清理', () => {
  let clears=0;
  const client=createClient({onIdentityChange:()=>clears++});
  client.identity(null,'token1');client.identity(undefined,'token2');client.identity(null,'token3');
  assert.equal(clears,0);assert.equal(client.epoch,0);
  client.identity({id:'public-user'},'token4');
  client.identity({id:'public-user'},'token5');assert.equal(clears,1);
  client.identity(null,'token6');assert.equal(clears,2);
});

test('200无效JSON必须报结果未确认；重试保留原意图键', async () => {
  const keys=[];let valid=false;
  const client=createClient({keyFactory:()=>`key-${keys.length+1}`,fetcher:async(path,options)=>{
    keys.push(options.headers['Idempotency-Key']);
    return {ok:true,json:async()=>{if(!valid)throw new SyntaxError();return {id:'item1'};}};
  }});
  await assert.rejects(client.command('/items','POST',{title:'book'}),ResponseFormatError);
  valid=true;await client.command('/items','POST',{title:'book'});
  assert.deepEqual(keys,['key-1','key-1']);
});

test('错误状态且非JSON时保留HTTP状态', async () => {
  const client=createClient({fetcher:async()=>({ok:false,status:503,json:async()=>{throw new SyntaxError();}})});
  await assert.rejects(client.request('/items'),e=>e.status===503);
});

test('没有randomUUID的局域网环境仍可生成合法随机意图键', () => {
  let calls=0;
  const api={getRandomValues:bytes=>{calls++;bytes.fill(calls);return bytes;}};
  const a=createIntentKey(api),b=createIntentKey(api);
  assert.match(a,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(a,b);assert.equal(calls,2);
});

test('同一账号权限变化隔离旧响应，权限不变不清理页面', async () => {
  let resolve,clears=0;
  const client=createClient({onIdentityChange:()=>clears++,fetcher:()=>new Promise(r=>resolve=r)});
  client.identity({id:'same',role:'USER',verified:1,restricted:0},'token');
  const pending=client.request('/orders');
  client.identity({id:'same',role:'USER',verified:true,restricted:false},'token2');
  assert.equal(clears,1);
  client.identity({id:'same',role:'USER',verified:1,restricted:1},'token3');
  resolve({ok:true,json:async()=>[{id:'stale-order'}]});
  await assert.rejects(pending,error=>error.constructor.name==='StaleResponse');
  assert.equal(clears,2);
});
