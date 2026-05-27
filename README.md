TripMate
========

Email OTP signup uses SMTP settings from environment variables:

- `SMTP_HOST`
- `SMTP_PORT` (defaults to `587`)
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- `SMTP_FROM_EMAIL`
- `SMTP_USE_TLS` (defaults to `true`)

Passwords are stored as hashes. Signup OTPs expire after 5 minutes.
