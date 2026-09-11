#!/usr/bin/env python3
"""Reproducibly obtain the pinned official source and matching licensed weights."""
import hashlib,json,shutil,subprocess,tempfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
meta=json.loads((ROOT/'vendor-manifest.json').read_text())
source=ROOT/'third_party/pikafish'
if source.exists():
    raise SystemExit('Use a clean checkout: third_party/pikafish already exists')
source.parent.mkdir(exist_ok=True)
subprocess.run(['git','clone','--depth','1','--branch','Pikafish-'+meta['engine_version'],
                'https://github.com/official-pikafish/Pikafish.git',str(source)],check=True)
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip()
if commit!=meta['upstream_commit']:raise SystemExit('Upstream commit mismatch')
with tempfile.TemporaryDirectory(prefix='myx-vendor-') as tmp:
    tmp=Path(tmp)
    archive=tmp/'release.7z'
    url='https://github.com/official-pikafish/Pikafish/releases/download/Pikafish-'+meta['engine_version']+'/Pikafish.'+meta['engine_version']+'.7z'
    urllib.request.urlretrieve(url,archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=meta['release_sha256']:
        raise SystemExit('Official archive checksum mismatch')
    subprocess.run(['7z','x',str(archive),'-o'+str(tmp/'release')],check=True)
    release=tmp/'release'; model=release/'pikafish.nnue'
    if model.stat().st_size!=meta['model_size'] or hashlib.sha256(model.read_bytes()).hexdigest()!=meta['model_sha256']:
        raise SystemExit('Model checksum mismatch')
    assets=ROOT/'app/src/main/assets'
    (assets/'engine').mkdir(parents=True,exist_ok=True)
    shutil.copy2(model,assets/'engine/pikafish.nnue')
    for name in ['Copying.txt','NNUE-License.md','AUTHORS']:
        shutil.copy2(release/name,assets/'licenses'/name)
    (ROOT/'build').mkdir(exist_ok=True)
    official=ROOT/'build/pikafish-official-host'
    shutil.copy2(release/'Pikafish-Linux-x86-64-universal',official);official.chmod(0o755)
subprocess.run(['python3',str(ROOT/'tools/patch_engine.py')],check=True)
print('Prepared Pikafish',meta['engine_version'],commit)
