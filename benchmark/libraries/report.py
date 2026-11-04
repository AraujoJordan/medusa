#!/usr/bin/env python3
"""Combine complete measured runs and correctness controls into reviewable JSON/Markdown."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
from generate import TOOLS, VERSIONS

NAMES={'medusa':'Medusa','paparazzi':'Paparazzi','roborazzi':'Roborazzi','google':'Google Compose Screenshot Testing','shot':'Shot','dropshots':'Dropshots'}

def main(args):
    runs=[];controls=[]
    for p in args.measurements:runs+=json.loads(p.read_text())['measurements']
    for p in args.controls:controls+=json.loads(p.read_text())['setup']
    for control in controls:
        if control['label']=='mutation-control':
            assert control['exit']!=0
            assert control['counts']['failures']+control['counts']['errors']>0 or (control['tool']=='shot' and control.get('mutation_detected'))
    # Keep filesystem-specific command paths out of the published evidence.
    for r in runs+controls:
        r['command']=[('benchmark/libraries/force-render.gradle' if a.endswith('/force-render.gradle') else a) for a in r['command']]
    rows=[];table=[]
    for tool in TOOLS:
        cells=[]
        for topology in ['single','multi']:
            successful={r['label'] for r in controls if r['tool']==tool and r['topology']==topology and (r['exit']==0 or r['label']=='mutation-control')}
            assert successful=={'setup-record','setup-verify','mutation-control','restored-verify'},(tool,topology,successful)
            for scenario in ['record','verify']:
                selected=[r for r in runs if r['tool']==tool and r['topology']==topology and r['scenario']==scenario and r['round']>=0]
                assert len(selected)==3 and {r['round'] for r in selected}=={0,1,2},(tool,topology,scenario,len(selected))
                assert all(r['exit']==0 and r['golden_dimensions']==[[320,640]] for r in selected)
                values=[r['seconds'] for r in selected]
                row=dict(tool=tool,topology=topology,scenario=scenario,median_seconds=statistics.median(values),min_seconds=min(values),max_seconds=max(values))
                rows.append(row);cells.append(f"{row['median_seconds']:.1f} s")
        table.append('| '+NAMES[tool]+' | '+' | '.join(cells)+' |')
    output=dict(environment=json.loads(args.environment.read_text()),versions=VERSIONS,summary=rows,controls=controls,runs=runs,
                harness_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(__file__).parent.glob('*.py')})
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'results.json').write_text(json.dumps(output,indent=2)+'\n')
    header=['| Library | Single record | Single verify | Multi record | Multi verify |','|---|---:|---:|---:|---:|']
    lines=['# Single- and multi-module benchmark results','', '50 screenshots in each fixture; multi-module means an app and four libraries with 10 screenshots each.',
           'Wall-clock medians of three measured runs following an untimed round. See [methodology](README.md).','',*header,*table,'',
           '## All measured ranges','', '| Library | Project | Operation | Median | Min–max |','|---|---|---|---:|---:|']
    for r in rows:lines.append(f"| {NAMES[r['tool']]} | {r['topology']} | {r['scenario']} | {r['median_seconds']:.2f} s | {r['min_seconds']:.2f}–{r['max_seconds']:.2f} s |")
    lines+=['','Every successful verification covered 50 tests with no failures, errors or skips. Every fixture passed a deliberate-mismatch control and a restored-source verification.', '', '[Raw timings and controls](results.json).']
    (args.output/'RESULTS.md').write_text('\n'.join(lines)+'\n')
    print('\n'.join(header+table))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--measurements',nargs='+',type=Path,required=True);p.add_argument('--controls',nargs='+',type=Path,required=True);p.add_argument('--environment',type=Path,required=True);p.add_argument('--output',type=Path,required=True);main(p.parse_args())
