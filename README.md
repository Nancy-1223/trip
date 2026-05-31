TripMate
========

Email OTP signup uses the Resend HTTP API.

- `RESEND_API_KEY`
- `RESEND_FROM_EMAIL` (use a sender on a verified Resend domain in production)
- `RESEND_TIMEOUT` (optional, defaults to `20` seconds)
- `RESEND_MAX_RETRIES` (optional, defaults to `2`)
- `RESEND_RETRY_DELAY` (optional, defaults to `0.5` seconds)
- `TEST_EMAIL_API_KEY` (required to call `POST /test-email`)
- `LOG_OTP_CODES` (optional development-only setting; set to `true` to log OTP codes)

Testing sender: `TripMate <onboarding@resend.dev>`. Resend only allows this sender to email the address associated with your Resend account. For real users, verify your own domain in Resend and set a sender such as `TripMate <no-reply@updates.example.com>`.

`POST /test-email` sends a diagnostic message to any requested address when the caller supplies the deployment secret:

```bash
curl -X POST https://your-service.onrender.com/test-email \
  -H "Content-Type: application/json" \
  -H "X-Test-Email-Key: your-test-email-api-key" \
  -d '{"email":"you@example.com"}'
```

Render deployment:

1. In the Resend dashboard, add your sending domain and complete DNS verification.
2. In the TripMate Render service dashboard, open **Environment**.
3. Add `RESEND_API_KEY` with your Resend API key.
4. Add `RESEND_FROM_EMAIL` using your verified domain, for example `TripMate <no-reply@updates.example.com>`.
5. Add `TEST_EMAIL_API_KEY` with a long random secret used only for the diagnostic endpoint.
6. Optionally add `RESEND_TIMEOUT`, `RESEND_MAX_RETRIES`, and `RESEND_RETRY_DELAY` to override their defaults.
7. Leave `LOG_OTP_CODES` unset or set it to `false` in production.
8. Deploy the latest commit from Render.
9. Open the Render logs and confirm `Email delivery configuration validation passed`.
10. Call `POST /test-email` with the curl command above and confirm the JSON response contains `"success": true`.
11. Complete a normal signup and confirm that the OTP email arrives.

Passwords are stored as hashes. Signup OTPs expire after 5 minutes. Delivery failures are logged and returned as errors so they are visible during deployment checks.
