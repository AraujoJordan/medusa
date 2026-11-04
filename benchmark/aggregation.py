#!/usr/bin/env python3
"""Measure CLI list + render over the same 21 previews. Run with JAVA_HOME set to JDK 21.
Default: sequential modules versus aggregation. --parallel: three concurrent modules.
Round zero is warm-up. Writes only temporary renders and benchmark result JSON.
"""
import sys
import subprocess,time,json,os,hashlib
from pathlib import Path
os.chdir(Path(__file__).resolve().parent.parent)
root=Path.cwd(); cli=str(Path.home()/'.local/bin/android'); fixture=root/'fixtures/multimodule'; output=Path('/tmp/medusa-aggregation-probe');output.mkdir(exist_ok=True)
def call(args):
 start=time.perf_counter();p=subprocess.run([cli,'--no-metrics','preview',*args,'--format=json'],cwd=fixture,capture_output=True,text=True)
 lines=p.stdout.splitlines();j=json.loads('\n'.join(lines[next(i for i,l in enumerate(lines) if l.strip()=='{'):]))
 if p.returncode or not j.get('success'):raise RuntimeError(str(j))
 return time.perf_counter()-start,j

if "--parallel" in sys.argv:
 from concurrent.futures import ThreadPoolExecutor
 results=[]
 for round in range(4):
  def module_run(module):
   t,l=call(['list',module]);dest=output/f'parallel-{round}-{module.replace(":","_")}'
   rt,r=call(['render','--daemon',f'--output={dest}',*[f'--fqn={f}' for f in dict.fromkeys(p['methodFqn'] for p in l['previews'])],module])
   hashes={}
   for item in r['results']:
    if item.get('error') or not item.get('imagePath'):raise RuntimeError(str(item))
    f=Path(item['imagePath']);hashes[item['methodFqn']+'/'+f.name]=hashlib.sha256(f.read_bytes()).hexdigest()
   return {'module':module,'list':t,'render':rt,'images':hashes}
  start=time.perf_counter()
  with ThreadPoolExecutor(max_workers=3) as pool: modules=list(pool.map(module_run,[':app',':feature:home',':core:designsystem']))
  result={'round':round,'seconds':time.perf_counter()-start,'modules':modules};results.append(result);print(round,result['seconds'],flush=True)
 (root/'benchmark/PARALLEL_AGGREGATION_RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
else:
 results=[]
 for round in range(4):
  for aggregate in ([False,True] if round%2==0 else [True,False]):
   start=time.perf_counter();counts=0;hashes={};times=[];fqns=[]
   for module in ([':app'] if aggregate else [':app',':feature:home',':core:designsystem']):
    t,l=call(['list',*(['--all'] if aggregate else []),module]);previews=l['previews'];fqns.extend(p['methodFqn'] for p in previews)
    dest=output/f'{round}-{aggregate}-{module.replace(":","_")}'
    rtime,r=call(['render','--daemon',f'--output={dest}',*[f'--fqn={f}' for f in dict.fromkeys(p['methodFqn'] for p in previews)],module])
    for item in r['results']:
     if item.get('error') or not item.get('imagePath'):raise RuntimeError(str(item))
     f=Path(item['imagePath']);key=item['methodFqn']+'/'+f.name
     if key in hashes:raise RuntimeError('duplicate '+key)
     hashes[key]=hashlib.sha256(f.read_bytes()).hexdigest()
    times.append({'module':module,'list':t,'render':rtime})
   entry={'round':round,'aggregate':aggregate,'seconds':time.perf_counter()-start,'times':times,'images':hashes,'previews':sorted(fqns)};results.append(entry);print({k:v for k,v in entry.items() if k not in ['images','previews']},flush=True)
 (root/'benchmark/AGGREGATION_RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
