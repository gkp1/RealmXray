#!/usr/bin/env bash
# Pulls the latest changes from the original upstream project (X-com/RealmShark)
# into whichever branch/worktree this is run from.
#
#   ./update-from-upstream.sh          (from the repo root)  -> merges upstream/realmshark into main
#   cd tomato && ../update-from-upstream.sh                  -> merges upstream/tomato into tomato
#
# "upstream" here is a read-only remote (push disabled) - it is never pushed to.
# Local commits are never pushed there either; only origin (this repo) is.
set -euo pipefail

CURRENT_BRANCH="$(git branch --show-current)"

case "$CURRENT_BRANCH" in
    main)
        UPSTREAM_BRANCH="realmshark"
        ;;
    tomato)
        UPSTREAM_BRANCH="tomato"
        ;;
    *)
        echo "error: don't know which upstream branch maps to local branch '$CURRENT_BRANCH'" >&2
        echo "       expected to be run from 'main' or 'tomato'." >&2
        exit 1
        ;;
esac

echo "==> Fetching upstream"
git fetch upstream

echo "==> Merging upstream/$UPSTREAM_BRANCH into local $CURRENT_BRANCH"
git merge "upstream/$UPSTREAM_BRANCH"

echo "==> Done. Push with: git push origin $CURRENT_BRANCH"
