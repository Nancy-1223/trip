from flask import Flask, render_template, request, jsonify, session, redirect, url_for
import sqlite3
import os
import json
import base64
import logging
import secrets
import smtplib
import socket
import ssl
import time
from datetime import datetime, timedelta
from email.message import EmailMessage
from functools import wraps
import urllib.error
import urllib.request
import urllib.parse
from werkzeug.security import generate_password_hash, check_password_hash

template_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), 'templates'))
static_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), 'static'))
app = Flask(__name__, static_folder=static_dir, template_folder=template_dir)
app.secret_key = 'tripmate_secret_2024'
DB_FILE = 'database.db'

# Gmail SMTP configuration is read from environment variables:
# GMAIL_EMAIL, GMAIL_APP_PASSWORD, SMTP_TIMEOUT, SMTP_MAX_RETRIES
OTP_EXPIRY_MINUTES = 5
GMAIL_SMTP_HOST = 'smtp.gmail.com'
GMAIL_SMTP_PORT = 465
MAX_SMTP_TIMEOUT_SECONDS = 10
logger = logging.getLogger('tripmate')
logging.basicConfig(level=os.environ.get('LOG_LEVEL', 'INFO').upper())

def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password TEXT NOT NULL,
            display_name TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    ''')
    existing_cols = {row[1] for row in c.execute('PRAGMA table_info(users)').fetchall()}
    legacy_users_need_verification_migration = 'is_verified' not in existing_cols
    migrations = [
        ('email', 'TEXT'),
        ('password_hash', 'TEXT'),
        ('otp_code', 'TEXT'),
        ('otp_expires_at', 'TEXT'),
        ('is_verified', 'INTEGER DEFAULT 0'),
    ]
    for col, definition in migrations:
        if col not in existing_cols:
            c.execute(f'ALTER TABLE users ADD COLUMN {col} {definition}')
    c.execute('''
        CREATE TABLE IF NOT EXISTS trips (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            destination TEXT,
            purpose TEXT,
            start_loc TEXT,
            distance REAL DEFAULT 0,
            cost REAL DEFAULT 0,
            fuel_cost REAL DEFAULT 0,
            food_cost REAL DEFAULT 0,
            accommodation_cost REAL DEFAULT 0,
            other_cost REAL DEFAULT 0,
            vehicle_type TEXT DEFAULT 'car',
            path TEXT DEFAULT '[]',
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
    ''')
    c.execute('''
        CREATE TABLE IF NOT EXISTS notes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            user_id INTEGER,
            title TEXT DEFAULT 'Untitled',
            content TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (trip_id) REFERENCES trips(id)
        )
    ''')
    c.execute('''
        CREATE TABLE IF NOT EXISTS memories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            user_id INTEGER,
            filename TEXT,
            image_data TEXT,
            caption TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (trip_id) REFERENCES trips(id)
        )
    ''')
    c.execute('''
        CREATE TABLE IF NOT EXISTS expenses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            user_id INTEGER,
            category TEXT NOT NULL,
            description TEXT,
            amount REAL NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (trip_id) REFERENCES trips(id)
        )
    ''')
    # Insert demo user
    try:
        c.execute("""INSERT INTO users (username, password, email, password_hash, display_name, is_verified)
                     VALUES (?, ?, ?, ?, ?, ?)""",
                  ('traveler', '', 'traveler', generate_password_hash('demo123'), 'Demo Traveler', 1))
    except:
        pass

    c.execute('SELECT id, username, password, email, password_hash, is_verified FROM users')
    for user_id, username, password, email, password_hash, is_verified in c.fetchall():
        updates = []
        values = []
        if not email:
            updates.append('email=?')
            values.append(username)
        if not password_hash and password:
            updates.append('password_hash=?')
            values.append(generate_password_hash(password))
        if password:
            updates.append('password=?')
            values.append('')
        if legacy_users_need_verification_migration or is_verified is None:
            updates.append('is_verified=?')
            values.append(1)
        if updates:
            values.append(user_id)
            c.execute(f'UPDATE users SET {", ".join(updates)} WHERE id=?', values)
    conn.commit()
    conn.close()

init_db()

def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if 'user_id' not in session:
            return jsonify({'error': 'Unauthorized'}), 401
        return f(*args, **kwargs)
    return decorated

# ── Auth Routes ──────────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('index.html')

def get_int_env(name, default, minimum=0):
    try:
        value = int(os.environ.get(name, str(default)))
    except ValueError:
        logger.warning('%s must be an integer; using default=%s', name, default)
        return default
    if value < minimum:
        logger.warning('%s must be at least %s; using default=%s', name, minimum, default)
        return default
    return value

def get_float_env(name, default, minimum=0):
    try:
        value = float(os.environ.get(name, str(default)))
    except ValueError:
        logger.warning('%s must be a number; using default=%s', name, default)
        return default
    if value < minimum:
        logger.warning('%s must be at least %s; using default=%s', name, minimum, default)
        return default
    return value

def get_email_config():
    gmail_email = os.environ.get('GMAIL_EMAIL', '').strip()
    app_password = os.environ.get('GMAIL_APP_PASSWORD', '').replace(' ', '')
    errors = []
    if not gmail_email or '@' not in gmail_email:
        errors.append('GMAIL_EMAIL is missing or invalid')
    if not app_password:
        errors.append('GMAIL_APP_PASSWORD is missing')
    logger.info(
        'Gmail SMTP configuration loaded email=%s app_password=%s',
        gmail_email or '<missing>',
        'yes' if app_password else 'no',
    )
    return {
        'gmail_email': gmail_email,
        'app_password': app_password,
        'timeout': min(get_int_env('SMTP_TIMEOUT', 10, minimum=1), MAX_SMTP_TIMEOUT_SECONDS),
        'max_retries': min(get_int_env('SMTP_MAX_RETRIES', 0, minimum=0), 1),
        'retry_delay': get_float_env('SMTP_RETRY_DELAY', 0.5, minimum=0),
        'errors': errors,
    }

def validate_email_config_at_startup():
    config = get_email_config()
    if config['errors']:
        logger.error('Email delivery is not ready: %s', '; '.join(config['errors']))
    else:
        logger.info('Gmail SMTP configuration validation passed')

validate_email_config_at_startup()

def send_email(email, subject, text):
    config = get_email_config()
    if config['errors']:
        error = '; '.join(config['errors'])
        logger.error('Email delivery configuration error recipient=%s error=%s', email, error)
        return {
            'success': False,
            'error': error,
            'error_type': 'configuration_error',
            'provider_response': None,
            'attempts': 0,
        }

    message = EmailMessage()
    message['From'] = config['gmail_email']
    message['To'] = email
    message['Subject'] = subject
    message.set_content(text)
    attempts = config['max_retries'] + 1

    for attempt in range(1, attempts + 1):
        logger.info(
            'Email request sent provider=gmail_smtp recipient=%s from=%s attempt=%s/%s',
            email,
            config['gmail_email'],
            attempt,
            attempts,
        )
        try:
            with smtplib.SMTP_SSL(
                GMAIL_SMTP_HOST,
                GMAIL_SMTP_PORT,
                timeout=config['timeout'],
                context=ssl.create_default_context(),
            ) as smtp:
                smtp.login(config['gmail_email'], config['app_password'])
                refused_recipients = smtp.send_message(message)
            if refused_recipients:
                logger.error(
                    'Email delivery error provider=gmail_smtp recipient=%s refused=%s',
                    email,
                    refused_recipients,
                )
                return {
                    'success': False,
                    'error': 'Gmail SMTP refused the recipient',
                    'error_type': 'provider_error',
                    'provider_response': {'refused_recipients': refused_recipients},
                    'attempts': attempt,
                }
            provider_response = {
                'provider': 'gmail_smtp',
                'accepted_recipients': [email],
            }
            logger.info(
                'Email provider response provider=gmail_smtp recipient=%s response=%s',
                email,
                provider_response,
            )
            return {
                'success': True,
                'provider_response': provider_response,
                'attempts': attempt,
            }
        except smtplib.SMTPAuthenticationError as exc:
            logger.exception(
                'Email delivery authentication error provider=gmail_smtp recipient=%s code=%s error=%s',
                email,
                exc.smtp_code,
                exc.smtp_error,
            )
            return {
                'success': False,
                'error': 'Gmail SMTP authentication failed. Check GMAIL_EMAIL and GMAIL_APP_PASSWORD.',
                'error_type': 'configuration_error',
                'provider_response': {'smtp_code': exc.smtp_code},
                'attempts': attempt,
            }
        except smtplib.SMTPRecipientsRefused as exc:
            logger.exception(
                'Email delivery error provider=gmail_smtp recipient=%s refused=%s',
                email,
                exc.recipients,
            )
            return {
                'success': False,
                'error': 'Gmail SMTP refused the recipient',
                'error_type': 'provider_error',
                'provider_response': {'refused_recipients': exc.recipients},
                'attempts': attempt,
            }
        except (smtplib.SMTPException, socket.timeout, OSError) as exc:
            logger.exception(
                'Email delivery error provider=gmail_smtp recipient=%s error=%s attempt=%s/%s',
                email,
                exc,
                attempt,
                attempts,
            )
            if attempt == attempts:
                return {
                    'success': False,
                    'error': f'Gmail SMTP delivery failed: {exc}',
                    'error_type': 'network_error',
                    'provider_response': None,
                    'attempts': attempt,
                }
        except Exception as exc:
            logger.exception('Unexpected email delivery error provider=gmail_smtp recipient=%s', email)
            return {
                'success': False,
                'error': f'Unexpected email delivery error: {type(exc).__name__}',
                'error_type': 'unexpected_error',
                'provider_response': None,
                'attempts': attempt,
            }
        if config['retry_delay']:
            time.sleep(config['retry_delay'] * attempt)

def send_otp_email(email, otp):
    logger.info('OTP generated recipient=%s expires_in_minutes=%s', email, OTP_EXPIRY_MINUTES)
    if os.environ.get('LOG_OTP_CODES', '').strip().lower() == 'true':
        logger.warning('Development OTP recipient=%s otp=%s', email, otp)
    try:
        delivery = send_email(
            email,
            'TripMate Email Verification OTP',
            f'Your TripMate verification OTP is: {otp}\n\n'
            f'This OTP is valid for {OTP_EXPIRY_MINUTES} minutes.',
        )
    except Exception as exc:
        logger.exception('Unexpected OTP email error recipient=%s', email)
        delivery = {
            'success': False,
            'error': f'Unexpected OTP email error: {type(exc).__name__}',
            'error_type': 'unexpected_error',
            'provider_response': None,
            'attempts': 0,
        }
    if delivery['success']:
        logger.info('OTP email accepted by provider recipient=%s', email)
    else:
        logger.error('OTP email delivery failed recipient=%s error=%s', email, delivery['error'])
    return delivery

def email_failure_response(delivery):
    return jsonify({
        'success': False,
        'email_sent': False,
        'error': 'OTP was generated, but the verification email could not be sent. Check the email configuration and try again.',
        'delivery_error': delivery['error'],
    }), 502

def get_display_name(display_name, email, username=''):
    if display_name and display_name.strip() and '@' not in display_name:
        return display_name.strip()
    fallback = (email or username or 'Traveler').split('@', 1)[0].strip()
    return fallback.capitalize() or 'Traveler'

def create_pending_user(email, password, full_name):
    otp = f'{secrets.randbelow(1000000):06d}'
    expires_at = (datetime.utcnow() + timedelta(minutes=OTP_EXPIRY_MINUTES)).isoformat()
    password_hash = generate_password_hash(password)

    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    existing = c.execute('SELECT id, is_verified FROM users WHERE email=? OR username=?', (email, email)).fetchone()
    if existing and existing['is_verified']:
        conn.close()
        return None, jsonify({'success': False, 'error': 'Email already registered'}), 409
    if existing:
        c.execute('''UPDATE users SET username=?, password=?, email=?, password_hash=?, otp_code=?,
                     otp_expires_at=?, is_verified=0, display_name=?
                     WHERE id=?''',
                  (email, '', email, password_hash, otp, expires_at, full_name, existing['id']))
    else:
        c.execute('''INSERT INTO users (username, password, email, password_hash, display_name,
                     otp_code, otp_expires_at, is_verified)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 0)''',
                  (email, '', email, password_hash, full_name, otp, expires_at))
    conn.commit()
    conn.close()
    return otp, None, None

def issue_new_otp(email):
    otp = f'{secrets.randbelow(1000000):06d}'
    expires_at = (datetime.utcnow() + timedelta(minutes=OTP_EXPIRY_MINUTES)).isoformat()

    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    user = c.execute('SELECT id, is_verified FROM users WHERE email=? OR username=?', (email, email)).fetchone()
    if not user:
        conn.close()
        return None, jsonify({'success': False, 'error': 'User not found'}), 404
    if user['is_verified']:
        conn.close()
        return None, jsonify({'success': False, 'error': 'Email already verified'}), 400

    c.execute('UPDATE users SET otp_code=?, otp_expires_at=? WHERE id=?', (otp, expires_at, user['id']))
    conn.commit()
    conn.close()
    return otp, None, None

@app.route('/signup', methods=['POST'])
@app.route('/api/auth/register', methods=['POST'])
def signup():
    data = request.get_json(silent=True) or {}
    email = (data.get('email') or data.get('username') or '').strip().lower()
    password = data.get('password') or ''
    full_name = (data.get('full_name') or data.get('name') or '').strip()
    if not full_name or not email or not password:
        return jsonify({'success': False, 'error': 'Full name, email and password are required'}), 400

    otp, response, status = create_pending_user(email, password, full_name)
    if response:
        return response, status

    try:
        delivery = send_otp_email(email, otp)
    except Exception as exc:
        logger.exception('Signup OTP email delivery crashed recipient=%s', email)
        delivery = {
            'success': False,
            'error': f'Unexpected OTP email error: {type(exc).__name__}',
            'error_type': 'unexpected_error',
        }
    if not delivery['success']:
        return email_failure_response(delivery)
    return jsonify({'success': True, 'message': 'OTP sent successfully'})

@app.route('/verify-otp', methods=['POST'])
def verify_otp():
    data = request.get_json(silent=True) or {}
    email = (data.get('email') or '').strip().lower()
    otp = (data.get('otp') or '').strip()
    if not email or not otp:
        return jsonify({'success': False, 'error': 'Email and OTP are required'}), 400

    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    user = c.execute('SELECT * FROM users WHERE email=? OR username=?', (email, email)).fetchone()
    if not user or user['otp_code'] != otp:
        conn.close()
        return jsonify({'success': False, 'error': 'Invalid OTP'}), 400
    try:
        expires_at = datetime.fromisoformat(user['otp_expires_at']) if user['otp_expires_at'] else datetime.min
    except ValueError:
        expires_at = datetime.min
    if datetime.utcnow() > expires_at:
        conn.close()
        return jsonify({'success': False, 'error': 'OTP expired, request new OTP'}), 400

    c.execute('UPDATE users SET is_verified=1, otp_code=NULL, otp_expires_at=NULL WHERE id=?', (user['id'],))
    conn.commit()
    conn.close()
    return jsonify({'success': True, 'message': 'Email verified successfully'})

@app.route('/resend-otp', methods=['POST'])
def resend_otp():
    data = request.get_json(silent=True) or {}
    email = (data.get('email') or '').strip().lower()
    if not email:
        return jsonify({'success': False, 'error': 'Email is required'}), 400

    otp, response, status = issue_new_otp(email)
    if response:
        return response, status

    delivery = send_otp_email(email, otp)
    if not delivery['success']:
        return email_failure_response(delivery)
    return jsonify({'success': True, 'message': 'OTP sent to email', 'email_sent': True})

@app.route('/test-email', methods=['POST'])
def test_email():
    expected_key = os.environ.get('TEST_EMAIL_API_KEY', '')
    provided_key = request.headers.get('X-Test-Email-Key', '')
    if not expected_key:
        return jsonify({
            'success': False,
            'error': 'TEST_EMAIL_API_KEY is not configured',
        }), 503
    if not secrets.compare_digest(provided_key, expected_key):
        return jsonify({'success': False, 'error': 'Unauthorized'}), 401

    data = request.get_json(silent=True) or {}
    email = (data.get('email') or '').strip().lower()
    if not email:
        return jsonify({'success': False, 'error': 'Email is required'}), 400

    delivery = send_email(
        email,
        'TripMate email delivery test',
        'This is a TripMate email delivery test.',
    )
    status = 200 if delivery['success'] else 502
    return jsonify({
        'success': delivery['success'],
        'provider_response': delivery['provider_response'],
        'error': delivery.get('error'),
        'attempts': delivery['attempts'],
    }), status

@app.route('/login', methods=['POST'])
@app.route('/api/auth/login', methods=['POST'])
def login():
    data = request.get_json(silent=True) or {}
    email = (data.get('email') or data.get('username') or '').strip().lower()
    password = data.get('password') or ''
    if not email or not password:
        return jsonify({'success': False, 'error': 'Email and password are required'}), 400

    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    user = c.execute('SELECT * FROM users WHERE email=? OR username=?', (email, email)).fetchone()
    conn.close()
    if not user or not user['password_hash'] or not check_password_hash(user['password_hash'], password):
        return jsonify({'success': False, 'error': 'Invalid email or password'}), 401
    if not user['is_verified']:
        return jsonify({'success': False, 'error': 'Please verify your email before login'}), 403

    session['user_id'] = user['id']
    session['username'] = user['username']
    session['display_name'] = get_display_name(user['display_name'], user['email'], user['username'])
    return jsonify({
        'success': True,
        'name': session['display_name'],
        'display_name': session['display_name'],
        'username': user['username'],
    })

@app.route('/api/auth/logout', methods=['POST'])
def logout():
    session.clear()
    return jsonify({'success': True})

@app.route('/api/auth/me')
def me():
    if 'user_id' in session:
        conn = sqlite3.connect(DB_FILE)
        conn.row_factory = sqlite3.Row
        user = conn.execute('SELECT username, email, display_name FROM users WHERE id=?', (session['user_id'],)).fetchone()
        conn.close()
        if not user:
            session.clear()
            return jsonify({'logged_in': False})
        display_name = get_display_name(user['display_name'], user['email'], user['username'])
        session['display_name'] = display_name
        return jsonify({
            'logged_in': True,
            'name': display_name,
            'display_name': display_name,
            'username': user['username'],
        })
    return jsonify({'logged_in': False})

# ── Trips ─────────────────────────────────────────────────────────────────────

@app.route('/api/trips', methods=['GET', 'POST'])
@login_required
def handle_trips():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()

    if request.method == 'POST':
        data = request.json
        c.execute('''INSERT INTO trips (user_id, destination, purpose, start_loc, cost,
                      fuel_cost, food_cost, accommodation_cost, other_cost, vehicle_type, path)
                      VALUES (?,?,?,?,?,?,?,?,?,?,?)''', (
            session['user_id'],
            data.get('destination', 'Unknown'),
            data.get('purpose', 'Personal'),
            data.get('start_loc', data.get('startLoc', 'Unknown')),
            data.get('cost', 0),
            data.get('fuel_cost', 0),
            data.get('food_cost', 0),
            data.get('accommodation_cost', 0),
            data.get('other_cost', 0),
            data.get('vehicle_type', 'car'),
            json.dumps([])
        ))
        conn.commit()
        new_id = c.lastrowid
        conn.close()
        return jsonify({'success': True, 'trip_id': new_id}), 201

    c.execute('SELECT * FROM trips WHERE user_id=? ORDER BY created_at DESC', (session['user_id'],))
    rows = c.fetchall()
    trips = []
    for row in rows:
        t = dict(row)
        t['path'] = json.loads(t['path'])
        trips.append(t)
    conn.close()
    return jsonify(trips)

@app.route('/api/trips/<int:trip_id>', methods=['GET', 'PUT'])
@login_required
def single_trip(trip_id):
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    if request.method == 'PUT':
        data = request.json
        c.execute('UPDATE trips SET distance=?, path=? WHERE id=? AND user_id=?',
                  (data.get('distance', 0), json.dumps(data.get('path', [])), trip_id, session['user_id']))
        conn.commit()
        conn.close()
        return jsonify({'success': True})
    c.execute('SELECT * FROM trips WHERE id=? AND user_id=?', (trip_id, session['user_id']))
    row = c.fetchone()
    if not row:
        conn.close()
        return jsonify({'error': 'Not found'}), 404
    t = dict(row)
    t['path'] = json.loads(t['path'])
    conn.close()
    return jsonify(t)

# ── Notes ─────────────────────────────────────────────────────────────────────

@app.route('/api/notes', methods=['GET', 'POST'])
@login_required
def handle_notes():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    if request.method == 'POST':
        data = request.json
        c.execute('INSERT INTO notes (trip_id, user_id, title, content) VALUES (?,?,?,?)',
                  (data.get('trip_id'), session['user_id'], data.get('title', 'Untitled'), data.get('content', '')))
        conn.commit()
        nid = c.lastrowid
        conn.close()
        return jsonify({'success': True, 'note_id': nid}), 201
    trip_id = request.args.get('trip_id')
    if trip_id:
        c.execute('SELECT * FROM notes WHERE user_id=? AND trip_id=? ORDER BY created_at DESC',
                  (session['user_id'], trip_id))
    else:
        c.execute('SELECT * FROM notes WHERE user_id=? ORDER BY created_at DESC', (session['user_id'],))
    notes = [dict(r) for r in c.fetchall()]
    conn.close()
    return jsonify(notes)

@app.route('/api/notes/<int:note_id>', methods=['PUT', 'DELETE'])
@login_required
def single_note(note_id):
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    if request.method == 'DELETE':
        c.execute('DELETE FROM notes WHERE id=? AND user_id=?', (note_id, session['user_id']))
        conn.commit()
        conn.close()
        return jsonify({'success': True})
    data = request.json
    c.execute('UPDATE notes SET title=?, content=? WHERE id=? AND user_id=?',
              (data.get('title'), data.get('content'), note_id, session['user_id']))
    conn.commit()
    conn.close()
    return jsonify({'success': True})

# ── Memories (Images) ─────────────────────────────────────────────────────────

@app.route('/api/memories', methods=['GET', 'POST'])
@login_required
def handle_memories():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    if request.method == 'POST':
        data = request.json
        c.execute('INSERT INTO memories (trip_id, user_id, filename, image_data, caption) VALUES (?,?,?,?,?)',
                  (data.get('trip_id'), session['user_id'], data.get('filename',''), data.get('image_data',''), data.get('caption','')))
        conn.commit()
        mid = c.lastrowid
        conn.close()
        return jsonify({'success': True, 'memory_id': mid}), 201
    trip_id = request.args.get('trip_id')
    if trip_id:
        c.execute('SELECT id, trip_id, user_id, filename, caption, created_at FROM memories WHERE user_id=? AND trip_id=? ORDER BY created_at DESC',
                  (session['user_id'], trip_id))
    else:
        c.execute('SELECT id, trip_id, user_id, filename, caption, created_at FROM memories WHERE user_id=? ORDER BY created_at DESC', (session['user_id'],))
    mems = [dict(r) for r in c.fetchall()]
    conn.close()
    return jsonify(mems)

@app.route('/api/memories/<int:mem_id>/image')
@app.route('/api/memories/<int:mem_id>/image-thumb')
@app.route('/api/memories/<int:mem_id>/image-inline')
@login_required
def get_memory_image(mem_id):
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    c.execute('SELECT image_data FROM memories WHERE id=? AND user_id=?', (mem_id, session['user_id']))
    row = c.fetchone()
    conn.close()
    if not row:
        return jsonify({'error': 'Not found'}), 404
    return jsonify({'image_data': row['image_data']})

def fetch_overpass_places(location=None, lat=None, lng=None, types=None):
    if types is None:
        types = ['tourism="attraction"', 'historic="landmark"', 'tourism="museum"']
    
    query_body = ""
    for t in types:
        if location:
            query_body += f'node[{t}](area.searchArea);way[{t}](area.searchArea);'
        elif lat and lng:
            query_body += f'node[{t}](around:5000,{lat},{lng});way[{t}](around:5000,{lat},{lng});'
    
    if location:
        query = f'[out:json][timeout:15];area[name="{location}"]->.searchArea;({query_body});out center 10;'
    else:
        query = f'[out:json][timeout:15];({query_body});out center 10;'
        
    url = 'https://overpass-api.de/api/interpreter?data=' + urllib.parse.quote(query)
    req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw_data = response.read().decode()
            if not raw_data: return []
            data = json.loads(raw_data)
            places = []
            for e in data.get('elements', []):
                tags = e.get('tags', {})
                name = tags.get('name')
                if not name: continue
                
                place_type = 'attraction'
                if 'amenity' in tags:
                    if tags['amenity'] == 'hospital': place_type = 'hospital'
                    elif tags['amenity'] == 'fuel': place_type = 'fuel'
                    elif tags['amenity'] == 'restaurant' or tags['amenity'] == 'cafe': place_type = 'food'
                elif 'tourism' in tags:
                    if tags['tourism'] == 'hotel': place_type = 'essential'
                
                places.append({
                    "name": name,
                    "lat": e.get('lat') or e.get('center', {}).get('lat'),
                    "lng": e.get('lon') or e.get('center', {}).get('lon'),
                    "type": place_type,
                    "description": tags.get('description', f"A beautiful {place_type} in {location or 'this area'}.")
                })
            return places
    except Exception as e:
        print(f"Overpass API error: {e}")
        return []

@app.route('/api/suggestions')
def suggestions():
    lat = request.args.get('lat', type=float)
    lng = request.args.get('lng', type=float)
    location = request.args.get('location', type=str)
    category = request.args.get('category', default='attraction')
    
    types = ['tourism="attraction"', 'historic="landmark"']
    if category == 'food':
        types = ['amenity="restaurant"', 'amenity="cafe"']
    elif category == 'nature':
        types = ['leisure="park"', 'landuse="forest"']
    elif category == 'essential':
        types = ['amenity="hospital"', 'amenity="pharmacy"', 'tourism="hotel"']

    places = fetch_overpass_places(location=location, lat=lat, lng=lng, types=types)
    if not places and lat and lng:
        places = [
            {"name": "Nearby Landmark", "lat": lat+0.005, "lng": lng+0.005, "type": "attraction"},
            {"name": "Scenic Spot", "lat": lat-0.005, "lng": lng-0.005, "type": "attraction"}
        ]
    return jsonify(places)

# ── Expenses ──────────────────────────────────────────────────────────────────

@app.route('/api/expenses', methods=['GET', 'POST'])
@login_required
def handle_expenses():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    if request.method == 'POST':
        data = request.json
        c.execute('INSERT INTO expenses (trip_id, user_id, category, description, amount) VALUES (?,?,?,?,?)',
                  (data.get('trip_id'), session['user_id'], data.get('category', 'Other'),
                   data.get('description', ''), data.get('amount', 0)))
        conn.commit()
        eid = c.lastrowid
        conn.close()
        return jsonify({'success': True, 'expense_id': eid}), 201
    trip_id = request.args.get('trip_id')
    if trip_id:
        c.execute('SELECT * FROM expenses WHERE user_id=? AND trip_id=? ORDER BY created_at DESC',
                  (session['user_id'], trip_id))
    else:
        c.execute('SELECT * FROM expenses WHERE user_id=? ORDER BY created_at DESC', (session['user_id'],))
    expenses = [dict(r) for r in c.fetchall()]
    conn.close()
    return jsonify(expenses)

@app.route('/api/expenses/<int:exp_id>', methods=['DELETE'])
@login_required
def delete_expense(exp_id):
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute('DELETE FROM expenses WHERE id=? AND user_id=?', (exp_id, session['user_id']))
    conn.commit()
    conn.close()
    return jsonify({'success': True})

# ── Tourist Spots ──────────────────────────────────────────────────────────────

@app.route('/api/tourist-spots')
def tourist_spots():
    destination = request.args.get('destination', '')
    if not destination:
        return jsonify([])
    try:
        geo_url = 'https://nominatim.openstreetmap.org/search?format=json&q=' + urllib.parse.quote(destination) + '&limit=1'
        geo_req = urllib.request.Request(geo_url, headers={'User-Agent': 'TripMateApp/1.0'})
        with urllib.request.urlopen(geo_req, timeout=8) as r:
            geo_data = json.loads(r.read().decode())
        if not geo_data:
            return jsonify([])
        lat = float(geo_data[0]['lat'])
        lng = float(geo_data[0]['lon'])
    except Exception as e:
        print(f"Geocoding error: {e}")
        return jsonify([])

    spots = []
    try:
        query = '[out:json][timeout:20];('
        query += f'node["tourism"="attraction"](around:15000,{lat},{lng});'
        query += f'node["tourism"="museum"](around:15000,{lat},{lng});'
        query += f'node["tourism"="viewpoint"](around:15000,{lat},{lng});'
        query += f'node["historic"="monument"](around:15000,{lat},{lng});'
        query += f'node["historic"="ruins"](around:15000,{lat},{lng});'
        query += f'node["leisure"="park"](around:15000,{lat},{lng});'
        query += f'node["amenity"="place_of_worship"](around:10000,{lat},{lng});'
        query += ');out body 25;'
        url = 'https://overpass-api.de/api/interpreter?data=' + urllib.parse.quote(query)
        req = urllib.request.Request(url, headers={'User-Agent': 'TripMateApp/1.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read().decode())
        seen = set()
        for e in data.get('elements', []):
            tags = e.get('tags', {})
            name = tags.get('name') or tags.get('name:en')
            if not name or name in seen:
                continue
            seen.add(name)
            spot_type = 'attraction'
            if tags.get('tourism') == 'museum': spot_type = 'museum'
            elif tags.get('tourism') == 'viewpoint': spot_type = 'viewpoint'
            elif tags.get('historic'): spot_type = 'historic'
            elif tags.get('leisure') == 'park': spot_type = 'park'
            elif tags.get('amenity') == 'place_of_worship': spot_type = 'temple'
            spots.append({
                'name': name,
                'lat': e.get('lat'),
                'lng': e.get('lon'),
                'type': spot_type,
                'description': tags.get('description', ''),
                'wikipedia': tags.get('wikipedia', ''),
                'website': tags.get('website', tags.get('contact:website', '')),
                'opening_hours': tags.get('opening_hours', ''),
                'fee': tags.get('fee', ''),
            })
    except Exception as e:
        print(f"Overpass spots error: {e}")
    return jsonify(spots[:20])

@app.route('/api/ai/chat', methods=['POST'])
@login_required
def ai_chat():
    data = request.json
    messages = data.get('messages', [])
    return jsonify({
        'content': [{
            'type': 'text',
            'text': "I'm your AI Travel Assistant! 🌍 How can I help you plan your journey today?"
        }]
    })

if __name__ == '__main__':
    app.run(debug=True, port=5000)
