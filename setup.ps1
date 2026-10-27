# NavMap setup for Windows: puts NavMap on a Garmin Forerunner 165 (or 165 Music) and NavBridge on an Android
# phone, with your own Google Maps key. It asks one thing at a time, and it's safe to run again: it checks each
# step and redoes only what's missing. It does what setup.sh does on a Mac or Linux.
#
#   powershell -ExecutionPolicy Bypass -File setup.ps1                     everything, with the published app files
#   powershell -ExecutionPolicy Bypass -File setup.ps1 key|phone|watch|test    one step again
#
# The tools it needs go in %LOCALAPPDATA%\navmap, nothing system-wide. Your key is kept in %APPDATA%\navmap,
# readable only by you. Runs with the Windows PowerShell that comes with Windows 10 and 11.
# This file is plain ASCII on purpose: Windows PowerShell reads a file without a byte-order mark as ANSI.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$REPO = 'Cube-VG/navmap-watch'
$FILES_URL = if ($env:NAVMAP_FILES_URL) { $env:NAVMAP_FILES_URL } else { "https://github.com/$REPO/releases/latest/download" }
$CONF = Join-Path $env:APPDATA 'navmap'
$CACHE = Join-Path $env:LOCALAPPDATA 'navmap'
$SECRETS = Join-Path $CONF 'secrets.properties'
$STATE = Join-Path $CONF 'setup.properties'
$PKG = 'com.cubevg.navbridge'
$GARMIN_CONNECT = 'com.garmin.android.apps.connectmobile'
$GOOGLE_MAPS = 'com.google.android.apps.maps'
$PROVIDER = 'content://com.cubevg.navbridge.setup'
$RUN = 'powershell -ExecutionPolicy Bypass -File setup.ps1'

# GitHub and Google only speak TLS 1.2 or newer; older Windows PowerShell doesn't offer it unless asked.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
# Windows PowerShell's own progress bar makes downloads many times slower.
$ProgressPreference = 'SilentlyContinue'

# ---- talking to the user ---------------------------------------------------------------------------------------

function Say([string]$Text) { Write-Host $Text }
function Ok([string]$Text) { Write-Host -NoNewline -ForegroundColor Green 'OK '; Write-Host $Text }
function Warn([string]$Text) { Write-Host -NoNewline -ForegroundColor Yellow '! '; Write-Host $Text }
function Why([string]$Text) { Write-Host -ForegroundColor DarkGray "  Why: $Text" }
function Bold([string]$Text) { Write-Host ''; Write-Host -ForegroundColor White $Text }

$script:Done = $false
function Die([string]$Text) {
    Write-Host ''
    Write-Host -ForegroundColor Red "X $Text"
    $script:Done = $true
    exit 1
}

$script:Step = 0
$script:Steps = 1
function Step([string]$Title) {
    $script:Step++
    Write-Host ''
    if ($script:Steps -gt 1) {
        Write-Host -ForegroundColor White "== Step $($script:Step) of $($script:Steps): $Title =="
    } else {
        Write-Host -ForegroundColor White "== $Title =="
    }
}

function Read-Line {
    $line = Read-Host '>'
    if ($null -eq $line) { Die "No answer (the input ended). Run $RUN again in PowerShell." }
    return $line
}

# Ask-Yes "Question?" "Why it's asked" [Y|N]: true for yes. Enter takes the default.
function Ask-Yes([string]$Question, [string]$Reason = '', [string]$Default = 'Y') {
    $hint = if ($Default -eq 'N') { '[y/N]' } else { '[Y/n]' }
    Bold "$Question $hint"
    if ($Reason) { Why $Reason }
    while ($true) {
        $reply = (Read-Line).Trim()
        if ($reply -eq '') { return ($Default -ne 'N') }
        if ($reply -match '^[Yy]') { return $true }
        if ($reply -match '^[Nn]') { return $false }
        Say 'Type y for yes or n for no.'
    }
}

# Choose "Question?" "Why it's asked" option...: the number picked, from 1.
function Choose([string]$Question, [string]$Reason, [string[]]$Options) {
    Bold $Question
    if ($Reason) { Why $Reason }
    for ($i = 0; $i -lt $Options.Count; $i++) { Say "  $($i + 1)) $($Options[$i])" }
    while ($true) {
        $reply = (Read-Line).Trim()
        if ($reply -match '^[0-9]+$') {
            $n = [int]$reply
            if ($n -ge 1 -and $n -le $Options.Count) { return $n }
        }
        Say "Type a number from 1 to $($Options.Count)."
    }
}

function Pause-For([string]$Text) {
    Bold $Text
    $null = Read-Host 'Press Enter to carry on'
}

function Open-Url([string]$Url) {
    Say "  $Url"
    try { Start-Process $Url } catch { }
}

# ---- small tools -----------------------------------------------------------------------------------------------

# Runs a program and gives back what it printed, as one string: standard output only, or with -All its error
# output as well. Its exit code is in $script:Exit. Windows PowerShell would otherwise stop the whole script at the
# first line a program writes to its error output.
function Run-Tool {
    param([string]$Exe, [string[]]$Arguments = @(), [switch]$All)
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($All) {
            $out = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
        } else {
            $out = & $Exe @Arguments 2>$null | ForEach-Object { "$_" }
        }
        $script:Exit = $LASTEXITCODE
    } catch {
        $out = "$_"
        $script:Exit = 1
    } finally {
        $ErrorActionPreference = $eap
    }
    if ($null -eq $out) { return '' }
    return ((@($out) -join "`n") -replace "`r", '')
}

function First-Line([string]$Text) {
    foreach ($line in ($Text -split "`n")) { if ($line.Trim()) { return $line.Trim() } }
    return ''
}

# Fetch URL FILE: downloads with a progress bar, keeping no half-downloaded file.
function Fetch([string]$Url, [string]$File) {
    $null = New-Item -ItemType Directory -Force -Path (Split-Path $File)
    $part = "$File.part"
    Remove-Item -Force -ErrorAction SilentlyContinue $part
    $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
    $okay = $false
    if (Test-Path $curl) {
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $curl -fL --retry 3 --connect-timeout 20 --progress-bar -o $part $Url
            $okay = ($LASTEXITCODE -eq 0)
        } finally { $ErrorActionPreference = $eap }
    } else {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $part
            $okay = $true
        } catch { $okay = $false }
    }
    if (-not $okay) {
        Remove-Item -Force -ErrorAction SilentlyContinue $part
        return $false
    }
    Move-Item -Force $part $File
    return $true
}

