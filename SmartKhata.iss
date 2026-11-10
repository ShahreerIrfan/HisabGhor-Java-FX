; =====================================================================
; SmartKhata — Professional Windows Desktop Installer Script (Inno Setup 6)
; Creates a self-contained, offline installer executable (SmartKhata-Setup.exe)
; Bundles embedded OpenJDK 21 LTS, JavaFX 21, and SQLite JDBC.
; Zero external dependencies (No JRE, JDK, MySQL, or Internet required).
; =====================================================================

#define MyAppName "SmartKhata"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "SmartKhata Contributors"
#define MyAppURL "https://github.com/smartkhata"
#define MyAppExeName "SmartKhata.exe"
#define SourceAppDir "dist\SmartKhata"

[Setup]
AppId={{D37F8A12-87C2-4C10-9E3E-5C1890B2E101}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
; Support per-user install without requiring administrator elevation
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
OutputDir=d:\Projects\Tali-Khata
OutputBaseFilename=SmartKhata-Setup
SetupIconFile=src\main\resources\com\smartkhata\ui\images\icons\smartkhata.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
DisableWelcomePage=no
DisableDirPage=no
DisableProgramGroupPage=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "quicklaunchicon"; Description: "Create a Quick Launch icon"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceAppDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; IconFilename: "{app}\{#MyAppExeName}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Messages]
FinishedHeadingLabel=SmartKhata Installation Complete
FinishedLabel=SmartKhata has been successfully installed on your computer. Your business data and books will be stored safely in your local application directory.%n%nClick Finish to launch SmartKhata.

