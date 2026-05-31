import os
import smtplib
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import app as backend


class FakeSMTP:
    def __init__(self):
        self.login_args = None
        self.message = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def login(self, email, app_password):
        self.login_args = (email, app_password)

    def send_message(self, message):
        self.message = message
        return {}


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
                'full_name': 'Nancy Nataz',
                'email': 'new@example.com',
                'password': 'secret-password',
            })

        self.assertEqual(response.status_code, 200)
        send_otp.assert_called_once()
        recipient, otp = send_otp.call_args.args
        self.assertEqual(recipient, 'new@example.com')
        self.assertRegex(otp, r'^\d{6}$')
        with sqlite3.connect(backend.DB_FILE) as conn:
            stored_otp, display_name = conn.execute(
                'SELECT otp_code, display_name FROM users WHERE email=?',
                ('new@example.com',),
            ).fetchone()
        self.assertEqual(stored_otp, otp)
        self.assertEqual(display_name, 'Nancy Nataz')

    def test_signup_reports_delivery_failure(self):
        delivery = {
            'success': False,
            'error': 'GMAIL_APP_PASSWORD is missing',
            'error_type': 'configuration_error',
            'provider_response': None,
            'attempts': 0,
        }
        with patch.object(backend, 'send_otp_email', return_value=delivery):
            response = self.client.post('/signup', json={
                'full_name': 'Nancy Nataz',
                'email': 'new@example.com',
                'password': 'secret-password',
            })

        self.assertEqual(response.status_code, 503)
        self.assertFalse(response.get_json()['success'])

    def test_login_and_profile_fall_back_to_capitalized_email_prefix(self):
        with sqlite3.connect(backend.DB_FILE) as conn:
            conn.execute(
                '''INSERT INTO users
                   (username, password, email, password_hash, display_name, is_verified)
                   VALUES (?, ?, ?, ?, ?, ?)''',
                (
                    'nancynataz@gmail.com',
                    '',
                    'nancynataz@gmail.com',
                    backend.generate_password_hash('secret-password'),
                    'nancynataz@gmail.com',
                    1,
                ),
            )

        login_response = self.client.post('/login', json={
            'email': 'nancynataz@gmail.com',
            'password': 'secret-password',
        })
        profile_response = self.client.get('/api/auth/me')

        self.assertEqual(login_response.status_code, 200)
        self.assertEqual(login_response.get_json()['name'], 'Nancynataz')
        self.assertEqual(profile_response.get_json()['name'], 'Nancynataz')

    def test_send_email_retries_transient_provider_error(self):
        provider_error = smtplib.SMTPServerDisconnected('try again')
        smtp = FakeSMTP()
        env = {
            'GMAIL_EMAIL': 'tripmate@gmail.com',
            'GMAIL_APP_PASSWORD': 'app-password',
            'SMTP_MAX_RETRIES': '1',
            'SMTP_RETRY_DELAY': '0',
        }
        with patch.dict(os.environ, env, clear=False):
            with patch.object(
                backend.smtplib,
                'SMTP_SSL',
                side_effect=[provider_error, smtp],
            ) as smtp_ssl:
                delivery = backend.send_email('person@example.com', 'Subject', 'Body')

        self.assertTrue(delivery['success'])
        self.assertEqual(delivery['attempts'], 2)
        self.assertEqual(smtp_ssl.call_count, 2)
        self.assertEqual(smtp.login_args, ('tripmate@gmail.com', 'app-password'))
        self.assertEqual(smtp.message['From'], 'tripmate@gmail.com')
        self.assertEqual(smtp.message['To'], 'person@example.com')
        self.assertEqual(smtp.message['Subject'], 'Subject')
        self.assertEqual(smtp.message.get_content().strip(), 'Body')

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
