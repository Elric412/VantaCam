export const FILE_DOWNLOAD_PROVIDER_CONFIG_DIRS = {
  'claude-code': '.claude',
  cursor: '.cursor',
  gemini: '.gemini',
  codex: '.codex',
  agents: '.agents',
  kiro: '.kiro',
  opencode: '.opencode',
  pi: '.pi',
};

export const FILE_DOWNLOAD_PROVIDERS = Object.keys(FILE_DOWNLOAD_PROVIDER_CONFIG_DIRS);
export const BUNDLE_DOWNLOAD_PROVIDERS = ['universal', 'universal-prefixed'];
export const DOWNLOAD_PROVIDERS = [...FILE_DOWNLOAD_PROVIDERS, ...BUNDLE_DOWNLOAD_PROVIDERS];
