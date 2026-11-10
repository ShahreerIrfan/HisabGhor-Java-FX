# SmartKhata — Desktop Application

SmartKhata (স্মার্টখাতা) is a native, offline-first desktop bookkeeping and inventory management application designed specifically for retail and wholesale businesses in Bangladesh. It functions 100% offline with zero cloud, internet, MySQL, XAMPP, or external JRE dependencies.

---

## 1. Technical Baseline

- **Operating System:** Windows 10 / 11 (64-bit)
- **Runtime:** Java 21 LTS (Microsoft OpenJDK / Temurin 21) — bundled as custom embedded JRE (74.96 MB)
- **Build Tool:** Apache Maven 3.9+
- **UI Framework:** JavaFX 21 + AtlantaFX 2.0.1 (Native macOS/iOS-inspired restrained theme)
- **Persistence:** SQLite 3.47 via JDBC with WAL (Write-Ahead Logging) mode
- **Reporting Engine:** iText 7.2 Core (Unicode-safe PDF generation) & RFC 4180 CSV export
- **Security & Integrity:** PBKDF2WithHmacSHA256 (100k iterations) with per-user salt, SQLite `VACUUM INTO` backup engine with SHA-256 manifest
- **Packaging:** Self-contained Windows App Image (`SmartKhata.exe`) via `jlink` + `jpackage`

---

## 2. Standalone Windows Distribution (No Setup / No Java Required)

A pre-packaged, fully self-contained distribution is ready in `dist/`:
- **Executable Launcher:** `dist\SmartKhata\SmartKhata.exe` (Embedded icon & JRE 21)
- **Portable ZIP Archive:** `dist\SmartKhata-1.0.0-windows-x64.zip` (58.38 MB)
- **1-Click Local Installer:** Double-click `scripts\install-smartkhata.bat` to install into `%LOCALAPPDATA%\Programs\SmartKhata` and generate Desktop + Start Menu shortcuts (no Administrator rights required).

---

## 3. Quick Start & Execution (Development Mode)

### Prerequisites
Verify that Java 21 LTS and Maven are installed and available on your PATH:
```powershell
java -version
mvn -version
```

### Launch Application (Development Mode)
Launch the desktop UI directly via the JavaFX Maven plugin:
```powershell
mvn javafx:run
```

### Build & Run Tests
Execute the full automated test suite (22 test classes, 137 tests verifying accounting invariants, stock movements, idempotency, multi-tenant security, and JavaFX UI launch smoke tests):
```powershell
mvn clean test
```

### Automated Windows Packaging Pipeline
To reproduce the standalone standalone bundle with custom JRE:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package-windows-app.ps1
```

---

## 4. Application Directories & Data Protection

All business records and operational logs are stored under the standard Windows per-user `%LOCALAPPDATA%` directory, isolated from program binaries:
- **Application Home:** `%LOCALAPPDATA%\SmartKhata\`
- **Database:** `%LOCALAPPDATA%\SmartKhata\data\smartkhata.db`
- **Rolling Logs:** `%LOCALAPPDATA%\SmartKhata\logs\smartkhata.log`
- **Backups:** `%LOCALAPPDATA%\SmartKhata\backups\`

> [!NOTE]
> **Data Preservation Guarantee:**  
> Uninstalling or upgrading SmartKhata removes only program files; your financial database at `%LOCALAPPDATA%\SmartKhata\data\` is 100% retained and protected.

---

## 5. Documentation & Release Artifacts

- **End-User Manual (Bengali):** [`docs/user-manual.md`](docs/user-manual.md)
- **Release Checklist & Licensing:** [`docs/release-checklist.md`](docs/release-checklist.md)
- **Full Specification & Traceability:** [`docs/SRS.md`](docs/SRS.md)
- **Complete Implementation & Audit Log:** [`docs/implementation-status.md`](docs/implementation-status.md)


