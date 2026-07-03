; Fonocam Desktop installer (Inno Setup)
#ifndef AppVersion
  #define AppVersion "1.0"
#endif

[Setup]
AppName=Fonocam
AppVersion={#AppVersion}
AppPublisher=Rahad
AppPublisherURL=https://github.com/rahadalways/camconnect
DefaultDirName={autopf}\Fonocam
DefaultGroupName=Fonocam
UninstallDisplayIcon={app}\Fonocam.exe
SetupIconFile=fonocam.ico
OutputDir=dist
OutputBaseFilename=Fonocam-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
DisableProgramGroupPage=yes

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop icon"; GroupDescription: "Additional icons:"

[Files]
Source: "dist\Fonocam.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Fonocam"; Filename: "{app}\Fonocam.exe"
Name: "{autodesktop}\Fonocam"; Filename: "{app}\Fonocam.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Fonocam.exe"; Description: "Launch Fonocam"; Flags: nowait postinstall skipifsilent
