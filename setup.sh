#!/usr/bin/env bash
# NavMap setup: puts NavMap on a Garmin Forerunner 165 (or 165 Music) and NavBridge on an Android phone, with
# your own Google Maps key. It asks one thing at a time, and it's safe to run again: it checks each step and
# redoes only what's missing.
#
#   bash setup.sh                  everything, with the published app files
#   bash setup.sh --from-source    build both apps on this computer first (needs a Garmin developer sign-in)
#   bash setup.sh key|phone|watch|test    one step again
#
# The tools it needs go in ~/.cache/navmap, nothing system-wide unless you say yes. Your key is kept in
# ~/.config/navmap, readable only by you. Runs on macOS and Linux, with the bash that comes with either.
set -Eeuo pipefail

REPO=Cube-VG/navmap-watch
FILES_URL="${NAVMAP_FILES_URL:-https://github.com/$REPO/releases/latest/download}"
CONF="$HOME/.config/navmap"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/navmap"
SECRETS="$CONF/secrets.properties"
STATE="$CONF/setup.properties"
PKG=com.cubevg.navbridge
GARMIN_CONNECT=com.garmin.android.apps.connectmobile
GOOGLE_MAPS=com.google.android.apps.maps
PROVIDER=content://com.cubevg.navbridge.setup
SDK_MANAGER_VERSION=0.8.4
CIQ_SDK_VERSION=9.2.0
# Python for gcloud where the computer has none new enough: python-build-standalone, the builds uv uses.
PY_BUILD=20261013
PY_VERSION=3.13.16
OS="$(uname -s)"
ARCH="$(uname -m)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- talking to the user ---------------------------------------------------------------------------------------

if [ -t 1 ] && [ "${TERM:-dumb}" != dumb ]; then
    BOLD=$'\033[1m' DIM=$'\033[2m' GREEN=$'\033[32m' YELLOW=$'\033[33m' RED=$'\033[31m' PLAIN=$'\033[0m'
else
    BOLD='' DIM='' GREEN='' YELLOW='' RED='' PLAIN=''
fi
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
    *UTF-8* | *utf-8* | *UTF8* | *utf8*) TICK='✓' CROSS='✗' ;;
    *) TICK='OK' CROSS='X' ;;
esac

say() { printf '%s\n' "$*"; }
ok() { printf '%s%s%s %s\n' "$GREEN" "$TICK" "$PLAIN" "$*"; }
warn() { printf '%s!%s %s\n' "$YELLOW" "$PLAIN" "$*"; }
why() { printf '%s  Why: %s%s\n' "$DIM" "$*" "$PLAIN"; }
die() {
    printf '\n%s%s %s%s\n' "$RED" "$CROSS" "$*" "$PLAIN" >&2
    DONE=yes
    exit 1
}

STEP=0
STEPS=1
step() {
    STEP=$((STEP + 1))
    if [ "$STEPS" -gt 1 ]; then
        printf '\n%s== Step %s of %s: %s ==%s\n' "$BOLD" "$STEP" "$STEPS" "$1" "$PLAIN"
    else
        printf '\n%s== %s ==%s\n' "$BOLD" "$1" "$PLAIN"
    fi
}

# One line of input into REPLY. Input that has ended (a closed terminal) stops the script instead of looping.
read_line() {
    if ! IFS= read -r REPLY; then
        die "No answer (the input ended). Run bash setup.sh again in a terminal."
    fi
}

# ask_yes "Question?" "Why it's asked" [Y|N]: true for yes. Enter takes the default.
ask_yes() {
    local default="${3:-Y}" hint="[Y/n]"
    if [ "$default" = N ]; then hint="[y/N]"; fi
    printf '\n%s%s%s %s\n' "$BOLD" "$1" "$PLAIN" "$hint"
    if [ -n "${2:-}" ]; then why "$2"; fi
    while true; do
        printf '> '
        read_line
        case "$REPLY" in
            '') [ "$default" = Y ] && return 0 || return 1 ;;
            [Yy]*) return 0 ;;
            [Nn]*) return 1 ;;
            *) say "Type y for yes or n for no." ;;
        esac
    done
}

# choose "Question?" "Why it's asked" option...: the number picked, from 1, in CHOICE.
choose() {
    local question="$1" reason="$2" i=1 option
    shift 2
    printf '\n%s%s%s\n' "$BOLD" "$question" "$PLAIN"
    if [ -n "$reason" ]; then why "$reason"; fi
    for option in "$@"; do
        printf '  %s) %s\n' "$i" "$option"
        i=$((i + 1))
    done
    while true; do
        printf '> '
        read_line
        case "$REPLY" in
            '' | *[!0-9]*) ;;
            *) if [ "$REPLY" -ge 1 ] && [ "$REPLY" -le $# ]; then
                CHOICE=$REPLY
                return 0
            fi ;;
        esac
        say "Type a number from 1 to $#."
    done
}

pause() {
    printf '\n%s%s%s\n' "$BOLD" "$1" "$PLAIN"
    printf 'Press Enter to carry on. '
    read_line
}

open_url() {
    say "  $1"
    if [ "$OS" = Darwin ]; then
        open "$1" >/dev/null 2>&1 || true
    elif [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$1" >/dev/null 2>&1 || true
    fi
}

# A failure the script didn't expect still ends with a line on what to do. die() has said that already.
DONE=''
FAILED_AT=''
on_exit() {
    local status=$?
    if [ "$status" -ne 0 ] && [ -z "$DONE" ]; then
        printf '\n%s%s Something went wrong (setup.sh, line %s).%s Run bash setup.sh again: it carries on from where it is.\n' \
            "$RED" "$CROSS" "${FAILED_AT:-?}" "$PLAIN" >&2
        printf 'If it keeps happening, open an issue at https://github.com/%s/issues with the lines above.\n' "$REPO" >&2
    fi
}
trap 'FAILED_AT=${FAILED_AT:-$LINENO}' ERR
trap on_exit EXIT
# Ctrl-C. A program running in the background ignores it, so that one is stopped here.
BG_PID=''
on_int() {
    DONE=yes
    if [ -n "$BG_PID" ]; then kill "$BG_PID" 2>/dev/null || true; fi
    printf '\n%sStopped.%s Run bash setup.sh again to carry on.\n' "$YELLOW" "$PLAIN"
    exit 130
}
trap on_int INT

# ---- small tools -----------------------------------------------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

# first_dir PATH...: the first one that is a directory, for globs that may match nothing.
first_dir() {
    local dir
    for dir in "$@"; do
        if [ -d "$dir" ]; then
            printf '%s' "$dir"
            return
        fi
    done
}

# fetch URL FILE: downloads with a progress bar, keeping no half-downloaded file.
fetch() {
    mkdir -p "$(dirname "$2")"
    if ! curl -fL --retry 3 --connect-timeout 20 --progress-bar -o "$2.part" "$1"; then
        rm -f "$2.part"
        return 1
    fi
    mv "$2.part" "$2"
}

unzip_to() {
    mkdir -p "$2"
    if have unzip; then
        unzip -q -o "$1" -d "$2"
    else
        python3 -I -m zipfile -e "$1" "$2"
    fi
}

sha256() {
    if have sha256sum; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# prop NAME FILE: a NAME=value line's value, or nothing.
prop() {
    if [ -f "$2" ]; then sed -n "s/^$1=//p" "$2" | tail -1; fi
}

# set_prop NAME VALUE FILE: keeps the file's other lines, and the file readable only by you.
set_prop() {
    local tmp
    if [ ! -d "$CONF" ]; then
        mkdir -p "$CONF"
        chmod 700 "$CONF"
    fi
    tmp="$(mktemp "$CONF/.setup.XXXXXX")"
    if [ -f "$3" ]; then grep -v "^$1=" "$3" >"$tmp" || true; fi
    printf '%s=%s\n' "$1" "$2" >>"$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$3"
}

# ---- step: the devices -----------------------------------------------------------------------------------------

check_computer() {
    case "$OS" in
        Darwin | Linux) ;;
        *) die "This script runs on a Mac or a Linux computer. On Windows, run setup.ps1 in PowerShell instead (see the README)." ;;
    esac
    local missing='' tool
    for tool in curl tar; do
        have "$tool" || missing="$missing $tool"
    done
    if ! have unzip && ! have python3; then missing="$missing unzip"; fi
    if [ -n "$missing" ]; then
        die "This computer needs:$missing. Install it (on Ubuntu: sudo apt install$missing), then run bash setup.sh again."
    fi
    mkdir -p "$CACHE"
}

