#!/usr/bin/env python3
"""Run the plugin's tests against the SDK without a device."""
import json
import os
import sys
from pathlib import Path
import subprocess
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
# The SDK's overlay classes use Android views. The tests never draw one,
# so the platform's stub classes are enough to compile and load them.
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error

# The manifest the plugin ships must parse and name settings the code reads.
manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text())
keys = {setting['key'] for setting in manifest['settings']}
for key in ['apiKey', 'allowLikely', 'source', 'maPlayer', 'mediaPlayer', 'offset', 'showWith', 'layout', 'textSize', 'animations', 'showControls', 'tapToSeek']:
    assert key in keys, key
assert manifest['id'] == 'spicy-lyrics-overlay' and manifest['apiVersion'] == 1

sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-Xlint:-options', '-cp', platform, '-d', directory, *map(str, sources)], check=True)
    for test in ['io.github.eibelucas.spicylyrics.CoreTest', 'io.github.eibelucas.spicylyrics.PluginTest']:
        subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, platform]), test], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)
subprocess.run([sys.executable, str(root / 'tools/test_plugin_manifest.py')], check=True)
subprocess.run([sys.executable, str(root / 'tools/test_plugin_assets.py')], check=True)
