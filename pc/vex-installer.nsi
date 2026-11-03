; ============================================================
;  Vex VPN - PC Edition installer (NSIS 3, per-user, no UAC)
; ============================================================
Unicode true
ManifestDPIAware true

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"

!define APPNAME      "Vex VPN"
!define APPVER       "1.1.0"
!define REGKEY       "Software\Microsoft\Windows\CurrentVersion\Uninstall\VexVPN"
!define PROXYREG     "Software\Microsoft\Windows\CurrentVersion\Internet Settings"

Name "${APPNAME} (${APPVER})"
OutFile "Vex-PC-Setup-v1.1.exe"
InstallDir "$LOCALAPPDATA\Programs\Vex VPN"
InstallDirRegKey HKCU "Software\VexVPN" "InstallDir"
RequestExecutionLevel user
BrandingText "Vex VPN PC ${APPVER}"

VIProductVersion "1.1.0.0"
VIAddVersionKey "ProductName"     "Vex VPN (PC)"
VIAddVersionKey "FileDescription" "Vex VPN - Windows Installer"
VIAddVersionKey "FileVersion"     "1.1.0.0"
VIAddVersionKey "ProductVersion"  "1.1.0.0"
VIAddVersionKey "LegalCopyright"  "Vex"

Icon          "vex.ico"
UninstallIcon "vex.ico"

!define MUI_ICON   "vex.ico"
!define MUI_UNICON "vex.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION "LaunchVex"
!define MUI_FINISHPAGE_RUN_TEXT "اجرای Vex VPN"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Farsi"
!insertmacro MUI_LANGUAGE "English"

Function .onInit
  SetShellVarContext current
FunctionEnd

; ---------------- install ----------------
Section "Install"
  SetOutPath "$INSTDIR"

  ; stop any running instance before replacing files
  nsExec::Exec 'taskkill /F /IM sing-box.exe'
  Pop $0
  Sleep 400

  File "vex.ps1"
  File "Vex-PC.bat"
  File "README.txt"
  File "servers.json"
  File "vex.ico"
  File "sing-box.exe"
  File "libcronet.dll"

  ; shortcuts (per-user shell context)
  CreateDirectory "$SMPROGRAMS\Vex VPN"
  CreateShortcut  "$SMPROGRAMS\Vex VPN\Vex VPN.lnk" \
                  "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" \
                  '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\vex.ps1"' \
                  "$INSTDIR\vex.ico" 0
  CreateShortcut  "$SMPROGRAMS\Vex VPN\README.lnk" "$INSTDIR\README.txt"
  CreateShortcut  "$SMPROGRAMS\Vex VPN\Uninstall Vex VPN.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortcut  "$DESKTOP\Vex VPN.lnk" \
                  "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" \
                  '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\vex.ps1"' \
                  "$INSTDIR\vex.ico" 0

  ; registry: remember dir + Add/Remove Programs entry
  WriteRegStr HKCU "Software\VexVPN" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${REGKEY}" "DisplayName"     "Vex VPN (PC)"
  WriteRegStr HKCU "${REGKEY}" "DisplayVersion"  "${APPVER}"
  WriteRegStr HKCU "${REGKEY}" "Publisher"       "Vex"
  WriteRegStr HKCU "${REGKEY}" "DisplayIcon"     "$INSTDIR\vex.ico"
  WriteRegStr HKCU "${REGKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${REGKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${REGKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${REGKEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${REGKEY}" "EstimatedSize" 92160

  WriteUninstaller "$INSTDIR\Uninstall.exe"
SectionEnd

Function LaunchVex
  Exec '"$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\vex.ps1"'
FunctionEnd

; ---------------- uninstall ----------------
Section "Uninstall"
  ; stop core
  nsExec::Exec 'taskkill /F /IM sing-box.exe'
  Pop $0
  Sleep 400

  ; turn off our system proxy (only if it points to Vex)
  ReadRegStr $0 HKCU "${PROXYREG}" "ProxyServer"
  ${If} $0 == "127.0.0.1:2080"
    WriteRegDWORD HKCU "${PROXYREG}" "ProxyEnable" 0
  ${EndIf}

  Delete "$SMPROGRAMS\Vex VPN\Vex VPN.lnk"
  Delete "$SMPROGRAMS\Vex VPN\README.lnk"
  Delete "$SMPROGRAMS\Vex VPN\Uninstall Vex VPN.lnk"
  RMDir  "$SMPROGRAMS\Vex VPN"
  Delete "$DESKTOP\Vex VPN.lnk"

  Delete "$INSTDIR\vex.ps1"
  Delete "$INSTDIR\Vex-PC.bat"
  Delete "$INSTDIR\README.txt"
  Delete "$INSTDIR\servers.json"
  Delete "$INSTDIR\vex.ico"
  Delete "$INSTDIR\sing-box.exe"
  Delete "$INSTDIR\libcronet.dll"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir  "$INSTDIR"

  ; app data (config, log, selected server)
  Delete "$LOCALAPPDATA\VexPC\config.json"
  Delete "$LOCALAPPDATA\VexPC\singbox.log"
  Delete "$LOCALAPPDATA\VexPC\selected.txt"
  RMDir  "$LOCALAPPDATA\VexPC"

  DeleteRegKey HKCU "${REGKEY}"
  DeleteRegKey HKCU "Software\VexVPN"
SectionEnd
