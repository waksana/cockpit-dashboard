import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const [apk, aapt, apksigner, output] = process.argv.slice(2);
if (!apk || !aapt || !apksigner || !output) throw new Error('Expected APK, aapt, apksigner, output directory');
const code = Number(process.env.DASHBOARD_VERSION_CODE);
const name = process.env.DASHBOARD_VERSION_NAME;
if (!Number.isSafeInteger(code) || code < 10000 || !/^\d+\.\d+\.\d+$/.test(name ?? '')) {
  throw new Error('Invalid release version');
}
const expected = readFileSync(new URL('../release-signing.sha256', import.meta.url), 'utf8').trim();
const signature = execFileSync(apksigner, ['verify', '--print-certs', apk], { encoding: 'utf8' });
const certs = [...signature.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$/gm)];
if (certs.length !== 1 || certs[0][1] !== expected) throw new Error('Unexpected release signing identity');
const badging = execFileSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8' });
const identity = badging.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m);
if (!identity || identity[1] !== 'io.github.waksana.cockpitdashboard'
    || Number(identity[2]) !== code || identity[3] !== name || /^application-debuggable/m.test(badging)) {
  throw new Error('Unexpected APK identity, version or debug build');
}
const bytes = readFileSync(apk);
if (!bytes.length || bytes.length > 100 * 1024 * 1024) throw new Error('APK size out of bounds');
const metadata = { versionCode: code, versionName: name, apk: 'cockpit-dashboard.apk',
  size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
writeFileSync(`${output}/cockpit-dashboard.apk`, bytes);
writeFileSync(`${output}/update.json`, `${JSON.stringify(metadata, null, 2)}\n`);
console.log(`Prepared ${name} (${code}) with pinned signer`);
