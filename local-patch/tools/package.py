from pathlib import Path
import zipfile, hashlib, json, xml.etree.ElementTree as ET, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[1];build=root/'build';dist=build/'dist';dist.mkdir(exist_ok=True)
repo=root/'upstream' if (root/'upstream').exists() else root.parent
report=json.loads((build/'test-report.json').read_text(encoding='utf-8'))
assert sum(v=='passed' for v in report.values())==13
assert report['replacement-script']['status']=='passed'
results=Path.home()/'AppData/Local/ZombieBuddyOptimizedBuild/test-results'
totals={}
for group in ['unitTest','test_patched','test_vanilla']:
    suites=[ET.parse(f).getroot() for f in (results/group).glob('TEST-*.xml')]
    assert suites,group
    counts={k:sum(int(s.attrib[k]) for s in suites) for k in ['tests','failures','errors','skipped']}
    assert counts['failures']==0 and counts['errors']==0,group
    totals[group]=counts
assert sum(c['tests'] for c in totals.values())==142
jar=build/'ZombieBuddy.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest()
assert digest==(build/'ZombieBuddy.jar.sha256').read_text().strip()
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    assert 'local/zbselective/RuntimeState.class' in names
    assert 'me/zed_0xff/zombie_buddy/NetworkClients.class' in names
    assert not any(n.startswith('META-INF/') and n.endswith(('.RSA','.SF','.DSA')) for n in names)
    manifest=z.read('META-INF/MANIFEST.MF').decode()
    assert 'Implementation-Version: 2.3.3' in manifest and 'X-Local-Optimized:' in manifest
metadata={'name':'ZombieBuddy 2.3.3 LY Optimized 1.0.0','upstream_commit':'0ddf161c27848f12d09e74de7fadbea9d50e621d',
    'upstream_url':'https://github.com/zed-0xff/ZombieBuddy','tested_game':'42.21','java':'game JRE 25.0.1',
    'jar_sha256':digest,'upstream_tests':totals,'custom_regressions':report,
    'limitations':['No full in-game world/multiplayer run','No full startup timing benchmark','Unsigned local replacement; original mod approval and ZBS checks preserved']}
metadata['authors_snapshot']=json.loads((root/'authors-provenance.json').read_text(encoding='utf-8'))
assert hashlib.sha256((root/'authors.json').read_bytes()).hexdigest()==metadata['authors_snapshot']['sha256']
metadata['fork']='https://github.com/yuruichang/ZombieBuddy/tree/codex/optimized-b42'
(build/'verification.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
def archive(path,files):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        for name,file in sorted(files.items()):
            info=zipfile.ZipInfo('ZombieBuddyOptimized/'+name,(2026,9,30,0,0,0))
            z.writestr(info,file.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    path.with_suffix('.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'\n',encoding='ascii')
files={'ZombieBuddy.jar':jar,'ZombieBuddy.jar.sha256':build/'ZombieBuddy.jar.sha256','verification.json':build/'verification.json'}
for name in ['README.md','LICENSE.txt','Replace-Jar.ps1','source-changes.patch','workshop-comparison.json',
    'authors.json','authors.json.sha256','authors-provenance.json','WORKSHOP_DESCRIPTION_CN_EN.txt']:
    files[name]=root/name
archive(dist/'ZombieBuddy-2.3.3-LY-Optimized-1.0.0.zip',files)
source=dict(files)
tracked=subprocess.run(['git','-C',str(repo),'ls-files','-z'],check=True,capture_output=True).stdout.decode().split('\0')
for name in tracked:
    file=repo/name
    if name and file.is_file() and 'build' not in Path(name).parts and file.suffix.lower() not in ['.jar','.class','.exe','.dll','.zbs']:
        source['upstream/'+name]=file
for folder in ['java/src/main/java/local/zbselective']:
    for f in (repo/folder).rglob('*.java'):source['upstream/'+f.relative_to(repo).as_posix()]=f
source['upstream/java/src/main/java/me/zed_0xff/zombie_buddy/NetworkClients.java']=repo/'java/src/main/java/me/zed_0xff/zombie_buddy/NetworkClients.java'
source['upstream/java/gradle/wrapper/gradle-wrapper.jar']=repo/'java/gradle/wrapper/gradle-wrapper.jar'
source['build.ps1']=root/'build.ps1'
for name in ['test.py','replacement-test.py','prepare-native.ps1']:
    source['tools/'+name]=root/'tools'/name
for f in (root/'test-java').rglob('*.java'):source[f.relative_to(root).as_posix()]=f
archive(dist/'ZombieBuddy-2.3.3-LY-Optimized-1.0.0-source.zip',source)
workshop_files={f.relative_to(root/'Workshop').as_posix():f for f in (root/'Workshop').rglob('*') if f.is_file()}
archive(dist/'ZombieBuddy-2.3.3-LY-Optimized-1.0.0-Workshop.zip',workshop_files)
print('Verified:',sum(c['tests'] for c in totals.values()),'upstream tests + 13 regression scenarios')
for p in sorted(dist.glob('*.zip')):print(p,p.stat().st_size,'bytes')
