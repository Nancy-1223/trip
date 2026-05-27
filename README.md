TripMate
========

Email OTP signup uses SMTP settings from environment variables:

- `SMTP_HOST`
- `SMTP_PORT` (defaults to `587`)
- `SMTP_USER`
- `SMTP_PASS`
- `SMTP_FROM`
- `SMTP_USE_TLS` (defaults to `true`)

If SMTP settings are missing or sending fails during development, signup still continues and the OTP is printed in the backend logs.

Passwords are stored as hashes. Signup OTPs expire after 5 minutes.