step_devices() {
    step "Your watch and phone"
    choose "Which watch do you have?" "NavMap is made for the Forerunner 165's screen and doesn't run on other watches." \
        "Forerunner 165" "Forerunner 165 Music" "Another watch"
    case "$CHOICE" in
        1) WATCH=fr165 ;;
        2) WATCH=fr165m ;;
        *) die "Sorry: NavMap only runs on the Garmin Forerunner 165 and Forerunner 165 Music." ;;
    esac
    set_prop WATCH "$WATCH" "$STATE"
    if ! ask_yes "Is your phone an Android phone?" "NavBridge, the half that runs on the phone, is an Android app. There's no iPhone version."; then
        die "Sorry: NavMap needs an Android phone (Android 8 or newer). It can't work with an iPhone."
    fi
    if ! ask_yes "Is the watch paired with the Garmin Connect app on that phone?" "The watch reaches the phone through Garmin Connect."; then
        say "Do that first, on the phone:"
        say "  1. Install Garmin Connect from the Play Store and sign in with your Garmin account."
        say "  2. Add the watch: tap the watch icon or Devices, then Add Device, and follow the steps."
        pause "Pair the watch in Garmin Connect."
    fi
    ok "A $(watch_name "$WATCH") and an Android phone: NavMap works with those."
}

watch_name() {
    if [ "$1" = fr165m ]; then printf 'Forerunner 165 Music'; else printf 'Forerunner 165'; fi
}

# ---- step: the Google Maps key ---------------------------------------------------------------------------------

# check_key KEY: true if the key works for both Maps Static API and Routes API; otherwise the reason in KEY_PROBLEM.
# The key goes to curl on its input, not on the command line, where other users of this computer could see it.
check_key() {
    local code body="$CACHE/key-check.txt"
    code="$(printf 'url = "https://maps.googleapis.com/maps/api/staticmap?center=51.5007,-0.1246&zoom=15&size=64x64&key=%s"\n' "$1" |
        curl -sS -K - -o "$body" -w '%{http_code}' --max-time 20 2>/dev/null)" || code=000
    if [ "$code" != 200 ]; then
        KEY_PROBLEM="Maps Static API answered $code: $(head -c 300 "$body" 2>/dev/null | tr '\n' ' ')"
        rm -f "$body"
        return 1
    fi
    code="$(printf 'header = "X-Goog-Api-Key: %s"\n' "$1" |
        curl -sS -K - -o "$body" -w '%{http_code}' --max-time 20 \
            -H 'Content-Type: application/json' -H 'X-Goog-FieldMask: routes.distanceMeters' \
            -d '{"origin":{"location":{"latLng":{"latitude":51.5007,"longitude":-0.1246}}},"destination":{"location":{"latLng":{"latitude":51.5033,"longitude":-0.1196}}},"travelMode":"WALK"}' \
            https://routes.googleapis.com/directions/v2:computeRoutes 2>/dev/null)" || code=000
    if [ "$code" != 200 ]; then
        KEY_PROBLEM="Routes API answered $code: $(head -c 300 "$body" 2>/dev/null | tr '\n' ' ')"
        rm -f "$body"
        return 1
    fi
    rm -f "$body"
}

# A new key, or APIs just switched on, can take a few minutes to work.
wait_for_key() {
    local tries=0
    while ! check_key "$1"; do
        tries=$((tries + 1))
        if [ "$tries" -ge 30 ]; then return 1; fi
        if [ "$tries" = 1 ]; then say "Google takes a minute or two to switch a new key on. Waiting (up to 5 minutes)…"; fi
        sleep 10
    done
}

step_key() {
    step "Your Google Maps key"
    say "The map pictures and the route line come from Google Maps Platform, billed to your own Google account."
    say "One person's riding normally stays inside Google's free monthly allowance (see the README), but Google"
    say "wants a payment card on file before it hands out a key."
    MAPS_KEY="$(prop MAPS_API_KEY "$SECRETS")"
    if [ -n "$MAPS_KEY" ]; then
        say "Checking the key from last time…"
        if check_key "$MAPS_KEY"; then
            ok "Your Google Maps key works."
            return
        fi
        warn "The saved key doesn't work any more ($KEY_PROBLEM). Let's set up one that does."
    fi
    choose "Do you already have a Google Maps API key you want to use?" \
        "With your own key, Google bills your account and nobody else's, inside your free allowance." \
        "No, make one for me (recommended)" "Yes, I'll paste it"
    if [ "$CHOICE" = 2 ]; then paste_key; else make_key; fi
    set_prop MAPS_API_KEY "$MAPS_KEY" "$SECRETS"
    ok "Key saved in $SECRETS (only you can read it)."
}

paste_key() {
    local re='^AIza[0-9A-Za-z_-]{35}$'
    while true; do
        printf '\n%sPaste your key and press Enter%s (it is not shown)\n> ' "$BOLD" "$PLAIN"
        if ! IFS= read -r -s MAPS_KEY; then die "No answer (the input ended). Run bash setup.sh again in a terminal."; fi
        printf '\n'
        MAPS_KEY="$(printf '%s' "$MAPS_KEY" | tr -d '[:space:]')"
        if [[ ! $MAPS_KEY =~ $re ]]; then
            say "That isn't a Google API key: those start with AIza and are 39 characters long."
            continue
        fi
        say "Checking it…"
        if check_key "$MAPS_KEY"; then return; fi
        say "That key doesn't work yet: $KEY_PROBLEM"
        say "Its Google Cloud project needs billing on and both Maps Static API and Routes API enabled."
        if ask_yes "Let this script make a new key instead?" "It sets all of that up for you." Y; then
            make_key
            return
        fi
    done
}

# Uses the gcloud already installed if it's new enough, else downloads Google's own into the cache.
get_gcloud() {
    if have gcloud && gcloud billing --help >/dev/null 2>&1; then
        GCLOUD="$(command -v gcloud)"
        return
    fi
    GCLOUD="$CACHE/google-cloud-sdk/bin/gcloud"
    gcloud_python
    if [ -x "$GCLOUD" ]; then return; fi
    local file
    case "$OS-$ARCH" in
        Linux-x86_64) file=linux-x86_64 ;;
        Linux-aarch64 | Linux-arm64) file=linux-arm ;;
        Darwin-arm64) file=darwin-arm ;;
        Darwin-x86_64) file=darwin-x86_64 ;;
        *) die "Google's command-line tool isn't made for this computer ($OS $ARCH). Make a key by hand (docs/SETUP.md), then run bash setup.sh again and paste it." ;;
    esac
    say "Downloading Google's command-line tool, gcloud (about 55-95 MB)…"
    fetch "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-$file.tar.gz" "$CACHE/gcloud.tar.gz" ||
        die "Couldn't download gcloud. Check the internet connection and run bash setup.sh again."
    tar -xzf "$CACHE/gcloud.tar.gz" -C "$CACHE"
    rm -f "$CACHE/gcloud.tar.gz"
}

