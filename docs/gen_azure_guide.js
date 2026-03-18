const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  Header, Footer, AlignmentType, HeadingLevel, BorderStyle, WidthType,
  ShadingType, VerticalAlign, PageNumber, ExternalHyperlink,
  LevelFormat, TableOfContents
} = require("docx");
const fs = require("fs");

// Brand colours
const RED   = "8B1A1A";
const LGREY = "F5F5F5";
const DGREY = "4A4A4A";
const WHITE = "FFFFFF";
const BLUE  = "0078D4";

// Border helper
const thinBorder = (color) => ({ style: BorderStyle.SINGLE, size: 1, color });
const allBorders  = (c) => ({ top: thinBorder(c), bottom: thinBorder(c), left: thinBorder(c), right: thinBorder(c) });

// Cell helper
function hdrCell(text, widthDxa) {
  return new TableCell({
    borders: allBorders("AAAAAA"),
    width: { size: widthDxa, type: WidthType.DXA },
    shading: { fill: RED, type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({
      alignment: AlignmentType.LEFT,
      children: [new TextRun({ text, bold: true, color: WHITE, font: "Arial", size: 20 })]
    })]
  });
}

function dataCell(text, widthDxa, mono = false) {
  return new TableCell({
    borders: allBorders("CCCCCC"),
    width: { size: widthDxa, type: WidthType.DXA },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({
      children: [new TextRun({ text, font: mono ? "Courier New" : "Arial", size: 18, color: DGREY })]
    })]
  });
}

function codeBlock(lines) {
  return new Table({
    width: { size: 9360, type: WidthType.DXA },
    columnWidths: [9360],
    rows: [new TableRow({
      children: [new TableCell({
        borders: allBorders("AAAAAA"),
        width: { size: 9360, type: WidthType.DXA },
        shading: { fill: "1E1E1E", type: ShadingType.CLEAR },
        margins: { top: 120, bottom: 120, left: 180, right: 180 },
        children: lines.map(l => new Paragraph({
          children: [new TextRun({ text: l, font: "Courier New", size: 18, color: "D4D4D4" })]
        }))
      })]
    })]
  });
}

function h1(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    spacing: { before: 360, after: 120 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: RED, space: 4 } },
    children: [new TextRun({ text, bold: true, font: "Arial", size: 32, color: RED })]
  });
}

function h2(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_2,
    spacing: { before: 240, after: 80 },
    children: [new TextRun({ text, bold: true, font: "Arial", size: 26, color: DGREY })]
  });
}

function h3(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_3,
    spacing: { before: 160, after: 60 },
    children: [new TextRun({ text, bold: true, font: "Arial", size: 22, color: DGREY })]
  });
}

function body(text, { bold = false, mono = false } = {}) {
  return new Paragraph({
    spacing: { after: 80 },
    children: [new TextRun({ text, font: mono ? "Courier New" : "Arial", size: 20, bold, color: DGREY })]
  });
}

function note(text) {
  return new Table({
    width: { size: 9360, type: WidthType.DXA },
    columnWidths: [9360],
    rows: [new TableRow({
      children: [new TableCell({
        borders: allBorders("0078D4"),
        width: { size: 9360, type: WidthType.DXA },
        shading: { fill: "EBF3FB", type: ShadingType.CLEAR },
        margins: { top: 80, bottom: 80, left: 160, right: 160 },
        children: [new Paragraph({
          children: [
            new TextRun({ text: "ℹ  NOTE: ", bold: true, font: "Arial", size: 18, color: BLUE }),
            new TextRun({ text, font: "Arial", size: 18, color: DGREY })
          ]
        })]
      })]
    })]
  });
}

function warn(text) {
  return new Table({
    width: { size: 9360, type: WidthType.DXA },
    columnWidths: [9360],
    rows: [new TableRow({
      children: [new TableCell({
        borders: allBorders("D4880A"),
        width: { size: 9360, type: WidthType.DXA },
        shading: { fill: "FEF3CD", type: ShadingType.CLEAR },
        margins: { top: 80, bottom: 80, left: 160, right: 160 },
        children: [new Paragraph({
          children: [
            new TextRun({ text: "⚠  WARNING: ", bold: true, font: "Arial", size: 18, color: "D4880A" }),
            new TextRun({ text, font: "Arial", size: 18, color: DGREY })
          ]
        })]
      })]
    })]
  });
}