function Unzip-To([string]$Zip, [string]$Dir) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $null = New-Item -ItemType Directory -Force -Path $Dir
    [IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dir)
}

function Sha256([string]$File) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $File).Hash.ToLower() }

# Prop NAME FILE: a NAME=value line's value, or nothing.
function Prop([string]$Name, [string]$File) {
    if (-not (Test-Path $File)) { return '' }
    $value = ''
    foreach ($line in [IO.File]::ReadAllLines($File)) {
        if ($line.StartsWith("$Name=")) { $value = $line.Substring($Name.Length + 1) }
    }
    return $value
}

# Set-Prop NAME VALUE FILE: keeps the file's other lines, and the file readable only by you.
function Set-Prop([string]$Name, [string]$Value, [string]$File) {
    $null = New-Item -ItemType Directory -Force -Path $CONF
    $lines = @()
    if (Test-Path $File) { $lines = @([IO.File]::ReadAllLines($File) | Where-Object { -not $_.StartsWith("$Name=") }) }
    $lines += "$Name=$Value"
    [IO.File]::WriteAllLines($File, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
    $me = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $null = Run-Tool 'icacls.exe' @($File, '/inheritance:r', '/grant:r', "${me}:(F)") -All
}

# ---- step: the devices -----------------------------------------------------------------------------------------

function Check-Computer {
    if ($PSVersionTable.PSVersion.Major -lt 5) {
        Die 'This needs PowerShell 5 or newer, which comes with Windows 10 and 11.'
    }
    if ([Environment]::OSVersion.Platform -ne 'Win32NT') {
        Die 'setup.ps1 is for Windows. On a Mac or Linux, run bash setup.sh instead.'
    }
    $null = New-Item -ItemType Directory -Force -Path $CACHE
}

function Watch-Name([string]$Model) {
    if ($Model -eq 'fr165m') { return 'Forerunner 165 Music' }
    return 'Forerunner 165'
}

function Step-Devices {
    Step 'Your watch and phone'
    $choice = Choose 'Which watch do you have?' "NavMap is made for the Forerunner 165's screen and doesn't run on other watches." `
        @('Forerunner 165', 'Forerunner 165 Music', 'Another watch')
    switch ($choice) {
        1 { $script:Watch = 'fr165' }
        2 { $script:Watch = 'fr165m' }
        default { Die 'Sorry: NavMap only runs on the Garmin Forerunner 165 and Forerunner 165 Music.' }
    }
    Set-Prop 'WATCH' $script:Watch $STATE
    if (-not (Ask-Yes 'Is your phone an Android phone?' "NavBridge, the half that runs on the phone, is an Android app. There's no iPhone version.")) {
        Die "Sorry: NavMap needs an Android phone (Android 8 or newer). It can't work with an iPhone."
    }
    if (-not (Ask-Yes 'Is the watch paired with the Garmin Connect app on that phone?' 'The watch reaches the phone through Garmin Connect.')) {
        Say 'Do that first, on the phone:'
        Say '  1. Install Garmin Connect from the Play Store and sign in with your Garmin account.'
        Say '  2. Add the watch: tap the watch icon or Devices, then Add Device, and follow the steps.'
        Pause-For 'Pair the watch in Garmin Connect.'
    }
    Ok "A $(Watch-Name $script:Watch) and an Android phone: NavMap works with those."
}

# ---- step: the Google Maps key ---------------------------------------------------------------------------------

# What a failed web request answered: its status code and the start of its body.
function Http-Problem($Err) {
    $code = '000'
    $body = ''
    $resp = $null
    try { $resp = $Err.Exception.Response } catch { }
    if ($resp) {
        try { $code = [string][int]$resp.StatusCode } catch { }
        if ($Err.ErrorDetails -and $Err.ErrorDetails.Message) {
            $body = $Err.ErrorDetails.Message
        } else {
            try { $body = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch { }
        }
    } else {
        $body = $Err.Exception.Message
    }
    $body = ($body -replace "[`r`n]+", ' ')
    if ($body.Length -gt 300) { $body = $body.Substring(0, 300) }
    return "${code}: $body"
}

# Test-Key KEY: true if the key works for both Maps Static API and Routes API; otherwise the reason in $script:KeyProblem.
function Test-Key([string]$Key) {
    try {
        $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 `
            -Uri "https://maps.googleapis.com/maps/api/staticmap?center=51.5007,-0.1246&zoom=15&size=64x64&key=$Key"
    } catch {
        $script:KeyProblem = "Maps Static API answered $(Http-Problem $_)"
        return $false
    }
    $route = '{"origin":{"location":{"latLng":{"latitude":51.5007,"longitude":-0.1246}}},"destination":{"location":{"latLng":{"latitude":51.5033,"longitude":-0.1196}}},"travelMode":"WALK"}'
    try {
        $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Method Post -ContentType 'application/json' `
            -Headers @{ 'X-Goog-Api-Key' = $Key; 'X-Goog-FieldMask' = 'routes.distanceMeters' } -Body $route `
            -Uri 'https://routes.googleapis.com/directions/v2:computeRoutes'
    } catch {
        $script:KeyProblem = "Routes API answered $(Http-Problem $_)"
        return $false
    }
    return $true
}

# A new key, or APIs just switched on, can take a few minutes to work.
function Wait-ForKey([string]$Key) {
    $tries = 0
    while (-not (Test-Key $Key)) {
        $tries++
        if ($tries -ge 30) { return $false }
        if ($tries -eq 1) { Say 'Google takes a minute or two to switch a new key on. Waiting (up to 5 minutes)...' }
        Start-Sleep -Seconds 10
    }
    return $true
}

function Step-Key {
    Step 'Your Google Maps key'
    Say 'The map pictures and the route line come from Google Maps Platform, billed to your own Google account.'
    Say "One person's riding normally stays inside Google's free monthly allowance (see the README), but Google"
    Say 'wants a payment card on file before it hands out a key.'
    $script:MapsKey = Prop 'MAPS_API_KEY' $SECRETS
    if ($script:MapsKey) {
        Say 'Checking the key from last time...'
        if (Test-Key $script:MapsKey) {
            Ok 'Your Google Maps key works.'
            return
        }
        Warn "The saved key doesn't work any more ($($script:KeyProblem)). Let's set up one that does."
    }
    $choice = Choose 'Do you already have a Google Maps API key you want to use?' `
        "With your own key, Google bills your account and nobody else's, inside your free allowance." `
        @('No, make one for me (recommended)', "Yes, I'll paste it")
    if ($choice -eq 2) { Paste-Key } else { Make-Key }
    Set-Prop 'MAPS_API_KEY' $script:MapsKey $SECRETS
    Ok "Key saved in $SECRETS (only you can read it)."
}

