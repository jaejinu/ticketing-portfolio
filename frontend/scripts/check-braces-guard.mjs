import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
let require = createRequire(import.meta.url);
for (const name of ['eslint-config-next', '@next/eslint-plugin-next', 'fast-glob', 'micromatch']) {
  require = createRequire(require.resolve(name));
}
const braces = require('braces');
if (process.argv[2] === '--probe') {
  const [method, kind] = process.argv.slice(3);
  let value;
  if (kind === 'ast') {
    value = { type: 'root', nodes: [] };
    let node = value;
    for (let i = 0; i < 512; i++) {
      const child = { type: 'paren', nodes: [], parent: node };
      node.nodes.push(child); node = child;
    }
    node.nodes.push({ type: 'text', value: 'x' });
  } else {
    const open = kind === 'parentheses' ? '(' : '{';
    const close = kind === 'parentheses' ? ')' : '}';
    value = open.repeat(4000) + 'x' + (kind === 'unclosed' ? '' : close.repeat(4000));
  }
  assert.throws(() => braces[method](value), error => error instanceof SyntaxError && error.message.startsWith('BRACES_MAX_DEPTH:'));
} else {
  assert.deepEqual(braces.expand('src/{a,b}/{1..3}.ts'), ['src/a/1.ts','src/a/2.ts','src/a/3.ts','src/b/1.ts','src/b/2.ts','src/b/3.ts']);
  assert.equal(braces.compile('src/{a,b}.ts'), 'src/(a|b).ts');
  assert.equal(braces.stringify(braces.parse('src/{a,b}.ts')), 'src/{a,b}.ts');
  assert.equal(braces.stringify('('.repeat(20) + 'x' + ')'.repeat(20)), '('.repeat(20) + 'x' + ')'.repeat(20));
  let count = 0;
  for (const method of ['parse', 'compile', 'expand', 'stringify']) {
    for (const kind of ['braces', 'parentheses', 'unclosed', ...(method === 'parse' ? [] : ['ast'])]) {
      const result = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--probe', method, kind], { timeout: 5000, encoding: 'utf8' });
      assert.equal(result.status, 0, `${method}/${kind}: ${result.error ?? result.stderr}`);
      count++;
    }
  }
  console.log(`braces guard: ${count} bounded hostile-input probes + normal-pattern compatibility passed`);
}
