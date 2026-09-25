import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';

const here=path.dirname(new URL(import.meta.url).pathname);
const outDir=path.join(here,'.frames');
fs.mkdirSync(outDir,{recursive:true});
const start=Number(process.argv[2]||0);
const end=Number(process.argv[3]||252);
const chrome='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const port=9333+start;
const profile='/tmp/droiddeck-boot-chrome-'+process.pid+'-'+start;
fs.rmSync(profile,{recursive:true,force:true});
const pageUrl='file://'+path.join(here,'boot.html');
const child=spawn(chrome,[
  '--headless=new','--hide-scrollbars','--disable-sync','--no-first-run',
  '--disable-background-networking','--disable-component-update',
  `--remote-debugging-port=${port}`,`--user-data-dir=${profile}`,
  '--window-size=1920,1080',pageUrl
],{stdio:'ignore'});

const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let wsUrl;
for(let i=0;i<80;i++){
  try{
    const pages=await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    wsUrl=pages.find(p=>p.type==='page')?.webSocketDebuggerUrl;
    if(wsUrl) break;
  }catch{}
  await sleep(100);
}
if(!wsUrl) throw new Error('Chrome DevTools endpoint did not start');
const ws=new WebSocket(wsUrl);
await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
let nextId=1;
const pending=new Map();
ws.onmessage=e=>{
  const msg=JSON.parse(e.data);
  if(msg.id && pending.has(msg.id)){
    const {resolve,reject}=pending.get(msg.id); pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
};
function send(method,params={}){
  const id=nextId++;
  ws.send(JSON.stringify({id,method,params}));
  return new Promise((resolve,reject)=>pending.set(id,{resolve,reject}));
}
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride',{width:1920,height:1080,deviceScaleFactor:1,mobile:false});
await sleep(300);

const fps=60, duration=4.2, frames=Math.ceil(fps*duration);
for(let i=start;i<Math.min(end,frames);i++){
  const t=i/fps;
  await send('Runtime.evaluate',{expression:`window.setBootTime(${t.toFixed(6)})`,awaitPromise:true});
  const shot=await send('Page.captureScreenshot',{format:'png',fromSurface:true,captureBeyondViewport:false});
  fs.writeFileSync(path.join(outDir,String(i).padStart(4,'0')+'.png'),Buffer.from(shot.data,'base64'));
  if(i%30===0) process.stdout.write(`rendered ${i}/${frames}\n`);
}
ws.close();
child.kill('SIGTERM');
console.log(`done: ${start}-${Math.min(end,frames)-1}`);