function numbered(items) {
  return items.map((text, i) => new Paragraph({
    numbering: { reference: "steps", level: 0 },
    spacing: { after: 60 },
    children: [new TextRun({ text, font: "Arial", size: 20, color: DGREY })]
  }));
}

function bullets(items) {
  return items.map(text => new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    spacing: { after: 40 },
    children: [new TextRun({ text, font: "Arial", size: 20, color: DGREY })]
  }));
}

function gap() { return new Paragraph({ spacing: { after: 120 }, children: [] }); }

// ── Document ──────────────────────────────────────────────────────────────────
const doc = new Document({
  numbering: {
    config: [
      {
        reference: "steps",
        levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } } }]
      },
      {
        reference: "bullets",
        levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 720, hanging: 360 } } } }]
      }
    ]
  },
  styles: {
    default: { document: { run: { font: "Arial", size: 20, color: DGREY } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: "Arial", color: RED },
        paragraph: { spacing: { before: 360, after: 120 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 26, bold: true, font: "Arial", color: DGREY },
        paragraph: { spacing: { before: 240, after: 80 }, outlineLevel: 1 } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 22, bold: true, font: "Arial", color: DGREY },
        paragraph: { spacing: { before: 160, after: 60 }, outlineLevel: 2 } },
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { width: 12240, height: 15840 },
        margin: { top: 1440, right: 1080, bottom: 1440, left: 1080 }
      }
    },
    headers: {
      default: new Header({
        children: [
          new Paragraph({
            border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: RED, space: 4 } },
            children: [
              new TextRun({ text: "Vamanit\u00AE Calendar", bold: true, font: "Arial", size: 20, color: RED }),
              new TextRun({ text: "   |   Azure Tenant Onboarding Guide", font: "Arial", size: 20, color: DGREY })
            ]
          })
        ]
      })
    },
    footers: {
      default: new Footer({
        children: [
          new Paragraph({
            alignment: AlignmentType.CENTER,
            border: { top: { style: BorderStyle.SINGLE, size: 2, color: "CCCCCC", space: 4 } },
            children: [
              new TextRun({ text: "Vamanit\u00AE Calendar  \u2022  CONFIDENTIAL  \u2022  Page ", font: "Arial", size: 16, color: "888888" }),
              new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 16, color: "888888" })
            ]
          })
        ]
      })
    },
    children: [

      // ── COVER ──────────────────────────────────────────────────────────────
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 1440, after: 480 },
        children: [new TextRun({ text: "Vamanit\u00AE Calendar", bold: true, font: "Arial", size: 72, color: RED })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { after: 240 },
        children: [new TextRun({ text: "Azure Tenant Onboarding Guide", font: "Arial", size: 40, color: DGREY })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { after: 1440 },
        children: [new TextRun({ text: "Step-by-step instructions for adding the app to a new Microsoft 365 tenant", font: "Arial", size: 22, color: "888888" })]
      }),

      // metadata table
      new Table({
        width: { size: 6480, type: WidthType.DXA },
        columnWidths: [2160, 4320],
        rows: [
          new TableRow({ children: [hdrCell("Document", 2160), dataCell("Azure Tenant Onboarding Guide", 4320)] }),
          new TableRow({ children: [hdrCell("Version", 2160), dataCell("1.0", 4320)] }),
          new TableRow({ children: [hdrCell("Date", 2160), dataCell(new Date().toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" }), 4320)] }),
          new TableRow({ children: [hdrCell("Audience", 2160), dataCell("IT Administrators / App Owners", 4320)] }),
          new TableRow({ children: [hdrCell("Package ID", 2160), dataCell("com.vamanit.calendar", 4320, true)] }),
          new TableRow({ children: [hdrCell("Reference App", 2160), dataCell("vamanit-oss/vamanit-calendar (GitHub)", 4320)] }),
        ]
      }),

      // ── TABLE OF CONTENTS ──────────────────────────────────────────────────
      new Paragraph({ children: [new TextRun({ text: "" })], pageBreakBefore: true }),
      new Paragraph({
        spacing: { before: 0, after: 240 },
        children: [new TextRun({ text: "Table of Contents", bold: true, font: "Arial", size: 28, color: RED })]
      }),
      new TableOfContents("Table of Contents", { hyperlink: true, headingStyleRange: "1-3" }),

      // ── SECTION 1: OVERVIEW ───────────────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("1. Overview"),
      body("Vamanit\u00AE Calendar is a native Android application that connects to Microsoft 365 (via Microsoft Graph API) and Google Calendar to display a unified schedule dashboard. It is designed for both Android TV kiosks and smartphones."),
      gap(),
      body("This guide walks an IT administrator through registering the application in a new Microsoft Entra ID (Azure AD) tenant so that users in that organisation can sign in with their Microsoft 365 accounts and grant calendar access."),
      gap(),
      h2("1.1 Architecture Summary"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [2800, 6560],
        rows: [
          new TableRow({ children: [hdrCell("Component", 2800), hdrCell("Technology", 6560)] }),
          new TableRow({ children: [dataCell("Microsoft Auth", 2800), dataCell("MSAL (Microsoft Authentication Library) for Android", 6560)] }),
          new TableRow({ children: [dataCell("Google Auth", 2800), dataCell("AppAuth (net.openid:appauth 0.11.1 — Apache 2.0)", 6560)] }),
          new TableRow({ children: [dataCell("MS Calendar API", 2800), dataCell("Microsoft Graph REST API v1.0 via OkHttp", 6560)] }),
          new TableRow({ children: [dataCell("Google Calendar", 2800), dataCell("google-api-services-calendar via AppAuth credential", 6560)] }),
          new TableRow({ children: [dataCell("Min Android SDK", 2800), dataCell("API 26 (Android 8.0 Oreo)", 6560)] }),
          new TableRow({ children: [dataCell("License", 2800), dataCell("MIT (all dependencies Apache 2.0 / MIT / BSD-3)", 6560)] }),
        ]
      }),

      // ── SECTION 2: PREREQUISITES ──────────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("2. Prerequisites"),
      h2("2.1 Required Accounts & Permissions"),
      ...bullets([
        "Microsoft Entra ID (Azure AD) — Global Administrator or Application Administrator role",
        "Access to the debug or release keystore for the APK you are distributing",
        "Android SDK with build-tools installed (to run keytool)",
        "The signed APK file: app-release.apk (or app-debug.apk for testing)",
      ]),
      gap(),
      h2("2.2 Information to Collect Before Starting"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [3600, 5760],
        rows: [
          new TableRow({ children: [hdrCell("Item", 3600), hdrCell("Where to Find It", 5760)] }),
          new TableRow({ children: [dataCell("APK package name", 3600), dataCell("com.vamanit.calendar (release) or com.vamanit.calendar.debug (debug)", 5760, true)] }),
          new TableRow({ children: [dataCell("Keystore SHA-1 fingerprint", 3600), dataCell("Run keytool command — see Section 3", 5760)] }),
          new TableRow({ children: [dataCell("Azure tenant domain", 3600), dataCell("Microsoft 365 admin centre or Azure portal (e.g. thinkcloud.in)", 5760)] }),
          new TableRow({ children: [dataCell("Tenant ID", 3600), dataCell("Azure portal \u2192 Microsoft Entra ID \u2192 Overview \u2192 Tenant ID", 5760)] }),
        ]
      }),

      // ── SECTION 3: SHA-1 FINGERPRINT ─────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("3. Obtain Your Keystore SHA-1 Fingerprint"),
      body("The MSAL redirect URI is derived from the APK\u2019s signing certificate SHA-1 fingerprint. You must supply the correct fingerprint for the keystore that will sign the distributed APK."),
      gap(),
      h2("3.1 Debug Keystore (Development / Testing)"),
      body("Run the following command in Terminal:"),
      gap(),
      codeBlock([
        "keytool -list -v \\",
        "  -keystore ~/.android/debug.keystore \\",
        "  -alias androiddebugkey \\",
        "  -storepass android \\",
        "  -keypass android",
      ]),
      gap(),
      body("Look for the line that starts with SHA1: in the output. Example output:"),
      gap(),
      codeBlock([
        "Certificate fingerprints:",
        "   SHA1: 47:00:FE:07:75:81:84:74:28:0E:4E:7D:36:AB:DF:1A:FC:87:9D:4B",
        "   SHA256: ...",
      ]),
      gap(),
      h2("3.2 Release Keystore (Production)"),
      codeBlock([
        "keytool -list -v \\",
        "  -keystore /path/to/release.keystore \\",
        "  -alias YOUR_ALIAS \\",
        "  -storepass YOUR_STORE_PASSWORD",
      ]),
      gap(),
      h2("3.3 Convert SHA-1 to Base64 (Required for Redirect URI)"),
      body("MSAL requires the SHA-1 bytes encoded as Base64 in the redirect URI. Run:"),
      gap(),
      codeBlock([
        "python3 -c \"",
        "import base64, binascii",
        "sha1_hex = '4700FE0775818474280E4E7D36ABDF1AFC879D4B'  # <-- replace with YOUR SHA1 (no colons)",
        "sha1_bytes = binascii.unhexlify(sha1_hex)",
        "print(base64.b64encode(sha1_bytes).decode())",
        "\"",
      ]),
      gap(),
      body("Note the output (e.g. RwD+B3WBhHQoDk59NqvfGvyHnUs=). You will use it in Section 4."),
      gap(),
      note("Remove the colons from the SHA-1 before passing to binascii.unhexlify(). The debug keystore SHA-1 for the reference build is 47:00:FE:07:75:81:84:74:28:0E:4E:7D:36:AB:DF:1A:FC:87:9D:4B."),

      // ── SECTION 4: AZURE APP REGISTRATION ────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("4. Register the App in Azure (Entra ID)"),
      h2("4.1 Open App Registrations"),
      ...numbered([
        "Sign in to https://portal.azure.com with a Global Administrator or Application Administrator account in the target tenant.",
        "In the left sidebar, search for or navigate to Microsoft Entra ID.",
        "Click App registrations in the left menu.",
        "Click + New registration at the top."
      ]),
      gap(),
      h2("4.2 Fill in the Registration Form"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [3000, 6360],
        rows: [
          new TableRow({ children: [hdrCell("Field", 3000), hdrCell("Value to Enter", 6360)] }),
          new TableRow({ children: [dataCell("Name", 3000), dataCell("Vamanit Calendar", 6360)] }),
          new TableRow({ children: [dataCell("Supported account types", 3000), dataCell("Any Entra ID Tenant + Personal Microsoft accounts (recommended for multi-tenant use)", 6360)] }),
          new TableRow({ children: [dataCell("Redirect URI \u2014 Platform", 3000), dataCell("Public client/native (mobile & desktop)", 6360)] }),
          new TableRow({ children: [dataCell("Redirect URI \u2014 Value", 3000), dataCell("msauth://com.vamanit.calendar/<BASE64_SHA1>  (replace <BASE64_SHA1> with your value from Section 3.3)", 6360, true)] }),
        ]
      }),
      gap(),
      warn("Use the package name that matches your APK: com.vamanit.calendar for release builds. For debug builds append .debug (com.vamanit.calendar.debug) and add a second redirect URI."),
      gap(),
      ...numbered([
        "Click Register. Azure will create the application.",
        "On the Overview page, copy the Application (client) ID (a UUID like 55e73e24-...). You will need it in Section 6."
      ]),
      gap(),
      h2("4.3 Add API Permissions"),
      ...numbered([
        "In the left menu, click API permissions.",
        "Click + Add a permission.",
        "Select Microsoft Graph.",
        "Choose Delegated permissions.",
        "Search for Calendars and expand the Calendars section.",
        "Check Calendars.Read (required) and Calendars.Read.Shared (optional \u2014 for shared/room calendars).",
        "Click Add permissions.",
        "Back on the API permissions page, also verify User.Read is listed (added by default).",
        "(Optional) Click Grant admin consent for <your-tenant> to pre-approve permissions for all users in the organisation, so they are not prompted individually."
      ]),
      gap(),
      note("Calendars.Read and Calendars.Read.Shared both have Admin consent required = No, so users can grant them themselves during first sign-in without administrator action."),

      // ── SECTION 5: CONFIGURE THE APP ─────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("5. Configure the App Source Code"),
      body("These changes are made once in the repository and apply to every APK build distributed to any tenant."),
      gap(),
      h2("5.1 Update msal_config.json"),
      body("File location: app/src/main/res/raw/msal_config.json"),
      gap(),
      body("Replace the placeholder values with the real Application (client) ID and redirect URI obtained in Section 4:"),
      gap(),
      codeBlock([
        "{",
        "  \"client_id\": \"<YOUR_APPLICATION_CLIENT_ID>\",",
        "  \"authorization_user_agent\": \"DEFAULT\",",
        "  \"redirect_uri\": \"msauth://com.vamanit.calendar/<BASE64_SHA1>\",",
        "  \"account_mode\": \"MULTIPLE\",",
        "  \"broker_redirect_uri_registered\": false,",
        "  \"authorities\": [",
        "    {",
        "      \"type\": \"AAD\",",
        "      \"audience\": {",
        "        \"type\": \"AzureADandPersonalMicrosoftAccount\",",
        "        \"tenant_id\": \"common\"",
        "      }",
        "    }",
        "  ]",
        "}",
      ]),
      gap(),
      note("Set tenant_id to common to allow sign-in from any tenant. Use your specific tenant ID to restrict sign-in to a single organisation."),
      gap(),
      h2("5.2 Update AndroidManifest.xml"),
      body("Ensure the MSAL redirect URI intent filter uses the correct package name and Base64 SHA-1 hash:"),
      gap(),
      codeBlock([
        "<activity",
        "    android:name=\"com.microsoft.identity.client.BrowserTabActivity\"",
        "    android:exported=\"true\">",
        "  <intent-filter>",
        "    <action android:name=\"android.intent.action.VIEW\" />",
        "    <category android:name=\"android.intent.category.DEFAULT\" />",
        "    <category android:name=\"android.intent.category.BROWSABLE\" />",
        "    <data",
        "        android:scheme=\"msauth\"",
        "        android:host=\"com.vamanit.calendar\"",
        "        android:path=\"/<BASE64_SHA1>\" />",
        "  </intent-filter>",
        "</activity>",
      ]),
      gap(),
      warn("The android:path must begin with a forward slash / followed immediately by the Base64 SHA-1 (no extra characters)."),
      gap(),
      h2("5.3 Build the APK"),
      body("Build a signed release APK using Gradle:"),
      gap(),
      codeBlock([
        "# Debug build (uses debug keystore automatically)",
        "./gradlew assembleDebug",
        "",
        "# Release build (requires signing config in build.gradle.kts)",
        "./gradlew assembleRelease",
      ]),
      gap(),
      body("The output APK will be at:"),
      ...bullets([
        "Debug:   app/build/outputs/apk/debug/app-debug.apk",
        "Release: app/build/outputs/apk/release/app-release.apk",
      ]),

      // ── SECTION 6: VERIFY ON DEVICE ──────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("6. Install and Verify"),
      h2("6.1 Install via ADB (Development)"),
      codeBlock([
        "adb install app/build/outputs/apk/debug/app-debug.apk",
        "adb shell am start -n \"com.vamanit.calendar.debug/com.vamanit.calendar.ui.signin.SignInActivity\"",
      ]),
      gap(),
      h2("6.2 Expected Sign-In Flow"),
      ...numbered([
        "The Sign-In screen opens showing the Vamanit\u00AE logo.",
        "Tap Sign in with Microsoft.",
        "A Microsoft login WebView opens (or Microsoft Authenticator if installed).",
        "Sign in with an account from the registered tenant.",
        "Accept the permission consent screen (Calendars.Read, User.Read).",
        "The app redirects back and loads the calendar dashboard.",
      ]),
      gap(),
      h2("6.3 Common Errors"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [3600, 5760],
        rows: [
          new TableRow({ children: [hdrCell("Error", 3600), hdrCell("Likely Cause & Fix", 5760)] }),
          new TableRow({ children: [dataCell("AADSTS700016: Application identifier not found", 3600), dataCell("Wrong or missing client_id in msal_config.json. Re-check Application (client) ID from Azure portal.", 5760)] }),
          new TableRow({ children: [dataCell("AADSTS50011: Redirect URI mismatch", 3600), dataCell("Redirect URI in Azure does not match the one derived from your keystore. Recompute the Base64 SHA-1 and update both Azure and msal_config.json.", 5760)] }),
          new TableRow({ children: [dataCell("No accounts found / silent auth fails", 3600), dataCell("User not signed in yet. Ensure interactive sign-in flow is triggered on first launch.", 5760)] }),
          new TableRow({ children: [dataCell("HTTP 403 on Graph API calendar call", 3600), dataCell("Calendars.Read permission not granted. Check API permissions in Azure and ensure user has consented.", 5760)] }),
        ]
      }),

      // ── SECTION 7: MULTI-TENANT CHECKLIST ────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("7. Multi-Tenant Deployment Checklist"),
      body("Use this checklist when onboarding each new Azure tenant:"),
      gap(),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [720, 7560, 1080],
        rows: [
          new TableRow({ children: [hdrCell("#", 720), hdrCell("Task", 7560), hdrCell("Done", 1080)] }),
          ...[
            ["1", "Obtained APK signing keystore SHA-1 fingerprint"],
            ["2", "Computed Base64 SHA-1 using python3 script in Section 3.3"],
            ["3", "Registered Vamanit Calendar app in target Azure tenant"],
            ["4", "Set Supported account types to Any Entra ID Tenant + Personal"],
            ["5", "Added Public client redirect URI: msauth://com.vamanit.calendar/<BASE64_SHA1>"],
            ["6", "Added Calendars.Read and Calendars.Read.Shared delegated permissions"],
            ["7", "Copied Application (client) ID from Azure Overview page"],
            ["8", "Updated client_id and redirect_uri in msal_config.json"],
            ["9", "Updated android:path in AndroidManifest.xml with /<BASE64_SHA1>"],
            ["10", "Built and signed APK with the matching keystore"],
            ["11", "Installed APK on test device and completed sign-in flow"],
            ["12", "Verified calendar events load correctly in the dashboard"],
          ].map(([n, task]) => new TableRow({
            children: [
              dataCell(n, 720),
              dataCell(task, 7560),
              new TableCell({
                borders: allBorders("CCCCCC"),
                width: { size: 1080, type: WidthType.DXA },
                margins: { top: 80, bottom: 80, left: 120, right: 120 },
                children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ text: "\u2610", size: 22 })] })]
              })
            ]
          }))
        ]
      }),

      // ── SECTION 8: REFERENCE ─────────────────────────────────────────────
      new Paragraph({ children: [], pageBreakBefore: true }),
      h1("8. Reference"),
      h2("8.1 Key Values (Reference Build \u2014 thinkcloud.in Tenant)"),
      new Table({
        width: { size: 9360, type: WidthType.DXA },
        columnWidths: [3200, 6160],
        rows: [
          new TableRow({ children: [hdrCell("Item", 3200), hdrCell("Value", 6160)] }),
          new TableRow({ children: [dataCell("Application (client) ID", 3200), dataCell("55e73e24-1390-4ea5-bf95-d7927dd8ec42", 6160, true)] }),
          new TableRow({ children: [dataCell("Directory (tenant) ID", 3200), dataCell("32ea410c-b6bc-432f-bfed-ae8e14f1ec67", 6160, true)] }),
          new TableRow({ children: [dataCell("Debug keystore SHA-1", 3200), dataCell("47:00:FE:07:75:81:84:74:28:0E:4E:7D:36:AB:DF:1A:FC:87:9D:4B", 6160, true)] }),
          new TableRow({ children: [dataCell("Redirect URI (debug)", 3200), dataCell("msauth://com.vamanit.calendar/RwD+B3WBhHQoDk59NqvfGvyHnUs=", 6160, true)] }),
          new TableRow({ children: [dataCell("Package (debug)", 3200), dataCell("com.vamanit.calendar.debug", 6160, true)] }),
          new TableRow({ children: [dataCell("Package (release)", 3200), dataCell("com.vamanit.calendar", 6160, true)] }),
        ]
      }),
      gap(),
      h2("8.2 Useful Links"),
      ...bullets([
        "GitHub Repository: https://github.com/vamanit-oss/vamanit-calendar",
        "Azure App Registrations: https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade",
        "Microsoft Graph Explorer: https://developer.microsoft.com/en-us/graph/graph-explorer",
        "MSAL for Android docs: https://learn.microsoft.com/en-us/azure/active-directory/develop/msal-android-shared-devices",
      ]),
      gap(),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 960 },
        children: [new TextRun({ text: "Vamanit\u00AE \u2014 Confidential and Proprietary", font: "Arial", size: 16, color: "AAAAAA" })]
      }),
    ]
  }]
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync("/Users/ramkaran/Downloads/vamanit-calendar/docs/vamanit-calendar-azure-onboarding.docx", buf);
  console.log("Done");
});