function Paste-Key {
    while ($true) {
        Bold 'Paste your key and press Enter (it is not shown)'
        $secure = Read-Host '>' -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        $key = $key -replace '\s', ''
        if ($key -cnotmatch '^AIza[0-9A-Za-z_-]{35}$') {
            Say "That isn't a Google API key: those start with AIza and are 39 characters long."
            continue
        }
        Say 'Checking it...'
        if (Test-Key $key) {
            $script:MapsKey = $key
            return
        }
        Say "That key doesn't work yet: $($script:KeyProblem)"
        Say 'Its Google Cloud project needs billing on and both Maps Static API and Routes API enabled.'
        if (Ask-Yes 'Let this script make a new key instead?' 'It sets all of that up for you.' 'Y') {
            Make-Key
            return
        }
    }
}

# gcloud is run the way Google's gcloud.cmd runs it: its Python on lib\gcloud.py. Called straight, rather than
# through gcloud.cmd, so arguments with spaces or = signs reach it as they are.
function Get-Gcloud {
    $root = ''
    $py = ''
    $installed = Get-Command 'gcloud.cmd' -ErrorAction SilentlyContinue
    if ($installed) {
        $root = Split-Path (Split-Path $installed.Source)
        if ($env:CLOUDSDK_PYTHON -and (Test-Path $env:CLOUDSDK_PYTHON)) { $py = $env:CLOUDSDK_PYTHON }
        elseif (Test-Path "$root\platform\bundledpython\python.exe") { $py = "$root\platform\bundledpython\python.exe" }
        if (-not ($py -and (Test-Path "$root\lib\gcloud.py"))) { $root = '' }
    }
    if (-not $root) {
        $root = "$CACHE\google-cloud-sdk"
        $py = "$root\platform\bundledpython\python.exe"
        if (-not (Test-Path $py)) {
            $file = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'windows-arm' } else { 'windows-x86_64' }
            Say "Downloading Google's command-line tool, gcloud, with its own Python (about 80-105 MB)..."
            if (-not (Fetch "https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-$file-bundled-python.zip" "$CACHE\gcloud.zip")) {
                Die "Couldn't download gcloud. Check the internet connection and run $RUN again."
            }
            Say 'Unpacking it (a minute or two)...'
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $root
            Unzip-To "$CACHE\gcloud.zip" $CACHE
            Remove-Item -Force "$CACHE\gcloud.zip"
            if (-not (Test-Path $py)) { Die "gcloud didn't unpack. Run $RUN again." }
        }
    }
    $script:GcPython = $py
    $script:GcScript = "$root\lib\gcloud.py"
    $env:PATH = "$root\bin\sdk;$env:PATH"
    $env:PYTHONHOME = ''
    # gcloud keeps this sign-in to itself, so any other gcloud setup on this computer is left as it is.
    $env:CLOUDSDK_CONFIG = "$CONF\gcloud"
    $env:CLOUDSDK_CORE_DISABLE_USAGE_REPORTING = 'true'
}

# Gcloud ARG...: gcloud's printed output. -All adds its messages; $script:Exit has its exit code.
function Gcloud {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Run-Tool $script:GcPython (@('-S', $script:GcScript) + $Arguments)
}
function Gcloud-All {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Run-Tool $script:GcPython (@('-S', $script:GcScript) + $Arguments) -All
}

function Make-Key {
    Get-Gcloud
    $account = First-Line (Gcloud 'auth' 'list' '--filter=status:ACTIVE' '--format=value(account)')
    if (-not $account) {
        Bold 'Sign in to Google.'
        Why 'gcloud makes the project and the key in your account. Sign in with the Google account that should pay for the maps.'
        Say "Your browser opens Google's sign-in page. If it doesn't, open the link gcloud shows below."
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $script:GcPython -S $script:GcScript auth login --brief } finally { $ErrorActionPreference = $eap }
        $account = First-Line (Gcloud 'auth' 'list' '--filter=status:ACTIVE' '--format=value(account)')
        if (-not $account) { Die "The Google sign-in didn't finish. Run $RUN again to try again." }
    }
    Ok "Signed in to Google as $account."
    Pick-Project
    Turn-OnBilling
    Say "Switching on Maps Static API and Routes API for $($script:Project) (about a minute)..."
    $out = Gcloud-All 'services' 'enable' 'static-maps-backend.googleapis.com' 'routes.googleapis.com' 'apikeys.googleapis.com' "--project=$($script:Project)" '--quiet'
    if ($script:Exit -ne 0) {
        Say $out
        Die "Google wouldn't switch the APIs on. Run $RUN again; if it fails again, check billing at https://console.cloud.google.com/billing"
    }
    $name = First-Line (Gcloud 'services' 'api-keys' 'list' "--project=$($script:Project)" '--filter=displayName=NavMap' '--format=value(name)' '--limit=1')
    if (-not $name) {
        Say 'Making a key that only works for those two APIs...'
        $out = Gcloud-All 'services' 'api-keys' 'create' "--project=$($script:Project)" '--display-name=NavMap' `
            '--api-target=service=static-maps-backend.googleapis.com' '--api-target=service=routes.googleapis.com' '--quiet'
        if ($script:Exit -ne 0) {
            Say $out
            Die "Google wouldn't make the key. Run $RUN again to try again."
        }
        $name = First-Line (Gcloud 'services' 'api-keys' 'list' "--project=$($script:Project)" '--filter=displayName=NavMap' '--format=value(name)' '--limit=1')
    }
    $script:MapsKey = First-Line (Gcloud 'services' 'api-keys' 'get-key-string' $name '--format=value(keyString)')
    if (-not $script:MapsKey) { Die "Google didn't give out the key. Run $RUN again to try again." }
    Say 'Checking the key...'
    if (-not (Wait-ForKey $script:MapsKey)) {
        Die "The new key doesn't work yet ($($script:KeyProblem)). Wait a few minutes and run $RUN again."
    }
    Ok 'Your key is made, limited to Maps Static API and Routes API, and it works.'
}

function Pick-Project {
    $script:Project = Prop 'GCP_PROJECT' $STATE
    if ($script:Project -and (First-Line (Gcloud 'projects' 'describe' $script:Project '--format=value(lifecycleState)')) -eq 'ACTIVE') {
        Ok "Using your Google Cloud project $($script:Project)."
        return
    }
    $script:Project = First-Line (Gcloud 'projects' 'list' '--filter=name=NavMap AND lifecycleState=ACTIVE' '--format=value(projectId)' '--limit=1')
    if (-not $script:Project) {
        if (Ask-Yes 'Make a Google Cloud project called NavMap for the key?' `
                "A project of its own keeps NavMap's key and its costs apart from anything else in your Google account." 'Y') {
            Create-Project
        } else {
            Choose-Project
        }
    }
    Set-Prop 'GCP_PROJECT' $script:Project $STATE
    Ok "Using your Google Cloud project $($script:Project)."
}

