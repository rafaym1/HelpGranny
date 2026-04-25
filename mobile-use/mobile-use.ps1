[CmdletBinding(PositionalBinding=$false)]
param (
    [string]$Interface = "",
    [Parameter(ValueFromRemainingArguments)]
    [string[]]$RemainingArgs
)


$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest


function Select-UsbDevice {
    $adb_devices = $(adb devices) -join "`n"
    $serials = @(
        [regex]::Matches(
            $adb_devices,
            '^(.*?)\s+device$',
            [System.Text.RegularExpressions.RegexOptions]::Multiline
        ) | ForEach-Object { $_.Groups[1].Value }
    )
    $selected_device_serial = $null

    if ($serials.Count -gt 1) {
        Write-Host "Multiple USB devices found. Please select one:"
        for ($i = 0; $i -lt $serials.Count; $i++) {
            Write-Host "[$($i+1)] $($serials[$i])"
        }
        $selection = Read-Host -Prompt "Enter number"
        $index = [int]$selection - 1
        if ($index -ge 0 -and $index -lt $serials.Count) {
            $selected_device_serial = $serials[$index]
        } else {
            Write-Error "Invalid selection."
            exit 1
        }
    } elseif ($serials.Count -eq 1) {
        $selected_device_serial = $serials[0]
    } else {
        Write-Error "No USB devices found."
        exit 1
    }
    return $selected_device_serial
}

function Is-EmulatorDevice {
    param(
        [string]$DeviceSerial = ""
    )

    $adb = "adb"
    $adbArgs = @()
    if (-not [string]::IsNullOrEmpty($DeviceSerial)) {
        $adbArgs += "-s", $DeviceSerial
    }

    $qemu = & $adb @adbArgs shell getprop ro.kernel.qemu
    if ($qemu -match "1") {
        return $true
    }

    $model = & $adb @adbArgs shell getprop ro.product.model
    if ($model -match "sdk|emulator|Android SDK built for x86") {
        return $true
    }

    $fingerprint = & $adb @adbArgs shell getprop ro.build.fingerprint
    if ($fingerprint -match "generic|emulator") {
        return $true
    }

    return $false
}


# Find devices connected via TCP/IP
$tcp_devices = @(
    [regex]::Matches(
        (adb devices | Out-String),
        '(\d{1,3}(?:\.\d{1,3}){3}:\d+)'
    ) | ForEach-Object { $_.Value } | Sort-Object -Unique
)

if ($tcp_devices) {
    if ($tcp_devices.Length -eq 1) {
        # If one TCP/IP device is found, use it
        $device_ip = ($tcp_devices -split '\s+')[0]
        Write-Host "Device already in TCP/IP mode: $device_ip"
    } else {
        # If multiple TCP/IP devices are found, prompt user to select one
        Write-Host "Multiple devices found. Please select one:"
        for ($i = 0; $i -lt $tcp_devices.Length; $i++) {
            $ip = ($tcp_devices[$i] -split '\s+')[0]
            Write-Host "[$($i+1)] $ip"
        }
        $selection = Read-Host -Prompt "Enter number"
        $index = [int]$selection - 1
        if ($index -ge 0 -and $index -lt $tcp_devices.Length) {
            $device_ip = ($tcp_devices[$index] -split '\s+')[0]
        } else {
            Write-Error "Invalid selection."
            exit 1
        }
    }
} else {
    # If no TCP/IP devices found, get IP and connect
    Write-Host "No device in TCP/IP mode, enabling..."

    $selected_device_serial = Select-UsbDevice

    Write-Output "Choosing device: $selected_device_serial"

    if (Is-EmulatorDevice -DeviceSerial "$selected_device_serial") {
        $device_ip_only = "host.docker.internal"
    } else {
        $device_ip_only = $null
        $wifi_interfaces = @()
        if (-not [string]::IsNullOrEmpty($Interface)) {
            Write-Host "Using specified network interface: $Interface"
            $wifi_interfaces = @($Interface)
        } else {
            # Try different common Wi-Fi interface names
            $wifi_interfaces = @("wlan0", "wlan1", "wifi0", "wifi1", "rmnet_data1", "swlan0", "swlan1")
        }

        foreach ($interface_item in $wifi_interfaces) {
            $ADB_COMMAND = "ip -f inet addr show $interface_item | grep 'inet ' | awk '{print `$2}' | cut -d/ -f1"
            $ip_result = adb -s $selected_device_serial shell $ADB_COMMAND
            if ($ip_result -and $ip_result.Trim() -ne "") {
                $device_ip_only = $ip_result.Trim()
                Write-Host "Found IP on interface $interface_item`: $device_ip_only"
                break
            }
        }
    }

    if (-not $device_ip_only) {
        Write-Error "Could not get device IP. Is a device connected via USB and on the same Wi-Fi network?"
        exit 1
    }

    $device_ip_only = ($device_ip_only).Trim()
    adb -s $selected_device_serial tcpip 5555
    $device_ip = "${device_ip_only}:5555"
}

Write-Output "Device IP is: $device_ip"
$env:ADB_CONNECT_ADDR = "$device_ip"

if (-not (Test-Path "./llm-config.override.jsonc")) {
    [System.IO.File]::WriteAllText("./llm-config.override.jsonc", "{}")
}

docker compose run --build --rm --remove-orphans -it mobile-use-full-ip $RemainingArgs
