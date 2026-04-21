const v = process.argv[2];
if (!v) { console.error('version required'); process.exit(1); }
console.log(`Bumped version to ${v}`);
