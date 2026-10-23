#!/usr/bin/env bash
# Usage: tools/notice.sh [-t title] [-s seconds] text…  — shows a card over whatever is on the TV (\n = new line)
source "$(dirname "$0")/env.sh"
title= seconds=8
while getopts t:s: o; do
  case $o in t) title=$OPTARG ;; s) seconds=$OPTARG ;; *) exit 2 ;; esac
done
shift $((OPTIND - 1))
# adb shell joins its arguments into one command line for the TV's shell, so quote for that shell.
q() { local sq="'\\''"; printf "'%s'" "${1//\'/$sq}"; }
tv am broadcast -a com.argon.launcher.NOTICE -p com.argon.launcher \
  --es text "$(q "$*")" --es title "$(q "$title")" --ei seconds "$seconds" >/dev/null