# gcloud runs on Python 3.10 to 3.14. Google's download includes one only for x86-64 Linux; elsewhere gcloud uses
# the computer's own if it's new enough, or else a standalone build this keeps in the cache, just for gcloud.
gcloud_python() {
    if [ "$OS-$ARCH" = Linux-x86_64 ]; then return; fi
    local py name target sum
    for name in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
        py="$(command -v "$name" 2>/dev/null)" || continue
        # Until Apple's developer tools are installed, the Mac's python3 only offers to install them.
        if [ "$OS" = Darwin ] && [ "$py" = /usr/bin/python3 ] && ! xcode-select -p >/dev/null 2>&1; then continue; fi
        if "$py" -c 'import sys; sys.exit(not (3, 10) <= sys.version_info[:2] <= (3, 14))' >/dev/null 2>&1; then
            export CLOUDSDK_PYTHON="$py"
            return
        fi
    done
    py="$CACHE/python/bin/python3"
    if [ ! -x "$py" ]; then
        case "$OS-$ARCH" in
            Darwin-arm64) target=aarch64-apple-darwin sum=16a5332fb0dcb77f163fc8f8cbccd95be1644cc53e68e5d2a79f50afd6680a0e ;;
            Darwin-x86_64) target=x86_64-apple-darwin sum=f507ae04eb7747cd97cb484e8c5d83936cbfc52d9b08a1f35c00b47c707b1ae1 ;;
            Linux-aarch64 | Linux-arm64) target=aarch64-unknown-linux-gnu sum=69a30ed26198178732c37b8de0b6b5191479fbacfe526954da776befe38f18e7 ;;
            *) die "gcloud needs Python 3.10 or newer. Install it, then run bash setup.sh again." ;;
        esac
        say "gcloud needs Python 3.10 or newer, which this computer doesn't have. Downloading one just for gcloud (about 30 MB)…"
        fetch "https://github.com/astral-sh/python-build-standalone/releases/download/$PY_BUILD/cpython-$PY_VERSION%2B$PY_BUILD-$target-install_only_stripped.tar.gz" \
            "$CACHE/python.tar.gz" || die "Couldn't download Python. Check the internet connection and run bash setup.sh again."
        if [ "$(sha256 "$CACHE/python.tar.gz")" != "$sum" ]; then
            rm -f "$CACHE/python.tar.gz"
            die "The Python download was damaged (its checksum is wrong). Run bash setup.sh again."
        fi
        rm -rf "$CACHE/python"
        tar -xzf "$CACHE/python.tar.gz" -C "$CACHE"
        rm -f "$CACHE/python.tar.gz"
    fi
    export CLOUDSDK_PYTHON="$py"
}

gc() { "$GCLOUD" "$@"; }

make_key() {
    get_gcloud
    # gcloud keeps this sign-in to itself, so any other gcloud setup on this computer is left as it is.
    export CLOUDSDK_CONFIG="$CONF/gcloud"
    export CLOUDSDK_CORE_DISABLE_USAGE_REPORTING=true
    local account
    account="$(gc auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | sed -n 1p)" || account=''
    if [ -z "$account" ]; then
        printf '\n%sSign in to Google.%s\n' "$BOLD" "$PLAIN"
        why "gcloud makes the project and the key in your account. Sign in with the Google account that should pay for the maps."
        say "Your browser opens Google's sign-in page. If it doesn't, open the link gcloud shows below."
        gc auth login --brief || die "The Google sign-in didn't finish. Run bash setup.sh again to try again."
        account="$(gc auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | sed -n 1p)" || account=''
        [ -n "$account" ] || die "The Google sign-in didn't finish. Run bash setup.sh again to try again."
    fi
    ok "Signed in to Google as $account."
    pick_project
    turn_on_billing
    say "Switching on Maps Static API and Routes API for $PROJECT (about a minute)…"
    gc services enable static-maps-backend.googleapis.com routes.googleapis.com apikeys.googleapis.com \
        --project="$PROJECT" --quiet ||
        die "Google wouldn't switch the APIs on. Run bash setup.sh again; if it fails again, check billing at https://console.cloud.google.com/billing"
    local name out
    name="$(gc services api-keys list --project="$PROJECT" --filter='displayName=NavMap' --format='value(name)' --limit=1 2>/dev/null)" || name=''
    if [ -z "$name" ]; then
        say "Making a key that only works for those two APIs…"
        if ! out="$(gc services api-keys create --project="$PROJECT" --display-name=NavMap \
            --api-target=service=static-maps-backend.googleapis.com --api-target=service=routes.googleapis.com --quiet 2>&1)"; then
            say "$out"
            die "Google wouldn't make the key. Run bash setup.sh again to try again."
        fi
        name="$(gc services api-keys list --project="$PROJECT" --filter='displayName=NavMap' --format='value(name)' --limit=1)"
    fi
    MAPS_KEY="$(gc services api-keys get-key-string "$name" --format='value(keyString)')"
    say "Checking the key…"
    wait_for_key "$MAPS_KEY" || die "The new key doesn't work yet ($KEY_PROBLEM). Wait a few minutes and run bash setup.sh again."
    ok "Your key is made, limited to Maps Static API and Routes API, and it works."
}

pick_project() {
    PROJECT="$(prop GCP_PROJECT "$STATE")"
    if [ -n "$PROJECT" ] && [ "$(gc projects describe "$PROJECT" --format='value(lifecycleState)' 2>/dev/null)" = ACTIVE ]; then
        ok "Using your Google Cloud project $PROJECT."
        return
    fi
    PROJECT="$(gc projects list --filter='name=NavMap AND lifecycleState=ACTIVE' --format='value(projectId)' --limit=1 2>/dev/null)" || PROJECT=''
    if [ -z "$PROJECT" ]; then
        if ask_yes "Make a Google Cloud project called NavMap for the key?" \
            "A project of its own keeps NavMap's key and its costs apart from anything else in your Google account." Y; then
            create_project
        else
            choose_project
        fi
    fi
    set_prop GCP_PROJECT "$PROJECT" "$STATE"
    ok "Using your Google Cloud project $PROJECT."
}

create_project() {
    local id out
    id="navmap-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
    say "Making the project $id…"
    if out="$(gc projects create "$id" --name=NavMap --quiet 2>&1)"; then
        PROJECT="$id"
        return
    fi
    case "$out" in
        *[Tt]erms*)
            say "Google wants you to accept its Cloud terms once, in the browser:"
            open_url "https://console.cloud.google.com/"
            pause "Accept the terms on that page (pick your country and tick the box)."
            create_project
            ;;
        *[Qq]uota* | *exceeded*)
            say "Your account can't make more projects, so pick one of yours."
            choose_project
            ;;
        *)
            say "$out"
            die "Google wouldn't make the project. Run bash setup.sh again to try again."
            ;;
    esac
}

choose_project() {
    local ids=() names=() id name
    while IFS=$'\t' read -r id name; do
        if [ -n "$id" ]; then
            ids+=("$id")
            names+=("$name ($id)")
        fi
    done <<EOF
$(gc projects list --filter='lifecycleState=ACTIVE' --format='value(projectId,name)' --limit=20 2>/dev/null || true)
EOF
    if [ "${#ids[@]}" -eq 0 ]; then die "Your Google account has no projects to use. Run bash setup.sh again and let it make one."; fi
    choose "Which project should hold the key?" "Google bills the maps to that project's billing account." "${names[@]}"
    PROJECT="${ids[$((CHOICE - 1))]}"
}

