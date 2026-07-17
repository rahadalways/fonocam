; Fonocam Desktop installer (Inno Setup)
#ifndef AppVersion
  #define AppVersion "1.0"
#endif

[Setup]
AppName=Fonocam
AppVersion={#AppVersion}
AppPublisher=Rahad
AppPublisherURL=https://github.com/rahadalways/fonocam
DefaultDirName={autopf}\Fonocam
DefaultGroupName=Fonocam
UninstallDisplayIcon={app}\Fonocam.exe
SetupIconFile=fonocam.ico
OutputDir=dist
OutputBaseFilename=Fonocam-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; admin so we can register the Unity Capture virtual-camera driver
PrivilegesRequired=admin
; 64-bit install: the Unity Capture DLL is 64-bit, so it must live in the
; 64-bit Program Files and be registered with the 64-bit regsvr32
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
DisableProgramGroupPage=yes

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop icon"; GroupDescription: "Additional icons:"

[Files]
Source: "dist\Fonocam.exe"; DestDir: "{app}"; Flags: ignoreversion
; bundled virtual-camera driver (so users don't need OBS)
Source: "UnityCaptureFilter64bit.dll"; DestDir: "{app}"; Flags: ignoreversion regserver 64bit

[Icons]
Name: "{group}\Fonocam"; Filename: "{app}\Fonocam.exe"
Name: "{autodesktop}\Fonocam"; Filename: "{app}\Fonocam.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Fonocam.exe"; Description: "Launch Fonocam"; Flags: nowait postinstall skipifsilent
