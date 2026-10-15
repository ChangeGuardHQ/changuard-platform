# Project conventions

- Always include concise inline documentation when creating or modifying code,
  configuration, and scripts.
- Explain intent, non-obvious behavior, assumptions, and important dependencies
  near the relevant implementation. Use the file's native comment syntax.
- Keep existing comments accurate when behavior changes. Avoid comments that
  merely repeat obvious names or syntax.
- Before committing or pushing, review the staged paths and diff. Keep generated
  output, caches, local credentials, and unrelated changes out of the commit.
  Push only the intended branch; retain required source assets, lockfiles,
  Maven wrappers, tests, documentation, and credential-free example configuration.
