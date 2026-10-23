TV="${TV:-192.168.2.13:5555}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$ROOT/backup/disabled_by_tools.txt"
adb connect "$TV" >/dev/null 2>&1
tv() { adb -s "$TV" shell "$@"; }
