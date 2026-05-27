TripMate
========

Email OTP signup uses the Resend HTTP API.

- `RESEND_API_KEY`
- `RESEND_TIMEOUT` (optional, defaults to `20` seconds)

Testing sender: `TripMate <onboarding@resend.dev>`.

If the Resend API key is missing or sending fails during development, signup still continues and the OTP is printed in the backend logs.

Passwords are stored as hashes. Signup OTPs expire after 5 minutes.
