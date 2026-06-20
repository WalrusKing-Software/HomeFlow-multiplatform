#!/bin/sh
# Pre-commit secret scanner — blocks commits containing secrets or .env files.
# Called from .husky/pre-commit

STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACM 2>/dev/null)

if [ -z "$STAGED_FILES" ]; then
  exit 0
fi

FAILED=0

for FILE in $STAGED_FILES; do
  if [ "$FILE" = "scripts/check-secrets.sh" ]; then
    continue
  fi
  BASENAME=$(basename "$FILE")

  # Block committing any .env file (belt-and-suspenders on top of .gitignore)
  case "$BASENAME" in
    .env.example)
      ;;
    .env | .env.*)
      echo "[secret-scan] ERROR: Refusing to commit env file: $FILE"
      FAILED=1
      continue
      ;;
  esac

  # Read staged content; skip if unreadable (binary, deleted, etc.)
  CONTENT=$(git show ":$FILE" 2>/dev/null) || continue

  # PEM private keys — real headers appear at the start of a line
  MATCHES=$(echo "$CONTENT" | grep -n -- '^-----BEGIN.*PRIVATE KEY' 2>/dev/null)
  if [ -n "$MATCHES" ]; then
    echo "[secret-scan] ERROR: Private key found in $FILE"
    echo "$MATCHES" | while IFS= read -r line; do
      echo "  line $line"
    done
    FAILED=1
  fi

  # AWS access key IDs
  MATCHES=$(echo "$CONTENT" | grep -nE 'AKIA[0-9A-Z]{16}' 2>/dev/null)
  if [ -n "$MATCHES" ]; then
    echo "[secret-scan] ERROR: AWS access key ID found in $FILE"
    echo "$MATCHES" | while IFS= read -r line; do
      LINENO=$(echo "$line" | cut -d: -f1)
      LINETEXT=$(echo "$line" | cut -d: -f2- | sed 's/[^ ]*AKIA[0-9A-Z]*/[REDACTED]/g')
      echo "  line $LINENO: $LINETEXT"
    done
    FAILED=1
  fi

  # Common secret variable assignments (e.g. API_KEY="abc123", password: "hunter2")
  # Exclude obvious placeholders and the .env.example file
  case "$FILE" in
    *.env.example | *env.example*)
      ;;
    *)
      MATCHES=$(echo "$CONTENT" | grep -niE \
        '(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|private[_-]?key)\s*[:=]\s*["\x27][^"'\''<>\$\{]{8,}' 2>/dev/null)
      if [ -n "$MATCHES" ]; then
        echo "[secret-scan] ERROR: Possible hardcoded secret in $FILE"
        echo "$MATCHES" | while IFS= read -r line; do
          LINENO=$(echo "$line" | cut -d: -f1)
          LINETEXT=$(echo "$line" | cut -d: -f2- | sed -E "s/(['\"])[^'\"]{8,}\1/\1[REDACTED]\1/g")
          echo "  line $LINENO: $LINETEXT"
        done
        FAILED=1
      fi
      ;;
  esac
done

if [ "$FAILED" -eq 1 ]; then
  echo ""
  echo "Commit blocked: potential secrets detected in staged files."
  echo "Remove secrets and try again. If this is a false positive, review the"
  echo "patterns in scripts/check-secrets.sh."
  exit 1
fi

exit 0
