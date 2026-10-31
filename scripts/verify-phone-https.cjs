// Read-only checks: do not print cookies, credentials or private keys.
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const lanIp = process.argv[2];
if (!lanIp || require('node:net').isIP(lanIp) !== 4) throw new Error('Usage: node scripts/verify-phone-https.cjs <LAN IPv4>');
const ca = fs.readFileSync(path.join(root, 'deploy/certs/lan/rootCA.pem'));
async function check(route) {
  return new Promise((resolve, reject) => {
    const req = https.get(`https://${lanIp}:5173${route}`, { ca, timeout: 15000 }, res => {
      res.resume();
      const cookies = res.headers['set-cookie'] || [];
      const secureSession = cookies.some(c => c.startsWith('JSESSIONID=') && /;\s*Secure(?:;|$)/i.test(c));
      const certificateVerified = res.socket.authorized;
      console.log(JSON.stringify({ route, status: res.statusCode, certificateVerified, ...(route.endsWith('/csrf') ? { secureSessionCookie: secureSession } : {}) }));
      res.on('end', () => res.statusCode === 200 && certificateVerified && (!route.endsWith('/csrf') || secureSession) ? resolve() : reject(new Error('HTTPS check failed')));
    });
    req.on('timeout', () => req.destroy(new Error('HTTPS check timed out')));
    req.on('error', reject);
  });
}
(async () => { for (const route of ['/', '/api/v1/health', '/api/v1/auth/csrf']) await check(route); })().catch(err => { console.error(err.message); process.exitCode = 1; });
