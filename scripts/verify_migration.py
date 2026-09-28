#!/usr/bin/env python3
"""Offline source contracts. Does not claim device/install-over verification.

Print contract names and paths only, never source or credential values.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INITIAL = '0864e972c9580dff9a2eb7a6a87aed5d43d98fa1'
DOC = ROOT / 'docs/upstream-sync-2026-09'
JAVA = 'app/src/main/java/com/lagradost/cloudstream3/'
def read(path): return (ROOT / path).read_text()
def original(path): return subprocess.check_output(['git', 'show', f'{INITIAL}:{path}'], cwd=ROOT)
def digest(data): return hashlib.sha256(data.encode() if isinstance(data, str) else data).hexdigest()
def function(source, name):
    match = re.search(r'\bfun\s+' + re.escape(name) + r'\s*\(', source)
    if not match: raise ValueError('Missing function: ' + name)
    begin = source.index('{', match.end())
    depth, end = 1, begin + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[match.start():end]
def license_function(source, name):
    # Exclude the two additive display-only expiry-history calls. These cannot
    # grant access, change a device ID, or change a server record mapping.
    return re.sub(r'^\s*(?:if \(isExpired\) )?rememberExpiredSubscription\(context, (?:expDate|dbExpired)\)\n', '', function(source, name), flags=re.M)

protected = [JAVA + p for p in ['RepoProtector.kt', 'PremiumDialogManager.kt', 'CampaignPopupManager.kt', 'SplashActivity.kt']]
protected += ['app/src/main/res/layout/activity_splash.xml']
protected += [str(p.relative_to(ROOT)) for p in (ROOT / 'app/src/main/res').glob('mipmap*/*')]
methods = ['getDeviceId', 'getSecurePrefs', 'getBackupPrefs', 'saveLicenseLocally',
    'checkAndMigrateOldOfflineUser', 'isPremium', 'deactivatePremium', 'getExpiryDateString',
    'checkAndSyncWithServer', 'registerUserToServer']
if '--record-baseline' in sys.argv:
    p = original(JAVA + 'PremiumManager.kt').decode()
    baseline = {'initial': INITIAL, 'protected': {path: digest(original(path)) for path in protected},
        'premiumMethods': {name: digest(license_function(p, name)) for name in methods},
        'xorEncrypt': digest(function(original('app/build.gradle.kts').decode(), 'xorEncrypt'))}
    (DOC / 'compatibility-contracts.json').write_text(json.dumps(baseline, indent=2) + '\n')
    print('Recorded original compatibility hashes; no source/credential values emitted.')
    sys.exit(0)

baseline = json.loads((DOC / 'compatibility-contracts.json').read_text())
results = []
def check(name, passed): results.append({'contract': name, 'passed': bool(passed)})
for path, expected in baseline['protected'].items():
    check('retained ' + path, (ROOT/path).exists() and digest((ROOT/path).read_bytes()) == expected)
premium = read(JAVA + 'PremiumManager.kt')
for name, expected in baseline['premiumMethods'].items():
    check('premium compatibility: '+name, digest(license_function(premium,name)) == expected)
for key in ['premium_secure_data','premium_fallback_prefs','is_premium_user','premium_expiry_date','obf_state','obf_exp']:
    check('premium storage key: '+key, '"'+key+'"' in premium)
check('legacy promo API', 'activatePromoWithCode(context, code, deviceId, true, onResult)' in premium)
service = read(JAVA+'ui/settings/SubscriptionViewModel.kt')
for method in ['getDeviceId','activatePremiumWithCode','activatePromoWithCode']:
    check('subscription existing API: '+method, 'PremiumManager.'+method+'(' in service)
main = read(JAVA+'MainActivity.kt')
check('offline migration at startup','PremiumManager.checkAndMigrateOldOfflineUser(this)' in main)
check('premium startup reconciliation','PremiumManager.isPremium(this@MainActivity)' in main and 'hasInvalidRepos' in main)
check('repository deep link guard','if (!RepositoryManager.isAllowedRepository(realUrl)) return false' in main)
gradle = read('app/build.gradle.kts')
check('production package identity','applicationId = "com.adixtream.app"' in gradle)
check('original version','versionCode = 90' in gradle and 'versionName = "4.8.3"' in gradle)
check('locale scope','listOf("en", "id", "in")' in gradle)
check('XOR algorithm unchanged',digest(function(gradle,'xorEncrypt')) == baseline['xorEncrypt'])
check('shared dependency','implementation(project(":shared"))' in gradle and '":shared"' in read('settings.gradle.kts'))
names=['ALIAS','KEY_STORE_PASSWORD','KEY_PASSWORD','XOR_SECRET_KEY','PREMIUM_REPO_ENCODED','FREE_REPO_ENCODED','FIREBASE_URL_ENCODED']
for name in names: check('existing env: '+name,'System.getenv("'+name+'")' in gradle)
workflow=read('.github/workflows/buat_apk.yml')
for name in ['SIGNING_KEY',*names,'SIMKL_CLIENT_ID','SIMKL_CLIENT_SECRET','ANILIST_CLIENT_SECRET','OPENSUBTITLES_API_KEY']:
    check('workflow secret: '+name,'${{ secrets.'+name+' }}' in workflow)
check('existing keystore path','KEYSTORE_PATH: release.keystore' in workflow and '> app/release.keystore' in workflow)
check('no signing fallback',not re.search(r'(?:storePassword|keyPassword|keyAlias)\s*=.*\?:',gradle))
check('release fails without config','verifyReleaseConfiguration' in gradle and 'preStableReleaseBuild' in gradle)
check('Compose configuration',all(v in gradle for v in ['libs.plugins.compose.compiler','libs.plugins.compose.multiplatform','libs.bundles.compose']))
root=read(JAVA+'ui/settings/SettingsFragmentScreen.kt')
titles=re.findall(r'title = R.string.(\w+)',re.sub(r'/\*.*?\*/','',root,flags=re.S))
check('root menu order',titles[:7]==['category_general','category_player','category_ui','category_updates','category_account','pref_category_extensions','adi_subscription_title'])
check('root scroll/build stamp','verticalScroll(outerListState)' in root and 'BuildStamp()' in root and 'Spacer(Modifier.height(96.dp))' in root)
check('no add repository menu','R.string.add_repository' not in root)
subscription=read(JAVA+'ui/settings/SettingsSubscriptionScreen.kt')
check('subscription scroll and TV focus','LazyColumn(' in subscription and '.focusOutline()' in subscription)
manager=read(JAVA+'plugins/RepositoryManager.kt')
check('repository fetch restriction','if (!isAllowedRepository(url)) return null' in manager)
check('repository insert restriction','require(isAllowedRepository(repository.url))' in manager)
extensions=read(JAVA+'ui/settings/extensions/ExtensionsFragment.kt')
check('no arbitrary repo form','AddRepoInputBinding' not in extensions and 'addRepositoryClick' not in extensions)
check('phone/TV add buttons disabled','addRepoButton.isGone = true' in extensions and 'addRepoButtonImageviewHolder.isGone = true' in extensions)
updater=read(JAVA+'ui/settings/GithubViewModel.kt')
check('Compose updater origin','APK_USERNAME = "michat88"' in updater and 'APK_REPOSITORY = "AdiXtream"' in updater)
legacy=read(JAVA+'utils/InAppUpdater.kt')
check('fallback updater origin','GITHUB_USER_NAME = "michat88"' in legacy and 'GITHUB_REPO = "AdiXtream"' in legacy)
check('package/certificate validation','fun validateUpgrade' in read(JAVA+'ui/settings/ApkUpdater.kt'))
nav=ET.fromstring(read('app/src/main/res/navigation/mobile_navigation.xml'))
android='{http://schemas.android.com/apk/res/android}'; app='{http://schemas.android.com/apk/res-auto}'
ids={n.get(android+'id','').split('/')[-1] for n in nav.iter()}
check('navigation destinations',all(n.get(app+'destination','').split('/')[-1] in ids for n in nav.iter('action')))
check('Compose settings root',any(n.get(android+'name','').endswith('.SettingsFragment2') for n in nav.iter('fragment')))
check('subscription destination',any(n.get(android+'name','').endswith('.SettingsSubscription') for n in nav.iter('fragment')))
for n in nav.iter():
    name=n.get(android+'name','')
    if name.startswith('com.lagradost.cloudstream3.'):
        check('navigation class: '+name,bool(list((ROOT/'app/src/main/java').rglob(name.split('.')[-1]+'.kt'))))
passed=sum(r['passed'] for r in results)
for r in results:
    if not r['passed']: print('FAIL: '+r['contract'])
print(f'Source compatibility contracts: {passed}/{len(results)} PASS')
if '--report' in sys.argv: (DOC/'source-contract-results.json').write_text(json.dumps(results,indent=2)+'\n')
sys.exit(0 if passed==len(results) else 1)
