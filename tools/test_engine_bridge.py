#!/usr/bin/env python3
"""Check the appstate bridge against Xiangqi terminal-rule fixtures and upstream perft."""
from pathlib import Path
import argparse,json,re,subprocess

ROOT=Path(__file__).resolve().parents[1]
MODEL_DIR=ROOT/'app/src/main/assets/engine'
ENGINE=ROOT/'build/pikafish-host'
START='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1'

def invoke(engine,commands):
    p=subprocess.run([str(engine)],input='\n'.join(commands+['quit','']),text=True,cwd=MODEL_DIR,capture_output=True,timeout=40)
    if p.returncode:raise RuntimeError(p.stderr or p.stdout)
    return p.stdout

def state(fen=START,moves=()):
    command='position fen '+fen+(' moves '+' '.join(moves) if moves else '')
    output=invoke(ENGINE,[command,'appstate'])
    data={}
    for line in output.splitlines():
        if line.startswith('appstate '):
            parts=line.split(' ',2);data[parts[1]]=parts[2] if len(parts)>2 else ''
    assert data.get('version')=='1',output
    data['moves']=set(data.get('moves','').split())
    return data

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--upstream',type=Path);opts=ap.parse_args()
    checks=[]
    def check(value,name):
        assert value,name
        checks.append(name)
    root=state()
    check(len(root['moves'])==44,'44 opening legal moves')
    check('b0c2' in root['moves'] and 'b0d1' not in root['moves'],'horse leg obstruction')
    check('b2b9' in root['moves'] and 'b2b7' not in root['moves'],'cannon screen capture')
    facing=state('4k4/9/9/9/4P4/9/9/9/9/4K4 w - - 0 1')
    check('e5e6' in facing['moves'] and 'e5d5' not in facing['moves'] and 'e5f5' not in facing['moves'],'flying-general exposure rejected')
    mate=state('4k4/3RPR3/9/9/9/9/9/9/9/4K4 b - - 0 1')
    check(mate['result']=='red' and mate['reason']=='checkmate' and not mate['moves'],'checkmate is a loss')
    stale=state('4k4/3R1R3/9/9/4P4/9/9/9/9/4K4 b - - 0 1')
    check(stale['result']=='red' and stale['reason']=='stalemate' and not stale['moves'],'stalemate is a loss in Xiangqi')
    cycle=['b0c2','b9c7','c2b0','c7b9']
    check(state(moves=cycle)['result']=='ongoing','a second occurrence alone is not a draw')
    check(state(moves=cycle*2)['result']=='draw','third neutral repetition is a draw')
    perpetual='4k4/3R5/9/9/4P4/9/9/9/9/4K4 w - - 0 1'
    checking=['d8e8','e9d9','e8d8','d9e9']*2
    check(state(perpetual,checking)['result']=='black','perpetual checker loses')
    check(state(START.replace('0 1','120 61'))['result']=='draw','Pikafish 60-move draw rule')
    check(state('3k5/9/9/9/9/9/9/9/9/4K4 w - - 0 1')['result']=='draw','insufficient mating material draw')
    commands=['position startpos','go perft 3']
    count=int(re.search(r'Nodes searched:\s*(\d+)',invoke(ENGINE,commands)).group(1))
    check(count==79666,'opening perft depth 3 = 79666')
    if opts.upstream:
        baseline=int(re.search(r'Nodes searched:\s*(\d+)',invoke(opts.upstream,commands)).group(1))
        check(baseline==count,'perft agrees with official release executable')
    report={'passed':len(checks),'checks':checks,'perft3':count}
    (ROOT/'build/engine-rule-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__':main()
