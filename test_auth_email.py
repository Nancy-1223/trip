import io
import json
import os
import sqlite3
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

import app as backend


class FakeResponse:
    def __init__(self, status=200, body=b'{"id":"email_123"}'):
        self.status = status
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return self.body


class AuthEmailTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.original_db_file = backend.DB_FILE
        backend.DB_FILE = os.path.join(self.temp_dir.name, 'test.db')
        backend.init_db()
        self.client = backend.app.test_client()

    def tearDown(self):
        backend.DB_FILE = self.original_db_file
        self.temp_dir.cleanup()

    def test_signup_persists_six_digit_otp_and_calls_email_sender(self):
        delivery = {'success': True, 'provider_response': {'id': 'email_123'}, 'attempts': 1}
        with patch.object(backend, 'send_otp_email', return_value=delivery) as send_otp:
            response = self.client.post('/signup', json={
                'email': 'new@example.com',
                'password': 'secret-password',
            })

        self.assertEqual(response.status_code, 200)
        send_otp.assert_called_once()
        recipient, otp = send_otp.call_args.args
        self.assertEqual(recipient, 'new@example.com')
        self.assertRegex(otp, r'^\d{6}$')
        with sqlite3.connect(backend.DB_FILE) as conn:
            stored_otp = conn.execute(
                'SELECT otp_code FROM users WHERE email=?',
                ('new@example.com',),
            ).fetchone()[0]
        self.assertEqual(stored_otp, otp)

    def test_signup_reports_delivery_failure(self):
        delivery = {
            'success': False,
            'error': 'RESEND_API_KEY is missing',
            'error_type': 'configuration_error',
            'provider_response': None,
            'attempts': 0,
        }
        with patch.object(backend, 'send_otp_email', return_value=delivery):
            response = self.client.post('/signup', json={
                'email': 'new@example.com',
                'password': 'secret-password',
            })

        self.assertEqual(response.status_code, 503)
        self.assertFalse(response.get_json()['success'])

    def test_send_email_retries_transient_provider_error(self):
        provider_error = urllib.error.HTTPError(
            backend.RESEND_API_URL,
            503,
            'Service unavailable',
            hdrs=None,
            fp=io.BytesIO(b'{"message":"try again"}'),
        )
        env = {
            'RESEND_API_KEY': 're_test',
            'RESEND_FROM_EMAIL': 'TripMate <no-reply@example.com>',
            'RESEND_MAX_RETRIES': '1',
            'RESEND_RETRY_DELAY': '0',
        }
        with patch.dict(os.environ, env, clear=False):
            with patch.object(
                backend.urllib.request,
                'urlopen',
                side_effect=[provider_error, FakeResponse()],
            ) as urlopen:
                delivery = backend.send_email('person@example.com', 'Subject', 'Body')

        self.assertTrue(delivery['success'])
        self.assertEqual(delivery['attempts'], 2)
        first_request = urlopen.call_args_list[0].args[0]
        second_request = urlopen.call_args_list[1].args[0]
        self.assertEqual(
            first_request.get_header('Idempotency-key'),
            second_request.get_header('Idempotency-key'),
        )
        self.assertEqual(
            json.loads(first_request.data),
            {
                'from': 'TripMate <no-reply@example.com>',
                'to': ['person@example.com'],
                'subject': 'Subject',
                'text': 'Body',
            },
        )

    def test_test_email_endpoint_requires_secret_and_returns_provider_response(self):
        delivery = {'success': True, 'provider_response': {'id': 'email_456'}, 'attempts': 1}
        with patch.dict(os.environ, {'TEST_EMAIL_API_KEY': 'diagnostic-secret'}, clear=False):
            unauthorized = self.client.post('/test-email', json={'email': 'person@example.com'})
            with patch.object(backend, 'send_email', return_value=delivery):
                response = self.client.post(
                    '/test-email',
                    headers={'X-Test-Email-Key': 'diagnostic-secret'},
                    json={'email': 'person@example.com'},
                )

        self.assertEqual(unauthorized.status_code, 401)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()['provider_response'], {'id': 'email_456'})


if __name__ == '__main__':
    unittest.main()
