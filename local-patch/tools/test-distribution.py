from pathlib import Path
import hashlib, json, subprocess, sys, zipfile

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parents[1]
build = root / 'build'
context_file = build / 'build-context.json'
inputs_file = build / 'regression-inputs.json'
staged = root / 'Workshop/Contents/mods/ZombieBuddyOptimized/42.21/ZombieBuddy.jar'
checksum = staged.with_suffix('.jar.sha256')
context_bytes, inputs_bytes = context_file.read_bytes(), inputs_file.read_bytes()
staged_bytes, checksum_bytes = staged.read_bytes(), checksum.read_bytes()
results = {}

def package(expected=0, message=None):
    result = subprocess.run([sys.executable, str(root / 'tools/package.py')], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=90, cwd=build)
    output = result.stdout + result.stderr
    assert (result.returncode == 0) == (expected == 0), output
    if message: assert message in output, output

try:
    context = json.loads(context_bytes)
    context['tests_run'] = False
    context_file.write_text(json.dumps(context), encoding='utf-8')
    package(1, 'Packaging requires a tested build')
    results['untested-build-rejected'] = 'passed'
    context_file.write_bytes(context_bytes)

    inputs_file.write_text(json.dumps({'jar_sha256': 'old-test-artifact'}), encoding='utf-8')
    package(1, 'belongs to another JAR')
    results['stale-regression-rejected'] = 'passed'
    inputs_file.write_bytes(inputs_bytes)

    (root / 'source-changes.patch').unlink(missing_ok=True)
    staged.write_bytes(b'outdated Workshop artifact')
    checksum.write_text('outdated checksum\n', encoding='ascii')
    package()
    assert (root / 'source-changes.patch').read_bytes().startswith(b'diff --git ')
    results['source-patch-generated'] = 'passed'
    digest = hashlib.sha256((build / 'ZombieBuddy.jar').read_bytes()).hexdigest()
    archives = sorted((build / 'dist').glob('*1.1.2*.zip'))
    assert len(archives) == 3
    for archive in archives:
        with zipfile.ZipFile(archive) as zipped:
            assert zipped.testzip() is None
            for name in zipped.namelist():
                if name.endswith('/ZombieBuddy.jar'):
                    assert hashlib.sha256(zipped.read(name)).hexdigest() == digest, (archive, name)
                    assert zipped.read(name + '.sha256').decode('ascii').strip() == digest
    results['stale-workshop-overridden'] = 'passed'
finally:
    context_file.write_bytes(context_bytes)
    inputs_file.write_bytes(inputs_bytes)
    staged.write_bytes(staged_bytes)
    checksum.write_bytes(checksum_bytes)

(build / 'distribution-report.json').write_text(json.dumps({'jar_sha256': digest, 'checks': results}, indent=2), encoding='utf-8')
for name in results: print('PASS', name)
