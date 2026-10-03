#!/usr/bin/env python3
"""Reproducible path/blob and commit inventory; never emits source/credential values."""
import collections
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT/'docs/upstream-sync-2026-09'
INITIAL='0864e972c9580dff9a2eb7a6a87aed5d43d98fa1'
BASE='a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288'
UPSTREAM='e36ff7aa7997be86d47aac9412c174f06128fd0d'
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT).decode().strip()
original=json.loads((DOC/'upstream-inventory.json').read_text())
assert {x['path'] for x in original} == set(git('diff','--name-only',BASE,UPSTREAM).splitlines())
rows=[]
for row in original:
    row=dict(row);p=ROOT/row['path']
    current=git('hash-object',str(p)) if p.is_file() else None
    row['current_blob']=current
    kind=row['classification']
    if kind=='direct replace' and current!=row['upstream'][1]:
        row['classification']='manual merge'
        row['note']='Originally identical to baseline; upstream port then adapted to preserve AdiXtream integration.'
    elif kind=='direct replace':
        assert row['adi']==row['baseline']
        row['note']='Original fork equals baseline; current blob equals pinned upstream.'
    elif kind=='added':
        row['note']='New upstream file'+(' adapted for AdiXtream.' if current!=row['upstream'][1] else ', imported unchanged.')
    elif kind=='manual merge':
        row['note']='Fork diverged; upstream diff ported onto the AdiXtream file.'
    else:
        row['note']='Translation/fastlane outside packaged en/id/in locale and AdiXtream branding scope; original retained or upstream-only addition omitted.'
    rows.append(row)
known={r['path'] for r in rows}
tracked=set(git('ls-files').splitlines()) | set(git('ls-files','--others','--exclude-standard').splitlines())
for path in sorted(tracked-known):
    if path.startswith(('docs/upstream-sync-2026-09/','scripts/')): continue
    p=ROOT/path
    if not p.is_file(): continue
    before=git('ls-tree',INITIAL,'--',path)
    old=before.split()[2] if before else None
    current=git('hash-object',str(p))
    if old==current: continue
    rows.append({'path':path,'classification':'added' if old is None else 'manual merge',
        'current_blob':current,'note':'AdiXtream integration, compatibility/security tests or prerequisite from earlier upstream.'})
protected=['app/build.gradle.kts','app/src/main/AndroidManifest.xml','.github/workflows/buat_apk.yml']
j='app/src/main/java/com/lagradost/cloudstream3/'
protected += [j+p for p in ['MainActivity.kt','ui/home/HomeFragment.kt','ui/settings/SettingsFragment.kt',
    'ui/settings/SettingsGeneral.kt','ui/settings/SettingsUpdates.kt','ui/player/CS3IPlayer.kt',
    'ui/player/FullScreenPlayer.kt','ui/player/GeneratorPlayer.kt','PremiumManager.kt','PremiumDialogManager.kt',
    'RepoProtector.kt','CampaignPopupManager.kt','SplashActivity.kt','utils/InAppUpdater.kt']]
for path in protected:
    if path in {r['path'] for r in rows}: continue
    before=git('ls-tree',INITIAL,'--',path)
    if not before or not (ROOT/path).is_file(): continue
    rows.append({'path':path,'classification':'protected / intentionally retained',
        'current_blob':git('hash-object',str(ROOT/path)), 'note':'Protected AdiXtream source retained byte-for-byte.'})
rows.sort(key=lambda x:(x['classification'],x['path']))
(DOC/'file-manifest.json').write_text(json.dumps(rows,indent=2)+'\n')
out=['# File decisions','',f'Pinned upstream `{BASE}` → `{UPSTREAM}`; fork base `{INITIAL}`.','',
     '298 upstream-changed paths were compared. Integration/prerequisite paths are listed too. Documentation/scripts are excluded from the source table; see Git diff for those.','']
for kind in ['direct replace','added','manual merge','protected / intentionally retained']:
    selected=[r for r in rows if r['classification']==kind]
    out += ['## '+kind+f' ({len(selected)})','','| Path | Decision |','| --- | --- |']
    out += [f"| `{r['path']}` | {r['note']} |" for r in selected]
    out += ['']
(DOC/'FILES.md').write_text('\n'.join(out))
commits=git('rev-list','--reverse',BASE+'..'+UPSTREAM).splitlines()
assert len(commits)==45
out=['# Upstream commit audit','',f'All {len(commits)} commits in the pinned range, including merge commits. File-level treatment is in [FILES.md](FILES.md).','',
     '| Commit | Subject | Treatment |','| --- | --- | --- |']
for sha in commits:
    subject=git('show','-s','--format=%s',sha).replace('|','/')
    paths=git('diff-tree','--no-commit-id','--name-only','-r',sha).splitlines()
    decisions={r['classification'] for r in rows if r['path'] in paths}
    if not paths: treatment='Merge commit; integrated result audited in baseline→HEAD file comparison.'
    elif decisions=={'protected / intentionally retained'}: treatment='Intentionally retained: non-packaged translations/fastlane.'
    else: treatment='Ported; see file decisions'+(' (non-packaged translations retained).' if 'protected / intentionally retained' in decisions else '.')
    out.append(f'| `{sha}` | {subject} | {treatment} |')
(DOC/'COMMITS.md').write_text('\n'.join(out)+'\n')
print('Upstream audit: 45 commits / 298 changed paths; final source categories:',dict(collections.Counter(r['classification'] for r in rows)))
