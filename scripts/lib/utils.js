import fs from 'node:fs';
import path from 'node:path';

export const PROVIDER_PLACEHOLDERS = {
  'claude-code': { model: 'Claude', config_file: 'CLAUDE.md', ask_instruction: 'STOP and call the AskUserQuestion tool to clarify.' },
  cursor: { model: 'the model', config_file: '.cursorrules', ask_instruction: 'ask the user directly to clarify what you cannot infer.' },
  gemini: { model: 'Gemini', config_file: 'GEMINI.md', ask_instruction: 'ask the user directly to clarify what you cannot infer.' },
  codex: { model: 'GPT', config_file: 'AGENTS.md', ask_instruction: 'ask the user directly to clarify what you cannot infer.' },
  agents: { model: 'the model', config_file: '.github/copilot-instructions.md', ask_instruction: 'ask the user directly to clarify what you cannot infer.' },
  kiro: { model: 'Claude', config_file: '.kiro/settings.json', ask_instruction: 'ask the user directly to clarify what you cannot infer.' },
};

export function parseFrontmatter(content) {
  const m = content.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/);
  if (!m) return { frontmatter: {}, body: content };
  const frontmatter = {};
  for (const line of m[1].split('\n')) {
    const idx = line.indexOf(':');
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim();
    let val = line.slice(idx + 1).trim();
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) val = val.slice(1, -1);
    if (val === 'true') val = true;
    else if (val === 'false') val = false;
    frontmatter[key] = val;
  }
  const body = (m[2] ?? '').replace(/^\n+/, '').replace(/\n+$/, '');
  return { frontmatter, body };
}

export function generateYamlFrontmatter(data) {
  const lines = ['---'];
  for (const [k, v] of Object.entries(data)) {
    if (v === undefined || v === null || v === '') continue;
    lines.push(`${k}: ${typeof v === 'boolean' ? (v ? 'true' : 'false') : String(v)}`);
  }
  lines.push('---');
  return lines.join('\n');
}

export function ensureDir(dir) { fs.mkdirSync(dir, { recursive: true }); }
export function cleanDir(dir) { if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true }); }
export function writeFile(filePath, content) { ensureDir(path.dirname(filePath)); fs.writeFileSync(filePath, content, 'utf-8'); }

export function readFilesRecursive(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...readFilesRecursive(p));
    else if (entry.isFile() && p.endsWith('.md')) out.push(p);
  }
  return out;
}

export function readSourceFiles(rootDir) {
  const skillsDir = path.join(rootDir, 'source/skills');
  if (!fs.existsSync(skillsDir)) return { skills: [] };
  const skills = [];
  for (const entry of fs.readdirSync(skillsDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const skillDir = path.join(skillsDir, entry.name);
    const skillFile = path.join(skillDir, 'SKILL.md');
    if (!fs.existsSync(skillFile)) continue;
    const { frontmatter, body } = parseFrontmatter(fs.readFileSync(skillFile, 'utf-8'));
    const refsDir = path.join(skillDir, 'reference');
    const references = fs.existsSync(refsDir)
      ? readFilesRecursive(refsDir).map((fp) => ({ name: path.basename(fp, '.md'), content: fs.readFileSync(fp, 'utf-8'), filePath: fp }))
      : [];
    skills.push({
      name: frontmatter.name || entry.name,
      description: frontmatter.description,
      license: frontmatter.license,
      compatibility: frontmatter.compatibility,
      metadata: frontmatter.metadata,
      userInvocable: frontmatter['user-invocable'] === true,
      argumentHint: frontmatter['argument-hint'],
      allowedTools: frontmatter['allowed-tools'],
      body: body.trim(),
      references,
    });
  }
  return { skills };
}

function extractSection(content, heading) {
  const re = new RegExp(`###\\s+${heading.replace(/[.*+?^${}()|[\\]\\]/g, '\\\\$&')}\\n([\\s\\S]*?)(?=\\n###\\s+|$)`, 'i');
  const m = content.match(re);
  if (!m) return [];
  const items = [];
  const itemRe = /\*\*(DO|DON'T)\*\*:\s*([^\n]+)/gi;
  let it;
  while ((it = itemRe.exec(m[1])) !== null) items.push({ kind: it[1], text: it[2].trim() });
  return items;
}

export function readPatterns(rootDir) {
  const p = path.join(rootDir, 'source/skills/impeccable/SKILL.md');
  if (!fs.existsSync(p)) return { patterns: [], antipatterns: [] };
  const content = fs.readFileSync(p, 'utf-8');
  const sectionOrder = ['Typography', 'Color & Contrast', 'Layout & Space', 'Motion', 'Visual Details'];
  const aliases = { 'Color & Contrast': ['Color & Contrast', 'Color & Theme'] };
  const patterns = [];
  const antipatterns = [];
  for (const sec of sectionOrder) {
    const hits = [];
    for (const candidate of (aliases[sec] || [sec])) hits.push(...extractSection(content, candidate));
    if (!hits.length) continue;
    patterns.push({ name: sec, items: hits.filter((h) => h.kind === 'DO').map((h) => h.text) });
    antipatterns.push({ name: sec, items: hits.filter((h) => h.kind === "DON'T").map((h) => h.text) });
  }
  return { patterns, antipatterns };
}

export function replacePlaceholders(content, provider, commands = []) {
  const p = PROVIDER_PLACEHOLDERS[provider] || PROVIDER_PLACEHOLDERS.cursor;
  const commandList = commands.filter((c) => !['impeccable', 'teach-impeccable', 'i-impeccable', 'i-teach-impeccable'].includes(c)).map((c) => `/${c}`).join(', ');
  return content
    .replace(/\{\{model\}\}/g, p.model)
    .replace(/\{\{config_file\}\}/g, p.config_file)
    .replace(/\{\{ask_instruction\}\}/g, p.ask_instruction)
    .replace(/\{\{available_commands\}\}/g, commandList);
}

function escapeRegex(str) { return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

export function prefixSkillReferences(content, prefix, skillNames) {
  if (!prefix || !skillNames?.length) return content;
  const sorted = [...skillNames].sort((a, b) => b.length - a.length);
  let out = content;
  for (const n of sorted) {
    out = out.replace(new RegExp(`/${escapeRegex(n)}(?=[^a-zA-Z0-9_-]|$)`, 'g'), `/${prefix}${n}`);
    out = out.replace(new RegExp(`\\b(the)\\s+${escapeRegex(n)}\\s+skill\\b`, 'gi'), (_, a) => `${a} ${prefix}${n} skill`);
  }
  return out;
}
