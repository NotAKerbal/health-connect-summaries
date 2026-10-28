# Contributing

Thanks for helping. Keep changes small and focused.

- **No personal data.** Never include health data, screenshots of real data, Site URLs, connection
  codes, tokens, logs with identifiers, or keystores in issues, pull requests, or commits.
  Synthetic test data belongs only under `cloud/test/` and `android/app/src/test/`.
- **Before a pull request**, run the checks in [docs/development.md](docs/development.md) and
  `python3 scripts/check-public-tree.py`.
- **Keep the privacy model.** Owner scoping, the default-off ChatGPT access switch, the pinned and
  confirmed origin rules, and their tests are required.
- **Migrations are append-only.** Add a new file; never edit an applied one.
- **Security issues** go through [SECURITY.md](SECURITY.md), not public issues.

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
