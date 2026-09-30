from pathlib import Path
import subprocess, json, shutil, hashlib, sys
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[1]
build=root/'build'
expected=hashlib.sha256((build/'ZombieBuddy.jar').read_bytes()).hexdigest()
shells=['powershell']
if shutil.which('pwsh'):shells.append('pwsh')
for shell in shells:
    fixture=build/('replace-fixture-'+shell);fixture.mkdir(exist_ok=True)
    previous=b'previous ZombieBuddy jar fixture'
    (fixture/'ZombieBuddy.jar').write_bytes(previous)
    (fixture/'ZombieBuddy.jar.zbs').write_text('old signature')
    (fixture/'ZombieBuddy.jar.new').write_text('pending old update')
    mod=fixture/'original-mod'; (mod/'libs').mkdir(parents=True,exist_ok=True)
    (mod/'libs/ZombieBuddy.jar').write_bytes(b'original workshop JAR must stay unchanged')
    (mod/'authors.json').write_text('old workshop authors')
    cache=fixture/'config'; cache.mkdir(exist_ok=True); (cache/'authors.json').write_text('old cache authors')
    config={'mainClass':'zombie/gameStates/MainScreenState','vmArgs':['-Xmx8g','-javaagent:Other.jar',
        '-javaagent:ZombieBuddySelectiveHooks.jar','-agentlib:zbNative=policy=deny-new','-Dtest=中文'],
        'windows':{'10.0.17134':{'vmArgs':['-XX:+UseZGC']}}}
    file=fixture/'ProjectZomboid64.json';file.write_text(json.dumps(config,ensure_ascii=False),encoding='utf-8')
    command=[shell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(root/'Replace-Jar.ps1'),'-GameDir',str(fixture),
        '-ZombieBuddyModDir',str(mod),'-ConfigDir',str(cache)]
    r=subprocess.run(command,capture_output=True,timeout=30)
    assert r.returncode==0,r.stdout+r.stderr
    assert hashlib.sha256((fixture/'ZombieBuddy.jar').read_bytes()).hexdigest()==expected
    updated=json.loads(file.read_text(encoding='utf-8-sig'))
    assert updated['vmArgs']==[v for v in config['vmArgs'] if 'ZombieBuddySelectiveHooks.jar' not in v]
    assert updated['windows']==config['windows']
    assert (mod/'authors.json').read_bytes()==(root/'authors.json').read_bytes()
    assert (cache/'authors.json').read_bytes()==(root/'authors.json').read_bytes()
    assert (mod/'libs/ZombieBuddy.jar').read_bytes()==b'original workshop JAR must stay unchanged'
    assert not (fixture/'ZombieBuddy.jar.new').exists() and not (fixture/'ZombieBuddy.jar.zbs').exists()
    backups=list(fixture.glob('ZombieBuddy-Optimized-backup-*'))
    assert any((b/'ZombieBuddy.jar').read_bytes()==previous for b in backups)
    assert any(json.loads((b/'ProjectZomboid64.json').read_text(encoding='utf-8-sig'))==config for b in backups)
    assert any((b/'authors-workshop.json').exists() and (b/'authors-workshop.json').read_text()=='old workshop authors' for b in backups)
    assert any((b/'authors-cache.json').exists() and (b/'authors-cache.json').read_text()=='old cache authors' for b in backups)
    print('PASS replacement',shell)
report=json.loads((build/'test-report.json').read_text(encoding='utf-8'))
report['replacement-script']={'status':'passed','shells':shells}
(build/'test-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
