from pathlib import Path
import subprocess, argparse, zipfile, json, os, shutil, sys
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--game-dir',default='E:/Steam/steamapps/common/ProjectZomboid');p.add_argument('--jar',required=True);args=p.parse_args()
game=Path(args.game_dir);build=root/'build';build.mkdir(exist_ok=True)
zb=build/'ZombieBuddy.jar';shutil.copy2(args.jar,zb)
classes=build/'test-classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[zb,game/'projectzomboid.jar']))
subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sorted((root/'test-java').rglob('*.java')))],check=True)
with zipfile.ZipFile(build/'TestAgent.jar','w') as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: local.zbselective.TestAgent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
    z.write(classes/'local/zbselective/TestAgent.class','local/zbselective/TestAgent.class')
sample=build/'Sample.jar'
with zipfile.ZipFile(sample,'w') as z:
    for f in sorted((classes/'fixture/sample').rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
host=build/'TestHost.jar'
with zipfile.ZipFile(host,'w') as z:
    for f in sorted(classes.rglob('*.class')):
        if 'fixture/sample' not in f.as_posix():z.write(f,f.relative_to(classes).as_posix())
cp+=os.pathsep+str(host)
state=build/'test-state.properties';state.unlink(missing_ok=True)
defaults=build/'test-default.txt';defaults.write_text('mods\n{\n mod = Sample,\n}\n')
java=str(game/'jre64/bin/java.exe')
plain=[java,'-ea','-Xverify:all','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',f'-Duser.home={build/"test-home"}']
base=plain+[f'-Dzbselective.state={state}',f'-Dzbselective.defaultMods={defaults}','-Dzbselective.restartUi=false',
    '-Djava.awt.headless=true','-Dtest.failHttp=true',f'-javaagent:{build/"TestAgent.jar"}',
    f'-javaagent:{zb}=policy=deny-new,config_dir={build/"isolated-config"}',
    '-cp',cp,'local.zbselective.TestMain']
results={}
def run(label,command,expected=0,cwd=None):
    r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90,cwd=cwd)
    log=r.stdout+'\n'+r.stderr;(build/f'test-{label}.log').write_text(log,encoding='utf-8')
    if r.returncode!=expected:
        print(log);raise RuntimeError(f'{label} exit {r.returncode} expected {expected}')
    results[label]='passed';print('PASS',label)
    return log
for stage in ['unit','prepare','resumed','resumed-loaded','reenabled','updated','disabled','prepare-save','profile','core']:
    if stage=='updated':
        with zipfile.ZipFile(sample,'a') as z:z.writestr('version.txt','updated')
    if stage=='core':state.unlink(missing_ok=True)
    log=run(stage,base+[stage,str(sample)],42 if stage=='disabled' else 0)
    if stage=='resumed-loaded':
        assert log.count('Transformed: fixture.target.Target (retransformed)')==1,'already-loaded Advice must be transformed once'
for mode in ['lazy','failure']:
    run('network-'+mode,plain+['-cp',cp,'me.zed_0xff.zombie_buddy.NetworkTest',mode])
# Exercise the existing native Windows launcher unchanged, with the integrated replacement JAR.
native=build/'native-fixture';native.mkdir(exist_ok=True)
shutil.copy2(zb,native/'ZombieBuddy.jar');shutil.copy2(game/'zbNative.dll',native/'zbNative.dll')
subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(root/'tools/prepare-native.ps1'),
    '-Fixture',str(native),'-RuntimePath',str(game/'jre64')],check=True)
state.unlink(missing_ok=True)
native_cmd=plain+[f'-Dzbselective.state={state}',f'-Dzbselective.defaultMods={defaults}','-Dzbselective.restartUi=false','-Dtest.failHttp=true',
    f'-agentpath:{native/"zbNative.dll"}=policy=deny-new,config_dir={build/"native-config"}',
    f'-javaagent:{build/"TestAgent.jar"}','-cp',os.pathsep.join(map(str,[host,native/'ZombieBuddy.jar',game/'projectzomboid.jar'])),
    'local.zbselective.TestMain','core',str(sample)]
run('native-launcher',native_cmd,cwd=native)
(build/'test-report.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print('ALL CUSTOM REGRESSIONS PASSED')
