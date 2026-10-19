#!/bin/bash
# Cloud sessions don't install the plugins enabled in .claude/settings.json,
# so install Superpowers here. Local CLI sessions handle it themselves.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

if claude plugin list 2>/dev/null | grep -q "superpowers@superpowers-marketplace"; then
  exit 0
fi

# Never block the session from starting if GitHub or the marketplace is unreachable.
claude plugin marketplace add obra/superpowers-marketplace >&2 \
  && claude plugin install superpowers@superpowers-marketplace >&2 \
  || echo "session-start: could not install the superpowers plugin" >&2
exit 0
