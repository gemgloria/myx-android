#!/usr/bin/env python3
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/core-tests'
out.mkdir(parents=True,exist_ok=True)
java=ROOT/'app/src/main/java/com/yexin/xiangqi'
files=[java/(name+'.java') for name in ['Position','EngineState','GameSession','PikafishClient']]
subprocess.run(['java','com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(out),*[str(f) for f in files],str(ROOT/'tests/CoreChecks.java')],check=True)
subprocess.run(['java','-cp',str(out),'com.yexin.xiangqi.CoreChecks',str(ROOT/'build/pikafish-host'),str(ROOT/'app/src/main/assets/engine/pikafish.nnue')],check=True)
