# SmartKhata — Release Checklist & Deployment Manifest

**Product:** SmartKhata (স্মার্টখাতা)  
**Release Version:** 1.0.0  
**Target Platform:** Windows Desktop (x64)  
**Release Date:** 2026-11-09  
**Publisher Placeholder:** `SmartKhata OpenSource / Local Business Software`  

---

## ১. রিলিজ সারসংক্ষেপ ও মেমোরি/ডিস্ক সাইজ (Release & Artifact Sizes)

| আর্টফ্যাক্ট / উপাদান | অবস্থান / পাথ | সাইজ (MB) | স্ট্যাটাস |
| :--- | :--- | :--- | :--- |
| **Windows Single-File Installer:** | `SmartKhata-Setup.exe` (Root Base Dir) | **51.17 MB (53,656 KB)** | **VERIFIED** |
| **Standalone Native App Image:** | `dist/SmartKhata/` | **109.66 MB** | **VERIFIED** |
| **Embedded Custom JRE (Java 21):** | `dist/SmartKhata/runtime/` | **74.96 MB** | **VERIFIED** |
| **Application Libraries & Assets:** | `dist/SmartKhata/app/` | **34.24 MB** | **VERIFIED** |
| **Native Executable Launcher:** | `dist/SmartKhata/SmartKhata.exe` | **0.44 MB (442 KB)** | **VERIFIED** |
| **Portable Distribution ZIP:** | `dist/SmartKhata-1.0.0-windows-x64.zip` | **56.86 MB** | **VERIFIED** |
| **Local SQLite Database (Clean):** | `%LOCALAPPDATA%\SmartKhata\data\smartkhata.db` | **~0.15 MB** | **VERIFIED** |

---

## ২. বিল্ড ও প্যাকেজিং কমান্ড (Reproducible Build Commands)

### ক. সম্পূর্ণ অটোমেটেড প্যাকেজিং স্ক্রিপ্ট:
```powershell
# রুট ডিরেক্টরি থেকে রান করুন
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package-windows-app.ps1
```

### খ. স্টেপ-বাই-স্টেপ ম্যানুয়াল কমান্ড:
```powershell
# ১. মেভেন টেস্ট ও কম্পাইলেশন
mvn clean test package

# ২. রানটাইম ডিপেনডেন্সি সংগ্রহ
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs

# ৩. কাস্টম স্ট্রিপড JRE তৈরি (jlink)
jlink --no-header-files --no-man-pages --strip-debug `
      --add-modules java.base,java.desktop,java.sql,java.naming,java.xml,jdk.unsupported,java.logging,jdk.crypto.ec `
      --output target/custom-runtime

# ৪. jpackage দিয়ে সেলফ-কন্টেইন্ড নেটিভ অ্যাপ তৈরি
jpackage --type app-image `
         --dest dist `
         --name SmartKhata `
         --app-version 1.0.0 `
         --vendor "SmartKhata OpenSource / Local Business Software" `
         --icon src/main/resources/com/smartkhata/ui/images/app-icon.ico `
         --runtime-image target/custom-runtime `
         --input target/package-input `
         --main-jar smartkhata-1.0.0-SNAPSHOT.jar `
         --main-class com.smartkhata.app.Launcher
```

### গ. WiX MSI ইনস্টলার কমান্ড (WiX Toolset ৩.১১+ প্রয়োজন):
```powershell
# WiX candle.exe ও light.exe ইনস্টল থাকলে সরাসরি MSI তৈরি হবে
jpackage --type msi `
         --dest dist `
         --name SmartKhata `
         --app-version 1.0.0 `
         --vendor "SmartKhata OpenSource / Local Business Software" `
         --icon src/main/resources/com/smartkhata/ui/images/app-icon.ico `
         --runtime-image target/custom-runtime `
         --input target/package-input `
         --main-jar smartkhata-1.0.0-SNAPSHOT.jar `
         --main-class com.smartkhata.app.Launcher `
         --win-dir-chooser `
         --win-menu `
         --win-shortcut `
         --win-per-user-install
```

---

## ৩. ডিপেনডেন্সি ও থার্ড-পার্টি লাইসেন্স নোটিশ (Third-Party Licenses)

