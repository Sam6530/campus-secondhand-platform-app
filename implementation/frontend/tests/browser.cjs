// 独立测试用API替身，仅由Playwright拦截；不打入生产页面。
const {createRequire}=require('node:module');const fs=require('node:fs');const path=require('node:path');
const runtimeRequire=process.env.PLAYWRIGHT_PACKAGE?createRequire(path.resolve(process.env.PLAYWRIGHT_PACKAGE,'package.json')):require;
const {chromium}=runtimeRequire('playwright');
(async()=>{const browser=await chromium.launch({headless:true,channel:process.env.BROWSER_CHANNEL||'msedge'});const page=await browser.newPage();let user=null;const errors=[];page.on('pageerror',e=>errors.push(e.message));
let orders=[],stage='待卖方确认',privateText='';const item={id:'i1',seller_id:'seller-ref',seller_name:'同学乙',seller_verified:true,title:'高等数学教材',description:'九成新，校内交接',category:'教材书籍',price:'25.00',version:1};const calls=[];
await page.route('**/api/**',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname.slice(4),method=req.method();calls.push([method,p,req.headers()['idempotency-key']]);let data=[];
 if(p==='/me')data={user,csrf:'test-token'};
 else if(p==='/auth/login'){user={id:'buyer',nickname:'同学甲',verified:1,restricted:0,role:'USER'};data={user,csrf:'test-token2'};}
 else if(p==='/auth/logout'){user=null;data={ok:true};}
 else if(p==='/items')data=[item];
 else if(p==='/orders'&&method==='POST'){const o={...item,id:'o1',item_id:'i1',buyer_id:'buyer',seller_id:'seller',buyer_name:'同学甲',seller_name:'同学乙',stage,disputed:0};orders=[o];data=o;}
 else if(p==='/orders')data=orders;
 else if(p==='/orders/o1/details')data={order:{...orders[0],stage},delivery:stage==='已完成'?{actor_name:'同学乙',note:'图书馆交接',created_at:'2026-11-25T10:00:00Z'}:null,reviews:[]};
 else if(p==='/notifications')data=[{id:'n1',content:'收到新的订单通知',created_at:'2026-11-25T10:00:00Z'}];
 else if(p==='/messages')data=[];
 else if(p==='/verifications')data=[];
 await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});
});
const evidence=path.resolve(process.cwd(),'../../documents/A-V1/evidence');fs.mkdirSync(evidence,{recursive:true});const passed=[];
await page.goto('http://127.0.0.1:5173');await page.getByRole('heading',{name:'高等数学教材'}).waitFor();passed.push('匿名市场渲染与公开物品');await page.screenshot({path:path.join(evidence,'01-market.png'),fullPage:true});
await page.getByRole('button',{name:'登录 / 注册'}).click();await page.getByLabel('账号',{exact:true}).fill('buyer');await page.getByLabel('密码',{exact:true}).fill('Password123!');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'退出登录'}).waitFor();passed.push('登录Session/CSRF消费');
await page.getByRole('button',{name:'确认下单',exact:true}).click();if(await page.getByRole('checkbox').isChecked())throw Error('声明默认勾选');await page.getByRole('checkbox').check();await page.getByRole('button',{name:'确认提交',exact:true}).click();await page.getByRole('button',{name:'我的订单',exact:true}).click();await page.getByRole('button',{name:'快照与交付详情'}).waitFor();if(!calls.find(c=>c[0]==='POST'&&c[1]==='/orders'&&c[2]))throw Error('下单无意图键');passed.push('主动声明、下单携意图键及订单列表');
stage='已完成';orders[0].stage=stage;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('button',{name:'查看历史沟通'}).click();await page.getByText('订单已结束，会话只读。',{exact:true}).waitFor();if(await page.getByRole('button',{name:'发送',exact:true}).count())throw Error('终态仍可发送');passed.push('终态会话只读且无发送框');await page.getByRole('button',{name:'关闭弹窗'}).click();
await page.getByRole('button',{name:'快照与交付详情'}).click();await page.getByText('图书馆交接',{exact:true}).waitFor();passed.push('交付详情按新契约显示');await page.getByRole('button',{name:'关闭弹窗'}).click();
// 对真实DOM注入待提交认证草稿，退出后重新进入同一页面确认清理。
user.verified=0;await page.getByRole('button',{name:'个人中心',exact:true}).click();await page.getByLabel('真实姓名',{exact:true}).fill('前一账户私人草稿');await page.getByLabel('学号',{exact:true}).fill('PRIVATE001');await page.getByRole('button',{name:'退出登录'}).click();await page.getByRole('button',{name:'登录 / 注册'}).waitFor();await page.getByRole('button',{name:'登录 / 注册'}).click();await page.getByLabel('账号',{exact:true}).fill('buyer');await page.getByLabel('密码',{exact:true}).fill('Password123!');await page.getByRole('button',{name:'登录',exact:true}).click();user.verified=0;await page.getByRole('button',{name:'个人中心',exact:true}).click();if(await page.getByLabel('真实姓名',{exact:true}).inputValue())throw Error('姓名残留');if(await page.getByLabel('学号',{exact:true}).inputValue())throw Error('学号残留');passed.push('退出重登录清空认证私人草稿');
await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'发现物品',exact:true}).click();await page.screenshot({path:path.join(evidence,'02-mobile.png'),fullPage:true});if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('手机横向溢出');passed.push('390px页面无横向溢出');
if(errors.length)throw Error(errors.join('\n'));fs.writeFileSync(path.join(evidence,'browser-results.json'),JSON.stringify({date:'2026-11-25',scope:'真实Chromium＋测试API替身；非B/C后端联调',passed,consoleErrors:errors},null,2));console.log(JSON.stringify({passed,errors},null,2));await browser.close();})().catch(e=>{console.error(e);process.exit(1);});
