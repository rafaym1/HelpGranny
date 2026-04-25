#!/bin/bash

set -eu

network_interface=""

# All arguments that are not script options will be passed to docker compose
docker_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--interface)
      network_interface="$2"
      shift 2
      ;;
    *)
      docker_args+=("$1")
      shift
      ;;
  esac
done

select_usb_device() {
    local serials
    serials=($(adb devices | grep -w "device" | awk '{print $1}'))

    if [ ${#serials[@]} -gt 1 ]; then
        echo "Multiple USB devices found. Please select one:" >&2
        for i in "${!serials[@]}"; do
            echo "[$((i+1))] ${serials[$i]}" >&2
        done

        local selection
        read -p "Enter number: " selection
        # Validate that the selection is a number and within the valid range
        if [[ "$selection" =~ ^[0-9]+$ ]] && [ "$selection" -gt 0 ] && [ "$selection" -le "${#serials[@]}" ]; then
            local index=$((selection-1))
            echo "${serials[$index]}"
        else
            echo "Invalid selection." >&2
            exit 1
        fi
    elif [ ${#serials[@]} -eq 1 ]; then
        echo "${serials[0]}"
    else
        echo "No USB devices found." >&2
        exit 1
    fi
}

is_emulator_device() {
    local device_serial="$1"
    local adb_cmd="adb"
    if [ -n "$device_serial" ]; then
        adb_cmd="adb -s $device_serial"
    fi

    if $adb_cmd shell getprop ro.kernel.qemu | grep -q "1"; then
        return 0
    fi

    if $adb_cmd shell getprop ro.product.model | grep -q -E "sdk|emulator|Android SDK built for x86"; then
        return 0
    fi

    if $adb_cmd shell getprop ro.build.fingerprint | grep -q -E "generic|emulator"; then
        return 0
    fi

    return 1
}

# Find devices connected via TCP/IP
tcp_devices=($(adb devices | grep -E -o '([0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+)' | sort -u))

if [ ${#tcp_devices[@]} -gt 0 ]; then
    if [ ${#tcp_devices[@]} -eq 1 ]; then
        # If one TCP/IP device is found, use it
        device_ip=${tcp_devices[0]}
        echo "Device already in TCP/IP mode: $device_ip"
    else
        # If multiple TCP/IP devices are found, prompt user to select one
        echo "Multiple devices found. Please select one:"
        for i in "${!tcp_devices[@]}"; do
            echo "[$((i+1))] ${tcp_devices[$i]}"
        done

        read -p "Enter number: " selection
        # Validate that the selection is a number and within the valid range
        if [[ "$selection" =~ ^[0-9]+$ ]] && [ "$selection" -gt 0 ] && [ "$selection" -le "${#tcp_devices[@]}" ]; then
            index=$((selection-1))
            device_ip=${tcp_devices[$index]}
        else
            echo "Invalid selection." >&2
            exit 1
        fi
    fi
else
    # If no TCP/IP devices found, get IP and connect
    echo "No device in TCP/IP mode, enabling..."
    
    selected_device_serial="$(select_usb_device)"
    echo "Choosing device: $selected_device_serial"

    if is_emulator_device "$selected_device_serial"; then
        device_ip_only="host.docker.internal"
    else
        device_ip_only=""
        if [ -n "$network_interface" ]; then
            echo "Using specified network interface: $network_interface"
            wifi_interfaces=("$network_interface")
        else
            # Try different common Wi-Fi interface names
            wifi_interfaces=("wlan0" "wlan1" "wifi0" "wifi1" "rmnet_data1", "swlan0", "swlan1")
        fi

        for interface in "${wifi_interfaces[@]}"; do
            ADB_COMMAND="ip -f inet addr show $interface | grep 'inet ' | awk '{print \$2}' | cut -d/ -f1"
            ip_result=$(adb -s "$selected_device_serial" shell "$ADB_COMMAND" | tr -d '\r\n')
            if [ -n "$ip_result" ]; then
                device_ip_only="$ip_result"
                echo "Found IP on interface $interface: $device_ip_only"
                break
            fi
        done
    fi
    
    if [ -z "$device_ip_only" ]; then
        echo "Error: Could not get device IP. Is a device connected via USB and on the same Wi-Fi network?" >&2
        exit 1
    fi

    adb -s "$selected_device_serial" tcpip 5555
    device_ip="${device_ip_only}:5555"
fi

echo "Device IP is: $device_ip"
export ADB_CONNECT_ADDR="$device_ip"

if [ ! -f "./llm-config.override.jsonc" ]; then
    echo "{}" > "./llm-config.override.jsonc"
fi

docker compose run --build --rm --remove-orphans -it mobile-use-full-ip "${docker_args[@]}"
