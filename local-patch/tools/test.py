from pathlib import Path
import subprocess, argparse, zipfile, json, os, shutil, sys, hashlib
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--game-dir',default='E:/Steam/steamapps/common/ProjectZomboid');p.add_argument('--jar',required=True);args=p.parse_args()
game=Path(args.game_dir);build=root/'build';build.mkdir(exist_ok=True)
zb=build/'ZombieBuddy.jar'
if Path(args.jar).resolve()!=zb.resolve():shutil.copy2(args.jar,zb)
(build/'ZombieBuddy.jar.sha256').write_text(hashlib.sha256(zb.read_bytes()).hexdigest()+'\n',encoding='ascii')
classes=build/'test-classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[zb,game/'projectzomboid.jar']))
subprocess.run(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sorted((root/'test-java').rglob('*.java')))],check=True)
with zipfile.ZipFile(build/'TestAgent.jar','w') as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: local.zbselective.TestAgent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n\n')
    z.write(classes/'local/zbselective/TestAgent.class','local/zbselective/TestAgent.class')
sample=build/'Sample.jar'
with zipfile.ZipFile(sample,'w') as z:
    for f in sorted((classes/'fixture/sample').rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
menu_sample=build/'MenuOnly.jar'
with zipfile.ZipFile(menu_sample,'w') as z:
    for f in sorted((classes/'fixture/menu').rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
preload_sample=build/'PreloadSample.jar'
with zipfile.ZipFile(preload_sample,'w') as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nZB-Preload: true\n\n')
    for f in sorted((classes/'fixture/preload').rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
host=build/'TestHost.jar'
with zipfile.ZipFile(host,'w') as z:
    for f in sorted(classes.rglob('*.class')):
        if not any(pkg in f.as_posix() for pkg in ['fixture/sample','fixture/menu','fixture/preload']):z.write(f,f.relative_to(classes).as_posix())
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
for mode in ['save-plan','default-plan','new-code','removed-code']:
    state.unlink(missing_ok=True)
    run('prepare-profile-'+mode,base+['prepare-save',str(sample)])
    if mode=='default-plan':
        content=state.read_text(encoding='utf-8').replace('profile=SaveProfile','profile=default')
        state.write_text(content,encoding='utf-8')
    command=base.copy();command[command.index('local.zbselective.TestMain')]='local.zbselective.ProfileCycleTest'
    run('profile-cycle-'+mode,command+[mode,str(sample),str(menu_sample)],42 if mode in ['new-code','removed-code'] else 0)
for mode in ['save','default','updated','invalid-signature']:
    state.unlink(missing_ok=True)
    config=build/('preload-config-'+mode);config.mkdir(exist_ok=True)
    (config/'config.json').unlink(missing_ok=True)
    signature=preload_sample.with_suffix('.jar.zbs');signature.unlink(missing_ok=True)
    command=base.copy();command[command.index('local.zbselective.TestMain')]='me.zed_0xff.zombie_buddy.PreloadSelectionTest'
    command[command.index(f'-javaagent:{zb}=policy=deny-new,config_dir={build/"isolated-config"}')]=f'-javaagent:{zb}=policy=deny-new,config_dir={config}'
    run('prepare-preload-'+mode,command+['prepare',str(preload_sample)])
    if mode=='default':state.write_text(state.read_text().replace('profile=currentGame','profile=default'))
    if mode=='updated':
        with zipfile.ZipFile(preload_sample,'a') as z:z.writestr('updated.txt','changed after selection')
    if mode=='invalid-signature':signature.write_text('invalid signature')
    info=build/'preload-mod.info';info.write_text('id=SaveOnly\njavaPkgName=fixture.preload\njavaJarFile=PreloadSample.jar\njavaPreload=true\n',encoding='utf-8')
    (config/'config.json').write_text(json.dumps({'preload_mods':{'SaveOnly':{'infPath':str(info),'jarPath':str(preload_sample.resolve())}}}),encoding='utf-8')
    run('preload-'+mode,command+[mode,str(preload_sample)])
signature.unlink(missing_ok=True)
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
(build/'regression-inputs.json').write_text(json.dumps({'jar_sha256':hashlib.sha256(zb.read_bytes()).hexdigest()}),encoding='utf-8')
print('ALL CUSTOM REGRESSIONS PASSED')
