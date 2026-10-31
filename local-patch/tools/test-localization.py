from pathlib import Path
import subprocess,json,os,sys,argparse,hashlib
sys.stdout.reconfigure(encoding='utf-8')
parser=argparse.ArgumentParser();parser.add_argument('--game-dir',required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1];build=root/'build';game=Path(args.game_dir).resolve();java=str(game/'jre64/bin/java.exe')
plain=[java,'-ea','-Xverify:all','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true',f'-Djava.library.path={game}']
cp=os.pathsep.join(map(str,[build/'TestHost.jar',build/'ZombieBuddy.jar',game/'projectzomboid.jar']))
results={}
for lang in ['CN','EN']:
    home=build/('locale-'+lang);(home/'Zomboid').mkdir(parents=True,exist_ok=True);(home/'Zomboid/options.ini').write_text('language='+lang+'\n',encoding='utf-8')
    cmd=plain+[f'-Duser.home={home}','-Dtest.failHttp=true',f'-Dzbselective.state={home/"state.properties"}',f'-javaagent:{build/"TestAgent.jar"}',f'-javaagent:{build/"ZombieBuddy.jar"}=policy=deny-new,config_dir={home/"config"}','-cp',cp,'local.zbselective.i18n.LocalizationTest',lang,str(build/'Sample.jar')]
    r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60);(build/f'locale-{lang}.log').write_text(r.stdout+'\n'+r.stderr,encoding='utf-8')
    if r.returncode: print(r.stdout,r.stderr)
    else: print('PASS startup/live UI + approval decisions',lang)
    if r.returncode:raise RuntimeError(lang+' localization failed')
    results[lang]='passed'
for lang in ['CN','EN']:
    cmd=plain+[f'-Dzb.ui.language={lang}','-cp',os.pathsep.join(map(str,[build/'TestHost.jar',build/'ZombieBuddy.jar'])),'local.zbselective.i18n.LocalizationTest','child']
    r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    assert r.returncode==0,r.stderr
    assert r.stdout.strip()==lang+(':'+('允许' if lang=='CN' else 'Allow')),r.stdout
    results['child-'+lang]='passed'
home=build/'locale-custom-home';(home/'Zomboid').mkdir(parents=True,exist_ok=True);(home/'Zomboid/options.ini').write_text('language=EN\n',encoding='utf-8')
cache=build/'locale custom cache';cache.mkdir(exist_ok=True);(cache/'options.ini').write_text('language=CN\n',encoding='utf-8')
cmd=plain+[f'-Duser.home={home}','-Dtest.failHttp=true',f'-Dzbselective.state={home/"state.properties"}',f'-javaagent:{build/"TestAgent.jar"}',f'-javaagent:{build/"ZombieBuddy.jar"}=policy=deny-new,config_dir={home/"config"}','-cp',cp,'local.zbselective.i18n.LocalizationTest','CN',str(build/'Sample.jar'),'-cachedir='+str(cache)]
r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
(build/'locale-custom-cache.log').write_text(r.stdout+'\n'+r.stderr,encoding='utf-8')
if r.returncode: print(r.stdout,r.stderr);raise RuntimeError('custom cachedir language lookup failed')
results['custom-cachedir']='passed';print('PASS custom -cachedir language lookup')
cmd=plain+['-cp',cp,'local.zbselective.i18n.LocalizationTest','font']
r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60);(build/'locale-font.log').write_text(r.stdout+'\n'+r.stderr,encoding='utf-8')
if r.returncode:print(r.stdout,r.stderr)
else:print('PASS native ImGui CJK glyphs')
if r.returncode:raise RuntimeError('native font glyph validation failed')
results['native-CJK-font']='passed'
(build/'localization-report.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
(build/'localization-inputs.json').write_text(json.dumps({'jar_sha256':hashlib.sha256((build/'ZombieBuddy.jar').read_bytes()).hexdigest()}),encoding='utf-8')
