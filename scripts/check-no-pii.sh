#!/usr/bin/env bash
# Fails if tracked text files contain e-mail addresses, local home-directory paths, or device
# identifier APIs. Run from the repository root (CI job "privacy-check").
#
# Allowed e-mail forms: GitHub noreply addresses and the Claude co-author noreply (the latter only
# appears in commit trailers, which this script does not scan; commit metadata is checked below).
# Excluded: .git/, binary files, this script, and docs/PLAN.md (which quotes these patterns).
set -euo pipefail

email_re='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
email_allow='(noreply@github\.com|@users\.noreply\.github\.com)'
path_re='(/home/[a-z]|/Users/|C:\\Users)'
device_re='(ANDROID_ID|Settings\.Secure\.ANDROID_ID|getSerial\(|Build\.SERIAL|getImei)'

fail=0
while IFS= read -r -d '' f; do
  case "$f" in
    scripts/check-no-pii.sh|docs/PLAN.md) continue ;;
  esac
  # Skip binary files (grep -I treats them as non-matching).
  if ! grep -Iq . "$f" 2>/dev/null; then continue; fi

  if hits=$(grep -nEo "$email_re" "$f" | grep -vE "$email_allow"); then
    echo "E-mail address in $f:"; echo "$hits"; fail=1
  fi
  if hits=$(grep -nE "$path_re" "$f"); then
    echo "Local user path in $f:"; echo "$hits"; fail=1
  fi
  if hits=$(grep -nE "$device_re" "$f"); then
    echo "Device identifier API in $f:"; echo "$hits"; fail=1
  fi
done < <(git ls-files -z)

# Commit metadata: authors and committers must be the project identity (or a GitHub noreply).
if git rev-parse --verify HEAD >/dev/null 2>&1; then
  bad=$(git log --format='%ae%n%ce' | sort -u | grep -vE "$email_allow" || true)
  if [ -n "$bad" ]; then
    echo "Commit author/committer e-mails that are not GitHub noreply addresses:"; echo "$bad"; fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "Privacy check FAILED"; exit 1
fi
echo "Privacy check passed"
