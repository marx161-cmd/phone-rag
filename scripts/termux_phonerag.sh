#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="com.termux.phonerag"
SERVICE="$PKG/.PhoneRagService"
RECEIVER="$PKG/.PhoneRagReceiver"
BASE="/sdcard/Android/data/$PKG/files"
INBOX="$BASE/inbox"
RESULT="$BASE/last-result.json"

AM="${AM:-/system/bin/am}"
SU="${SU:-su}"

usage() {
  cat >&2 <<'EOF'
usage:
  phonerag health
  phonerag start
  phonerag status
  phonerag query "text" [top_k]
  phonerag query-file FILE [top_k]
  phonerag index FILE [title] [source]
  phonerag index-dir DIR_OR_FILE [...]
  phonerag index-paths FILE_WITH_PATHS
  phonerag clear-result

This is the phone-native Termux steering path. It talks to the Phone RAG APK
through Android intents and reads /sdcard/Android/data/com.termux.phonerag/files/last-result.json.
EOF
}

ensure_dirs() {
  root_sh "mkdir -p $(sq "$INBOX")"
}

clear_result() {
  root_sh "rm -f $(sq "$RESULT")"
}

wait_result() {
  local timeout="${1:-120}"
  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if root_sh "test -s $(sq "$RESULT")"; then
      local output
      output="$(root_sh "cat $(sq "$RESULT")")"
      printf '%s\n' "$output"
      printf '\n'
      result_is_ok "$output"
      return $?
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "timed out waiting for $RESULT" >&2
  return 1
}

safe_name() {
  basename "$1" | tr '/[:space:]' '__' | tr -cd 'A-Za-z0-9._-'
}

sq() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

root_sh() {
  "$SU" -c "$1" </dev/null
}

result_is_ok() {
  local json="$1"
  printf '%s\n' "$json" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'
}

cmd_health() {
  ensure_dirs
  clear_result
  "$AM" broadcast --user 0 -a "$PKG.HEALTH" -n "$RECEIVER" >/dev/null
  wait_result 20
}

cmd_start() {
  "$AM" start --user 0 -n "$PKG/.MainActivity" >/dev/null
}

cmd_status() {
  echo "package=$PKG"
  echo "result=$RESULT"
  if root_sh "test -s $(sq "$RESULT")"; then
    echo "last_result=yes"
  else
    echo "last_result=no"
  fi
  "$AM" start-foreground-service --user 0 -n "$SERVICE" >/dev/null 2>&1 || true
  if command -v pidof >/dev/null 2>&1; then
    pidof "$PKG" || true
  fi
}

cmd_query() {
  local query="${1:-}"
  local top_k="${2:-6}"
  if [ -z "$query" ]; then
    usage
    return 2
  fi
  ensure_dirs
  clear_result
  "$AM" start-foreground-service --user 0 \
    -n "$SERVICE" \
    -a "$PKG.QUERY" \
    --es query "$query" \
    --ei top_k "$top_k" >/dev/null
  wait_result 120
}

cmd_query_file() {
  local file="${1:-}"
  local top_k="${2:-6}"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    usage
    return 2
  fi
  ensure_dirs
  clear_result
  local remote="$INBOX/query-$(date +%s)-$(safe_name "$file")"
  root_sh "cp $(sq "$file") $(sq "$remote")"
  "$AM" start-foreground-service --user 0 \
    -n "$SERVICE" \
    -a "$PKG.QUERY" \
    --es query_file "$remote" \
    --ei top_k "$top_k" >/dev/null
  wait_result 120
}

cmd_index() {
  local file="${1:-}"
  local title="${2:-}"
  local source="${3:-}"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    usage
    return 2
  fi
  title="${title:-$(basename "$file")}"
  source="${source:-$file}"
  ensure_dirs
  clear_result
  local stamp
  stamp="$(date +%s)"
  local remote="$INBOX/$stamp-$(safe_name "$file")"
  root_sh "cp $(sq "$file") $(sq "$remote")"
  "$AM" start-foreground-service --user 0 \
    -n "$SERVICE" \
    -a "$PKG.INDEX" \
    --es text_file "$remote" \
    --es title "$title" \
    --es source "$source" >/dev/null
  wait_result 600
}

is_indexable_file() {
  local file="$1"
  [ -f "$file" ] || return 1
  [ "$(wc -c < "$file")" -le 2097152 ] || return 1
  case "${file##*.}" in
    txt|TXT|md|MD|markdown|MARKDOWN|rst|RST|org|ORG|json|JSON|jsonl|JSONL|yaml|YAML|yml|YML|toml|TOML|ini|INI|cfg|CFG|conf|CONF|log|LOG|csv|CSV|tsv|TSV|xml|XML|html|HTML|htm|HTM|css|CSS|js|JS|ts|TS|tsx|TSX|jsx|JSX|kt|KT|java|JAVA|py|PY|sh|SH|bash|BASH|zsh|ZSH|fish|FISH|c|C|cc|CC|cpp|CPP|h|H|hpp|HPP|rs|RS|go|GO|sql|SQL)
      return 0
      ;;
  esac
  return 1
}

find_indexable_files() {
  local path
  for path in "$@"; do
    if [ -f "$path" ]; then
      is_indexable_file "$path" && printf '%s\0' "$path"
    elif [ -d "$path" ]; then
      find "$path" \
        \( -path '*/.git' -o -path '*/node_modules' -o -path '*/build' -o -path '*/.gradle' -o -path '*/__pycache__' \) -prune \
        -o -type f -size -2M -print0 |
        while IFS= read -r -d '' candidate; do
          is_indexable_file "$candidate" && printf '%s\0' "$candidate"
        done
    else
      echo "skipping missing path: $path" >&2
    fi
  done
}

cmd_index_dir() {
  if [ "$#" -eq 0 ]; then
    set -- .
  fi

  local count=0
  local failed=0
  while IFS= read -r -d '' file; do
    count=$((count + 1))
    echo "[$count] indexing $file" >&2
    if cmd_index "$file" "$(basename "$file")" "$file"; then
      :
    else
      failed=$((failed + 1))
      echo "failed: $file" >&2
    fi
  done < <(find_indexable_files "$@")

  echo "indexed_attempted=$count failed=$failed" >&2
  [ "$failed" -eq 0 ]
}

cmd_index_paths() {
  local list_file="${1:-}"
  if [ -z "$list_file" ] || [ ! -f "$list_file" ]; then
    usage
    return 2
  fi

  local count=0
  local failed=0
  while IFS= read -r file || [ -n "$file" ]; do
    [ -n "$file" ] || continue
    case "$file" in \#*) continue ;; esac
    if ! is_indexable_file "$file"; then
      echo "skipping non-indexable path: $file" >&2
      continue
    fi
    count=$((count + 1))
    echo "[$count] indexing $file" >&2
    if cmd_index "$file" "$(basename "$file")" "$file"; then
      :
    else
      failed=$((failed + 1))
      echo "failed: $file" >&2
    fi
  done < "$list_file"

  echo "indexed_attempted=$count failed=$failed" >&2
  [ "$failed" -eq 0 ]
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
    health) cmd_health "$@" ;;
    start) cmd_start "$@" ;;
    status) cmd_status "$@" ;;
    query) cmd_query "$@" ;;
    query-file) cmd_query_file "$@" ;;
    index) cmd_index "$@" ;;
    index-dir) cmd_index_dir "$@" ;;
    index-paths) cmd_index_paths "$@" ;;
    clear-result) clear_result ;;
    -h|--help|help|"") usage; [ -n "$cmd" ] ;;
    *) usage; return 2 ;;
  esac
}

main "$@"
