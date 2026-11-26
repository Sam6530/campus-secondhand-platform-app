// 实际Vue页面、合成API；验证交互与局域网入口，不替代真实MySQL业务验收。
const {createRequire}=require('node:module');
const path=require('node:path'),fs=require('node:fs'),os=require('node:os');
const {spawn}=require('node:child_process');
const runtimeRequire=process.env.PLAYWRIGHT_PACKAGE?createRequire(path.resolve(process.env.PLAYWRIGHT_PACKAGE,'package.json')):require;
const {chromium}=runtimeRequire('playwright');
const root=path.resolve(__dirname,'..');
const output=path.resolve(root,'../../documents/evidence/v1-integration-20261126');
const port=15173;
const addresses=Object.values(os.networkInterfaces()).flat();
const lan=addresses.find(a=>a.family==='IPv4'&&!a.internal&&/^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address));
const base=`http://${lan?.address||'127.0.0.1'}:${port}`;
const server=spawn(process.execPath,['serve.mjs'],{cwd:root,windowsHide:true,env:{...process.env,FRONTEND_HOST:'0.0.0.0',FRONTEND_PORT:String(port)},stdio:'ignore'});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
  let browser;
  try {
    for(let i=0;i<60;i++){try{if((await fetch(base)).ok)break;}catch{}await delay(100);}
    browser=await chromium.launch({headless:true,channel:'msedge'});
    const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    let user=null,published=false,invalid=false,posts=0,reviewed=false,reviewLength=0;
    await page.route('**/api/**',async route=>{
      const req=route.request(),p=new URL(req.url()).pathname.slice(4),method=req.method();
      let data=[];
      if(p==='/me')data={user,csrf:'token'};
      else if(p==='/auth/login'){user={id:'buyer-public',nickname:'测试用户',role:'USER',verified:1,restricted:0};data={user,csrf:'token2'};}
      else if(p==='/assets')data={id:'image1'};
      else if(p==='/items'&&method==='POST'){
        posts++;
        if(invalid){await route.fulfill({status:200,contentType:'text/html',body:'<html>invalid gateway response</html>'});return;}
        published=true;data={id:'item1'};
      }
      else if(p==='/notifications'&&published){await route.fulfill({status:503,contentType:'application/json',body:'{"message":"模拟列表查询失败"}'});return;}
      else if(p==='/admin/dashboard'){
        if(reviewed){await route.fulfill({status:503,contentType:'application/json',body:'{"message":"模拟刷新失败"}'});return;}
        data={items:[],verifications:[{id:'v1',version:1,real_name:'合成学生',student_no:'S1'}]};
      }
      else if(p==='/admin/verification/v1'){reviewed=true;reviewLength=JSON.parse(req.postData()).reason.length;data={ok:true};}
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});
    });
    const passed=[];
    await page.goto(base);
    await page.getByRole('button',{name:'登录 / 注册'}).click();
    await page.getByLabel('账号',{exact:true}).fill('buyer');
    await delay(6200);
    if(await page.getByLabel('账号',{exact:true}).inputValue()!=='buyer')throw Error('匿名轮询清除了登录输入');
    passed.push('匿名登录输入跨过5秒轮询仍保留');
    await page.getByLabel('密码',{exact:true}).fill('Password123!');
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByRole('button',{name:'退出登录'}).waitFor();
    async function fillItem(title){
      await page.getByRole('button',{name:'＋ 发布闲置',exact:true}).click();
      await page.getByLabel('物品名称',{exact:true}).fill(title);
      await page.getByLabel('价格 / 元',{exact:true}).fill('10.00');
      await page.getByLabel('物品描述',{exact:true}).fill('合成测试物品');
      await page.getByLabel('图片（JPEG/PNG，最多5MB）',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY1kAAAAASUVORK5CYII=','base64')});
      await page.getByRole('button',{name:'提交审核',exact:true}).waitFor();
      await page.waitForFunction(()=>!Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='提交审核')?.disabled);
    }
    await fillItem('成功后刷新失败');
    await page.getByRole('button',{name:'提交审核',exact:true}).click();
    await page.getByRole('status').filter({hasText:'已提交当前版本审核。 列表刷新失败'}).waitFor();
    if(posts!==1)throw Error('成功提交发生了重复请求');
    passed.push('发布成功与列表刷新失败分开提示，未重复提交');
    invalid=true;await fillItem('无效响应仍保留表单');
    await page.getByRole('button',{name:'提交审核',exact:true}).click();
    await page.getByRole('status').filter({hasText:'结果尚未确认'}).waitFor();
    if(await page.getByLabel('物品名称',{exact:true}).inputValue()!=='无效响应仍保留表单')throw Error('无效响应清除了表单');
    passed.push('200非JSON拒绝成功处理且保留表单');
    published=false;user.role='ADMIN';
    await page.getByRole('button',{name:'管理台',exact:true}).waitFor({timeout:10000});
    await page.getByRole('button',{name:'管理台',exact:true}).click();
    await page.getByLabel('审核依据',{exact:true}).fill('审'.repeat(1500));
    await page.getByRole('button',{name:'通过',exact:true}).click();
    await page.getByRole('alert').filter({hasText:'审核已保存，列表刷新失败'}).waitFor();
    if(reviewLength!==1500)throw Error('1500字审核理由未完整发送');
    passed.push('同账号角色变化可见管理台，1500字审核与刷新失败提示正确');
    await page.setViewportSize({width:390,height:844});
    if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('移动端横向溢出');
    passed.push('390px页面无横向溢出');
    if(lan)passed.push('前端通过本机局域网IPv4地址可访问');
    if(errors.length)throw Error(errors.join('\n'));
    fs.mkdirSync(output,{recursive:true});
    fs.writeFileSync(path.join(output,'browser-results.json'),JSON.stringify({date:'2026-11-26',base,scope:'真实Vue页面和Edge；API为合成替身；局域网地址由本机访问',passed,errors},null,2));
    console.log(JSON.stringify({base,passed,errors},null,2));
  } finally {await browser?.close();server.kill();}
})().catch(e=>{console.error(e);process.exitCode=1;});
