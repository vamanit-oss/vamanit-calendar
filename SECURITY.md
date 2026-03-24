# Vamanit Calendar — Security & Network Reference

## 1. Network Ports

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443  | HTTPS/TLS 1.2+ | Outbound | All API traffic — Google Calendar, Google OAuth2, Microsoft Graph, Microsoft OAuth2 |
| 80   | HTTP | Blocked | Cleartext HTTP explicitly denied by `network_security_config.xml` |

Localhost/emulator exceptions (debug only):

| Host | Port | Purpose |
|------|------|---------|
| 10.0.2.2 | any | Android emulator host loopback (dev only) |
| localhost | any | Local debug server (dev only) |

---

## 2. External Endpoints & Protocols

### 2a. Google OAuth2 / Calendar

| Endpoint | Protocol | Port | Purpose |
|----------|----------|------|---------|
| `accounts.google.com/o/oauth2/v2/auth` | HTTPS (OAuth2 Authorization Code) | 443 | Phone interactive sign-in (AppAuth browser redirect) |
| `oauth2.googleapis.com/device/code` | HTTPS (RFC 8628 Device Authorization) | 443 | TV device-code initiation |
| `oauth2.googleapis.com/token` | HTTPS (OAuth2 Token endpoint) | 443 | Token exchange + refresh (phone & TV) |
| `www.googleapis.com/calendar/v3/calendars/*/events` | HTTPS (REST/JSON) | 443 | Fetch calendar events |
| `www.googleapis.com/calendar/v3/users/me/calendarList` | HTTPS (REST/JSON) | 443 | Fetch user's calendar list (delegated resource discovery) |
| `www.googleapis.com/calendar/v3/calendars/primary` | HTTPS (REST/JSON) | 443 | Fetch signed-in user's display name |

**OAuth2 Flow — Phone (AppAuth, RFC 6749 Authorization Code + PKCE):**
1. App opens system browser to `accounts.google.com/o/oauth2/v2/auth`
2. User authenticates; Google redirects to reverse-scheme URI (`com.googleusercontent.apps.{id}:/oauth2redirect`)
3. AppAuth intercepts redirect, exchanges code + PKCE verifier at `/token`
4. Access + refresh tokens returned over HTTPS

**OAuth2 Flow — TV (RFC 8628 Device Authorization Grant):**
1. `POST /device/code` → returns `device_code`, `user_code`, `verification_url`
2. TV displays `user_code` + `verification_url` to user
3. User opens URL on phone/PC and authenticates
4. TV polls `POST /token` with `grant_type=urn:ietf:params:oauth2:grant_type:device_code` at `interval` seconds
5. On `authorization_pending`: keep polling. On success: persist tokens.

### 2b. Microsoft Graph / MSAL

| Endpoint | Protocol | Port | Purpose |
|----------|----------|------|---------|
| `login.microsoftonline.com/common/oauth2/v2.0/authorize` | HTTPS (OAuth2 + OIDC) | 443 | Interactive MSAL sign-in (WebView on TV, Custom Tab on phone) |
| `login.microsoftonline.com/common/oauth2/v2.0/token` | HTTPS (OAuth2 Token) | 443 | Silent token refresh via MSAL |
| `graph.microsoft.com/v1.0/me/calendars` | HTTPS (REST/JSON, OData v4) | 443 | Fetch user's calendar list |
| `graph.microsoft.com/v1.0/me/calendars/{id}/calendarView` | HTTPS (REST/JSON, OData v4) | 443 | Fetch events from owned calendars |
| `graph.microsoft.com/v1.0/users/{email}/calendarView` | HTTPS (REST/JSON, OData v4) | 443 | Fetch events from delegated room calendars |
| `graph.microsoft.com/v1.0/places/microsoft.graph.room` | HTTPS (REST/JSON, OData v4) | 443 | Discover Exchange room resources (Places API) |
| `graph.microsoft.com/v1.0/me` | HTTPS (REST/JSON) | 443 | Fetch signed-in user's display name |