function Create-Project {
    $id = 'navmap-' + (-join (1..8 | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) }))
    Say "Making the project $id..."
    $out = Gcloud-All 'projects' 'create' $id '--name=NavMap' '--quiet'
    if ($script:Exit -eq 0) {
        $script:Project = $id
        return
    }
    if ($out -match '[Tt]erms') {
        Say 'Google wants you to accept its Cloud terms once, in the browser:'
        Open-Url 'https://console.cloud.google.com/'
        Pause-For 'Accept the terms on that page (pick your country and tick the box).'
        Create-Project
    } elseif ($out -match '[Qq]uota|exceeded') {
        Say "Your account can't make more projects, so pick one of yours."
        Choose-Project
    } else {
        Say $out
        Die "Google wouldn't make the project. Run $RUN again to try again."
    }
}

function Choose-Project {
    $ids = @()
    $names = @()
    foreach ($line in ((Gcloud 'projects' 'list' '--filter=lifecycleState=ACTIVE' '--format=value(projectId,name)' '--limit=20') -split "`n")) {
        $parts = $line -split "`t", 2
        if ($parts[0].Trim()) {
            $ids += $parts[0].Trim()
            $label = if ($parts.Count -gt 1) { $parts[1] } else { '' }
            $names += "$label ($($parts[0].Trim()))"
        }
    }
    if ($ids.Count -eq 0) { Die "Your Google account has no projects to use. Run $RUN again and let it make one." }
    $choice = Choose 'Which project should hold the key?' "Google bills the maps to that project's billing account." $names
    $script:Project = $ids[$choice - 1]
}

function Turn-OnBilling {
    while ($true) {
        if ((First-Line (Gcloud 'billing' 'projects' 'describe' $script:Project '--format=value(billingEnabled)')) -eq 'True') {
            Ok "Billing is on for $($script:Project)."
            return
        }
        $ids = @()
        $names = @()
        foreach ($line in ((Gcloud 'billing' 'accounts' 'list' '--filter=open=true' '--format=value(name,displayName)') -split "`n")) {
            $parts = $line -split "`t", 2
            if ($parts[0].Trim()) {
                $ids += $parts[0].Trim()
                $names += $(if ($parts.Count -gt 1) { $parts[1] } else { $parts[0] })
            }
        }
        if ($ids.Count -eq 0) {
            Bold 'Your Google account needs a billing account.'
            Why 'Google Maps Platform only works with billing on. Its free monthly allowance comes first; past it, the card pays.'
            Say 'This page makes one (you give Google a payment card):'
            Open-Url 'https://console.cloud.google.com/billing/create'
            Pause-For 'Make the billing account on that page.'
            continue
        }
        if ($ids.Count -eq 1) {
            $account = $ids[0]
        } else {
            $choice = Choose 'Which billing account should pay for NavMap''s maps?' 'Each Google Cloud project bills one account.' $names
            $account = $ids[$choice - 1]
        }
        $account = $account -replace '^billingAccounts/', ''
        Say "Turning billing on for $($script:Project)..."
        $out = Gcloud-All 'billing' 'projects' 'link' $script:Project "--billing-account=$account" '--quiet'
        if ($script:Exit -ne 0) {
            Say $out
            Say "Google wouldn't link that billing account. You need to be its administrator, and it must be open."
            Pause-For 'Fix it at https://console.cloud.google.com/billing (or make a new billing account there).'
        }
    }
}

# ---- step: the app files ---------------------------------------------------------------------------------------

function Step-Files {
    Step 'Download NavMap and NavBridge'
    Why 'These are the two apps: NavMap for the watch and NavBridge for the phone. Neither has any key built in.'
    Get-Files
}

function Get-Files {
    $script:Files = "$CACHE\release"
    if ($env:NAVMAP_FILES) {
        $script:Files = $env:NAVMAP_FILES
        Say "Using the app files in $($script:Files)."
    } else {
        foreach ($file in @('SHA256SUMS', 'NavBridge.apk', 'NavMap-fr165.prg', 'NavMap-fr165m.prg')) {
            Say "Downloading $file..."
            if (-not (Fetch "$FILES_URL/$file" "$($script:Files)\$file")) {
                Die "Couldn't download $file from $FILES_URL. Check the internet connection and run $RUN again."
            }
        }
    }
    $sums = "$($script:Files)\SHA256SUMS"
    if (-not (Test-Path $sums)) { Die "$($script:Files) has no SHA256SUMS, so the app files can't be checked." }
    $checked = 0
    foreach ($line in [IO.File]::ReadAllLines($sums)) {
        $parts = $line.Trim() -split '\s+', 2
        if ($parts.Count -lt 2) { continue }
        $name = $parts[1].TrimStart('*')
        $path = "$($script:Files)\$name"
        if (-not (Test-Path $path)) { continue }
        if ((Sha256 $path) -ne $parts[0].ToLower()) {
            Die "$name is damaged (its checksum is wrong). Run $RUN again to download it again."
        }
        $checked++
    }
    foreach ($name in @('NavBridge.apk', 'NavMap-fr165.prg', 'NavMap-fr165m.prg')) {
        if (-not (Test-Path "$($script:Files)\$name")) { Die "$name is missing from $($script:Files)." }
    }
    Ok "The app files are here and intact ($checked checked)."
}

# ---- step: the phone -------------------------------------------------------------------------------------------