| প্যাকেজ / লাইব্রেরি | সংস্করণ | লাইসেন্স | ব্যবহারের ধরন |
| :--- | :--- | :--- | :--- |
| **OpenJDK 21 (Microsoft Hotspot)** | 21.0.13 | GPL v2 with Classpath Exception | Embedded Core Runtime |
| **OpenJFX (JavaFX)** | 21.0.5 | GPL v2 with Classpath Exception | UI Controls, Graphics, FXML |
| **AtlantaFX** | 2.0.1 | MIT License | Modern Desktop CSS Theme Engine |
| **SQLite JDBC (Xerial)** | 3.47.1.0 | Apache License 2.0 / Public Domain | Embedded Relational Database |
| **SLF4J API** | 2.0.16 | MIT License | Structured Logging Facade |
| **Logback Classic** | 1.5.12 | EPL 1.0 / LGPL 2.1 | Rolling File Logger |
| **iText 7 Core (Kernel & Layout)** | 7.2.5 | AGPL v3 / Commercial | PDF Export & Invoice Generation |
| **BouncyCastle Crypto Provider** | 1.70 | MIT-style License | Cryptographic Signatures & PDF Sec |

---

## ৪. নিরাপত্তা ও ডেটা স্টোরেজ গ্যারান্টি (Storage & Safety Invariants)

1. **লোকাল ফাইল পাথ:**  
   নতুন চালুর সময় স্বয়ংক্রিয়ভাবে `%LOCALAPPDATA%\SmartKhata\data\smartkhata.db` ফাইলে ডাটাবেজ তৈরি হয়।
2. **আপগ্রেড ও রি-ইনস্টল প্রতিরোধ:**  
   সফটওয়্যারের নতুন সংস্করণ ইনস্টল করলে বা পুরাতন ইনস্টলেশন রিমুভ করলে উইন্ডোজের নিয়ম অনুযায়ী `%LOCALAPPDATA%\SmartKhata\` ফোল্ডারটি কখনো ডিলিট হয় না। ফলে ব্যবহারকারীর আগের বিক্রয় চালান, স্টক ও বাকি হিসাব ১০০% সুরক্ষিত থাকে।
3. **শূন্য ক্লাউড নির্ভরশীলতা:**  
   অ্যাপ্লিকেশনটি চালানোর জন্য কোনো ইন্টারনেট কানেকশন, ক্লাউড একাউন্ট, SMS গেটওয়ে, বা ডিস্ট্রিবিউটেড সার্ভিসের প্রয়োজন নেই।

---

## ৫. সীমাবদ্ধতা ও অপারেশনাল শর্ত (Known Limitations)

1. **সিঙ্গেল-মেশিন আর্কিটেকচার:**  
   সফটওয়্যারটি একটি নির্দিষ্ট কম্পিউটারে একক দোকানের হিসাব রাখার জন্য তৈরি। রিয়েল-টাইম মাল্টি-ডিভাইস ল্যান শেয়ারিংয়ের জন্য লোকাল SQLite ফাইল একই সাথে একাধিক কম্পিউটারে রাইট মোডে ওপেন করা উচিত নয় (লকিং কনফ্লিক্ট এড়াতে)।
2. **অপারেটিং সিস্টেম সাপোর্ট:**  
   শুধুমাত্র Windows 10 এবং Windows 11 (64-bit) সমর্থিত। 32-bit Windows আর্কিটেকচারে চালানো যাবে না।
3. **উইন্ডোজ MSI ইনস্টলার বিল্ড:**  
   নন-অ্যাডমিন বিল্ড টার্মিনালে WiX 3.x সরাসরি ইনস্টল করা না থাকায় স্বয়ংক্রিয়ভাবে সেলফ-কন্টেইন্ড নেটিভ বান্ডেল (`dist/SmartKhata/SmartKhata.exe`) এবং ইনস্টলার স্ক্রিপ্ট (`install-smartkhata.bat`) তৈরি করা হয়েছে। WiX 3.11+ অ্যাডমিন এনভায়রনমেন্টে থাকলে এক ক্লিকেই `.msi` প্যাকেজ আউটপুট হবে।

---

## ৬. ব্যাকআপ ও মেইনটেন্যান্স সুপারিশ (Backup Policy)

- **সাপ্তাহিক এক্সটার্নাল ব্যাকআপ:** প্রতি শুক্রবার বা সপ্তাহের শেষে `Settings -> Back Up Now` থেকে এক্সটার্নাল পেনড্রাইভে একটি ব্যাকআপ ফাইল সংরক্ষণ করুন।
- **মাস্টার রিকভারি কোড:** প্রথম অনবোর্ডিংয়ের সময় পাওয়া `SK-XXXX-XXXX-...` কোডটি ডায়েরিতে সংরক্ষণ করুন।

