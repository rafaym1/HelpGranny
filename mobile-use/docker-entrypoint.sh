#!/bin/bash

set -eu

while true; do
    set +e
    adb connect "$ADB_CONNECT_ADDR"
    state="$(adb get-state 2>/dev/null)"
    set -e

    adb devices

    if [[ "$state" = "device" ]]; then
        echo "Device is connected and authorized!"
        break
    fi

    set +e; adb disconnect "$ADB_CONNECT_ADDR"; set -e

    echo "Waiting for device authorization... (accept the prompt on your phone)"
    sleep 2
done

mobile-use "$@"
