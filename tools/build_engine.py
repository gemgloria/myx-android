#!/usr/bin/env python3
"""Rebuild Pikafish + appstate bridge. Android: --ndk PATH. Host: --host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ndk', type=Path)
    ap.add_argument('--host', action='store_true')
    ap.add_argument('--jobs', type=int, default=4)
    opts = ap.parse_args()
    variant = 'host' if opts.host else 'arm64'
    work = ROOT / 'build' / ('engine-' + variant) / 'pikafish'
    shutil.copytree(ROOT / 'third_party/pikafish', work, dirs_exist_ok=True)
    shutil.copy2(ROOT / 'app/src/main/assets/engine/pikafish.nnue', work / 'src/pikafish.nnue')
    env = dict(os.environ)
    cmd = ['make', '-j' + str(opts.jobs), 'build']
    if opts.host:
        cmd += ['ARCH=x86-64-sse41-popcnt', 'COMP=gcc', 'EXE=pikafish-host']
        destination = ROOT / 'build/pikafish-host'
        strip = 'strip'
    else:
        ndk = opts.ndk or Path(os.environ.get('ANDROID_NDK_HOME', 'ndk'))
        tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        compiler = tools / 'aarch64-linux-android26-clang++'
        if not compiler.is_file():
            raise SystemExit('Pass --ndk pointing to Android NDK r27c or later (Linux host).')
        env['PATH'] = str(tools.resolve()) + os.pathsep + env.get('PATH', '')
        cmd += ['ARCH=armv8', 'COMP=ndk', 'CXX=' + str(compiler.absolute()),
                'EXTRALDFLAGS=-Wl,-z,max-page-size=16384', 'EXE=libpikafish.so']
        destination = ROOT / 'app/src/main/jniLibs/arm64-v8a/libpikafish.so'
        strip = str(tools / 'llvm-strip')
    subprocess.run(cmd, cwd=work / 'src', env=env, check=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(work / 'src' / ('pikafish-host' if opts.host else 'libpikafish.so'), destination)
    subprocess.run([strip, str(destination)], check=True)
    if not opts.host:
        manifest = ROOT / 'vendor-manifest.json'
        meta = json.loads(manifest.read_text())
        meta['android_engine_sha256'] = hashlib.sha256(destination.read_bytes()).hexdigest()
        manifest.write_text(json.dumps(meta, indent=2) + '\n')
    print(destination)


if __name__ == '__main__':
    main()
