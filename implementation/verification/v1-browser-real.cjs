// 真实前端代理、后端与MySQL；不拦截API，不使用mock。
const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process'),{createRequire}=require('node:module');
const runtimeRequire=process.env.PLAYWRIGHT_PACKAGE?createRequire(path.join(process.env.PLAYWRIGHT_PACKAGE,'package.json')):require;
const {chromium}=runtimeRequire('playwright');
const root=path.resolve(__dirname,'../..'),output=path.join(root,'documents/evidence/v1-integration-20261126');
const config=Object.fromEntries(fs.readFileSync(path.join(root,'.env.mysql-selftest.local'),'utf8').trim().split(/\r?\n/).map(l=>{const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1)];}));
const delay=ms=>new Promise(r=>setTimeout(r,ms));const processes=[],logs=[];
function launch(exe,args,cwd,env,name){const log=fs.openSync(path.join(output,name+'.log'),'a');logs.push(log);const child=spawn(exe,args,{cwd,env:{...process.env,...env},windowsHide:true,stdio:['ignore',log,log]});processes.push(child);return child;}
async function wait(url,child){for(let i=0;i<300;i++){if(child.exitCode!==null)throw Error('临时服务提前退出');try{if((await fetch(url)).ok)return;}catch{}await delay(100);}throw Error('启动超时');}
(async()=>{let browser;try{
 const backend=launch(process.env.JAVA_BIN||'java',['-Xmx512m','-jar','target/campus-resale-backend.jar'],path.join(root,'implementation/backend'),{DB_URL:'jdbc:mysql://127.0.0.1:13317/campus_v1_app_selftest?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC',DB_USERNAME:'root',DB_PASSWORD:config.MYSQL_SELFTEST_PASSWORD,SERVER_PORT:'18081',SERVER_ADDRESS:'127.0.0.1',UPLOAD_DIR:path.join(root,'implementation/backend/target/http-test-uploads')},'browser-backend');
 await wait('http://127.0.0.1:18081/api/health',backend);
 const frontend=launch(process.execPath,['serve.mjs'],path.join(root,'implementation/frontend'),{FRONTEND_HOST:'127.0.0.1',FRONTEND_PORT:'15173',API_PROXY_TARGET:'http://127.0.0.1:18081'},'browser-frontend');
 await wait('http://127.0.0.1:15173',frontend);
 browser=await chromium.launch({headless:true,channel:'msedge'});const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));const passed=[];
 await page.goto('http://127.0.0.1:15173');
 await page.getByRole('button',{name:'登录 / 注册'}).click();
 await page.getByLabel('账号',{exact:true}).fill(config.SELFTEST_BUYER_USERNAME);
 await page.getByLabel('密码',{exact:true}).fill(config.SELFTEST_USER_PASSWORD);
 await page.getByRole('button',{name:'登录',exact:true}).click();
 await page.getByRole('button',{name:'退出登录',exact:true}).waitFor();passed.push('真实前端代理登录，Session与CSRF联通');
 await page.getByRole('button',{name:'我的订单',exact:true}).click();
 await page.getByRole('button',{name:'快照与交付详情',exact:true}).waitFor();passed.push('读取真实MySQL本人订单');
 await page.getByRole('button',{name:'快照与交付详情',exact:true}).click();await page.getByText('已在图书馆交付',{exact:true}).waitFor();passed.push('显示真实订单快照与交付记录');
 await page.getByRole('button',{name:'关闭弹窗',exact:true}).click();
 await page.getByRole('button',{name:'查看历史沟通',exact:true}).click();await page.getByText('订单已结束，会话只读。',{exact:true}).waitFor();await page.getByText('图书馆见',{exact:true}).waitFor();
 if(await page.getByRole('button',{name:'发送',exact:true}).count())throw Error('终态仍显示发送按钮');passed.push('真实终态会话可读、无发送入口');
 await page.getByRole('button',{name:'关闭弹窗',exact:true}).click();await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('button',{name:'登录 / 注册'}).waitFor();passed.push('真实退出登录并清理私人页面');
 if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(output,'browser-real-results.json'),JSON.stringify({date:'2026-11-26',scope:'Edge真实页面、真实代理/HTTP/Session/CSRF、MySQL；无API替身',passed,errors},null,2));console.log(JSON.stringify({passed,errors},null,2));
}finally{await browser?.close();for(const child of processes.reverse()){if(child.exitCode===null){const done=new Promise(r=>child.once('exit',r));child.kill();await done;}}for(const log of logs)fs.closeSync(log);}})().catch(e=>{console.error(e);process.exitCode=1;});
