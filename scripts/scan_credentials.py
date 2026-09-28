#!/usr/bin/env python3
"""Offline tracked-tree + added-diff checks. Output paths/categories, never values.

This is a targeted regression scan, not a claim to detect every possible secret.
Historical commits are retained intentionally; removed lines are not printed.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = '0864e972c9580dff9a2eb7a6a87aed5d43d98fa1'
def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)
paths = set(filter(None, git('ls-files', '-z').decode().split('\0')))
paths.update(filter(None, git('ls-files', '--others', '--exclude-standard', '-z').decode().split('\0')))
old = git('show', BASE + ':app/build.gradle.kts').decode()
# Match the two legacy long client strings internally, without disclosing them.
known = re.findall(r'\\"([a-f0-9]{50,})\\"', old)
rules = {
    'private key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'GitHub token': re.compile(rb'\bgh[pousr]_[A-Za-z0-9]{30,}\b|github_pat_[A-Za-z0-9_]{50,}'),
    'AWS access key': re.compile(rb'\bAKIA[0-9A-Z]{16}\b'),
}
findings = set()
for path in sorted(paths):
    p = ROOT / path
    if not p.is_file(): continue
    data = p.read_bytes()
    if p.suffix in {'.jks', '.keystore', '.p12'}:
        findings.add((path, 'tracked signing material'))
    if any(value.encode() in data for value in known):
        findings.add((path, 'legacy embedded SIMKL value'))
    for kind, pattern in rules.items():
        if pattern.search(data): findings.add((path, kind))
gradle = (ROOT / 'app/build.gradle.kts').read_text()
if re.search(r'(storePassword|keyPassword|keyAlias)\s*=.*\?:', gradle):
    findings.add(('app/build.gradle.kts', 'signing fallback'))
if re.search(r'"SIMKL_CLIENT_SECRET"\s*,\s*"\\"[^"$]+', gradle):
    findings.add(('app/build.gradle.kts', 'embedded client secret'))
diff = git('diff', BASE, '--unified=0', '--', '.', ':(exclude)docs/upstream-sync-2026-09/upstream-inventory.json').decode(errors='replace')
added = '\n'.join(l[1:] for l in diff.splitlines() if l.startswith('+') and not l.startswith('+++')).encode()
for kind, pattern in rules.items():
    if pattern.search(added): findings.add(('added diff', kind))
for path, kind in sorted(findings):
    print(f'FAIL: {path}: {kind}')
print(f'Credential regression scan: {len(paths)} files; {len(findings)} findings. No values emitted.')
sys.exit(bool(findings))
