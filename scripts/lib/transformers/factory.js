import path from 'node:path';
import { ensureDir, cleanDir, writeFile, generateYamlFrontmatter, replacePlaceholders, prefixSkillReferences } from '../utils.js';

function buildFrontmatter(skill, allow) {
  const fm = { name: skill.name, description: skill.description };
  if (allow.includes('user-invocable') && skill.userInvocable) fm['user-invocable'] = true;
  if (allow.includes('argument-hint') && skill.userInvocable && skill.argumentHint) fm['argument-hint'] = skill.argumentHint;
  if (allow.includes('license') && skill.license) fm.license = skill.license;
  if (allow.includes('compatibility') && skill.compatibility) fm.compatibility = skill.compatibility;
  if (allow.includes('metadata') && skill.metadata) fm.metadata = skill.metadata;
  if (allow.includes('allowed-tools') && skill.allowedTools) fm['allowed-tools'] = skill.allowedTools;
  return fm;
}

export function createTransformer(config) {
  return function transform(skills, outRoot, options = {}) {
    const prefix = options.prefix || '';
    const outputSuffix = options.outputSuffix || '';
    const providerOut = `${config.provider}${outputSuffix}`;
    const base = path.join(outRoot, providerOut, config.configDir, 'skills');
    cleanDir(base);
    ensureDir(base);

    const allSkillNames = skills.map((s) => s.name);
    let invocable = 0;

    for (const srcSkill of skills) {
      const skill = { ...srcSkill, name: `${prefix}${srcSkill.name}` };
      if (skill.userInvocable) invocable++;
      const fm = buildFrontmatter(skill, config.frontmatterFields || []);
      let body = replacePlaceholders(srcSkill.body || '', config.placeholderProvider || config.provider, allSkillNames);
      body = prefixSkillReferences(body, prefix, allSkillNames);
      if (config.bodyTransform) body = config.bodyTransform(body);

      const file = path.join(base, skill.name, 'SKILL.md');
      writeFile(file, `${generateYamlFrontmatter(fm)}\n\n${body}`.trimEnd() + '\n');

      for (const ref of srcSkill.references || []) {
        writeFile(path.join(base, skill.name, 'reference', `${ref.name}.md`), ref.content);
      }
    }

    console.log(`✓ ${config.displayName}: ${skills.length} skills, ${invocable} user-invocable`);
  };
}
