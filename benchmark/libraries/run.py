#!/usr/bin/env python3
"""Sequential end-to-end benchmark; setup/control failures abort instead of producing speed claims.
Requires JAVA_HOME (21), ANDROID_HOME, Android CLI, and a running dedicated emulator for device tools.
Generate fixtures first with generate.py. No timed workloads run concurrently.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import statistics
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from generate import TOOLS, VERSIONS

HERE=Path(__file__).resolve().parent
TASKS={
 'medusa':('medusaRecord','medusaVerify'),
 'paparazzi':('recordPaparazziDebug','verifyPaparazziDebug'),
 'roborazzi':('recordRoborazziDebug','verifyRoborazziDebug'),
 'google':('updateScreenshotTestDefaultDebugTestSuite','testScreenshotTestDefaultDebugTestSuite'),
 'shot':('executeScreenshotTests','executeScreenshotTests'),
 'dropshots':('recordDebugAndroidTestScreenshots','connectedDebugAndroidTest'),
}

def reports(project,tool,scenario):
    if tool=='medusa': glob='*/build/medusa/test-results/medusa.xml'
    elif tool in ['paparazzi','roborazzi']: glob='*/build/test-results/testDebugUnitTest/TEST-*.xml'
    elif tool=='google':glob=f'*/build/intermediates/debug/{TASKS[tool][0 if scenario=="record" else 1]}/results/TEST-*.xml'
    else:glob='*/build/outputs/androidTest-results/connected/**/*.xml'
    totals=dict(tests=0,failures=0,errors=0,skipped=0)
    paths=[]
    for f in project.glob(glob):
        if not f.name.startswith('TEST-') and tool!='medusa':continue
        root=ET.parse(f).getroot()
        if root.tag!='testsuite':continue
        for k in totals:totals[k]+=int(root.attrib.get(k,0))
        paths.append(f)
    return totals, paths

def goldens(project,tool):
    pattern={'medusa':'*/src/main/**/*.png','paparazzi':'*/src/test/snapshots/images/*.png','roborazzi':'*/build/outputs/roborazzi/*.png','google':'*/src/screenshotTestDefaultDebug/reference/**/*.png','shot':'*/screenshots/**/*.png','dropshots':'*/src/androidTest/screenshots/**/*.png'}[tool]
    return sorted(f for f in project.glob(pattern) if not (tool == "roborazzi" and f.name.endswith(("_actual.png", "_compare.png"))))

def command(project,tool,scenario,label,expected_failure=False):
    args=['./gradlew','--console=plain','--no-build-cache','--init-script',str(HERE/'force-render.gradle'),TASKS[tool][0 if scenario=='record' else 1]]
    if tool not in ['shot','dropshots']:args+=['--parallel','--max-workers=3']
    if tool=='shot' and scenario=='record':args+=['-Precord']
    before = {str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in goldens(project,tool)} if scenario=='record' else {}
    start_wall=time.time();start=time.perf_counter()
    proc=subprocess.run(args,cwd=project,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    elapsed=time.perf_counter()-start
    logs=project/'benchmark-logs';logs.mkdir(exist_ok=True)
    (logs/f'{label}.log').write_text(proc.stdout)
    counts,paths=reports(project,tool,scenario)
    result=dict(tool=tool,topology=project.name,scenario=scenario,label=label,seconds=elapsed,exit=proc.returncode,counts=counts,command=args)
    if expected_failure:
        pixel_failure = counts['failures']+counts['errors']>0
        if tool=='shot':
            pixel_failure = counts['tests']==50 and 'The application UI has been modified for test:' in proc.stdout and 'card0' in proc.stdout
        assert proc.returncode!=0 and pixel_failure, f'{tool}: mutation did not fail a screenshot test; see {logs}/{label}.log'
        result['mutation_detected']=True
    else:
        assert proc.returncode==0, f'{tool}: failed; see {logs}/{label}.log'
        if scenario=='verify' or tool not in ['medusa']:
            assert counts['tests']==50 and counts['failures']==0 and counts['errors']==0 and counts['skipped']==0, f'{tool}: incomplete test coverage {counts}; see {logs}/{label}.log'
            assert all(f.stat().st_mtime>=start_wall-2 for f in paths),f'{tool}: stale XML results'
        images=goldens(project,tool)
        assert len(images)==50,f'{tool}: expected 50 golden PNGs, found {len(images)}'
        result['golden_dimensions']=sorted(set(struct.unpack('>II',f.read_bytes()[16:24]) for f in images))
        assert result['golden_dimensions']==[(320,640)], f'{tool}: unexpected image dimensions'
        if scenario=='record' and label.startswith('round-'):
            result['changed_goldens']=sum(before.get(str(f))!=hashlib.sha256(f.read_bytes()).hexdigest() for f in images)
            assert result['changed_goldens']>0, f'{tool}: recording the edit changed no golden images'
    print(json.dumps(result),flush=True)
    return result

def edit(project,label):
    p=project/'app/src/main/kotlin/bench/app/Cards.kt'
    s=p.read_text()
    if f'Text("Card 0 {label}"' in s:label+='x'
    s=re.sub(r'Text\("Card 0[^"\n]*"',f'Text("Card 0 {label}"',s,count=1);p.write_text(s)

def setup(project,tool):
    results=[]
    results.append(command(project,tool,'record','setup-record'))
    results.append(command(project,tool,'verify','setup-verify'))
    src=project/'app/src/main/kotlin/bench/app/Cards.kt';original=src.read_text()
    # Mutate only one screen, leaving baseline and other modules untouched.
    src.write_text(re.sub(r'Text\("Card 0[^"\n]*"', 'Text("INTENTIONAL MISMATCH"', original, count=1))
    try:results.append(command(project,tool,'verify','mutation-control',expected_failure=True))
    finally:src.write_text(original)
    results.append(command(project,tool,'verify','restored-verify'))
    return results

def main(args):
    selected=args.tools.split(',');topologies=args.topologies.split(',')
    pairs=[(t,top) for t in selected for top in topologies]
    path=args.output
    result={'date':time.strftime('%Y-%m-%d'),'versions':{t:VERSIONS[t] for t in selected},'machine':platform.platform(),'cores':os.cpu_count(),'screenshots':50,'multi_modules':5,'build_cache':False,'configuration_cache':False,'gradle_parallel':'host tools only, max workers 3; device tools serial','junit_forks':1,'rounds':args.rounds,'setup':[],'measurements':[]}
    def save():path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(result,indent=2)+'\n')
    if not args.skip_setup:
        for tool,top in pairs:result['setup']+=setup(args.directory/tool/top,tool);save()
    if args.setup_only:return
    for round in range(-1,args.rounds):
        # Rotate project/tool order; one untimed complete round warms every configuration.
        order=pairs if round<0 else pairs[round%len(pairs):]+pairs[:round%len(pairs)]
        for tool,top in order:
            project=args.directory/tool/top
            for scenario in ['verify','record']:
                if scenario=='record':edit(project,f'round-{round}')
                measured=command(project,tool,scenario,f'round-{round}-{scenario}')
                measured['round']=round;result['measurements'].append(measured);save()
    for tool,top in pairs:
        for scenario in ['record','verify']:
            values=[r['seconds'] for r in result['measurements'] if r['tool']==tool and r['topology']==top and r['scenario']==scenario and r['round']>=0]
            print(tool,top,scenario,statistics.median(values),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--tools',default=','.join(TOOLS));p.add_argument('--topologies',default='single,multi');p.add_argument('--rounds',type=int,default=3);p.add_argument('--output',type=Path,required=True);p.add_argument('--setup-only',action='store_true');p.add_argument('--skip-setup',action='store_true');main(p.parse_args())
