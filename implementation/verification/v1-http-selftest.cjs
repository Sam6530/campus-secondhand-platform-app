// 真实HTTP、Session、CSRF和独立MySQL。只允许使用本轮固定的合成测试库。
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawn,execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..'),backend=path.join(root,'implementation/backend');
const evidence=path.join(root,'documents/evidence/v1-integration-20261126');fs.mkdirSync(evidence,{recursive:true});
const credentialPath=path.join(root,'.env.mysql-selftest.local');
const password=fs.readFileSync(credentialPath,'utf8').split(/\r?\n/).find(l=>l.startsWith('MYSQL_SELFTEST_PASSWORD=')).split('=')[1];
const docker='C:/Program Files/Docker/Docker/resources/bin/docker.exe';
const database='campus_v1_app_selftest',port=18081,base=`http://127.0.0.1:${port}/api`;
const env={...process.env,DB_URL:`jdbc:mysql://127.0.0.1:13317/${database}?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC`,DB_USERNAME:'root',DB_PASSWORD:password,SERVER_PORT:String(port),SERVER_ADDRESS:'127.0.0.1',UPLOAD_DIR:path.join(backend,'target/http-test-uploads')};
const passed=[];let server,log;
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function listening(){try{await fetch(base+'/health',{signal:AbortSignal.timeout(500)});return true;}catch{return false;}}
function sql(statement){return execFileSync(docker,['exec','-e',`MYSQL_PWD=${password}`,'campus-v1-selftest-20261126','mysql','--default-character-set=utf8mb4','-uroot','-N','-B',database,'-e',statement],{encoding:'utf8',windowsHide:true}).trim();}
async function start(){
 if(await listening())throw Error('测试端口已有服务，拒绝把其他进程当成本次启动');
 log=fs.openSync(path.join(evidence,'http-backend.log'),'a');
 server=spawn(process.env.JAVA_BIN||'java',['-Xmx512m','-jar','target/campus-resale-backend.jar'],{cwd:backend,env,stdio:['ignore',log,log],windowsHide:true});
 for(let i=0;i<300;i++){
  if(server.exitCode!==null)throw Error('后端提前退出，请查看http-backend.log');
  try{const r=await fetch(base+'/health');if(r.ok){assert.equal((await r.json()).schema,'V3');return;}}catch{}
  await delay(100);
 }
 throw Error('后端启动超时');
}
async function stop(){if(server&&server.exitCode===null){const exit=new Promise(r=>server.once('exit',r));server.kill();await exit;}if(log!==undefined){fs.closeSync(log);log=undefined;}for(let i=0;i<50;i++){if(!await listening())return;await delay(100);}throw Error('临时服务尚未退出，不能进行重启验证');}
class Client{
 cookie='';csrf='';
 async request(route,{method='GET',body,key,expect=200}={}){
  const headers={};if(this.cookie)headers.Cookie=this.cookie;if(method!=='GET')headers['X-CSRF-Token']=this.csrf;if(key)headers['Idempotency-Key']=key;
  const form=body instanceof FormData;if(body!==undefined&&!form)headers['Content-Type']='application/json';
  const r=await fetch(base+route,{method,headers,body:body===undefined?undefined:form?body:JSON.stringify(body)});
  const cookie=r.headers.getSetCookie().find(v=>v.startsWith('JSESSIONID='));if(cookie)this.cookie=cookie.split(';')[0];
  const text=await r.text();let data;try{data=JSON.parse(text);}catch{data=text;}
  assert.equal(r.status,expect,`${method} ${route}: ${JSON.stringify(data)}`);
  if(data?.csrf)this.csrf=data.csrf;return data;
 }
 async register(username){await this.request('/me');return this.request('/auth/register',{method:'POST',body:{username,password:'SelftestPassword123!',nickname:username}});}
 async upload(kind){const f=new FormData();f.append('kind',kind);f.append('file',new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==','base64')],{type:'image/png'}),'selftest.png');return this.request('/assets',{method:'POST',body:f});}
}
const key=()=>`http-${crypto.randomUUID()}`;
(async()=>{try{
 await start();passed.push('独立MySQL真实应用启动，V1/V2/V3升级校验成功');
 const suffix=Date.now().toString(36),admin=new Client(),seller=new Client(),buyer=new Client(),outsider=new Client();
 const adminName='admin_'+suffix,sellerName='seller_'+suffix,buyerName='buyer_'+suffix;
 await admin.register(adminName);await seller.register(sellerName);await buyer.register(buyerName);await outsider.register('other_'+suffix);
 sql(`UPDATE accounts SET role='ADMIN',verified=1 WHERE username='${adminName}';`);
 assert.equal((await admin.request('/me')).user.role,'ADMIN');passed.push('真实注册会话和管理员初始化');
 for(const [client,label] of [[seller,'S'],[buyer,'B']]){
  const asset=await client.upload('VERIFY');
  await outsider.request('/assets/'+asset.id,{expect:403});
  const verification=await client.request('/verifications',{method:'POST',body:{real_name:'合成'+label,student_no:label+suffix,asset_id:asset.id}});
  await admin.request('/admin/verification/'+verification.id,{method:'POST',body:{version:verification.version,pass:true,reason:'审'.repeat(1500)}});
  assert.equal((await client.request('/me')).user.verified,1);
 }
 passed.push('私有认证材料隔离、1500字审核、买卖双方认证');
 const image=await seller.upload('ITEM');const publishKey=key();
 const input={title:'真实HTTP合成教材',category:'教材书籍',description:'独立测试，校内交付',price:'25.00',image_id:image.id};
 const item=await seller.request('/items',{method:'POST',body:input,key:publishKey});
 assert.equal((await seller.request('/items',{method:'POST',body:input,key:publishKey})).id,item.id);
 await admin.request('/admin/item/'+item.id,{method:'POST',body:{version:item.version,pass:true,reason:'审'.repeat(1500)}});
 const anonymous=new Client();assert((await anonymous.request('/items')).some(i=>i.id===item.id));
 passed.push('图片上传、发布幂等、1500字物品审核、匿名浏览');
 await buyer.request('/messages?type=ITEM&object='+item.id,{method:'POST',body:{content:'可以校内交付吗'}});
 const thread=(await seller.request('/inbox')).find(t=>t.context_id===item.id);
 assert(thread);await seller.request('/messages?'+new URLSearchParams({type:'ITEM',object:item.id,peer:thread.peer_id}),{method:'POST',body:{content:'可以'}});
 assert.equal((await buyer.request('/messages?type=ITEM&object='+item.id)).length,2);passed.push('物品咨询与卖方按peer回复');
 const orderKey=key(),order=await buyer.request('/orders',{method:'POST',key:orderKey,body:{item_id:item.id,version:item.version}});
 assert.equal((await buyer.request('/orders',{method:'POST',key:orderKey,body:{item_id:item.id,version:item.version}})).id,order.id);
 assert.equal(order.buyer_id,(await buyer.request('/me')).user.id);assert.equal(order.seller_id,(await seller.request('/me')).user.id);
 await outsider.request('/orders/'+order.id+'/details',{expect:403});
 await seller.request('/orders/'+order.id+'/accept',{method:'POST',key:key(),body:{}});
 await buyer.request('/messages?type=ORDER&object='+order.id,{method:'POST',body:{content:'图书馆见'}});
 await seller.request('/orders/'+order.id+'/deliver',{method:'POST',key:key(),body:{reason:'已在图书馆交付',declared:true}});
 const details=await buyer.request('/orders/'+order.id+'/details');assert.equal(details.delivery.note,'已在图书馆交付');
 await seller.request('/orders/'+order.id+'/confirm',{method:'POST',key:key(),body:{declared:true},expect:403});
 const confirmKey=key();await buyer.request('/orders/'+order.id+'/confirm',{method:'POST',key:confirmKey,body:{declared:true}});
 await buyer.request('/orders/'+order.id+'/confirm',{method:'POST',key:confirmKey,body:{declared:true}});
 assert.equal((await buyer.request('/orders/'+order.id+'/details')).order.stage,'已完成');
 await buyer.request('/messages?type=ORDER&object='+order.id,{method:'POST',body:{content:'终态不能发送'},expect:403});
 assert.equal((await buyer.request('/messages?type=ORDER&object='+order.id)).length,1);
 assert((await buyer.request('/notifications')).length>0);
 passed.push('下单幂等、公开ID匹配、接单、交付、买方确认、重复确认及终态聊天');
 const state=sql(`SELECT stage FROM orders WHERE id='${order.id}'; SELECT status FROM items WHERE id='${item.id}'; SELECT COUNT(*) FROM reservations WHERE item_id='${item.id}'; SELECT COUNT(*) FROM deliveries WHERE order_id='${order.id}';`);
 assert.deepEqual(state.split(/\r?\n/),['已完成','已成交','0','1']);passed.push('MySQL订单、物品、占用和交付登记后态一致');
 const snapshot=sql('SELECT GROUP_CONCAT(CONCAT(version,":",checksum) ORDER BY version) FROM schema_migrations;');
 await stop();await start();
 const again=new Client();await again.request('/me');await again.request('/auth/login',{method:'POST',body:{username:buyerName,password:'SelftestPassword123!'}});
 assert.equal((await again.request('/orders/'+order.id+'/details')).order.stage,'已完成');
 assert.equal(sql('SELECT GROUP_CONCAT(CONCAT(version,":",checksum) ORDER BY version) FROM schema_migrations;'),snapshot);
 await buyer.request('/auth/logout',{method:'POST',body:{}}).catch(()=>{});
 passed.push('应用重启后凭据和订单保留，升级校验及记录不重复');
 fs.writeFileSync(path.join(evidence,'http-mysql-results.json'),JSON.stringify({date:'2026-11-26',database:'MySQL 8.0.39 / '+database,port,scope:'真实HTTP、Session、CSRF、独立MySQL、实际图片上传；不使用API替身',passed,orderId:order.id,itemId:item.id,databaseState:state},null,2));
 fs.appendFileSync(credentialPath,`SELFTEST_ADMIN_USERNAME=${adminName}\nSELFTEST_BUYER_USERNAME=${buyerName}\nSELFTEST_USER_PASSWORD=SelftestPassword123!\n`);
 console.log(JSON.stringify({passed,orderId:order.id,itemId:item.id},null,2));
}finally{await stop();}})().catch(e=>{console.error(e);process.exitCode=1;});
