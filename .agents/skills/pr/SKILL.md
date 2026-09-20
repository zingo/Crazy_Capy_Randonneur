---
name: pr
description: Use when the user says "pr" or "/pr" meaning they want the current local commit(s) pushed and opened as a GitHub pull request against main. Pushes the branch and runs gh pr create.
---

# Open a pull request

When the user types "pr" or "/pr", publish the current branch and open a GitHub
PR against `main`.

1. Check state:
   - `git status --short` — if dirty, stop and ask whether to commit first
     (inspect `git diff`; never commit secrets).
   - `git log origin/main..HEAD --oneline` — if empty, there is nothing to PR.
2. Get the branch: `git branch --show-current`.
   - If it is `main`, create a feature branch first:
     `git switch -c <short-descriptive-name>`.
3. Push the branch: `git push -u origin HEAD`.
4. Create the PR:

   ```bash
   gh pr create --base main --fill
   ```

   Prefer an explicit `--title`/`--body` when `--fill` would produce a poor
   description. Reuse the commit message for a single-commit branch.
5. Return the PR URL.

Notes:
- `gh` is installed and authenticated (account `zingo`). Remotes are `origin`
  (zingo) and `partymola`; default to `origin`.
- Do not force-push, amend, or rebase unless the user asks.