turn_on_billing() {
    local accounts count account out ids=() names=() id name
    while true; do
        if [ "$(gc billing projects describe "$PROJECT" --format='value(billingEnabled)' 2>/dev/null)" = True ]; then
            ok "Billing is on for $PROJECT."
            return
        fi
        accounts="$(gc billing accounts list --filter=open=true --format='value(name,displayName)' 2>/dev/null)" || accounts=''
        count="$(printf '%s' "$accounts" | grep -c . || true)"
        if [ "$count" -eq 0 ]; then
            printf '\n%sYour Google account needs a billing account.%s\n' "$BOLD" "$PLAIN"
            why "Google Maps Platform only works with billing on. Its free monthly allowance comes first; past it, the card pays."
            say "This page makes one (you give Google a payment card):"
            open_url "https://console.cloud.google.com/billing/create"
            pause "Make the billing account on that page."
            continue
        fi
        if [ "$count" -eq 1 ]; then
            account="$(printf '%s' "$accounts" | cut -f1)"
        else
            ids=()
            names=()
            while IFS=$'\t' read -r id name; do
                ids+=("$id")
                names+=("$name")
            done <<EOF
$accounts
EOF
            choose "Which billing account should pay for NavMap's maps?" "Each Google Cloud project bills one account." "${names[@]}"
            account="${ids[$((CHOICE - 1))]}"
        fi
        account="${account#billingAccounts/}"
        say "Turning billing on for $PROJECT…"
        if ! out="$(gc billing projects link "$PROJECT" --billing-account="$account" --quiet 2>&1)"; then
            say "$out"
            say "Google wouldn't link that billing account. You need to be its administrator, and it must be open."
            pause "Fix it at https://console.cloud.google.com/billing (or make a new billing account there)."
        fi
    done
}

# ---- step: the app files ---------------------------------------------------------------------------------------

step_files() {
    step "Download NavMap and NavBridge"
    why "These are the two apps: NavMap for the watch and NavBridge for the phone. Neither has any key built in."
    get_files
}

get_files() {
    FILES="$CACHE/release"
    if [ -n "${NAVMAP_FILES:-}" ]; then
        FILES="$NAVMAP_FILES"
        say "Using the app files in $FILES."
    else
        local file
        for file in SHA256SUMS NavBridge.apk NavMap-fr165.prg NavMap-fr165m.prg; do
            say "Downloading $file…"
            fetch "$FILES_URL/$file" "$FILES/$file" ||
                die "Couldn't download $file from $FILES_URL. If no release is published yet, run bash setup.sh --from-source to build the apps."
        done
    fi
    local sum name checked=0
    [ -f "$FILES/SHA256SUMS" ] || die "$FILES has no SHA256SUMS, so the app files can't be checked."
    while read -r sum name; do
        name="${name#\*}"
        if [ -z "$name" ] || [ ! -f "$FILES/$name" ]; then continue; fi
        [ "$(sha256 "$FILES/$name")" = "$sum" ] || die "$name is damaged (its checksum is wrong). Run bash setup.sh again to download it again."
        checked=$((checked + 1))
    done <"$FILES/SHA256SUMS"
    for name in NavBridge.apk NavMap-fr165.prg NavMap-fr165m.prg; do
        [ -f "$FILES/$name" ] || die "$name is missing from $FILES."
    done
    ok "The app files are here and intact ($checked checked)."
}

# ---- --from-source: Garmin sign-in, signing key, build ---------------------------------------------------------

get_sdk_manager() {
    if [ -x "$HOME/bin/connect-iq-sdk-manager" ]; then
        SDK_MANAGER="$HOME/bin/connect-iq-sdk-manager"
        return
    fi
    if have connect-iq-sdk-manager; then
        SDK_MANAGER="$(command -v connect-iq-sdk-manager)"
        return
    fi
    SDK_MANAGER="$CACHE/bin/connect-iq-sdk-manager"
    if [ -x "$SDK_MANAGER" ]; then return; fi
    local file
    case "$OS-$ARCH" in
        Linux-x86_64) file=Linux_x86_64 ;;
        Linux-aarch64 | Linux-arm64) file=Linux_ARM64 ;;
        Darwin-arm64) file=Darwin_ARM64 ;;
        Darwin-x86_64) file=Darwin_x86_64 ;;
        *) die "Garmin's SDK manager isn't made for this computer ($OS $ARCH)." ;;
    esac
    say "Downloading connect-iq-sdk-manager, which fetches Garmin's SDK…"
    fetch "https://github.com/lindell/connect-iq-sdk-manager-cli/releases/download/v$SDK_MANAGER_VERSION/connect-iq-sdk-manager-cli_${SDK_MANAGER_VERSION}_$file.tar.gz" \
        "$CACHE/sdk-manager.tar.gz" || die "Couldn't download connect-iq-sdk-manager. Run bash setup.sh --from-source again."
    mkdir -p "$CACHE/bin"
    tar -xzf "$CACHE/sdk-manager.tar.gz" -C "$CACHE/bin" connect-iq-sdk-manager
    rm -f "$CACHE/sdk-manager.tar.gz"
}

step_garmin() {
    step "Garmin developer sign-in"
    why "Garmin gives out the Connect IQ SDK, which builds NavMap, only to signed-in developers (a free Garmin account)."
    get_sdk_manager
    if "$SDK_MANAGER" sdk current-path >/dev/null 2>&1 && [ -d "$HOME/.Garmin/ConnectIQ/Devices/fr165" ] &&
        [ -d "$HOME/.Garmin/ConnectIQ/Devices/fr165m" ]; then
        ok "Garmin's SDK and the Forerunner 165 files are already here."
        return
    fi
    local agreement url hash
    agreement="$("$SDK_MANAGER" agreement view 2>&1)" || agreement=''
    url="$(printf '%s\n' "$agreement" | sed -n 's/^Agreement: *//p' | sed -n 1p)"
    hash="$(printf '%s\n' "$agreement" | sed -n 's/^Current Hash: *//p' | sed -n 1p)"
    [ -n "$hash" ] || die "Garmin's SDK manager didn't show the licence: $agreement"
    say "Garmin's licence for the SDK (the Connect IQ Developer Agreement) is on this page:"
    open_url "${url:-https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html}"
    if ! ask_yes "Do you accept it?" "Garmin lets you use the SDK only once you have." N; then
        die "Without that, Garmin's SDK can't be used. bash setup.sh (without --from-source) needs no SDK."
    fi
    "$SDK_MANAGER" agreement accept --agreement-hash="$hash" >/dev/null
    garmin_login
    say "Downloading the SDK and the Forerunner 165 device files (a few hundred MB)…"
    "$SDK_MANAGER" sdk set "$CIQ_SDK_VERSION"
    "$SDK_MANAGER" device download -d fr165 -d fr165m
    ok "Garmin's SDK is ready."
}

