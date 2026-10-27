#!/usr/bin/env python3
"""Offline build against the installed DataGrip SDK. No downloads or IDE installation."""
import argparse
import os
import pathlib
import subprocess
import zipfile
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--ide', default=os.environ.get('DATAGRIP_HOME'))
parser.add_argument('--test', action='store_true')
args = parser.parse_args()
if not args.ide:
    parser.error('Set DATAGRIP_HOME or pass --ide /path/to/datagrip')
ide = pathlib.Path(args.ide).resolve()
root = pathlib.Path(__file__).resolve().parent
jars = list((ide / 'lib').rglob('*.jar')) + list((ide / 'plugins/DatabaseTools').rglob('*.jar')) + list((ide / 'plugins/grid-core-plugin').rglob('*.jar'))
classpath = os.pathsep.join(str(p) for p in jars)
classes = root / 'build/classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)
sources = sorted((root / 'src/main/java').rglob('*.java'))
if args.test:
    sources += sorted((root / 'src/test/java').rglob('*.java'))
subprocess.run([str(ide / 'jbr/bin/javac'), '-encoding', 'UTF-8', '--release', '21', '-classpath', classpath,
                '-d', str(classes)] + [str(p) for p in sources], check=True)
if args.test:
    subprocess.run([str(ide / 'jbr/bin/java'), '--add-modules', 'jdk.httpserver', '-ea', '-classpath', str(classes) + os.pathsep + classpath,
                    'cn.swg.pii.ReaderTest'], check=True, cwd=root)
dist = root / 'dist'
dist.mkdir(exist_ok=True)
jar = root / 'build/pii-reader.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as output:
    for path in sorted(classes.rglob('*.class')):
        if path.name.startswith('ReaderTest'):
            continue
        output.write(path, str(path.relative_to(classes)))
    for path in (root / 'src/main/resources').rglob('*'):
        if path.is_file(): output.write(path, str(path.relative_to(root / 'src/main/resources')))
package = dist / 'workbench-pii-reader-0.7.0.zip'
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as output:
    output.write(jar, 'workbench-pii-reader/lib/pii-reader.jar')
print(package)
