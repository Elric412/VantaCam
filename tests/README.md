# Test runtime lanes

- Files annotated `// @runtime=bun` require `bun test`.
- Files annotated `// @runtime=node` require `node --test`.
- Default: Bun. Use `npm test` (mapped to `bun test` in the root package.json).