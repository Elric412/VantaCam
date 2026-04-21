import fs from 'node:fs';
const input = process.argv[2];
if (input && fs.existsSync(input)) {
  const entry = fs.readFileSync(input, 'utf-8');
  const existing = fs.existsSync('CHANGELOG.md') ? fs.readFileSync('CHANGELOG.md', 'utf-8') : '# Changelog\n\n';
  fs.writeFileSync('CHANGELOG.md', `${existing}\n${entry}\n`);
}
console.log('changelog updated');