**Microsoft Graph API Headers:**
- `Authorization: Bearer <access_token>` — on all requests
- `Prefer: outlook.timezone="<device_tz>"` — on `calendarView` requests to receive datetimes in local timezone
- No `Prefer` header on Places API (`/places/microsoft.graph.room`) — omitting avoids 400 errors

### 2c. Play Integrity API

| Endpoint | Protocol | Port | Purpose |
|----------|----------|------|---------|
| `integrity.googleapis.com` | HTTPS | 443 | Device/app attestation token request |

---

## 3. TLS Configuration

### Minimum TLS Version
Android 10+ (API 29+) enforces **TLS 1.2 minimum** by default. This app targets API 26+ but all supported Android versions in practice enforce TLS 1.2+ for all network connections.

### Network Security Config (`res/xml/network_security_config.xml`)
```xml
<network-security-config>
    <!-- Cleartext BLOCKED globally -->
    <base-config cleartextTrafficPermitted="false" />

    <!-- Emulator/localhost exception for development only -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

**Effect:** Any HTTP (non-TLS) request to a production host is blocked at the OS level. Only loopback addresses may use cleartext, and only in debug builds.

### OkHttp Client Timeouts
All Microsoft Graph API calls use a shared OkHttpClient configured in `NetworkModule`:

| Timeout | Value |
|---------|-------|
| Connect | 30 seconds |
| Read | 30 seconds |
| Write | 30 seconds |

### Certificate Validation
Standard Android TLS certificate chain validation is used. The system trust store provides the CA roots. No custom `TrustManager` or `HostnameVerifier` overrides exist in the codebase.

---

## 4. OAuth2 Scopes Requested

### Google (read-only)
| Scope | Purpose |
|-------|---------|
| `openid` | OIDC identity |
| `email` | User email address |
| `profile` | User display name |
| `https://www.googleapis.com/auth/calendar.readonly` | Read calendar events and calendar list |

### Microsoft (read-only)
| Scope | Purpose |
|-------|---------|
| `User.Read` | Signed-in user profile |
| `User.ReadBasic.All` | Basic profile of other users (room discovery) |
| `Calendars.Read` | Read user's own calendars and events |
| `Calendars.Read.Shared` | Read calendars shared/delegated to the user (room mailboxes) |
| `Place.Read.All` | Read Exchange room resources via Places API |
| `offline_access` | Obtain refresh tokens for silent re-authentication |

---

## 5. Token & Secret Storage

### Token Storage — EncryptedSharedPreferences (AES-256)

All OAuth tokens and client secrets are stored using **`EncryptedSharedPreferences`** (androidx.security.crypto), backed by the **Android Keystore** hardware security module where available.

| Store file | Contents | Encryption |
|------------|----------|------------|
| `google_auth` | Google access token, refresh token | AES-256-GCM (values), AES-256-SIV (keys) |
| `microsoft_auth` | MSAL account presence flag | AES-256-GCM (values), AES-256-SIV (keys) |
| `secrets_store` | Google OAuth client secrets (if entered at runtime) | AES-256-GCM (values), AES-256-SIV (keys) |

**MasterKey scheme:** `AES256_GCM` — generated in Android Keystore, never leaves secure hardware.

MSAL (Microsoft) tokens are additionally managed by the MSAL SDK's own encrypted MSAL cache, which also uses Android Keystore.

### Signing Keystore
Release signing credentials are loaded from `local.properties` at build time:
```
KEYSTORE_PASSWORD=<secret>
KEY_ALIAS=thinkcloud
KEY_PASSWORD=<secret>
```
`local.properties` is in `.gitignore` and is never committed to version control.

### What Is NOT Stored on Device
- Microsoft access tokens (managed by MSAL SDK cache)
- Raw user passwords (OAuth2 — no passwords ever handled)
- Any PII beyond what's required to render the UI

---

## 6. Identity Management

### Google Client IDs (Public — Safe to Expose)
OAuth2 public client IDs for installed apps are not secret. They appear in the app binary by design and are required for the OAuth2 authorization request. Anyone who obtains a client ID can only request scopes the app is authorised for and only with user consent.

