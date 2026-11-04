#!/usr/bin/env python3
"""Check renderer PID reuse across modules and projects (macOS/Linux ps required).
Run using JDK 21 after `python3 benchmark/generate.py 50`.
Writes temporary rendered PNGs and DAEMON_REUSE_RESULTS.json; never modifies goldens.
Timings include process sampling overhead and are single observations, not benchmarks.
"""
import subprocess,os,time,json,tempfile,threading
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
root=Path(__file__).resolve().parent.parent
# Set JAVA_HOME to JDK 21 before invoking this probe.
cli=str(Path.home()/'.local/bin/android')
def processes():
 lines=subprocess.check_output(['ps','-axo','pid=,ppid=,comm='],text=True).splitlines()
 return [dict(pid=int(p[0]),ppid=int(p[1])) for line in lines if len(p:=line.split(None,2))==3 and '/.android/cli/packs/' in p[2] and p[2].endswith('/preview/renderer')]
base=Path(tempfile.mkdtemp(prefix='medusa-daemon-probe-'));results=[]
def run(module,daemon=True,cwd=None):
 listing=subprocess.run([cli,'--no-metrics','preview','list','--format=json',module],cwd=cwd or root/'fixtures/multimodule',capture_output=True,text=True,check=True)
 lines=listing.stdout.splitlines();j=json.loads('\n'.join(lines[next(i for i,l in enumerate(lines) if l.strip()=='{'):]))
 fqns=list(dict.fromkeys(r['methodFqn'] for r in j['previews']))
 before=processes(); seen=set();stop=threading.Event()
 def watch():
  while not stop.wait(.05):seen.update(p['pid'] for p in processes())
 monitor=threading.Thread(target=watch);monitor.start();start=time.perf_counter()
 try:
  p=subprocess.run([cli,'--no-metrics','-v','preview','render','--format=json',*(['--daemon'] if daemon else []),f'--output={base/module.replace(":","_")}/{time.monotonic_ns()}',*[f'--fqn={f}' for f in fqns],module],cwd=cwd or root/'fixtures/multimodule',capture_output=True,text=True)
 finally:stop.set();monitor.join()
 lines=p.stdout.splitlines();j=json.loads('\n'.join(lines[next(i for i,l in enumerate(lines) if l.strip()=='{'):]))
 if p.returncode or not j.get('success') or any(r.get('error') for r in j.get('results',[])):raise RuntimeError(str(j))
 result={'module':module,'daemon':daemon,'seconds':time.perf_counter()-start,'images':len(j['results']),'before':before,'seen_pids':sorted(seen),'after':processes()};print(result,flush=True);return result
for module in [':app',':feature:home',':core:designsystem']:
 results.append(run(module))
with ThreadPoolExecutor(max_workers=3) as pool:
 results.extend(pool.map(run,[':app',':feature:home',':core:designsystem']))
results.append(run(':bench',cwd=root))
results.append(run(':app',False))
(root/'benchmark/DAEMON_REUSE_RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
