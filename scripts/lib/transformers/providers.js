export const PROVIDERS = {
  cursor: {
    provider: 'cursor', configDir: '.cursor', displayName: 'Cursor',
    frontmatterFields: ['license', 'compatibility', 'metadata', 'allowed-tools'],
  },
  'claude-code': {
    provider: 'claude-code', configDir: '.claude', displayName: 'Claude Code',
    frontmatterFields: ['user-invocable', 'argument-hint', 'license', 'compatibility', 'metadata', 'allowed-tools'],
  },
  gemini: {
    provider: 'gemini', configDir: '.gemini', displayName: 'Gemini',
    frontmatterFields: ['license', 'compatibility', 'metadata', 'allowed-tools'],
  },
  codex: {
    provider: 'codex', configDir: '.codex', displayName: 'Codex',
    frontmatterFields: ['license', 'compatibility', 'metadata', 'allowed-tools'],
  },
  agents: {
    provider: 'agents', configDir: '.agents', displayName: 'GitHub Agents',
    frontmatterFields: ['license', 'compatibility', 'metadata', 'allowed-tools'],
  },
  kiro: {
    provider: 'kiro', configDir: '.kiro', displayName: 'Kiro',
    frontmatterFields: ['license', 'compatibility', 'metadata', 'allowed-tools'],
  },
};
