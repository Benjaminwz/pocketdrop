; PocketDrop 口袋快傳 Windows 安裝程式（Inno Setup 6）
; 不要直接編譯：執行 build_release.ps1，它會先做好 APK 和 PocketDrop.exe，再呼叫 ISCC。
; 要系統管理員權限，因為要替防火牆加上「私人＋公用網路」的允許規則（手機的 USB 網路會被 Windows 當成公用網路）。
; 測試用：ISCC /DTESTBUILD 會做一個不用管理員權限、不建捷徑、不動防火牆的版本，可以裝到任意資料夾試。

#ifndef AppVersion
  #define AppVersion "dev"
#endif

[Setup]
#ifdef TESTBUILD
AppId={{9E4A61C2-TEST-4F85-A1C6-000000000000}
PrivilegesRequired=lowest
#else
AppId={{9E4A61C2-7B3D-4F85-A1C6-2D8E5B0F7A31}
PrivilegesRequired=admin
#endif
AppName=PocketDrop
AppVersion={#AppVersion}
AppVerName=PocketDrop {#AppVersion}
AppPublisher=Benjaminwz
AppPublisherURL=https://github.com/Benjaminwz/pocketdrop
AppSupportURL=https://github.com/Benjaminwz/pocketdrop/issues
DefaultDirName={autopf}\PocketDrop
DisableProgramGroupPage=yes
UsedUserAreasWarning=no
OutputDir=..\dist
OutputBaseFilename=PocketDrop-Setup-{#AppVersion}
SetupIconFile=..\pc\icon.ico
UninstallDisplayIcon={app}\PocketDrop.exe
WizardStyle=modern
WizardImageFile=wizard.bmp,wizard_2x.bmp
WizardSmallImageFile=wizard_small.bmp,wizard_small_2x.bmp
Compression=lzma2/max
SolidCompression=yes
CloseApplications=force
ShowLanguageDialog=no
LanguageDetectionMethod=uilanguage

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zh"; MessagesFile: "ChineseTraditional.isl"

[CustomMessages]
en.AppTitle=PocketDrop
zh.AppTitle=口袋快傳
en.SendTo=PocketDrop (send to phone)
zh.SendTo=口袋快傳（傳到手機）
en.GroupOptions=Options:
zh.GroupOptions=選項：
en.TaskSendTo=Add PocketDrop to the right-click "Send to" menu
zh.TaskSendTo=在檔案右鍵的「傳送到」加入口袋快傳
en.TaskFirewall=Allow PocketDrop through Windows Firewall (Wi-Fi and USB cable)
zh.TaskFirewall=讓防火牆允許口袋快傳（Wi-Fi 和 USB 線都能連）
en.TaskStartup=Open PocketDrop when I sign in to Windows
zh.TaskStartup=開機時自動打開口袋快傳
en.LaunchApp=Open PocketDrop now (it will help you connect your phone)
zh.LaunchApp=現在打開口袋快傳（會帶你連接手機）

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "sendto"; Description: "{cm:TaskSendTo}"; GroupDescription: "{cm:AdditionalIcons}"
#ifndef TESTBUILD
Name: "firewall"; Description: "{cm:TaskFirewall}"; GroupDescription: "{cm:GroupOptions}"
#endif
Name: "startup"; Description: "{cm:TaskStartup}"; GroupDescription: "{cm:GroupOptions}"; Flags: unchecked

[Files]
Source: "..\dist\PocketDrop.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
#ifndef TESTBUILD
Name: "{autoprograms}\{cm:AppTitle}"; Filename: "{app}\PocketDrop.exe"; AppUserModelID: "PocketDrop.Desktop"
#endif
Name: "{autodesktop}\{cm:AppTitle}"; Filename: "{app}\PocketDrop.exe"; Tasks: desktopicon; AppUserModelID: "PocketDrop.Desktop"
Name: "{usersendto}\{cm:SendTo}"; Filename: "{app}\PocketDrop.exe"; Tasks: sendto
Name: "{userstartup}\{cm:AppTitle}"; Filename: "{app}\PocketDrop.exe"; Tasks: startup

[Run]
#ifndef TESTBUILD
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PocketDrop"""; Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PocketDrop"" dir=in action=allow program=""{app}\PocketDrop.exe"" enable=yes profile=private,public"; Flags: runhidden; Tasks: firewall
#endif
; 用原本的使用者身分（不是系統管理員）打開，不然從檔案總管拖檔案進視窗會被 Windows 擋掉
Filename: "{app}\PocketDrop.exe"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/im PocketDrop.exe /f"; Flags: runhidden; RunOnceId: "KillApp"
#ifndef TESTBUILD
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PocketDrop"""; Flags: runhidden; RunOnceId: "DelFirewall"
#endif