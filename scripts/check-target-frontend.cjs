const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const target = JSON.parse(fs.readFileSync(path.join(__dirname, 'target-environment.json'), 'utf8'));
const project = JSON.parse(fs.readFileSync(path.join(root, 'frontend', 'package.json'), 'utf8'));
const declared = { ...project.dependencies, ...project.devDependencies };
for (const [name, version] of Object.entries(declared)) {
  if (target.frontendDependencies[name] !== version) {
    throw new Error(`Direct dependency ${name} must be pinned to its target version; found ${version}.`);
  }
}
for (const [name, expected] of Object.entries(target.frontendDependencies)) {
  if (declared[name] !== expected) throw new Error(`Missing target declaration ${name}==${expected}.`);
  const filename = path.join(root, 'frontend', 'node_modules', name, 'package.json');
  if (!fs.existsSync(filename)) throw new Error(`Missing ${name}; run target pnpm install --frozen-lockfile.`);
  const installed = JSON.parse(fs.readFileSync(filename, 'utf8')).version;
  if (installed !== expected) throw new Error(`${name}: expected ${expected}, found ${installed}.`);
}
if (!fs.existsSync(path.join(root, 'frontend', 'node_modules', 'vite', 'bin', 'vite.js'))) {
  throw new Error('Vite executable missing; reinstall dependencies using the lockfile.');
}
console.log('Frontend target dependency versions verified.');