# The SDK manager signs in through a page it serves on this computer, and only prints that page's address.
garmin_login() {
    local log="$CACHE/garmin-login.txt" pid url='' waited=0
    printf '\n%sSign in to Garmin%s with your Garmin account (the one Garmin Connect uses).\n' "$BOLD" "$PLAIN"
    "$SDK_MANAGER" login >"$log" 2>&1 &
    pid=$!
    BG_PID=$pid
    while [ -z "$url" ] && [ "$waited" -lt 30 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        url="$(sed -n 's/.*Please login at: *\([^ ]*\).*/\1/p' "$log" | sed -n 1p)"
    done
    if [ -n "$url" ]; then
        say "Your browser opens Garmin's sign-in page. If it doesn't, open this link in a browser on this computer:"
        open_url "$url"
        say "Waiting for the sign-in…"
    fi
    if ! wait "$pid"; then
        BG_PID=''
        cat "$log"
        die "The Garmin sign-in didn't finish. Run bash setup.sh --from-source again."
    fi
    BG_PID=''
    rm -f "$log"
    ok "Signed in to Garmin."
}

step_signing_key() {
    step "Your developer key"
    why "Garmin watches only run apps signed with a developer key. This makes yours, once, with openssl."
    local der="$CONF/developer_key.der"
    if [ -f "$der" ]; then
        ok "You already have one: $der"
        return
    fi
    have openssl || die "This needs openssl (on Ubuntu: sudo apt install openssl)."
    mkdir -p "$CONF"
    chmod 700 "$CONF"
    (umask 077 && openssl genrsa -out "$CONF/developer_key.pem" 4096 2>/dev/null &&
        openssl pkcs8 -topk8 -inform PEM -outform DER -in "$CONF/developer_key.pem" -out "$der" -nocrypt)
    ok "Made $der. Keep it: updates of NavMap must be signed with the same key."
}

get_jdk() {
    if [ -n "${JAVA_HOME:-}" ]; then
        case "$("$JAVA_HOME/bin/java" -version 2>&1)" in *'version "17'*) return ;; esac
    fi
    local found
    found="$(first_dir "$HOME"/opt/jdk-17* "$CACHE"/jdk/jdk-17*)"
    if [ -z "$found" ]; then
        local os arch
        case "$OS" in Darwin) os=mac ;; *) os=linux ;; esac
        case "$ARCH" in x86_64) arch=x64 ;; *) arch=aarch64 ;; esac
        say "Downloading Java 17 (about 190 MB)…"
        fetch "https://api.adoptium.net/v3/binary/latest/17/ga/$os/$arch/jdk/hotspot/normal/eclipse" "$CACHE/jdk.tar.gz" ||
            die "Couldn't download Java 17. Run bash setup.sh --from-source again."
        mkdir -p "$CACHE/jdk"
        tar -xzf "$CACHE/jdk.tar.gz" -C "$CACHE/jdk"
        rm -f "$CACHE/jdk.tar.gz"
        found="$(first_dir "$CACHE"/jdk/jdk-17*)"
    fi
    if [ -d "$found/Contents/Home" ]; then found="$found/Contents/Home"; fi
    export JAVA_HOME="$found"
}

get_android_sdk() {
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then return; fi
    if [ -d "$HOME/opt/android-sdk/platforms" ]; then
        export ANDROID_HOME="$HOME/opt/android-sdk"
        return
    fi
    export ANDROID_HOME="$CACHE/android-sdk"
    local tools="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$tools" ]; then
        local os zip
        case "$OS" in Darwin) os=mac ;; *) os=linux ;; esac
        zip="$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml |
            grep -o "commandlinetools-$os-[0-9]*_latest.zip" | sort -t- -k3 -n | tail -1)" || zip=''
        [ -n "$zip" ] || die "Couldn't find Google's Android command-line tools. Run bash setup.sh --from-source again."
        say "Downloading the Android SDK tools…"
        fetch "https://dl.google.com/android/repository/$zip" "$CACHE/cmdline-tools.zip" ||
            die "Couldn't download the Android SDK tools. Run bash setup.sh --from-source again."
        rm -rf "$ANDROID_HOME/cmdline-tools"
        unzip_to "$CACHE/cmdline-tools.zip" "$ANDROID_HOME/cmdline-tools"
        mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
        chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
        rm -f "$CACHE/cmdline-tools.zip"
    fi
    say "Google's Android SDK comes with licence terms: read them below and type y to accept each."
    "$tools" --sdk_root="$ANDROID_HOME" --licenses || die "Without the Android SDK licences, NavBridge can't be built."
    # 35.0.0 is what the Android Gradle plugin (8.13) builds with; keep it in step when that changes.
    "$tools" --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0" >/dev/null
}

step_build() {
    step "Build NavMap and NavBridge"
    why "You build them yourself, signed with your own key, instead of downloading them."
    if [ "$OS-$ARCH" = Linux-aarch64 ] || [ "$OS-$ARCH" = Linux-arm64 ]; then
        [ -x "$HOME/opt/android-x86-tools/aapt2" ] ||
            die "Google's Android build tools don't run on ARM Linux. Build on a Mac or an x86-64 Linux computer, or run bash setup.sh without --from-source."
    fi
    local src="$HERE"
    if [ ! -x "$src/tools/build-watch.sh" ]; then
        src="$CACHE/src"
        say "Downloading NavMap's source code…"
        rm -rf "$src"
        mkdir -p "$src"
        curl -fsSL "https://github.com/$REPO/archive/refs/heads/main.tar.gz" | tar -xz -C "$src" --strip-components=1 ||
            die "Couldn't download the source code. Run bash setup.sh --from-source again."
    fi
    get_jdk
    get_android_sdk
    FILES="$src/dist/release"
    mkdir -p "$FILES"
    local device
    for device in fr165 fr165m; do
        say "Building NavMap for the $(watch_name "$device")…"
        CIQ_SDK_MANAGER="$SDK_MANAGER" "$src/tools/build-watch.sh" "$device" >"$CACHE/build-$device.log" 2>&1 ||
            { tail -20 "$CACHE/build-$device.log"; die "The watch build failed (the whole log is $CACHE/build-$device.log)."; }
        cp "$src/dist/NavMap.prg" "$FILES/NavMap-$device.prg"
    done
    say "Building NavBridge and running its tests (the first time takes several minutes)…"
    "$src/tools/build-phone.sh" >"$CACHE/build-phone.log" 2>&1 ||
        { tail -20 "$CACHE/build-phone.log"; die "The phone build failed (the whole log is $CACHE/build-phone.log)."; }
    cp "$src/dist/NavBridge.apk" "$FILES/NavBridge.apk"
    (cd "$FILES" && for f in NavBridge.apk NavMap-fr165.prg NavMap-fr165m.prg; do printf '%s  %s\n' "$(sha256 "$f")" "$f"; done >SHA256SUMS)
    export NAVMAP_FILES="$FILES"
    ok "Built both apps into $FILES."
}

# ---- step: the phone -------------------------------------------------------------------------------------------

get_adb() {
    if have adb; then
        ADB="$(command -v adb)"
        return
    fi
    ADB="$CACHE/platform-tools/adb"
    if [ -x "$ADB" ]; then return; fi
    local zip
    case "$OS-$ARCH" in
        Darwin-*) zip=platform-tools-latest-darwin.zip ;;
        Linux-x86_64) zip=platform-tools-latest-linux.zip ;;
        *)
            # Google makes no adb for ARM Linux, but Debian and Ubuntu do.
            if have apt-get && ask_yes "Install adb with apt-get (it asks for your password)?" \
                "adb is how this computer talks to the phone, and Google doesn't make it for ARM Linux." Y; then
                if [ "$(id -u)" = 0 ]; then apt-get install -y adb; else sudo apt-get install -y adb; fi
                ADB="$(command -v adb)" || die "adb didn't install."
                return
            fi
            die "This computer needs adb (Android's USB tool). Install it, for example with sudo apt install adb, and run bash setup.sh again."
            ;;
    esac
    say "Downloading Google's adb, which talks to the phone over USB (about 10 MB)…"
    fetch "https://dl.google.com/android/repository/$zip" "$CACHE/platform-tools.zip" ||
        die "Couldn't download adb. Check the internet connection and run bash setup.sh again."
    unzip_to "$CACHE/platform-tools.zip" "$CACHE"
    chmod +x "$ADB"
    rm -f "$CACHE/platform-tools.zip"
}

