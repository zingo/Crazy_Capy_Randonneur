Push the current branch and open a GitHub pull request against `main`.

1. `git status --short` and `git log origin/main..HEAD --oneline`. If the working
   tree is dirty, stop and ask the user whether to commit first (inspect
   `git diff` before staging; never commit secrets). If there are no commits
   ahead of `main`, stop and say so.
2. Note the branch with `git branch --show-current`.
   - If it is `main` (or the base branch), create a feature branch first:
     `git switch -c <short-descriptive-name>`.
3. Confirm the remote: `git remote -v` (expect `origin` =
   `https://github.com/zingo/Crazy_Capy_Randonneur.git`). Default to `origin`.
4. Push: `git push -u origin HEAD`.
5. Open the PR against `main`, reusing the commit message:

   ```bash
   gh pr create --base main --fill
   # or pass an explicit --title/--body for a cleaner description
   ```

6. Print the PR URL that `gh` returns.

Notes:
- `gh` is installed and authenticated (account `zingo`). There is also a
  `partymola` remote; do not push there unless the user asks.
- Never force-push, amend, or rebase unless the user explicitly asks.