| Client | ID |
|--------|----|
| Phone (Desktop OAuth) | `534654568144-qbbo6knmoqo3uqga35e0ipsq92d7dskl.apps.googleusercontent.com` |
| TV (Limited Input) | `534654568144-r4ljh9had1sr3d6e5fdgvpahst0ipglp.apps.googleusercontent.com` |

### Google Client Secrets
Google **Desktop** and **TVs and Limited Input** clients are *public clients* — they use `client_secret` only for the token endpoint. The secret is stored encrypted at rest (see §5) and never transmitted except over HTTPS to `oauth2.googleapis.com/token`.

### Microsoft Client ID (Public — Safe to Expose)
| Client | ID |
|--------|----|
| MSAL (all platforms) | `55e73e24-1390-4ea5-bf95-d7927dd8ec42` |

MSAL is configured as a **public client** (no client secret). Azure AD validates by redirect URI + app signature. No secret is stored for Microsoft.

### MSAL Redirect URI
```
msauth://com.vamanit.calendar/<BASE64_KEYSTORE_HASH>
```
Registered in Azure Portal under **Authentication → Mobile and desktop applications → Redirect URIs**.

---

## 7. Best Practices Implemented

| Practice | Status | Detail |
|----------|--------|--------|
| All traffic over HTTPS/TLS 1.2+ | ✅ | `network_security_config.xml` blocks cleartext globally |
| No custom TrustManager / HostnameVerifier | ✅ | Standard Android CA validation |
| OAuth2 with PKCE (phone) | ✅ | AppAuth library enforces PKCE on Authorization Code flow |
| Device Authorization Grant (TV) | ✅ | RFC 8628 — no browser required on TV |
| Read-only OAuth scopes only | ✅ | No write/send/delete scopes requested |
| Tokens stored encrypted at rest | ✅ | EncryptedSharedPreferences + Android Keystore AES-256-GCM |
| MSAL token cache encrypted | ✅ | MSAL SDK uses Android Keystore internally |
| Signing keystore password in local.properties | ✅ | Not committed to version control |
| Keystore file in .gitignore | ✅ | `*.jks` excluded from git |
| Play Integrity attestation | ✅ | Device/app integrity check via Play Integrity API |
| OkHttp timeouts (connect/read/write 30s) | ✅ | Prevents indefinite hangs |
| No sensitive data in Timber logs | ✅ | Tokens never logged; only request URLs and error codes |
| Independent auth failure isolation | ✅ | Google failure never blocks Microsoft and vice versa |
| MSAL scope retry on MsalDeclinedScopeException | ✅ | offline_access implicit grant handled gracefully |

---

## 8. Exchange Permissions Required (Server-Side)

For room calendar event access via Microsoft Graph, the Exchange admin must grant explicit mailbox folder permissions on each room:

```powershell
# Grant calendar read access (Reviewer) to a user on a room mailbox
Add-MailboxFolderPermission `
  -Identity "RoomName@domain.com:\Calendar" `
  -User "user@domain.com" `
  -AccessRights Reviewer
```

This is separate from Exchange delegate (booking management) access. Without `MailboxFolderPermission`, Graph API returns `403 Forbidden` on `/users/{room}/calendarView` even when the user is listed as a booking delegate.

---

## 9. Known Limitations

| Item | Detail |
|------|--------|
| No certificate pinning | Standard CA chain validation only. Certificate pinning would add defence-in-depth but requires key rotation management. |
| Google client secrets in binary | Desktop OAuth client secrets are embedded in the app (required by Google's public client model). Secrets are encrypted at rest but present in APK. Mitigated by read-only scopes and OAuth consent screen restrictions. |
| MSAL `offline_access` scope | Azure AD returns `offline_access` implicitly (as a refresh token) but omits it from the scope list, triggering `MsalDeclinedScopeException`. App handles this by retrying with the granted scopes. |
| Play Integrity verdict not server-verified | The integrity verdict is currently evaluated client-side only. Full protection requires server-side verification (send verdict token to a backend that calls the Play Integrity API). |

