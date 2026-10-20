import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const dir = path.dirname(fileURLToPath(import.meta.url));
fs.mkdirSync(dir, { recursive: true });
const C = {blue:'#a5d8ff',green:'#b2f2bb',purple:'#d0bfff',orange:'#ffd8a8',yellow:'#fff3bf',teal:'#c3fae8',red:'#ffc9c9'};
const box=(id,x,y,w,h,label,color,group)=>({type:'rectangle',id,x,y,width:w,height:h,backgroundColor:C[color],groupIds:group?[group]:[],label:{text:label,fontSize:22}});
const text=(id,x,y,label,size=22)=>({type:'text',id,x,y,text:label,fontSize:size});
const arrow=(id,from,to,x,y,dx,dy)=>({type:'arrow',id,x,y,points:[[0,0],[dx,dy]],start:{id:from},end:{id:to}});
const diagrams={
 constraint:[
 text('title',160,-100,'The constraint shapes the system',36),
 box('pi',0,0,440,220,'Raspberry Pi 5 · ARM64\n16 GB · shared production host\nMemory cgroup off','blue','pi'),
 box('edit',0,310,440,130,'Ticket agent\nRead · Glob · Grep · Edit · Write','purple','pi'),
 box('actions',650,0,430,220,'GitHub Actions\nResolve dependencies · compile\nTests · Testcontainers · checks','green','verification'),
 box('human',650,310,430,130,'A person approves the ticket\nand merges sensitive changes','yellow','decisions'),
 arrow('pi-edit','pi','edit',220,220,0,90),arrow('edit-actions','edit','actions',440,360,210,-200),
 text('boundary',470,230,'push',20),text('caption',160,500,'Small feedback loops, verified away from the Pi',26),
 ],
 architecture:[
 text('title',290,-110,'Inside the software factory',36),
 box('github',0,0,270,120,'GitHub\nsigned webhooks','blue','ingress'),
 box('ingress',370,0,270,120,'Cloudflare → tunnel\nnginx · exact route','blue','ingress'),
 box('factory',760,0,380,150,'software-factory\nWebhook receiver + workers\ngit + Claude Code','purple','workers'),
 box('temporal',760,260,380,120,'Temporal\ndurable workflows + role queues','yellow','orchestration'),
 box('deployer',760,490,380,140,'deployer\nNo public ingress\nDocker socket + deploy queue','red','deployer'),
 box('systems',0,260,510,120,'GitHub Actions · Linear\nLoki · Dependency-Track','orange','external'),
 box('queues',0,490,510,140,'code-review · review-feedback · cve-fix\nlogwatch · ticket-agent · linear\ndeploy · platform-backup','teal','orchestration'),
 arrow('github-ingress','github','ingress',270,60,100,0),arrow('ingress-factory','ingress','factory',640,60,120,0),
 arrow('factory-temporal','factory','temporal',950,150,0,110),arrow('deployer-temporal','deployer','temporal',950,490,0,-110),
 arrow('systems-factory','systems','factory',510,310,250,-200),arrow('queues-temporal','queues','temporal',510,550,250,-190),
 ],
 repair:[
 text('title',230,-100,'A red check becomes the next brief',36),
 box('ticket',0,0,300,110,'Person approves a ticket\nfactory:agent','yellow','approval'),
 box('edit',420,0,300,110,'Attempt\nfile edits on the Pi','purple','attempt'),
 box('checks',840,0,310,110,'GitHub Actions + reviewer\nverify the exact commit','green','checks'),
 box('red',840,260,310,130,'Red: failure brief\nNext attempt, up to 3 total\nThen return to a person','red','repair'),
 box('merge',420,260,300,130,'Green: In Review\nEligible auto-merge, or\nhuman dependency merge','yellow','merge'),
 box('done',0,260,300,130,'Merged: Done\nPublish → deploy → watch','green','complete'),
 arrow('approve-edit','ticket','edit',300,55,120,0),arrow('edit-check','edit','checks',720,55,120,0),
 arrow('checks-red','checks','red',995,110,0,150),arrow('red-edit','red','edit',840,300,-220,-190),
 arrow('checks-merge','checks','merge',840,90,-230,170),arrow('merge-done','merge','done',420,325,-120,0),
 ],
 cve:[
 text('title',230,-100,'Public vulnerability, human merge',36),
 text('machine-title',70,-30,'Machine work',26),text('human-title',650,-30,'Human decisions',26),
 box('scan',0,40,430,100,'Dependency-Track → nightly scan\nOne consolidated Linear report','blue','scan'),
 box('approve',610,40,390,100,'Choose and approve\na scoped backend fix','yellow','approval'),
 box('bump',0,250,430,100,'Ticket agent edits versions\nNo build on the Pi','purple','repair'),
 box('verify',0,460,430,100,'GitHub Actions + code reviewer\nChecks and dependency PR','green','verification'),
 box('merge',610,460,390,100,'no-auto-merge\nSimon reviews and merges','yellow','merge'),
 text('npm-gap',535,250,'npm lockfile regeneration\nstill needs a person',24),
 arrow('scan-approve','scan','approve',430,90,180,0),arrow('approve-bump','approve','bump',610,120,-180,150),
 arrow('bump-verify','bump','verify',215,350,0,110),arrow('verify-merge','verify','merge',430,510,180,0),
 ]};
for (const [name,elements] of Object.entries(diagrams)) fs.writeFileSync(path.join(dir,name+'.json'),JSON.stringify(elements,null,2)+'\n');