phone() { "$ADB" -s "$SERIAL" "$@"; }
phone_sh() { phone shell "$@" 2>/dev/null | tr -d '\r'; }
has_package() {
    case "$(phone_sh pm path "$1")" in *package:*) return 0 ;; *) return 1 ;; esac
}

connect_phone() {
    if [ -n "${SERIAL:-}" ] && [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = device ]; then return; fi
    "$ADB" start-server >/dev/null 2>&1 || true
    local list ready count waited=0 told='' serial models=() serials=()
    while true; do
        list="$("$ADB" devices 2>/dev/null | sed 1d | grep -v '^[[:space:]]*$' || true)"
        ready="$(printf '%s\n' "$list" | awk '$2 == "device" { print $1 }')"
        count="$(printf '%s' "$ready" | grep -c . || true)"
        if [ "$count" -eq 1 ]; then
            SERIAL="$ready"
            break
        fi
        if [ "$count" -gt 1 ]; then
            for serial in $ready; do
                serials+=("$serial")
                models+=("$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r') ($serial)")
            done
            choose "Which phone is yours?" "More than one Android device is plugged in." "${models[@]}"
            SERIAL="${serials[$((CHOICE - 1))]}"
            break
        fi
        if [ -z "$told" ]; then
            told=yes
            printf '\n%sConnect the phone with USB debugging.%s\n' "$BOLD" "$PLAIN"
            why "With it, this computer can install NavBridge and switch on its settings for you."
            say "On the phone, once:"
            say "  1. Settings > About phone: tap Build number seven times (Samsung: About phone > Software information)."
            say "     Enter your PIN if asked. The phone says you're now a developer."
            say "  2. Settings > System > Developer options (Samsung: Settings > Developer options): turn on USB debugging."
            say "  3. Plug the phone into this computer. (Some cables only charge: if nothing happens, try another.)"
            say "  4. The phone asks \"Allow USB debugging?\": tick \"Always allow from this computer\" and tap Allow."
            say "Waiting for the phone…"
        fi
        case "$list" in
            *unauthorized*) [ "$told" = unauthorized ] || { told=unauthorized; say "  The phone hasn't allowed this computer yet: look for \"Allow USB debugging?\" on its screen. No question? Unplug it and plug it in again."; } ;;
            *"no permissions"*) [ "$told" = permissions ] || { told=permissions; say "  Linux won't let you use the phone over USB yet. On the phone, tap the USB notification and choose File transfer, or install Android's USB rules (Ubuntu: sudo apt install android-sdk-platform-tools-common), then plug it in again."; } ;;
            *offline*) [ "$told" = offline ] || { told=offline; say "  The phone shows up but isn't answering: unplug it and plug it in again."; } ;;
        esac
        sleep 2
        waited=$((waited + 2))
        if [ "$waited" -ge 180 ]; then
            pause "Still no phone. Check the steps above."
            waited=0
        fi
    done
    local sdk
    sdk="$(phone_sh getprop ro.build.version.sdk)" || sdk=0
    case "$sdk" in '' | *[!0-9]*) sdk=0 ;; esac
    [ "$sdk" -ge 26 ] || die "This phone has Android $(phone_sh getprop ro.build.version.release). NavBridge needs Android 8 or newer."
    PHONE_SDK="$sdk"
    ok "Connected to the $(phone_sh getprop ro.product.manufacturer) $(phone_sh getprop ro.product.model) (Android $(phone_sh getprop ro.build.version.release))."
}

# setup_call METHOD [ARG]: asks NavBridge's setup provider (SetupProvider.kt); its answer, a Bundle, is in SETUP.
setup_call() {
    local args=(content call --uri "$PROVIDER" --method "$1")
    if [ -n "${2:-}" ]; then args+=(--arg "$2"); fi
    SETUP="$(phone shell "${args[@]}" 2>&1 | tr -d '\r')" || true
    case "$SETUP" in
        *"Result: Bundle"*) return 0 ;;
        *) return 1 ;;
    esac
}

# setup_value NAME: one value from the last setup_call's answer, e.g. setup_value paired gives true.
setup_value() {
    # shellcheck disable=SC2020 # one newline for each of the three characters, on purpose
    printf '%s\n' "$SETUP" | tr '{},' '\n\n\n' | sed -n "s/^ *$1=//p" | sed -n 1p
}

# The reason a setup_call failed, in a line.
setup_error() {
    printf '%s\n' "$SETUP" | sed -n 's/.*Exception: //p' | sed -n 1p
}

step_phone() {
    step "Phone: install NavBridge"
    why "NavBridge reads Google Maps' turn-by-turn notification and passes each turn to the watch."
    get_adb
    connect_phone
    while ! has_package "$GARMIN_CONNECT"; do
        say "Garmin Connect isn't on this phone. NavMap reaches the phone through it. Opening it in the Play Store…"
        phone_sh am start -a android.intent.action.VIEW -d "market://details?id=$GARMIN_CONNECT" >/dev/null || true
        pause "Install Garmin Connect, sign in with your Garmin account and pair the watch."
    done
    while ! has_package "$GOOGLE_MAPS"; do
        say "Google Maps isn't on this phone. Opening it in the Play Store…"
        phone_sh am start -a android.intent.action.VIEW -d "market://details?id=$GOOGLE_MAPS" >/dev/null || true
        pause "Install Google Maps."
    done
    install_navbridge
    say "Switching on what NavBridge needs…"
    # Reading notifications: Google Maps' turn-by-turn notification is where the turns come from.
    phone_sh cmd notification allow_listener "$PKG/$PKG.NavListenerService" >/dev/null || true
    # Its own notification, which says when the watch needs a button press.
    if [ "$PHONE_SDK" -ge 33 ]; then phone_sh pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null || true; fi
    # No battery saving for NavBridge or Garmin Connect, so Android doesn't stop either one mid-ride.
    local app
    for app in "$PKG" "$GARMIN_CONNECT"; do
        phone_sh dumpsys deviceidle whitelist "+$app" >/dev/null || true
        phone_sh cmd appops set "$app" RUN_ANY_IN_BACKGROUND allow >/dev/null || true
    done
    # And Android doesn't take its permissions back if it goes unused for a few months.
    if [ "$PHONE_SDK" -ge 30 ]; then phone_sh cmd appops set "$PKG" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore >/dev/null || true; fi
    setup_call key "$MAPS_KEY" || die "NavBridge didn't take the key: $(setup_error)"
    choose "How do you usually travel?" \
        "Google plans the blue route line for this, and NavBridge starts Google Maps navigation in this mode." \
        "Motorbike or scooter" "Car" "Bicycle" "Walking"
    local modes=(MOTORBIKE CAR BICYCLE WALK)
    setup_call mode "${modes[$((CHOICE - 1))]}" || die "NavBridge didn't take the travel mode: $(setup_error)"
    check_phone_settings
    wait_for_consent
    ok "NavBridge $(setup_value version) is installed and set up, with your key."
}

