# Prahari Verify

**NTB / NTC / ETB Disbursement Verification** ke liye alag standalone Android app
(Kotlin + Jetpack Compose). Prahari AutoFill app se banaya gaya hai — lekin isme
**center fill, member fill, CDS photo, Gemini, Chat, Rules, Firebase kuch nahi hai.**
Sirf verification flow hai, taaki sahi kaam kare to baad me AutoFill me merge kiya ja sake.

## Kya karta hai

1. **Prahari Connect** — Prahari username/password se login (JWT token SecureStorage me,
   password kabhi save nahi hota). Phone me InstaSafe VPN ON hona chahiye.
2. **Audit list** — assigned audits dikhta hai, audit chuno.
3. **Verification list** — category chuno (NTB / NTC / ETB) → counts (audited/total) +
   customer list + per-customer status.
4. **Per-customer verification** — borrower/loan details + 7 fields (rules se pre-filled,
   sab edit karne layak) → Save → dobara padhkar verify → Verified/Failed dikhta hai.
5. **Bulk fill** — "Fill all unverified" button: pehle se verified skip, baaki sab bharo,
   5 ke batch + har batch ke baad 1 second gap (API block se bachne ke liye),
   live progress + har item ka result. Stop button se beech me roka ja sakta hai.

## Fill rules (user-confirmed defaults, save se pehle edit kar sakte ho)

| Field | Default |
|---|---|
| DISB_VERIFY_STATUS | Yes |
| DISB_VERIFY_AMOUNT | disbursed amount (customer record se) |
| LOAN_PURPOSE_VERIFY | personal |
| LOAN_CARD_RECEIVED | yes |
| LOAN_AGREEMENT_DOC | yes |
| COMMISSION_THIRD_PARTY | 0 |
| REMARK | ok |
| CALL_STATUS | NA (automatic, pehla item) |

## Prahari endpoints (base: `https://prahari.sonataindia.com`)

| Kaam | Endpoint |
|---|---|
| Checklist master (form structure) | GET `/backend/audit/disbursement-checklist-master/?category=<NTB\|NTC\|ETB>` |
| Customer list | `/backend/audit/branch/ntb-ntc-data/` |
| Summary counts (progress) | `/backend/audit/branch/ntb-ntc-counts/` |
| Feedback read (skip-check + post-save verify) | GET `/backend/audit/disbursement-checklist-feedback/?disbursement_id=<loan>&category=<cat>` |
| Feedback save | POST `/backend/audit/disbursement-checklist-feedback/save/` |

Save body (exact keys, koi extra key nahi):
`{audit_id (number), auditor_id (number), branch_id (STRING, jaise "66"),
center_id (number), clientCategory, client_name, customerinfoid (STRING),
disbursementid (STRING), items: [{checklist_id, parameter_code, parameter_name,
answer}], token, verification_mode: "Physical"}`

Har parameter ka `checklist_id` checklist-master response se map hota hai
(CALL_STATUS ka 1 hai). Token kabhi code me hardcode nahi hai — login ke baad
SecureStorage se aata hai.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions (`.github/workflows/build.yml`) se bhi build hota hai — **koi
google-services.json / Firebase secret ki zaroorat nahi hai** (Firebase is app me
hai hi nahi).

## Phone par test karne se pehle (open items)

- **VPN ON** + Prahari login karo. Token 55 minute me expire hota hai (dobara Connect).
- **List/counts ka method confirm karo:** `ntb-ntc-data/` aur `ntb-ntc-counts/` ka
  method (POST vs GET) pakka nahi hai. App pehle POST try karta hai (token +
  audit/branch/category body me); 404/405 aaye to GET try karta hai (query params +
  Authorization header). Agar dono fail ho to error me saaf likha aayega.
- **List/counts ke param keys:** app `audit_id`, `branch_id`, `category`,
  `clientCategory` bhejta hai. Agar server kuch aur key maangta hai to error dikhega —
  tab network capture se sahi keys confirm karke `PrahariApi.getCustomerList/getCounts`
  me badalna.
- **Answer casing:** Status "Yes", Card/Agreement "yes", Purpose "personal" user ke
  bataye exact strings hain. Pehle save par stored values se match karke confirm karo
  (app khud re-read karke batayega match hua ya nahi).
- **Amount format:** answers strings me bheje jaate hain (jaise "30300"). Agar server
  number maangta hai to pehle save ka verify step mismatch dikhayega — tab
  `PrahariApi.saveVerification` me answer type badalna.
- **List status:** customer list me status flag ho to "Verified/Pending" dikhta hai,
  warna "Pending" dikhkar detail/bulk me feedback se pakka check hota hai.
- **Auditor ID:** save ke liye number chahiye. Login response se mile to wahi, warna
  username `AUDIT_<number>` se derive hota hai, warna Connect screen me haath se dalo.
- **Pehle ek customer se test karo** (single detail save), phir bulk chalao.

## Merge note (baad me AutoFill me daalne ke liye)

Same patterns rakhe gaye hain taaki merge aasan ho: `PrahariApi` (OkHttp +
kotlinx.serialization, token POST body me), `SecureStorage` (EncryptedSharedPreferences),
`VerifySession` (FillSession jaisa holder), `VerifyEngine.fillOne` (single + bulk dono
use karte hain). Merge ke waqt `data/api`, `data/verify`, `ui/screens/Verification*`
copy karke `MainActivity` nav me routes jodne hain — center/member/CDS code se koi
takraav nahi hai (alag package, alag file names).
