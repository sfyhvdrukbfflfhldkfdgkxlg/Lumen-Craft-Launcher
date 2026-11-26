"""Archive the release sources and verify that APKs match this checkout."""
from pathlib import Path
import hashlib
import zipfile

root=Path(__file__).resolve().parents[1]
dist=root/'dist'
main=dist/'LumenCraft-Alpha1.0.0.apk'
rooms=dist/'LumenCraft-Rooms-Alpha1.0.0.apk'
def digest(p):
    with p.open('rb') as src:return hashlib.file_digest(src,'sha256').hexdigest()
for path,module,lib_names in [
    (main,'app',('liblumencap.so','liblumenjvm.so','liblumengl.so','liblumenclassic.so')),
    (rooms,'rooms',('libterracotta.so',))
]:
    with zipfile.ZipFile(path) as apk:
        if apk.testzip() is not None:raise RuntimeError(f'Corrupt APK {path.name}')
        source=root/module/'src/main'
        for abi in ['x86','arm64-v8a','x86_64','armeabi-v7a']:
            for name in lib_names:
                assert (source/'jniLibs'/abi/name).is_file(),f'{abi}/{name} is missing'
        for native in (source/'jniLibs').rglob('*.so'):
            rel=native.relative_to(source/'jniLibs').as_posix()
            assert apk.read('lib/'+rel)==native.read_bytes(),f'{rel} is stale'
        for p in (source/'assets').rglob('*'):
            if p.is_file():
                assert apk.read('assets/'+p.relative_to(source/'assets').as_posix())==p.read_bytes(),f'Stale asset {p.name}'
src_zip=dist/'LumenCraft-Alpha1.0.0-source.zip'
skip={'build','.gradle','.kotlin','__pycache__','.idea','.git'}
folders=['app/src','client','core','docs','gradle','graphics','rooms/src','rooms/native','rooms/upstream','runtime','tests','tools']
files=[root/f for f in ['.gitignore','settings.gradle.kts','build.gradle.kts','app/build.gradle.kts','rooms/build.gradle.kts','gradle.properties','gradlew','gradlew.bat','build.ps1','README.md','DEPENDENCIES.md','TEST-REPORT.md']]
for d in folders:
    files.extend(p for p in (root/d).rglob('*') if p.is_file() and not (set(p.relative_to(root).parts)&skip))
files.extend(p for p in (root/'rooms').glob('*.md'))
files.extend(p for p in (root/'rooms').glob('*.ps1'))
files.extend(p for p in (root/'rooms').glob('*.py'))
with zipfile.ZipFile(src_zip,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(set(files)):
        if p.suffix.lower() in {'.keystore','.jks','.pyc'}:raise ValueError('Unexpected file '+str(p))
        z.write(p,'LumenCraftAndroid/'+p.relative_to(root).as_posix())
with zipfile.ZipFile(src_zip) as z:
    assert z.testzip() is None
(dist/'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in [main,rooms,src_zip]),encoding='ascii')
print((dist/'SHA256SUMS.txt').read_text())