function Get-Adb {
    $installed = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($installed) {
        $script:Adb = $installed.Source
        return
    }
    $script:Adb = "$CACHE\platform-tools\adb.exe"
    if (Test-Path $script:Adb) { return }
    Say "Downloading Google's adb, which talks to the phone over USB (about 10 MB)..."
    if (-not (Fetch 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' "$CACHE\platform-tools.zip")) {
        Die "Couldn't download adb. Check the internet connection and run $RUN again."
    }
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue "$CACHE\platform-tools"
    Unzip-To "$CACHE\platform-tools.zip" $CACHE
    Remove-Item -Force "$CACHE\platform-tools.zip"
}

function Phone {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Run-Tool $script:Adb (@('-s', $script:Serial) + $Arguments) -All
}
function Phone-Sh {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    return Run-Tool $script:Adb (@('-s', $script:Serial, 'shell') + $Arguments)
}
function Has-Package([string]$Name) { return ((Phone-Sh 'pm' 'path' $Name) -match 'package:') }

function Connect-Phone {
    if ($script:Serial -and (Run-Tool $script:Adb @('-s', $script:Serial, 'get-state')).Trim() -eq 'device') { return }
    $null = Run-Tool $script:Adb @('start-server') -All
    $waited = 0
    $told = ''
    while ($true) {
        $lines = @(((Run-Tool $script:Adb @('devices')) -split "`n") | Select-Object -Skip 1 | Where-Object { $_.Trim() })
        $ready = @($lines | Where-Object { ($_ -split '\s+')[1] -eq 'device' } | ForEach-Object { ($_ -split '\s+')[0] })
        $list = $lines -join "`n"
        if ($ready.Count -eq 1) {
            $script:Serial = $ready[0]
            break
        }
        if ($ready.Count -gt 1) {
            $models = @($ready | ForEach-Object { "$((Run-Tool $script:Adb @('-s', $_, 'shell', 'getprop', 'ro.product.model')).Trim()) ($_)" })
            $choice = Choose 'Which phone is yours?' 'More than one Android device is plugged in.' $models
            $script:Serial = $ready[$choice - 1]
            break
        }
        if (-not $told) {
            $told = 'yes'
            Bold 'Connect the phone with USB debugging.'
            Why 'With it, this computer can install NavBridge and switch on its settings for you.'
            Say 'On the phone, once:'
            Say '  1. Settings > About phone: tap Build number seven times (Samsung: About phone > Software information).'
            Say "     Enter your PIN if asked. The phone says you're now a developer."
            Say '  2. Settings > System > Developer options (Samsung: Settings > Developer options): turn on USB debugging.'
            Say '  3. Plug the phone into this computer. (Some cables only charge: if nothing happens, try another.)'
            Say '  4. The phone asks "Allow USB debugging?": tick "Always allow from this computer" and tap Allow.'
            Say 'Waiting for the phone...'
        }
        if ($list -match 'unauthorized' -and $told -ne 'unauthorized') {
            $told = 'unauthorized'
            Say '  The phone hasn''t allowed this computer yet: look for "Allow USB debugging?" on its screen. No question? Unplug it and plug it in again.'
        } elseif ($list -match 'offline' -and $told -ne 'offline') {
            $told = 'offline'
            Say '  The phone shows up but isn''t answering: unplug it and plug it in again.'
        } elseif (-not $list -and $waited -ge 40 -and $told -eq 'yes') {
            $told = 'driver'
            Say '  Still nothing. Windows may need the phone maker''s USB driver before adb can see the phone'
            Say '  (Samsung: "Samsung Android USB Driver"; others: https://developer.android.com/studio/run/oem-usb).'
            Say '  Pixel phones and most new phones need none. Also try another USB port or cable.'
        }
        Start-Sleep -Seconds 2
        $waited += 2
        if ($waited -ge 180) {
            Pause-For 'Still no phone. Check the steps above.'
            $waited = 0
        }
    }
    $sdk = (Phone-Sh 'getprop' 'ro.build.version.sdk').Trim()
    if ($sdk -notmatch '^[0-9]+$') { $sdk = '0' }
    if ([int]$sdk -lt 26) {
        Die "This phone has Android $((Phone-Sh 'getprop' 'ro.build.version.release').Trim()). NavBridge needs Android 8 or newer."
    }
    $script:PhoneSdk = [int]$sdk
    $maker = (Phone-Sh 'getprop' 'ro.product.manufacturer').Trim()
    $model = (Phone-Sh 'getprop' 'ro.product.model').Trim()
    $release = (Phone-Sh 'getprop' 'ro.build.version.release').Trim()
    Ok "Connected to the $maker $model (Android $release)."
}

# Setup-Call METHOD [ARG]: asks NavBridge's setup provider (SetupProvider.kt); its answer, a Bundle, is in $script:Setup.
function Setup-Call([string]$Method, [string]$Arg = '') {
    $a = @('shell', 'content', 'call', '--uri', $PROVIDER, '--method', $Method)
    if ($Arg) { $a += @('--arg', $Arg) }
    $script:Setup = Run-Tool $script:Adb (@('-s', $script:Serial) + $a) -All
    return ($script:Setup -match 'Result: Bundle')
}

# Setup-Value NAME: one value from the last Setup-Call's answer, e.g. Setup-Value paired gives true.
function Setup-Value([string]$Name) {
    foreach ($piece in ($script:Setup -split '[{},\n]')) {
        if ($piece -match "^\s*$([regex]::Escape($Name))=(.*)$") { return $Matches[1].Trim() }
    }
    return ''
}

# The reason a Setup-Call failed, in a line.
function Setup-Error {
    foreach ($line in ($script:Setup -split "`n")) {
        if ($line -match 'Exception: (.*)$') { return $Matches[1] }
    }
    return (First-Line $script:Setup)
}

function Step-Phone {
    Step 'Phone: install NavBridge'
    Why "NavBridge reads Google Maps' turn-by-turn notification and passes each turn to the watch."
    Get-Adb
    Connect-Phone
    while (-not (Has-Package $GARMIN_CONNECT)) {
        Say "Garmin Connect isn't on this phone. NavMap reaches the phone through it. Opening it in the Play Store..."
        $null = Phone-Sh 'am' 'start' '-a' 'android.intent.action.VIEW' '-d' "market://details?id=$GARMIN_CONNECT"
        Pause-For 'Install Garmin Connect, sign in with your Garmin account and pair the watch.'
    }
    while (-not (Has-Package $GOOGLE_MAPS)) {
        Say "Google Maps isn't on this phone. Opening it in the Play Store..."
        $null = Phone-Sh 'am' 'start' '-a' 'android.intent.action.VIEW' '-d' "market://details?id=$GOOGLE_MAPS"
        Pause-For 'Install Google Maps.'
    }
    Install-NavBridge
    Say 'Switching on what NavBridge needs...'
    # Reading notifications: Google Maps' turn-by-turn notification is where the turns come from.
    $null = Phone-Sh 'cmd' 'notification' 'allow_listener' "$PKG/$PKG.NavListenerService"
    # Its own notification, which says when the watch needs a button press.
    if ($script:PhoneSdk -ge 33) { $null = Phone-Sh 'pm' 'grant' $PKG 'android.permission.POST_NOTIFICATIONS' }
    # No battery saving for NavBridge or Garmin Connect, so Android doesn't stop either one mid-ride.
    foreach ($app in @($PKG, $GARMIN_CONNECT)) {
        $null = Phone-Sh 'dumpsys' 'deviceidle' 'whitelist' "+$app"
        $null = Phone-Sh 'cmd' 'appops' 'set' $app 'RUN_ANY_IN_BACKGROUND' 'allow'
    }
    # And Android doesn't take its permissions back if it goes unused for a few months.
    if ($script:PhoneSdk -ge 30) { $null = Phone-Sh 'cmd' 'appops' 'set' $PKG 'AUTO_REVOKE_PERMISSIONS_IF_UNUSED' 'ignore' }
    if (-not (Setup-Call 'key' $script:MapsKey)) { Die "NavBridge didn't take the key: $(Setup-Error)" }
    $choice = Choose 'How do you usually travel?' `
        'Google plans the blue route line for this, and NavBridge starts Google Maps navigation in this mode.' `
        @('Motorbike or scooter', 'Car', 'Bicycle', 'Walking')
    $mode = @('MOTORBIKE', 'CAR', 'BICYCLE', 'WALK')[$choice - 1]
    if (-not (Setup-Call 'mode' $mode)) { Die "NavBridge didn't take the travel mode: $(Setup-Error)" }
    Check-PhoneSettings
    Wait-ForConsent
    Ok "NavBridge $(Setup-Value 'version') is installed and set up, with your key."
}

# Google's terms require the user's own OK before an app uses their location, so adb can't give it: NavBridge asks
# on its first screen, and this waits for the tap. Without it, NavMap shows no map or turns.
function Wait-ForConsent {
    if (-not (Setup-Call 'status')) { Die "NavBridge isn't answering: $(Setup-Error)" }
    if ((Setup-Value 'consent') -eq 'true') { return }
    $null = Phone-Sh 'am' 'start' '-n' "$PKG/.MainActivity"
    Bold 'On the phone: NavBridge shows "Before you start". Read it, then tap Agree.'
    Why "It says what NavBridge does with your location, and that Google's terms apply to the map and route line. Only you can agree to that."
    $waited = 0
    while ($true) {
        Start-Sleep -Seconds 2
        $waited += 2
        if (-not (Setup-Call 'status')) { Die "NavBridge isn't answering: $(Setup-Error)" }
        if ((Setup-Value 'consent') -eq 'true') { break }
        if ($waited -ge 180) {
            if (-not (Ask-Yes 'Still waiting for Agree on the phone. Keep waiting?' `
                        'Tapped Not now? In NavBridge, the first line under Setup, "Google Maps terms and your location", has a Read button.' 'Y')) {
                Die "NavMap shows nothing until you agree in NavBridge. When you're ready, tap Agree there, then run $RUN test."
            }
            $waited = 0
        }
    }
    Ok 'You agreed in NavBridge.'
}

function Install-NavBridge {
    Say 'Installing NavBridge... If the phone asks about the app (Play Protect, scanning), tap Install or Install anyway.'
    while ($true) {
        $out = Phone 'install' '-r' "$($script:Files)\NavBridge.apk"
        if ($out -match 'Success') {
            Ok 'NavBridge is installed.'
            return
        } elseif ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
            if (Ask-Yes 'A different build of NavBridge is on the phone. Replace it?' `
                    'Android only updates an app with one signed by the same maker. Its settings go, and this script sets them again.' 'Y') {
                $null = Phone 'uninstall' $PKG
            } else {
                Die "NavBridge wasn't replaced. Uninstall it on the phone, then run $RUN again."
            }
        } elseif ($out -match 'USER_RESTRICTED') {
            Say 'The phone blocks installs over USB. In Developer options, turn on "Install via USB" (Xiaomi phones may ask you to sign in first).'
            Pause-For 'Turn on Install via USB.'
        } elseif ($out -match 'VERIFICATION|REJECTED|[Bb]locked') {
            Say 'The phone stopped the install. On its screen, tap More details > Install anyway (Play Protect).'
            Pause-For 'Allow the install on the phone.'
        } else {
            Say $out
            Die "Android didn't install NavBridge."
        }
    }
}

# Opens the settings screen for what adb couldn't switch on, and waits until the user has.
function Check-PhoneSettings {
    for ($tries = 1; $tries -le 3; $tries++) {
        if (-not (Setup-Call 'status')) { Die "NavBridge isn't answering: $(Setup-Error)" }
        if ((Setup-Value 'listener') -eq 'true') { break }
        Say "This phone didn't let adb switch on NavBridge's notification access. Opening the setting on the phone..."
        $null = Phone-Sh 'am' 'start' '-a' 'android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS'
        Pause-For 'On the phone, turn on NavBridge in that list (Notification access / Device & app notifications).'
    }
    if ((Setup-Value 'listener') -ne 'true') { Die "NavBridge still can't read notifications, so it can't see Google Maps' turns." }
    if ((Setup-Value 'battery') -ne 'true') {
        Warn "Android kept battery saving on for NavBridge. If turns stop mid-ride, set NavBridge's Battery to Unrestricted in its App info."
    }
    if ((Setup-Value 'garminBattery') -ne 'true') {
        Warn 'Android kept battery saving on for Garmin Connect. If the watch loses the phone mid-ride, set its Battery to Unrestricted.'
    }
}

# ---- step: the watch -------------------------------------------------------------------------------------------

function Step-Watch {
    Step 'Watch: copy NavMap'
    Why "Garmin installs apps from outside its store only when they're copied over the watch's USB cable."
    Bold 'Plug the watch into this computer with its charging cable.'
    Copy-ToWatch
    Write-Host ''
    Write-Host -NoNewline -ForegroundColor White 'Unplug the watch now.'
    Write-Host ' It installs NavMap as it disconnects.'
    Set-Prop 'WATCH' $script:Watch $STATE
}

# Watch-Model PARTNUMBER: the build that fits, from GarminDevice.xml's part number, or nothing.
function Watch-Model([string]$Part) {
    if ($Part -like '006-B4432-*') { return 'fr165' }
    if ($Part -like '006-B4433-*') { return 'fr165m' }
    return ''
}

# Checks the watch's own part number against the answer in step 1; the watch wins.
function Check-Model([string]$Part) {
    if (-not $Part) { return }
    $model = Watch-Model $Part
    if (-not $model) {
        Die "This watch isn't a Forerunner 165 (its part number is $Part). NavMap only runs on the Forerunner 165 and 165 Music."
    }
    if ($model -ne $script:Watch) {
        Say "The watch says it's a $(Watch-Name $model), so that's the version it gets."
        $script:Watch = $model
    }
}

function Manual-Copy([string]$Reason) {
    Say $Reason
    Say 'Copy NavMap by hand instead:'
    Say "  1. Open File Explorer, then This PC: the watch shows up there as $(Watch-Name $script:Watch)."
    Say '  2. Copy this file into its GARMIN folder, then into Apps (if a NavMap file is there already, replace it):'
    Say "     $($script:Files)\NavMap-$($script:Watch).prg"
    try { Start-Process 'explorer.exe' "/select,`"$($script:Files)\NavMap-$($script:Watch).prg`"" } catch { }
    Pause-For 'Copy the file onto the watch.'
}

# A child of a Shell folder item by name, any case, or nothing.
function Shell-Child($Item, [string]$Name) {
    if (-not $Item -or -not $Item.IsFolder) { return $null }
    foreach ($child in $Item.GetFolder.Items()) { if ($child.Name -eq $Name) { return $child } }
    return $null
}

# The watch's GARMIN folder: on the watch itself, or in its one storage ("Internal Storage" or "Primary").
function Find-GarminFolder($Device) {
    $garmin = Shell-Child $Device 'GARMIN'
    if ($garmin) { return $garmin }
    foreach ($storage in $Device.GetFolder.Items()) {
        $garmin = Shell-Child $storage 'GARMIN'
        if ($garmin) { return $garmin }
    }
    return $null
}

function Mtp-Size($Item) {
    try { $size = $Item.ExtendedProperty('System.Size'); if ($size) { return [long]$size } } catch { }
    try { return [long]$Item.Size } catch { return [long]0 }
}

# Windows opens the watch (an MTP device, like an Android phone) by itself, in File Explorer under This PC. Scripts
# reach it through the same Shell, which copies in the background, so this waits for the file to arrive.
function Copy-ToWatch {
    $shell = New-Object -ComObject Shell.Application
    $garmin = $null
    $device = $null
    $waited = 0
    while (-not $garmin) {
        $devices = @($shell.Namespace(17).Items() | Where-Object { -not $_.IsFileSystem })
        $device = @($devices | Where-Object { $_.Name -match 'Forerunner|Garmin' }) | Select-Object -First 1
        if ($device) { $garmin = Find-GarminFolder $device }
        if (-not $garmin) {
            foreach ($d in $devices) {
                $garmin = Find-GarminFolder $d
                if ($garmin) { $device = $d; break }
            }
        }
        if ($garmin) { break }
        Start-Sleep -Seconds 2
        $waited += 2
        if ($waited -ge 60) {
            Manual-Copy "The watch doesn't show up here. (Is it plugged in, and is the cable a data cable?)"
            return
        }
    }
    $apps = Shell-Child $garmin 'Apps'
    if (-not $apps) {
        Manual-Copy "The watch is there but its GARMIN\Apps folder isn't."
        return
    }
    $xml = Shell-Child $garmin 'GarminDevice.xml'
    if ($xml) {
        $tmp = Join-Path $CACHE ('device-' + [guid]::NewGuid().ToString('N'))
        $null = New-Item -ItemType Directory -Force -Path $tmp
        $shell.Namespace($tmp).CopyHere($xml, 4 + 16 + 1024)
        for ($i = 0; $i -lt 30 -and -not (Test-Path "$tmp\GarminDevice.xml"); $i++) { Start-Sleep -Milliseconds 500 }
        Start-Sleep -Milliseconds 500
        if (Test-Path "$tmp\GarminDevice.xml") {
            $text = [IO.File]::ReadAllText("$tmp\GarminDevice.xml")
            if ($text -match '<PartNumber>([^<]*)<') { Check-Model $Matches[1] }
        }
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
    }
    # The Shell keeps the file's own name, so it goes over as NavMap.prg from a folder of its own.
    $src = Join-Path $CACHE ('copy-' + [guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Force -Path $src
    Copy-Item "$($script:Files)\NavMap-$($script:Watch).prg" "$src\NavMap.prg"
    $size = (Get-Item "$src\NavMap.prg").Length
    $old = Shell-Child $apps 'NavMap.prg'
    $oldSize = -1
    $oldDate = $null
    if ($old) {
        $oldSize = Mtp-Size $old
        $oldDate = $old.ModifyDate
        Say 'A NavMap is on the watch already. If Windows asks about replacing NavMap.prg, choose Replace the file.'
    }
    $apps.GetFolder.CopyHere("$src\NavMap.prg", 4 + 16 + 1024)
    $copied = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        $new = Shell-Child $apps 'NavMap.prg'
        if (-not $new) { continue }
        $newSize = Mtp-Size $new
        if (-not $old) {
            # Some watches only give the size once the copy is done; a new file that stays at 0 is taken as done.
            if ($newSize -eq $size -or ($newSize -eq 0 -and $i -ge 5)) { $copied = $true; break }
        } elseif ($newSize -eq $size -and ($oldSize -ne $size -or $new.ModifyDate -ne $oldDate)) {
            $copied = $true
            break
        }
    }
    # A file of the same size that didn't change is the same NavMap, already there.
    if (-not $copied -and $old -and $oldSize -eq $size) { $copied = $true }
    # The Shell reads the file in the background; it must not go before the copy has.
    Start-Sleep -Seconds 2
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $src
    if (-not $copied) {
        Manual-Copy 'Copying to the watch failed.'
        return
    }
    Ok 'Copied NavMap to the watch.'
}

# ---- step: pair and test ---------------------------------------------------------------------------------------

function Step-Test {
    Step 'Pair and test'
    Why 'The first time NavMap opens, it pairs with NavBridge and gets your Google Maps key from it.'
    Get-Adb
    Connect-Phone
    if (-not (Setup-Call 'status')) { Die "NavBridge isn't answering: $(Setup-Error). Run $RUN phone to install it again." }
    Wait-ForConsent
    if (-not (Setup-Call 'pair')) { Die "NavBridge isn't answering: $(Setup-Error). Run $RUN phone to install it again." }
    Bold 'On the watch: press START (top right), scroll to NavMap and press START again.'
    Say 'Keep the phone near the watch. Waiting for NavMap to ask NavBridge for turns...'
    $waited = 0
    $hint = ''
    while ($true) {
        if (-not (Setup-Call 'status')) { Die "NavBridge isn't answering: $(Setup-Error)" }
        if ((Setup-Value 'paired') -eq 'true' -and (Setup-Value 'watchHasKey') -eq 'true') { break }
        if ($waited -ge 20) {
            $garmin = Setup-Value 'garmin'
            $seen = Setup-Value 'watch'
            if ($garmin -ne 'READY' -and $hint -ne 'garmin') {
                $hint = 'garmin'
                Say "  NavBridge can't reach Garmin Connect yet: open Garmin Connect on the phone once."
            } elseif ($garmin -eq 'READY' -and -not $seen -and $hint -ne 'watch') {
                $hint = 'watch'
                Say "  Garmin Connect doesn't see the watch: open Garmin Connect and check it says the watch is connected."
            } elseif ((Setup-Value 'lastPollSecs') -eq '-1' -and $hint -ne 'open' -and $waited -ge 60) {
                $hint = 'open'
                Say '  NavMap hasn''t asked yet. Is it open on the watch? If the watch shows "Phone not connected", open Garmin Connect.'
            }
        }
        Start-Sleep -Seconds 3
        $waited += 3
        if ($waited -ge 240) {
            Say "NavMap still hasn't reached NavBridge. Check the hints above."
            Pause-For 'Open NavMap on the watch again.'
            $null = Setup-Call 'pair'
            $waited = 0
        }
    }
    Ok 'NavMap is paired with NavBridge and has your Google Maps key.'
    if (-not (Setup-Call 'simulate')) { Die "NavBridge couldn't start the demo ride: $(Setup-Error)" }
    Say 'NavBridge is playing a made-up ride now: the watch should show the next turn, its arrow and the distance.'
    Say '(The map itself waits for the watch''s GPS, which needs open sky.)'
    if (Ask-Yes 'Do you see the turn on the watch?' '' 'Y') {
        Ok 'Everything works.'
    } else {
        Say "Open NavBridge on the phone: its Watch card says what's missing. docs/SETUP.md has a troubleshooting list."
    }
    $null = Setup-Call 'stop'
}

function Finish {
    Write-Host ''
    Write-Host -NoNewline -ForegroundColor White 'All set.'
    Write-Host ' To use it:'
    Say '  1. Open Google Maps on the phone, choose where to go, tap Directions, then Start.'
    Say '  2. The watch asks to open NavMap: press START. (Or open NavMap yourself first.)'
    Say '  3. Outdoors, the map appears once the watch has GPS: a few seconds to a couple of minutes.'
    Say 'For the blue route line too: in Google Maps open the place, tap Share and choose Navigate with NavMap.'
    Say 'You can turn USB debugging off again: Settings > System > Developer options.'
    Say "Run $RUN again any time: it checks everything and fixes what's missing."
}

# ---- main ------------------------------------------------------------------------------------------------------

function Main([string[]]$Arguments) {
    $only = ''
    foreach ($a in $Arguments) {
        switch -regex ($a) {
            '^(key|phone|watch|test)$' { $only = $a }
            '^--from-source$' { Die 'Building the apps from source runs on a Mac or Linux (docs/DEVELOPING.md). On Windows, setup.ps1 uses the published app files.' }
            '^(-h|--help|/\?)$' {
                Get-Content $PSCommandPath -TotalCount 10 | ForEach-Object { $_ -replace '^# ?', '' }
                return
            }
            default { Die "Unknown option: $a. Run it with no option, or with key, phone, watch or test." }
        }
    }
    Check-Computer
    $script:Watch = Prop 'WATCH' $STATE
    if (-not $script:Watch) { $script:Watch = 'fr165' }
    $script:MapsKey = Prop 'MAPS_API_KEY' $SECRETS
    $script:Serial = ''
    if ($only) {
        switch ($only) {
            'key' { Step-Key }
            'phone' {
                if (-not $script:MapsKey) { Die "There's no Google Maps key yet: run $RUN key first." }
                Get-Files
                Step-Phone
            }
            'watch' {
                Get-Files
                Step-Watch
            }
            'test' { Step-Test }
        }
        return
    }
    Write-Host -ForegroundColor White 'NavMap setup'
    Say 'This puts NavMap on your Garmin Forerunner 165 and NavBridge on your Android phone, so the watch shows'
    Say 'the map and the next turn while Google Maps navigates on the phone.'
    Say ''
    Say 'Have these at hand:'
    Say '  - the watch and its charging cable'
    Say '  - the Android phone, with Garmin Connect and Google Maps, and a USB cable for it'
    Say '  - your Google account and a payment card (Google wants one on file for maps)'
    Say 'It takes about 20 minutes. Ctrl-C stops it at any point; run it again to carry on.'
    $null = Read-Host 'Press Enter to start'
    $script:Steps = 6
    Step-Devices
    Step-Key
    Step-Files
    Step-Phone
    Step-Watch
    Step-Test
    Finish
}

# A failure the script didn't expect still ends with a line on what to do. Die has said that already.
try {
    Main $args
} catch {
    if (-not $script:Done) {
        Write-Host ''
        Write-Host -ForegroundColor Red "X Something went wrong (setup.ps1, line $($_.InvocationInfo.ScriptLineNumber)): $($_.Exception.Message)"
        Write-Host "Run $RUN again: it carries on from where it is."
        Write-Host "If it keeps happening, open an issue at https://github.com/$REPO/issues with the lines above."
        exit 1
    }
}
