#!/usr/bin/env python3
"""Engine/session checks requiring no private screenshots and no Android device."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/cloud-tests';out.mkdir(parents=True,exist_ok=True)
src=ROOT/'app/src/main/java/com/yexin/xiangqi'
names=['Position','EngineState','PikafishClient','ScreenGame','BoardRecognizer','RecognitionRecovery','OverlayTiming','MoveTapPlan','MoveRetryPolicy','WdlProbabilities']
checks=['OverlayChecks','RecoveryChecks','RetryChecks','WdlChecks','UpgradeChecks']
subprocess.run(['java','com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(out),
    *[str(src/(n+'.java')) for n in names],*[str(ROOT/'tests'/(n+'.java')) for n in checks]],check=True)
for n in checks:
    subprocess.run(['java','-cp',str(out),'com.yexin.xiangqi.'+n,
        str(ROOT/'build/pikafish-host'),str(ROOT/'app/src/main/assets/engine/pikafish.nnue')],check=True)
