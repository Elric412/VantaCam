import { readdirSync } from 'node:fs';
import path from 'node:path';

export async function generateSubPages(rootDir) {
  const publicDir = path.join(rootDir, 'public');
  let files = [];
  try {
    files = readdirSync(publicDir).filter((f) => f.endsWith('.html'));
  } catch {
    files = [];
  }
  return { files };
}
