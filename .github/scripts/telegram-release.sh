#!/usr/bin/env bash
# Announce a published release on Telegram: the release notes as a chat message and the
# APK as a document (its SHA-256 rides in the caption). Both workflows call this after
# `gh release create`, with RELEASE_VERSION / RELEASE_CHANNEL / RELEASE_NOTES pointing
# at what was just published.
#
# Needs two repository secrets: TELEGRAM_BOT_TOKEN (@BotFather) and TELEGRAM_CHAT_ID
# (channel, group or user id — for a channel the bot must be an admin). With either
# absent the step logs a notice and succeeds: a release must not fail because chat
# plumbing is unconfigured (fresh setup, forks).
#
# Telegram's Bot API caps messages at 4096 chars and uploads at 50 MB — notes longer
# than the cap are cut at a line boundary and the full text is attached as a document
# next to the APK.
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${RELEASE_CHANNEL:?RELEASE_CHANNEL is required}"
: "${RELEASE_NOTES:?RELEASE_NOTES is required}"

if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CHAT_ID:-}" ]; then
  echo "::notice::Telegram not configured (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID); skipping announcement"
  exit 0
fi

API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"
RELEASE_URL="https://github.com/${GITHUB_REPOSITORY}/releases/tag/v${RELEASE_VERSION}"
APK="Kaorios-RePatcher-${RELEASE_VERSION}.apk"

send() {
  # --fail-with-body: a Telegram 4xx carries the reason in the JSON body; print it
  # instead of a bare curl exit code. Three retries smooth over rate limits.
  # Deliberately NOT -fsS: -f and --fail-with-body are mutually exclusive
  # (curl/curl#9221) and the runner's newer curl rejects the pair with
  # "option --fail-with-body: is badly used here".
  local endpoint=$1
  shift
  local resp
  if ! resp=$(curl -sS --fail-with-body --retry 3 --retry-delay 3 \
      -X POST "${API}/${endpoint}" "$@"); then
    echo "${endpoint} failed:" >&2
    echo "${resp}" >&2
    exit 1
  fi
}

# The message: header + notes, headings flattened to plain text (no parse_mode, so the
# markdown in changelog bullets is shown literally — safer than escaping it).
{
  echo "Kaorios RePatcher ${RELEASE_VERSION} (${RELEASE_CHANNEL})"
  echo "${RELEASE_URL}"
  echo
  sed -e 's/^#\+ //' -e 's/\r$//' "${RELEASE_NOTES}"
} > announcement.txt

MESSAGE_LIMIT=4000
NOTES_DOC=0
if [ "$(wc -c < announcement.txt)" -gt "$MESSAGE_LIMIT" ]; then
  # Cut at a line boundary so the visible half stays readable; the full notes go out
  # as a document below.
  head -c "$MESSAGE_LIMIT" announcement.txt | head -n -1 > announcement-cut.txt
  {
    cat announcement-cut.txt
    echo
    echo "…truncated here; full notes at the release page and attached."
  } > announcement.txt
  NOTES_DOC=1
fi

send sendMessage \
  -d "chat_id=${TELEGRAM_CHAT_ID}" \
  --data-urlencode "text@announcement.txt"

send sendDocument \
  -F "chat_id=${TELEGRAM_CHAT_ID}" \
  -F "document=@${APK}" \
  -F "caption=Kaorios RePatcher ${RELEASE_VERSION} — SHA-256 $(sha256sum "${APK}" | cut -d' ' -f1)"

if [ "${NOTES_DOC}" = "1" ]; then
  send sendDocument \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "document=@${RELEASE_NOTES}" \
    -F "caption=Kaorios RePatcher ${RELEASE_VERSION} — full release notes"
fi