# Google's terms require the user's own OK before an app uses their location, so adb can't give it: NavBridge asks on
# its first screen, and this waits for the tap. Without it, NavMap shows no map or turns.
wait_for_consent() {
    setup_call status || die "NavBridge isn't answering: $(setup_error)"
    if [ "$(setup_value consent)" = true ]; then return; fi
    phone_sh am start -n "$PKG/.MainActivity" >/dev/null || true
    printf '\n%sOn the phone: NavBridge shows "Before you start". Read it, then tap Agree.%s\n' "$BOLD" "$PLAIN"
    why "It says what NavBridge does with your location, and that Google's terms apply to the map and route line. Only you can agree to that."
    local waited=0
    while true; do
        sleep 2
        waited=$((waited + 2))
        setup_call status || die "NavBridge isn't answering: $(setup_error)"
        if [ "$(setup_value consent)" = true ]; then break; fi
        if [ "$waited" -ge 180 ]; then
            if ! ask_yes "Still waiting for Agree on the phone. Keep waiting?" \
                "Tapped Not now? In NavBridge, the first line under Setup, \"Google Maps terms and your location\", has a Read button." Y; then
                die "NavMap shows nothing until you agree in NavBridge. When you're ready, tap Agree there, then run bash setup.sh test."
            fi
            waited=0
        fi
    done
    ok "You agreed in NavBridge."
}

install_navbridge() {
    say "Installing NavBridge… If the phone asks about the app (Play Protect, scanning), tap Install or Install anyway."
    local out
    while true; do
        out="$(phone install -r "$FILES/NavBridge.apk" 2>&1)" || true
        case "$out" in
            *Success*)
                ok "NavBridge is installed."
                return
                ;;
            *UPDATE_INCOMPATIBLE* | *"signatures do not match"*)
                if ask_yes "A different build of NavBridge is on the phone. Replace it?" \
                    "Android only updates an app with one signed by the same maker. Its settings go, and this script sets them again." Y; then
                    phone uninstall "$PKG" >/dev/null 2>&1 || true
                else
                    die "NavBridge wasn't replaced. Uninstall it on the phone, then run bash setup.sh again."
                fi
                ;;
            *USER_RESTRICTED*)
                say "The phone blocks installs over USB. In Developer options, turn on \"Install via USB\" (Xiaomi phones may ask you to sign in first)."
                pause "Turn on Install via USB."
                ;;
            *VERIFICATION* | *REJECTED* | *[Bb]locked*)
                say "The phone stopped the install. On its screen, tap More details > Install anyway (Play Protect)."
                pause "Allow the install on the phone."
                ;;
            *)
                say "$out"
                die "Android didn't install NavBridge."
                ;;
        esac
    done
}

# Opens the settings screen for what adb couldn't switch on, and waits until the user has.
check_phone_settings() {
    local tries
    for tries in 1 2 3; do
        setup_call status || die "NavBridge isn't answering: $(setup_error)"
        if [ "$(setup_value listener)" = true ]; then break; fi
        say "This phone didn't let adb switch on NavBridge's notification access. Opening the setting on the phone…"
        phone_sh am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS >/dev/null || true
        pause "On the phone, turn on NavBridge in that list (Notification access / Device & app notifications)."
    done
    [ "$(setup_value listener)" = true ] || die "NavBridge still can't read notifications, so it can't see Google Maps' turns."
    if [ "$(setup_value battery)" != true ]; then
        warn "Android kept battery saving on for NavBridge. If turns stop mid-ride, set NavBridge's Battery to Unrestricted in its App info."
    fi
    if [ "$(setup_value garminBattery)" != true ]; then
        warn "Android kept battery saving on for Garmin Connect. If the watch loses the phone mid-ride, set its Battery to Unrestricted."
    fi
}

# ---- step: the watch -------------------------------------------------------------------------------------------

step_watch() {
    step "Watch: copy NavMap"
    why "Garmin installs apps from outside its store only when they're copied over the watch's USB cable."
    printf '\n%sPlug the watch into this computer with its charging cable.%s\n' "$BOLD" "$PLAIN"
    if [ "$OS" = Darwin ]; then copy_mac; else copy_linux; fi
    printf '\n%sUnplug the watch now.%s It installs NavMap as it disconnects.\n' "$BOLD" "$PLAIN"
    set_prop WATCH "$WATCH" "$STATE"
}

# watch_model PARTNUMBER: the build that fits, from GarminDevice.xml's part number, or nothing.
watch_model() {
    case "$1" in
        006-B4432-*) printf fr165 ;;
        006-B4433-*) printf fr165m ;;
    esac
}

# Checks the watch's own part number against the answer in step 1; the watch wins.
check_model() {
    local part="$1" model
    if [ -z "$part" ]; then return; fi
    model="$(watch_model "$part")"
    if [ -z "$model" ]; then
        die "This watch isn't a Forerunner 165 (its part number is $part). NavMap only runs on the Forerunner 165 and 165 Music."
    fi
    if [ "$model" != "$WATCH" ]; then
        say "The watch says it's a $(watch_name "$model"), so that's the version it gets."
        WATCH="$model"
    fi
}

manual_copy() {
    say "$1"
    say "Copy NavMap by hand instead:"
    say "  1. Open the watch in your file manager (it shows up as $(watch_name "$WATCH"))."
    if [ "$OS" = Darwin ]; then say "     On a Mac you need OpenMTP for that: https://openmtp.ganeshrvel.com"; fi
    say "  2. Copy this file into its GARMIN folder, then into Apps (if a NavMap file is there already, replace it):"
    say "     $FILES/NavMap-$WATCH.prg"
    pause "Copy the file onto the watch."
}

# Linux desktops reach the watch (an MTP device, like an Android phone) through GVfs.
copy_linux() {
    if ! have gio; then
        manual_copy "This computer has no gio, the desktop's tool for phones and watches."
        return
    fi
    local root='' mounts waited=0 uri apps='' garmin='' part
    while true; do
        mounts="$(gio mount -li 2>/dev/null)" || mounts=''
        root="$(printf '%s\n' "$mounts" | grep -o 'mtp://[^ ]*' | grep -i garmin | sed -n 1p)" || root=''
        if [ -z "$root" ]; then root="$(printf '%s\n' "$mounts" | grep -o 'mtp://[^ ]*' | sed -n 1p)" || root=''; fi
        if [ -n "$root" ]; then break; fi
        sleep 2
        waited=$((waited + 2))
        if [ "$waited" -ge 60 ]; then
            manual_copy "The watch doesn't show up here. (Is it plugged in, and is the cable a data cable?)"
            return
        fi
    done
    gio mount "$root" >/dev/null 2>&1 || true
    while IFS= read -r uri; do
        if gio info "$uri/GARMIN/Apps" >/dev/null 2>&1; then
            garmin="$uri/GARMIN"
            apps="$uri/GARMIN/Apps"
            break
        fi
        if gio info "$uri/GARMIN/APPS" >/dev/null 2>&1; then
            garmin="$uri/GARMIN"
            apps="$uri/GARMIN/APPS"
            break
        fi
    done < <(gio list -u "$root" 2>/dev/null)
    if [ -z "$apps" ]; then
        manual_copy "The watch is there but its GARMIN/Apps folder isn't."
        return
    fi
    part="$(gio cat "$garmin/GarminDevice.xml" 2>/dev/null)" || part=''
    part="$(printf '%s\n' "$part" | sed -n 's/.*<PartNumber>\([^<]*\)<.*/\1/p' | sed -n 1p)"
    check_model "$part"
    gio remove "$apps/NavMap.prg" >/dev/null 2>&1 || true
    gio copy "$FILES/NavMap-$WATCH.prg" "$apps/NavMap.prg" || { manual_copy "Copying to the watch failed."; return; }
    ok "Copied NavMap to the watch."
}

