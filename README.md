TripMate
========

Email OTP signup uses Gmail SMTP with a Google App Password.

Google setup references: [App Passwords](https://support.google.com/accounts/answer/185833) and [Gmail SMTP configuration](https://support.google.com/a/answer/176600).

SMTP connection: `smtp.gmail.com` on port `587` with STARTTLS enabled.

- `GMAIL_EMAIL`
- `GMAIL_APP_PASSWORD`
- `SMTP_TIMEOUT` (optional, defaults to `20` seconds)
- `SMTP_MAX_RETRIES` (optional, defaults to `2`)
- `SMTP_RETRY_DELAY` (optional, defaults to `0.5` seconds)
- `TEST_EMAIL_API_KEY` (required to call `POST /test-email`)
- `LOG_OTP_CODES` (optional development-only setting; set to `true` to log OTP codes)

`POST /test-email` sends a diagnostic message to any requested address when the caller supplies the deployment secret:

```bash
curl -X POST https://your-service.onrender.com/test-email \
  -H "Content-Type: application/json" \
  -H "X-Test-Email-Key: your-test-email-api-key" \
  -d '{"email":"you@example.com"}'
```

Render deployment:

1. Enable 2-Step Verification for the Google account that will send TripMate OTP emails.
2. In the Google account security settings, create an App Password for TripMate.
3. In the TripMate Render service dashboard, open **Environment**.
4. Add `GMAIL_EMAIL` with the complete Gmail address used to send OTP emails.
5. Add `GMAIL_APP_PASSWORD` with the generated App Password. Do not use your normal Gmail password.
6. Add `TEST_EMAIL_API_KEY` with a long random secret used only for the diagnostic endpoint.
7. Optionally add `SMTP_TIMEOUT`, `SMTP_MAX_RETRIES`, and `SMTP_RETRY_DELAY` to override their defaults.
8. Leave `LOG_OTP_CODES` unset or set it to `false` in production.
9. Remove environment variables from the previous email provider configuration.
10. Deploy the latest commit from Render.
11. Open the Render logs and confirm `Gmail SMTP configuration validation passed`.
12. Call `POST /test-email` with the curl command above and confirm the JSON response contains `"success": true`.
13. Complete a normal signup and confirm that the OTP email arrives.

Passwords are stored as hashes. Signup OTPs expire after 5 minutes. Delivery failures are logged and returned as errors so they are visible during deployment checks.
