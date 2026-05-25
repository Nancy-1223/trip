from flask import Flask, render_template, request, jsonify, session, redirect, url_for
import sqlite3
import os
import json
import base64
from datetime import datetime
from functools import wraps
import urllib.request
import urllib.parse

template_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), 'templates'))
static_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), 'static'))
app = Flask(__name__, static_folder=static_dir, template_folder=template_dir)
app.secret_key = 'tripmate_secret_2024'
DB_FILE = 'database.db'

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
        c.execute("INSERT INTO users (username, password, display_name) VALUES (?, ?, ?)",
                  ('traveler', 'demo123', 'Demo Traveler'))
    except:
        pass
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

@app.route('/api/auth/login', methods=['POST'])
def login():
    data = request.json
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    c = conn.cursor()
    c.execute('SELECT * FROM users WHERE username=? AND password=?',
              (data.get('username'), data.get('password')))
    user = c.fetchone()
    conn.close()
    if user:
        session['user_id'] = user['id']
        session['username'] = user['username']
        session['display_name'] = user['display_name']
        return jsonify({'success': True, 'display_name': user['display_name'], 'username': user['username']})
    return jsonify({'success': False, 'error': 'Invalid credentials'}), 401

@app.route('/api/auth/register', methods=['POST'])
def register():
    data = request.json
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    try:
        c.execute("INSERT INTO users (username, password, display_name) VALUES (?, ?, ?)",
                  (data.get('username'), data.get('password'), data.get('display_name', data.get('username'))))
        conn.commit()
        user_id = c.lastrowid
        conn.close()
        session['user_id'] = user_id
        session['username'] = data['username']
        session['display_name'] = data.get('display_name', data['username'])
        return jsonify({'success': True, 'display_name': session['display_name']})
    except sqlite3.IntegrityError:
        conn.close()
        return jsonify({'success': False, 'error': 'Username already taken'}), 409

@app.route('/api/auth/logout', methods=['POST'])
def logout():
    session.clear()
    return jsonify({'success': True})

@app.route('/api/auth/me')
def me():
    if 'user_id' in session:
        return jsonify({'logged_in': True, 'display_name': session.get('display_name'), 'username': session.get('username')})
    return jsonify({'logged_in': False})

# ── Trip Cost Calculator ──────────────────────────────────────────────────────

@app.route('/api/calculate-cost', methods=['POST'])
def calculate_cost():
    data = request.json
    distance = float(data.get('distance', 0))        # km
    vehicle  = data.get('vehicle', 'car')
    days     = int(data.get('days', 1))
    people   = int(data.get('people', 1))

    # Realistic Indian fuel/transport rates (₹/km, one-way)
    fuel_rates = {'car': 6, 'bike': 2, 'bus': 0.5, 'auto': 12, 'train': 0.5, 'flight': 4}
    fuel_per_km = fuel_rates.get(vehicle, 6)
    if vehicle == 'flight':
        fuel_cost = round(max(2500, distance * fuel_per_km) * people, 2)
    elif vehicle == 'auto':
        fuel_cost = round(min(distance, 30) * fuel_per_km * people, 2)
    else:
        fuel_cost = round(distance * fuel_per_km * people, 2)

    food_per_day = {'Tour': 300, 'Business': 500, 'Personal': 200}
    food_cost = round(food_per_day.get(data.get('purpose', 'Personal'), 250) * people * days, 2)

    acc_per_night = {'Tour': 800, 'Business': 1500, 'Personal': 500}
    acc_nights = max(0, days - 1)
    acc_cost = round(acc_per_night.get(data.get('purpose', 'Personal'), 700) * acc_nights, 2)

    misc = round(150 * people * days if data.get('purpose') == 'Tour' else 50 * people, 2)
    total = fuel_cost + food_cost + acc_cost + misc

    return jsonify({
        'fuel': fuel_cost,
        'food': food_cost,
        'accommodation': acc_cost,
        'misc': misc,
        'total': round(total, 2),
        'per_person': round(total / people, 2)
    })

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
