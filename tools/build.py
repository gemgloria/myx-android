#!/usr/bin/env python3
"""Build a signed, dependency-free native Android APK with official SDK tools.

Requires Java 17+, Android SDK Platform 35, Build Tools 35.0.0, Python 3.
The prebuilt ARM64 Pikafish executable and NNUE are included in the source bundle.
Use build_engine.py to rebuild the native engine from the included full source.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    print('Running:', Path(str(args[0])).name, str(args[1]) if len(args) > 1 else '', flush=True)
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--build-tools', type=Path)
    ap.add_argument('--android-jar', type=Path)
    ap.add_argument('--unsigned', action='store_true', help='Build without ever accessing or creating signing keys')
    ap.add_argument('--signing-dir', type=Path, default=ROOT.parent / 'xiangqi-signing-private')
    opts = ap.parse_args()
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', 'sdk')))
    bt = opts.build_tools or sdk / 'build-tools/35.0.0'
    android = opts.android_jar or sdk / 'platforms/android-35/android.jar'
    if not (bt / 'aapt2').is_file() or not android.is_file():
        raise SystemExit('Install SDK 35 and Build Tools 35.0.0; set ANDROID_HOME or pass both paths.')
    bt, android = bt.resolve(), android.resolve()
    app = ROOT / 'app/src/main'
    native = app / 'jniLibs/arm64-v8a/libpikafish.so'
    meta = json.loads((ROOT / 'vendor-manifest.json').read_text())
    model = app / 'assets/engine/pikafish.nnue'
    if hashlib.sha256(model.read_bytes()).hexdigest() != meta['model_sha256']:
        raise SystemExit('NNUE checksum mismatch')
    if not native.is_file():
        raise SystemExit('First rebuild the native engine with tools/build_engine.py')
    if hashlib.sha256(native.read_bytes()).hexdigest() != meta.get('android_engine_sha256'):
        raise SystemExit('Native engine checksum missing/mismatched: rebuild the pinned source first')
    (ROOT / 'build').mkdir(exist_ok=True)
    build = Path(tempfile.mkdtemp(prefix='apk-', dir=ROOT / 'build'))
    generated, classes, dex = (build / n for n in ('generated', 'classes', 'dex'))
    for d in (generated, classes, dex):
        d.mkdir()
    run([bt / 'aapt2', 'compile', '--dir', app / 'res', '-o', build / 'resources.zip'])
    run([bt / 'aapt2', 'link', '-o', build / 'resources.apk', '-I', android,
         '--manifest', app / 'AndroidManifest.xml', '--java', generated,
         '--min-sdk-version', '26', '--target-sdk-version', '35',
         '--version-code', '7', '--version-name', '1.2.0', build / 'resources.zip'])
    with zipfile.ZipFile(build / 'resources.apk') as z:
        if 'AndroidManifest.xml' not in z.namelist() or z.testzip():
            raise SystemExit('aapt2 did not produce a valid resources archive')
    sources = sorted((app / 'java').rglob('*.java')) + sorted(generated.rglob('*.java'))
    boot = str(bt / 'core-lambda-stubs.jar') + os.pathsep + str(android)
    run(['java', 'com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-bootclasspath', boot, '-d', classes, *sources])
    jar = build / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(classes.rglob('*.class')):
            z.write(p, p.relative_to(classes).as_posix())
    run(['java', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release',
         '--min-api', '26', '--lib', android, '--output', dex, jar])
    unsigned = build / 'unsigned.apk'
    with zipfile.ZipFile(build / 'resources.apk') as resources, \
            zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as z:
        for info in resources.infolist():
            z.writestr(info, resources.read(info.filename))
        for p in sorted((app / 'assets').rglob('*')):
            if p.is_file():
                compression = zipfile.ZIP_STORED if p.suffix == '.nnue' else zipfile.ZIP_DEFLATED
                z.write(p, 'assets/' + p.relative_to(app / 'assets').as_posix(), compress_type=compression)
        for p in sorted(dex.glob('*.dex')):
            z.write(p, p.name)
        z.write(native, 'lib/arm64-v8a/libpikafish.so')
    with unsigned.open('rb') as f:
        os.fsync(f.fileno())
    with zipfile.ZipFile(unsigned) as z:
        required = {'AndroidManifest.xml', 'resources.arsc', 'classes.dex',
                    'assets/engine/pikafish.nnue', 'lib/arm64-v8a/libpikafish.so'}
        if not required.issubset(z.namelist()) or z.testzip():
            raise SystemExit('APK assembly integrity check failed')
    aligned = build / 'aligned.apk'
    # Build Tools 34 has no -P. Native .so is compressed and extracted on install.
    align_help = subprocess.run([str(bt / 'zipalign')], capture_output=True, text=True)
    page_align = ['-P', '16'] if '-P' in align_help.stdout + align_help.stderr else []
    run([bt / 'zipalign', '-f', *page_align, '4', unsigned, aligned])
    if opts.unsigned:
        dist = ROOT / 'dist'
        dist.mkdir(exist_ok=True)
        apk = dist / 'xiangqi-floating-myx-v1.2.0-arm64-unsigned.apk'
        shutil.copy2(aligned, apk)
        print(json.dumps({'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signed':False}))
        return
    signing = opts.signing_dir.resolve()
    signing.mkdir(parents=True, exist_ok=True)
    keystore, password = signing / 'offline-xiangqi.p12', signing / 'password.txt'
    if not keystore.exists():
        if not password.exists():
            password.write_text(secrets.token_urlsafe(30) + '\n')
            password.chmod(0o600)
        run(['keytool', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
             '-storepass:file', password, '-alias', 'offline-xiangqi', '-keyalg', 'RSA',
             '-keysize', '3072', '-validity', '10000',
             '-dname', 'CN=Offline Xiangqi, O=Personal Software, C=CN'])
        keystore.chmod(0o600)
    if not password.exists():
        raise SystemExit('Missing signing password file; restore the signing backup.')
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    apk = dist / 'xiangqi-floating-myx-v1.2.0-arm64.apk'
    signed = build / 'signed.apk'
    run(['java', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', keystore,
         '--ks-key-alias', 'offline-xiangqi', '--ks-pass', 'file:' + str(password),
         '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
         '--out', signed, aligned])
    run(['java', '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', signed])
    run([bt / 'zipalign', '-c', *page_align, '4', signed])
    with zipfile.ZipFile(signed) as z:
        if not required.issubset(z.namelist()) or z.testzip():
            raise SystemExit('Signed APK integrity check failed')
        if hashlib.sha256(z.read('assets/engine/pikafish.nnue')).hexdigest() != meta['model_sha256']:
            raise SystemExit('Signed APK NNUE checksum mismatch')
    signed.replace(apk)
    result = {'apk': apk.name, 'size_bytes': apk.stat().st_size,
              'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
    (dist / 'apk-checksum.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
