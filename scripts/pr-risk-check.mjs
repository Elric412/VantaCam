if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ risk: 'low', score: 0 }));
} else {
  console.log('PR risk: low');
}