# macOS can't open MTP devices by itself; libmtp (from Homebrew) can.
copy_mac() {
    if ! have mtp-sendfile && have brew; then
        if ask_yes "Install libmtp with Homebrew, to copy NavMap to the watch?" \
            "Macs can't open Garmin watches by themselves: they use MTP, like Android phones." Y; then
            brew install libmtp || true
        fi
    fi
    if ! have mtp-sendfile; then
        manual_copy "This Mac has no way yet to copy files to the watch."
        return
    fi
    local listing waited=0 id part dir out
    while true; do
        listing="$(mtp-files 2>&1)" || true
        case "$listing" in
            *"File ID:"*) break ;;
        esac
        # Image Capture's helper can hold on to the watch; it starts again by itself when needed.
        pkill -x ptpcamerad 2>/dev/null || true
        sleep 3
        waited=$((waited + 3))
        if [ "$waited" -ge 60 ]; then
            manual_copy "The watch doesn't show up here. (Is it plugged in, and is the cable a data cable?)"
            return
        fi
    done
    id="$(printf '%s\n' "$listing" | awk '/^File ID:/ { id = $3 } /Filename: GarminDevice.xml$/ { print id; exit }')"
    if [ -n "$id" ] && mtp-getfile "$id" "$CACHE/GarminDevice.xml" >/dev/null 2>&1; then
        part="$(sed -n 's/.*<PartNumber>\([^<]*\)<.*/\1/p' "$CACHE/GarminDevice.xml" | sed -n 1p)"
        check_model "$part"
    fi
    mtp-delfile -f /GARMIN/Apps/NavMap.prg >/dev/null 2>&1 || true
    # mtp-sendfile keeps the local file's name and takes the folder to put it in.
    dir="$(mktemp -d "$CACHE/copy.XXXXXX")"
    cp "$FILES/NavMap-$WATCH.prg" "$dir/NavMap.prg"
    out="$(mtp-sendfile "$dir/NavMap.prg" /GARMIN/Apps 2>&1)" || out="$out (error)"
    rm -rf "$dir"
    case "$out" in
        *[Ee]rror* | *"could not"* | *skipping*)
            manual_copy "Copying to the watch failed."
            return
            ;;
    esac
    ok "Copied NavMap to the watch."
}

# ---- step: pair and test ---------------------------------------------------------------------------------------

step_test() {
    step "Pair and test"
    why "The first time NavMap opens, it pairs with NavBridge and gets your Google Maps key from it."
    get_adb
    connect_phone
    setup_call status || die "NavBridge isn't answering: $(setup_error). Run bash setup.sh phone to install it again."
    wait_for_consent
    setup_call pair || die "NavBridge isn't answering: $(setup_error). Run bash setup.sh phone to install it again."
    printf '\n%sOn the watch: press START (top right), scroll to NavMap and press START again.%s\n' "$BOLD" "$PLAIN"
    say "Keep the phone near the watch. Waiting for NavMap to ask NavBridge for turns…"
    local waited=0 hint=''
    while true; do
        setup_call status || die "NavBridge isn't answering: $(setup_error)"
        if [ "$(setup_value paired)" = true ] && [ "$(setup_value watchHasKey)" = true ]; then break; fi
        if [ "$waited" -ge 20 ]; then
            local garmin seen
            garmin="$(setup_value garmin)"
            seen="$(setup_value watch)"
            if [ "$garmin" != READY ] && [ "$hint" != garmin ]; then
                hint=garmin
                say "  NavBridge can't reach Garmin Connect yet: open Garmin Connect on the phone once."
            elif [ "$garmin" = READY ] && [ -z "$seen" ] && [ "$hint" != watch ]; then
                hint=watch
                say "  Garmin Connect doesn't see the watch: open Garmin Connect and check it says the watch is connected."
            elif [ "$(setup_value lastPollSecs)" = -1 ] && [ "$hint" != open ] && [ "$waited" -ge 60 ]; then
                hint=open
                say "  NavMap hasn't asked yet. Is it open on the watch? If the watch shows \"Phone not connected\", open Garmin Connect."
            fi
        fi
        sleep 3
        waited=$((waited + 3))
        if [ "$waited" -ge 240 ]; then
            say "NavMap still hasn't reached NavBridge. Check the hints above."
            pause "Open NavMap on the watch again."
            setup_call pair || true
            waited=0
        fi
    done
    ok "NavMap is paired with NavBridge and has your Google Maps key."
    setup_call simulate || die "NavBridge couldn't start the demo ride: $(setup_error)"
    say "NavBridge is playing a made-up ride now: the watch should show the next turn, its arrow and the distance."
    say "(The map itself waits for the watch's GPS, which needs open sky.)"
    if ask_yes "Do you see the turn on the watch?" "" Y; then
        ok "Everything works."
    else
        say "Open NavBridge on the phone: its Watch card says what's missing. docs/SETUP.md has a troubleshooting list."
    fi
    setup_call stop || true
}

finish() {
    printf '\n%sAll set.%s To use it:\n' "$BOLD" "$PLAIN"
    say "  1. Open Google Maps on the phone, choose where to go, tap Directions, then Start."
    say "  2. The watch asks to open NavMap: press START. (Or open NavMap yourself first.)"
    say "  3. Outdoors, the map appears once the watch has GPS: a few seconds to a couple of minutes."
    say "For the blue route line too: in Google Maps open the place, tap Share and choose Navigate with NavMap."
    say "You can turn USB debugging off again: Settings > System > Developer options."
    say "Run bash setup.sh again any time: it checks everything and fixes what's missing."
}

# ---- main ------------------------------------------------------------------------------------------------------

usage() {
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

main() {
    local from_source='' only=''
    while [ $# -gt 0 ]; do
        case "$1" in
            --from-source) from_source=yes ;;
            key | phone | watch | test) only="$1" ;;
            -h | --help)
                usage
                return
                ;;
            *)
                usage
                die "Unknown option: $1"
                ;;
        esac
        shift
    done
    check_computer
    WATCH="$(prop WATCH "$STATE")"
    WATCH="${WATCH:-fr165}"
    MAPS_KEY="$(prop MAPS_API_KEY "$SECRETS")"
    if [ -n "$only" ]; then
        case "$only" in
            key) step_key ;;
            phone)
                [ -n "$MAPS_KEY" ] || die "There's no Google Maps key yet: run bash setup.sh key first."
                get_files
                step_phone
                ;;
            watch)
                get_files
                step_watch
                ;;
            test) step_test ;;
        esac
        return
    fi
    printf '%sNavMap setup%s\n' "$BOLD" "$PLAIN"
    say "This puts NavMap on your Garmin Forerunner 165 and NavBridge on your Android phone, so the watch shows"
    say "the map and the next turn while Google Maps navigates on the phone."
    say ""
    say "Have these at hand:"
    say "  - the watch and its charging cable"
    say "  - the Android phone, with Garmin Connect and Google Maps, and a USB cable for it"
    say "  - your Google account and a payment card (Google wants one on file for maps)"
    say "It takes about 20 minutes. Ctrl-C stops it at any point; run it again to carry on."
    printf 'Press Enter to start. '
    read_line
    if [ -n "$from_source" ]; then
        STEPS=8
        step_devices
        step_garmin
        step_key
        step_signing_key
        step_build
    else
        STEPS=6
        step_devices
        step_key
        step_files
    fi
    step_phone
    step_watch
    step_test
    finish
}

main "$@"
