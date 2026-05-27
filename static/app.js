// ═══════════════════════════════════════════ INIT
lucide.createIcons();

// ─── State ────────────────────────────────────────────────────────────────────
let map, mapMarker, mapPolyline, routePolyline, routeDestinationMarker;
let touristSpotMarkers = [];
let pathData = [], watchId = null, currentTripId = null;
let tripPurpose = 'Tour';
const FALLBACK_LATLNG = [20.5937, 78.9629];
let userCurrentLatLng = null;
let currentLat = null, currentLng = null;
let gpsLocationReady = false;
let gpsAlertShown = false;
let activeDestinationName = '';
let plannerLastStartLatLng = null;
let plannerLastDestinationLatLng = null;
let plannerLastRouteGeometry = null;
let allTrips = [];
let selectedImageData = null;
let isAuthenticated = false;
let mapUserHasInteracted = false;
let lastAutoFitRouteKey = null;
let pendingSignupEmail = '';

// ─── Auth ─────────────────────────────────────────────────────────────────────
async function checkAuth() {
    try {
        const res = await fetch('/api/auth/me');
        const data = await res.json();
        if (data.logged_in) {
            isAuthenticated = true;
            document.getElementById('greeting-name').textContent = `Hi, ${data.display_name}! 👋`;
            showView('dashboard-view');
            loadTrips();
        } else {
            isAuthenticated = false;
            showView('login-view');
        }
    } catch {
        isAuthenticated = false;
        showView('login-view');
    }
}

function switchAuthTab(tab) {
    document.querySelectorAll('.tab-btn').forEach((b, i) => b.classList.toggle('active', (i === 0) === (tab === 'login')));
    document.getElementById('login-form').classList.toggle('hidden', tab !== 'login');
    document.getElementById('register-form').classList.toggle('hidden', tab !== 'register');
    document.getElementById('otp-form').classList.add('hidden');
}

async function doLogin() {
    const username = document.getElementById('login-username').value.trim();
    const password = document.getElementById('login-password').value;
    const errEl = document.getElementById('login-error');
    errEl.classList.add('hidden');
    if (!username || !password) { errEl.textContent = 'Email and password are required'; errEl.classList.remove('hidden'); return; }
    try {
        const res = await fetch('/login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ email: username, password }) });
        const data = await res.json();
        if (res.ok && data.success) {
            isAuthenticated = true;
            document.getElementById('greeting-name').textContent = `Hi, ${data.display_name}! 👋`;
            showView('dashboard-view');
            loadTrips();
        } else {
            errEl.textContent = data.error || 'Invalid email or password';
            errEl.classList.remove('hidden');
        }
    } catch { errEl.textContent = 'Network error.'; errEl.classList.remove('hidden'); }
}

async function doRegister() {
    const email = document.getElementById('reg-email').value.trim().toLowerCase();
    const password = document.getElementById('reg-password').value;
    const errEl = document.getElementById('reg-error');
    errEl.classList.add('hidden');
    if (!email || !password) { errEl.textContent = 'Email and password are required'; errEl.classList.remove('hidden'); return; }
    try {
        const res = await fetch('/signup', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ email, password }) });
        const data = await res.json();
        if (res.ok && data.success) {
            pendingSignupEmail = email;
            document.getElementById('register-form').classList.add('hidden');
            document.getElementById('otp-form').classList.remove('hidden');
            document.getElementById('otp-input').value = '';
            document.getElementById('otp-error').classList.add('hidden');
            document.getElementById('otp-success').classList.add('hidden');
            document.getElementById('otp-input').focus();
        } else {
            errEl.textContent = data.error || 'Registration failed.';
            errEl.classList.remove('hidden');
        }
    } catch { errEl.textContent = 'Network error.'; errEl.classList.remove('hidden'); }
}

async function verifyOtp() {
    const otp = document.getElementById('otp-input').value.trim();
    const errEl = document.getElementById('otp-error');
    const successEl = document.getElementById('otp-success');
    errEl.classList.add('hidden');
    successEl.classList.add('hidden');
    if (!otp) { errEl.textContent = 'Enter the OTP'; errEl.classList.remove('hidden'); return; }
    try {
        const res = await fetch('/verify-otp', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ email: pendingSignupEmail, otp })
        });
        const data = await res.json();
        if (res.ok && data.success) {
            successEl.textContent = 'Email verified successfully';
            successEl.classList.remove('hidden');
            document.getElementById('login-username').value = pendingSignupEmail;
            setTimeout(() => switchAuthTab('login'), 1200);
        } else {
            errEl.textContent = data.error || 'Invalid OTP';
            errEl.classList.remove('hidden');
        }
    } catch {
        errEl.textContent = 'Network error.';
        errEl.classList.remove('hidden');
    }
}

async function resendOtp() {
    const errEl = document.getElementById('otp-error');
    const successEl = document.getElementById('otp-success');
    errEl.classList.add('hidden');
    successEl.classList.add('hidden');
    if (!pendingSignupEmail) {
        errEl.textContent = 'Email is required';
        errEl.classList.remove('hidden');
        return;
    }
    try {
        const res = await fetch('/resend-otp', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ email: pendingSignupEmail })
        });
        const data = await res.json();
        if (res.ok && data.success) {
            successEl.textContent = data.message || 'OTP sent to email';
            successEl.classList.remove('hidden');
            document.getElementById('otp-input').value = '';
            document.getElementById('otp-input').focus();
        } else {
            errEl.textContent = data.error || 'Could not resend OTP';
            errEl.classList.remove('hidden');
        }
    } catch {
        errEl.textContent = 'Network error.';
        errEl.classList.remove('hidden');
    }
}

async function doLogout() {
    await fetch('/api/auth/logout', { method: 'POST' });
    isAuthenticated = false;
    showView('login-view');
}

// ─── View Navigation ──────────────────────────────────────────────────────────
function showView(viewId) {
    if (viewId !== 'login-view' && !isAuthenticated) {
        viewId = 'login-view';
    }
    document.querySelectorAll('.view').forEach(v => {
        v.classList.remove('active-view');
        v.classList.add('hidden');
    });
    const v = document.getElementById(viewId);
    v.classList.remove('hidden');
    v.classList.add('active-view');
    if (viewId === 'map-view') {
        setTimeout(restoreActiveTripFromStorage, 100);
    }
}

// ─── Theme ────────────────────────────────────────────────────────────────────
function toggleTheme() {
    document.body.classList.toggle('dark');
    const isDark = document.body.classList.contains('dark');
    document.getElementById('theme-icon').setAttribute('data-lucide', isDark ? 'sun' : 'moon');
    lucide.createIcons();
}

// ─── Toast ────────────────────────────────────────────────────────────────────
function showToast(msg, duration = 2200) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(window._toastTimer);
    window._toastTimer = setTimeout(() => t.classList.add('hidden'), duration);
}

// ─── Load Trips ───────────────────────────────────────────────────────────────
async function loadTrips() {
    try {
        const res = await fetch('/api/trips');
        if (res.status === 401) { showView('login-view'); return; }
        allTrips = await res.json();
        renderTripsList();
    } catch { document.getElementById('trips-list').innerHTML = '<div class="empty-placeholder">Could not load trips.</div>'; }
}

function renderTripsList() {
    const list = document.getElementById('trips-list');
    if (!allTrips.length) {
        list.innerHTML = '<div class="empty-placeholder">No trips yet. Start your first adventure!</div>';
        return;
    }
    list.innerHTML = allTrips.slice(0, 5).map(t => `
        <div class="trip-card">
            <div class="trip-card-left">
                <div class="trip-icon"><i data-lucide="map-pin"></i></div>
                <div>
                    <div class="trip-dest">${t.destination}</div>
                    <div class="trip-meta">${t.purpose} · ${new Date(t.created_at).toLocaleDateString('en-IN')}</div>
                </div>
            </div>
            <button class="icon-btn blue" onclick="showTripDetail(${t.id})"><i data-lucide="chevron-right"></i></button>
        </div>
    `).join('');
    lucide.createIcons();
}

function showTripDetail(tripId) {
    const t = allTrips.find(x => x.id === tripId);
    if (!t) return;
    showToast(`${t.destination}`);
}

// ─── Trip Setup Modal ─────────────────────────────────────────────────────────
function showSetupModal() {
    document.getElementById('setup-modal').classList.remove('hidden');
    detectLocation();
}
function hideSetupModal() {
    document.getElementById('setup-modal').classList.add('hidden');
}

function detectLocation() {
    const input = document.getElementById('start-point-input');
    const iconWrap = document.getElementById('start-icon-wrap');
    const detectWrap = document.getElementById('detect-icon-wrap');
    input.value = 'Detecting location...';
    iconWrap.innerHTML = '<i data-lucide="loader-2" class="spin text-blue" style="width:18px;height:18px"></i>';
    detectWrap.innerHTML = '<i data-lucide="loader-2" class="spin" style="width:18px;height:18px"></i>';
    lucide.createIcons();
    startLiveLocationWatch();
    return;
    if (!navigator.geolocation) { input.value = 'GPS not supported'; return; }
    navigator.geolocation.getCurrentPosition(
        async pos => {
            userCurrentLatLng = [pos.coords.latitude, pos.coords.longitude];
            let name = `${pos.coords.latitude.toFixed(3)}, ${pos.coords.longitude.toFixed(3)}`;
            try {
                const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${pos.coords.latitude}&lon=${pos.coords.longitude}`);
                const d = await r.json();
                if (d.address) name = d.address.city || d.address.town || d.address.village || d.address.suburb || name;
            } catch {}
            input.value = `📍 ${name}`;
            iconWrap.innerHTML = '<i data-lucide="check-circle" style="color:#22c55e;width:18px;height:18px"></i>';
            detectWrap.innerHTML = '<i data-lucide="crosshair" style="width:18px;height:18px"></i>';
            lucide.createIcons();
        },
        () => {
            input.value = 'GPS Unavailable';
            iconWrap.innerHTML = '<i data-lucide="alert-circle" style="color:#ef4444;width:18px;height:18px"></i>';
            detectWrap.innerHTML = '<i data-lucide="crosshair" style="width:18px;height:18px"></i>';
            lucide.createIcons();
        },
        { enableHighAccuracy: true, timeout: 8000 }
    );
}

let startTimeout;
function onStartChange() {
    clearTimeout(startTimeout);
    startTimeout = setTimeout(async () => {
        const start = document.getElementById('start-point-input').value.trim();
        const iconWrap = document.getElementById('start-icon-wrap');
        if (start.length > 2) {
            iconWrap.innerHTML = '<i data-lucide="loader-2" class="spin text-blue" style="width:18px;height:18px"></i>';
            lucide.createIcons();
            try {
                const geoRes = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(start)}`);
                const geoData = await geoRes.json();
                if (geoData && geoData.length > 0) {
                    if (!gpsLocationReady) userCurrentLatLng = [parseFloat(geoData[0].lat), parseFloat(geoData[0].lon)];
                    iconWrap.innerHTML = '<i data-lucide="check-circle" style="color:#22c55e;width:18px;height:18px"></i>';
                    lucide.createIcons();
                    // recalculate distance if destination is also present
                    onDestChange();
                } else {
                    iconWrap.innerHTML = '<i data-lucide="map-pin" class="text-blue"></i>';
                    lucide.createIcons();
                }
            } catch (err) {
                console.error("Error geocoding start location:", err);
            }
        }
    }, 1200);
}

function selectPurpose(el) {
    document.querySelectorAll('.purpose-chip').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    tripPurpose = el.dataset.purpose;
    updateSetupSuggestions();
}

let destTimeout;
function onDestChange() {
    updateSetupSuggestions();
    activeDestinationName = document.getElementById('destination-input').value.trim();
    startLiveLocationWatch();
    
    // Auto-calculate distance
    clearTimeout(destTimeout);
    destTimeout = setTimeout(async () => {
        const dest = document.getElementById('destination-input').value.trim();
        const routeStart = getRouteStartLatLng();
        if (dest.length > 2 && !routeStart) {
            showToast('Waiting for live GPS location...');
            return;
        }
        if (dest.length > 2 && routeStart) {
            try {
                const destinationLatLng = await geocodeDestination(dest);

                if (destinationLatLng) {
                    const destLat = destinationLatLng[0];
                    const destLon = destinationLatLng[1];
                    
                    const route = await fetchRouteGeometry(routeStart, [destLat, destLon]);
                    let distKM = route ? Math.round(route.distance / 100) / 10 : 0;
                    
                    if (distKM === 0) {
                        distKM = calcPathDistance([routeStart, [destLat, destLon]]);
                    }

                    if (map && route) {
                        drawRoutePolyline(route.latLngs, [destLat, destLon]);
                    }
                    
                    if (distKM > 0) {
                        showToast(`Calculated distance: ${distKM} km`);
                    }
                } else {
                    showToast(`Could not find destination: ${dest}`);
                }
            } catch (err) {
                console.error("Error calculating distance:", err);
                showToast('Route not available');
            }
        }
    }, 1200);
}

async function updateSetupSuggestions() {
    const dest = document.getElementById('destination-input').value.trim();
    const card = document.getElementById('setup-suggestions-card');
    if (tripPurpose === 'Tour' && dest.length > 2) {
        card.classList.remove('hidden');
        document.getElementById('sugg-loc-label').textContent = dest;
        document.getElementById('setup-suggestions-list').innerHTML = '<div style="color:var(--sub);font-size:0.8rem;padding:8px 0">Loading spots...</div>';
        try {
            const res = await fetch(`/api/tourist-spots?destination=${encodeURIComponent(dest)}`);
            const places = await res.json();
            const badge = document.getElementById('sugg-count-badge');
            badge.textContent = places.length ? `${places.length} spots` : '';
            if (!places.length) {
                document.getElementById('setup-suggestions-list').innerHTML = '<div style="color:var(--sub);font-size:0.8rem;padding:8px 0">No spots found nearby.</div>';
                return;
            }
            const typeEmoji = { attraction:'🏛', museum:'🏛', viewpoint:'👁', historic:'🏯', park:'🌿', temple:'🛕' };
            document.getElementById('setup-suggestions-list').innerHTML = places.slice(0, 8).map(p => `
                <div class="sugg-item">
                    <span>${typeEmoji[p.type] || '📍'}</span>
                    <span class="sugg-item-type">${p.type}</span>
                    <span class="sugg-item-name">${escHtml(p.name)}</span>
                </div>
            `).join('');
        } catch {}
    } else {
        card.classList.add('hidden');
    }
}

// ─── Start Trip ───────────────────────────────────────────────────────────────
async function startTrip() {
    const dest = document.getElementById('destination-input').value.trim();
    const start = document.getElementById('start-point-input').value.trim();
    if (!dest) { showToast('Enter a destination'); return; }

    hideSetupModal();
    showView('map-view');
    document.getElementById('trip-status-badge').textContent = `${tripPurpose.toUpperCase()} ACTIVE`;
    document.getElementById('trip-dest-badge').textContent = `TO: ${dest}`;
    activeDestinationName = dest;
    lastAutoFitRouteKey = null;
    mapUserHasInteracted = false;
    initMap();
    startLiveLocationWatch(dest);
    await drawRouteToDestination(dest);
    if (tripPurpose === 'Tour') addTouristSpotMarkersForDestination(dest);

    try {
        const res = await fetch('/api/trips', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({
                destination: dest, 
                start_loc: start,
                purpose: tripPurpose,
                distance: 0
            })
        });
        const data = await res.json();
        currentTripId = data.trip_id;
    } catch (err) { console.error(err); }
}

// ─── Map ──────────────────────────────────────────────────────────────────────
function initMap() {
    if (map) { setTimeout(() => map.invalidateSize(), 150); return; }
    map = L.map('map', { zoomControl: false }).setView(FALLBACK_LATLNG, 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OSM' }).addTo(map);
    mapPolyline = L.polyline([], { color: '#3b82f6', weight: 6, opacity: 0.8, dashArray: '12,8' }).addTo(map);
    routePolyline = L.polyline([], { color: '#2563eb', weight: 6, opacity: 0.9 }).addTo(map);
    mapMarker = L.marker(FALLBACK_LATLNG);
    const markManualMapInteraction = () => { mapUserHasInteracted = true; };
    map.on('dragstart', markManualMapInteraction);
    const container = map.getContainer();
    ['wheel', 'mousedown', 'touchstart', 'dblclick', 'keydown'].forEach(eventName => {
        container.addEventListener(eventName, markManualMapInteraction, { passive: true });
    });
    setTimeout(() => map.invalidateSize(), 150);
}

function getRouteStartLatLng() {
    if (gpsLocationReady && currentLat !== null && currentLng !== null) return [currentLat, currentLng];
    return null;
}

function updateCurrentLocationMarker(latLng, shouldCenter = true) {
    if (!map) initMap();
    if (!mapMarker) mapMarker = L.marker(latLng);
    mapMarker.setLatLng(latLng);
    if (!map.hasLayer(mapMarker)) mapMarker.addTo(map);
    if (shouldCenter && !mapUserHasInteracted) map.setView(latLng, 16);
}

async function updateDetectedLocationInput(latLng) {
    const input = document.getElementById('start-point-input');
    const iconWrap = document.getElementById('start-icon-wrap');
    const detectWrap = document.getElementById('detect-icon-wrap');
    if (!input) return;

    let name = `${latLng[0].toFixed(3)}, ${latLng[1].toFixed(3)}`;
    try {
        const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${latLng[0]}&lon=${latLng[1]}`);
        const d = await r.json();
        if (d.address) name = d.address.city || d.address.town || d.address.village || d.address.suburb || name;
    } catch {}

    input.value = `Live: ${name}`;
    if (iconWrap) iconWrap.innerHTML = '<i data-lucide="check-circle" style="color:#22c55e;width:18px;height:18px"></i>';
    if (detectWrap) detectWrap.innerHTML = '<i data-lucide="crosshair" style="width:18px;height:18px"></i>';
    lucide.createIcons();
}

function applyLiveLocation(lat, lng, shouldCenter = true) {
    const nextLat = Number(lat);
    const nextLng = Number(lng);
    const latLng = getValidLatLng({ lat: nextLat, lng: nextLng });
    if (!latLng) return;

    currentLat = latLng[0];
    currentLng = latLng[1];
    gpsLocationReady = true;
    userCurrentLatLng = latLng;

    updateCurrentLocationMarker(latLng, shouldCenter);
    updateDetectedLocationInput(latLng);

    if (pathData.length === 0 || calcPathDistance([pathData[pathData.length - 1], latLng]) > 0.003) {
        pathData.push(latLng);
        if (mapPolyline) mapPolyline.setLatLngs(pathData);
    }

    if (activeDestinationName) drawRouteToDestination(activeDestinationName);
}

window.updateNativeLocation = function(lat, lng) {
    applyLiveLocation(lat, lng, false);
};

function handleGpsFailure() {
    if (!gpsAlertShown) {
        alert('Please enable GPS/location permission');
        gpsAlertShown = true;
    }

    if (!userCurrentLatLng) userCurrentLatLng = FALLBACK_LATLNG;
    updateCurrentLocationMarker(userCurrentLatLng, true);

    const input = document.getElementById('start-point-input');
    const iconWrap = document.getElementById('start-icon-wrap');
    const detectWrap = document.getElementById('detect-icon-wrap');
    if (input) input.value = 'GPS Unavailable';
    if (iconWrap) iconWrap.innerHTML = '<i data-lucide="alert-circle" style="color:#ef4444;width:18px;height:18px"></i>';
    if (detectWrap) detectWrap.innerHTML = '<i data-lucide="crosshair" style="width:18px;height:18px"></i>';
    lucide.createIcons();
}

function startLiveLocationWatch(destinationName = activeDestinationName) {
    if (destinationName) activeDestinationName = destinationName;

    if (!navigator.geolocation) {
        handleGpsFailure();
        return;
    }

    if (watchId !== null) {
        if (gpsLocationReady && activeDestinationName) drawRouteToDestination(activeDestinationName);
        return;
    }

    watchId = navigator.geolocation.watchPosition(
        pos => {
            applyLiveLocation(pos.coords.latitude, pos.coords.longitude, false);
            if (tripPurpose === 'Tour') fetchLiveSuggestions(pos.coords.latitude, pos.coords.longitude);
        },
        err => {
            console.warn('GPS error:', err);
            if (watchId !== null) {
                navigator.geolocation.clearWatch(watchId);
                watchId = null;
            }
            handleGpsFailure();
        },
        { enableHighAccuracy: true, timeout: 10000, maximumAge: 5000 }
    );
}

function clearRoutePolyline() {
    if (routePolyline) routePolyline.setLatLngs([]);
    if (map && routeDestinationMarker) {
        map.removeLayer(routeDestinationMarker);
        routeDestinationMarker = null;
    }
}

function saveActiveTripToStorage(data) {
    Object.entries(data).forEach(([key, value]) => {
        if (value === null || value === undefined || value === '') return;
        sessionStorage.setItem(key, typeof value === 'string' ? value : JSON.stringify(value));
    });
}

function readStoredNumber(key) {
    const value = sessionStorage.getItem(key);
    if (value === null) return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
}

function readStoredGeometry(key) {
    try {
        const value = sessionStorage.getItem(key);
        return value ? JSON.parse(value) : null;
    } catch {
        return null;
    }
}

async function restoreActiveTripFromStorage() {
    if (!document.getElementById('map-view')?.classList.contains('active-view')) return;

    const storedDestination = sessionStorage.getItem('activeDestinationName') || activeDestinationName;
    if (!storedDestination) return;

    const destLat = readStoredNumber('activeDestinationLat');
    const destLng = readStoredNumber('activeDestinationLng');
    const startLat = readStoredNumber('activeStartLat');
    const startLng = readStoredNumber('activeStartLng');
    const routeGeometry = readStoredGeometry('activeRouteGeometry');
    const destinationLatLng = getValidLatLng({ lat: destLat, lng: destLng });
    const startLatLng = getValidLatLng({ lat: startLat, lng: startLng });

    activeDestinationName = storedDestination;
    document.getElementById('trip-status-badge').textContent = 'TOUR ACTIVE';
    document.getElementById('trip-dest-badge').textContent = `TO: ${storedDestination}`;
    initMap();

    if (startLatLng && !gpsLocationReady) {
        userCurrentLatLng = startLatLng;
        updateCurrentLocationMarker(startLatLng, true);
    }

    const validRouteGeometry = Array.isArray(routeGeometry)
        ? routeGeometry.map(p => getValidLatLng({ lat: p?.[0], lng: p?.[1] })).filter(Boolean)
        : [];

    if (destinationLatLng && validRouteGeometry.length >= 2) {
        drawRoutePolyline(validRouteGeometry, destinationLatLng);
        return;
    }

    if (destinationLatLng && startLatLng) {
        try {
            const route = await fetchRouteGeometry(startLatLng, destinationLatLng);
            if (route?.latLngs?.length) {
                saveActiveTripToStorage({ activeRouteGeometry: route.latLngs });
                drawRoutePolyline(route.latLngs, destinationLatLng);
            }
        } catch (err) {
            console.error('[TripMate] Failed to restore active route:', err);
            showToast('Route not available');
        }
    }
}

async function geocodeDestination(destination) {
    if (!destination) return null;

    const rawDestination = destination.trim();
    const lowerDestination = rawDestination.toLowerCase();
    const searchAttempts = [rawDestination];

    if (lowerDestination === 'ooty') {
        searchAttempts.push('Ooty Tamil Nadu India');
    }
    if (!lowerDestination.includes('india')) {
        searchAttempts.push(`${rawDestination} India`);
    }
    if (!lowerDestination.includes('tamil nadu') && lowerDestination.includes('ooty')) {
        searchAttempts.push(`${rawDestination} Tamil Nadu India`);
    }

    const uniqueAttempts = [...new Set(searchAttempts.map(q => q.trim()).filter(Boolean))];

    for (const query of uniqueAttempts) {
        for (const scopedToIndia of [true, false]) {
            const params = new URLSearchParams({
                format: 'json',
                q: query,
                limit: '3',
                addressdetails: '1'
            });
            if (scopedToIndia) params.set('countrycodes', 'in');

            const url = `https://nominatim.openstreetmap.org/search?${params.toString()}`;
            console.log('[TripMate] Geocoding destination:', query, scopedToIndia ? '(India scoped)' : '(global)', url);

            const geoRes = await fetch(url, {
                headers: {
                    'Accept': 'application/json',
                    'Accept-Language': 'en'
                }
            });
            console.log('[TripMate] Nominatim status:', geoRes.status, geoRes.statusText);

            if (!geoRes.ok) {
                console.warn('[TripMate] Nominatim request failed:', query, geoRes.status);
                continue;
            }

            const geoData = await geoRes.json();
            if (!Array.isArray(geoData) || !geoData.length) {
                console.warn('[TripMate] No geocode result for:', query, scopedToIndia ? '(India scoped)' : '(global)');
                continue;
            }

            const best = geoData.find(item => getValidLatLng({ lat: item.lat, lng: item.lon })) || geoData[0];
            const latLng = getValidLatLng({ lat: best.lat, lng: best.lon });
            if (latLng) {
                console.log('[TripMate] Destination coordinates:', latLng[0], latLng[1], best.display_name || query);
                return latLng;
            }
        }
    }

    console.error('[TripMate] Geocoding failed for destination:', rawDestination, uniqueAttempts);
    return null;
}

async function fetchRouteGeometry(sourceLatLng, destinationLatLng) {
    const source = getValidLatLng({ lat: sourceLatLng?.[0], lng: sourceLatLng?.[1] });
    const destination = getValidLatLng({ lat: destinationLatLng?.[0], lng: destinationLatLng?.[1] });
    if (!source || !destination) {
        console.error('[TripMate] Invalid route endpoints:', { sourceLatLng, destinationLatLng });
        return null;
    }

    const url = `https://router.project-osrm.org/route/v1/driving/${source[1]},${source[0]};${destination[1]},${destination[0]}?overview=full&geometries=geojson`;
    console.log('[TripMate] Current GPS:', currentLat, currentLng);
    console.log('[TripMate] Destination for OSRM:', destination[0], destination[1]);
    console.log('[TripMate] OSRM route URL:', url);
    const routeRes = await fetch(url);
    console.log('[TripMate] OSRM response status:', routeRes.status, routeRes.statusText);
    if (!routeRes.ok) throw new Error(`Route request failed: ${routeRes.status}`);

    const routeData = await routeRes.json();
    console.log('[TripMate] OSRM response:', routeData);
    if (routeData?.code && routeData.code !== 'Ok') {
        throw new Error(`OSRM returned ${routeData.code}`);
    }
    const route = routeData?.routes?.[0];
    const coords = route?.geometry?.coordinates;
    if (!Array.isArray(coords) || coords.length < 2) {
        console.error('[TripMate] OSRM route has no drawable geometry:', routeData);
        return null;
    }

    const latLngs = coords
        .map(coord => [Number(coord[1]), Number(coord[0])])
        .filter(coord => getValidLatLng({ lat: coord[0], lng: coord[1] }));

    if (latLngs.length < 2) {
        console.error('[TripMate] OSRM coordinates were invalid after parsing:', coords);
        return null;
    }

    return {
        distance: Number(route.distance) || 0,
        duration: Number(route.duration) || 0,
        latLngs
    };
}

function drawRoutePolyline(routeLatLngs, destinationLatLng) {
    if (!map) initMap();
    clearRoutePolyline();

    if (!Array.isArray(routeLatLngs) || routeLatLngs.length < 2) {
        showToast('Route path is unavailable for this destination.');
        return false;
    }

    if (!routePolyline) {
        routePolyline = L.polyline([], { color: '#2563eb', weight: 6, opacity: 0.9 });
    }
    if (!map.hasLayer(routePolyline)) {
        routePolyline.addTo(map);
    }
    routePolyline.setLatLngs(routeLatLngs);

    const validDestination = getValidLatLng({ lat: destinationLatLng?.[0], lng: destinationLatLng?.[1] });
    if (validDestination) {
        routeDestinationMarker = L.marker(validDestination).addTo(map).bindPopup('Destination');
    }

    const boundsPoints = [...routeLatLngs];
    const routeStart = getRouteStartLatLng();
    if (routeStart) boundsPoints.push(routeStart);
    if (validDestination) boundsPoints.push(validDestination);

    const destinationKey = validDestination ? validDestination.map(n => Number(n).toFixed(5)).join(',') : 'no-dest';
    const routeKey = destinationKey;
    if (!mapUserHasInteracted && lastAutoFitRouteKey !== routeKey) {
        map.fitBounds(L.latLngBounds(boundsPoints), { padding: [46, 46], maxZoom: 15 });
        lastAutoFitRouteKey = routeKey;
    }
    setTimeout(() => map.invalidateSize(), 150);
    return true;
}

async function drawRouteToDestination(destinationName) {
    const destinationText = String(destinationName || '').trim();
    const routeStart = getRouteStartLatLng();
    if (!map) initMap();
    if (!destinationText) {
        showToast('Enter a destination');
        return;
    }
    console.log('[TripMate] Destination text:', destinationText);
    console.log('[TripMate] Current GPS before route:', currentLat, currentLng);
    if (!routeStart) {
        showToast('Waiting for live GPS location...');
        return;
    }

    try {
        const destinationLatLng = await geocodeDestination(destinationText);
        if (!destinationLatLng) {
            clearRoutePolyline();
            showToast(`Destination location not found: ${destinationText}`);
            return;
        }
        console.log('[TripMate] Geocoded coordinates:', destinationLatLng[0], destinationLatLng[1]);

        const route = await fetchRouteGeometry(routeStart, destinationLatLng);
        if (!route || !route.latLngs.length) {
            clearRoutePolyline();
            showToast('Route not available');
            return;
        }

        drawRoutePolyline(route.latLngs, destinationLatLng);
    } catch (err) {
        console.error('Routing failed:', err);
        clearRoutePolyline();
        showToast('Route not available');
    }
}

function clearTouristSpotMarkers() {
    touristSpotMarkers.forEach(marker => {
        if (map && marker) map.removeLayer(marker);
    });
    touristSpotMarkers = [];
}

function getValidLatLng(place) {
    const lat = Number(place?.lat);
    const lng = Number(place?.lng ?? place?.lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
    return [lat, lng];
}

function addTouristSpotMarkers(spots, destination = '', autoFit = true) {
    if (!map) initMap();
    clearTouristSpotMarkers();

    const boundsPoints = [];
    (spots || []).forEach(spot => {
        const latLng = getValidLatLng(spot);
        if (!latLng) return;

        const icon = getPlaceIcon(spot.type || 'attraction');
        const marker = L.marker(latLng, { icon }).addTo(map)
            .bindPopup(`<strong>${escHtml(spot.name || 'Tourist spot')}</strong>${spot.description ? `<br>${escHtml(spot.description)}` : ''}`);
        touristSpotMarkers.push(marker);
        boundsPoints.push(latLng);
    });

    if (boundsPoints.length && autoFit) {
        if (!mapUserHasInteracted) {
            map.fitBounds(L.latLngBounds(boundsPoints), { padding: [42, 42], maxZoom: 15 });
        }
    } else if (destination) {
        showToast(`No mappable tourist spots found for ${destination}.`);
    }

    setTimeout(() => map.invalidateSize(), 150);
    return boundsPoints.length;
}

async function addTouristSpotMarkersForDestination(destination) {
    if (!destination) return 0;
    try {
        const res = await fetch(`/api/tourist-spots?destination=${encodeURIComponent(destination)}`);
        const spots = await res.json();
        if (!Array.isArray(spots) || !spots.length) {
            clearTouristSpotMarkers();
            showToast(`No tourist spots found for ${destination}.`);
            return 0;
        }
        return addTouristSpotMarkers(spots, destination, false);
    } catch {
        showToast('Could not load tourist spots for this destination.');
        return 0;
    }
}

function centerMapOnUser() {
    if (userCurrentLatLng && map) map.setView(userCurrentLatLng, 16);
    else showToast('Live location not available yet.');
}

async function fetchLiveSuggestions(lat, lng) {
    try {
        const res = await fetch(`/api/suggestions?lat=${lat}&lng=${lng}&purpose=Tour`);
        const places = await res.json();
        if (!places.length) return;
        document.getElementById('suggestions-box').classList.remove('hidden');
        document.getElementById('suggestions-list').innerHTML = places.map(p =>
            `<div class="sugg-chip" onclick="centerMapOnPlace(${p.lat}, ${p.lng})">📍 ${p.name}</div>`
        ).join('');

        clearTouristSpotMarkers();
        places.forEach(p => {
            const latLng = getValidLatLng(p);
            if(latLng) {
                const icon = getPlaceIcon(p.type);
                const marker = L.marker(latLng, { icon }).addTo(map)
                 .bindPopup(`<strong>${p.name}</strong><br>${p.description}`);
                touristSpotMarkers.push(marker);
            }
        });
    } catch {}
}

function getPlaceIcon(type) {
    const iconMap = {
        'attraction': { color: '#3b82f6', icon: 'camera' },
        'hospital': { color: '#ef4444', icon: 'plus-square' },
        'fuel': { color: '#f59e0b', icon: 'fuel' }
    };
    const config = iconMap[type] || iconMap['attraction'];
    return L.divIcon({
        className: 'custom-div-icon',
        html: `<div style="background-color:${config.color}; color:white; border-radius:50%; width:30px; height:30px; display:flex; align-items:center; justify-content:center; border:2px solid white; box-shadow:0 2px 5px rgba(0,0,0,0.2)">
                 <i data-lucide="${config.icon}" style="width:16px; height:16px"></i>
               </div>`,
        iconSize: [30, 30],
        iconAnchor: [15, 15]
    });
}

function centerMapOnPlace(lat, lng) {
    if (map) map.setView([lat, lng], 16);
}

async function endTrip() {
    if (watchId) { navigator.geolocation.clearWatch(watchId); watchId = null; }
    const gpsDistance = calcPathDistance(pathData);
    if (currentTripId) {
        try {
            await fetch(`/api/trips/${currentTripId}`, {
                method: 'PUT',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify({ path: pathData, distance: gpsDistance })
            });
        } catch {}
    }
    pathData = []; currentTripId = null; activeDestinationName = '';
    if (mapPolyline) mapPolyline.setLatLngs([]);
    clearRoutePolyline();
    // Always re-fetch trips so history and dashboard are up-to-date
    await loadTrips();
    showView('dashboard-view');
    showToast('Trip ended! ' + (gpsDistance > 0 ? gpsDistance + ' km tracked' : ''));
}

function calcPathDistance(path) {
    if (path.length < 2) return 0;
    let d = 0;
    for (let i = 1; i < path.length; i++) {
        const [la1,lo1] = path[i-1], [la2,lo2] = path[i];
        const R = 6371, dLa = (la2-la1)*Math.PI/180, dLo = (lo2-lo1)*Math.PI/180;
        const a = Math.sin(dLa/2)**2 + Math.cos(la1*Math.PI/180)*Math.cos(la2*Math.PI/180)*Math.sin(dLo/2)**2;
        d += R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
    return Math.round(d * 10) / 10;
}

// ─── Quick actions during trip ────────────────────────────────────────────────
function openQuickNote() {
    openNoteEditor(currentTripId);
    showView('notes-view');
}
function openQuickCamera() {
    openImageUpload(currentTripId);
}

// ─── Notes ────────────────────────────────────────────────────────────────────
async function openNotesView() {
    showView('notes-view');
    await loadNotes();
}

async function loadNotes() {
    const list = document.getElementById('notes-list');
    list.innerHTML = '<div class="empty-placeholder">Loading...</div>';
    try {
        const res = await fetch('/api/notes');
        const notes = await res.json();
        if (!notes.length) { list.innerHTML = '<div class="empty-placeholder">No notes yet. Tap + to add one!</div>'; return; }
        list.innerHTML = notes.map(n => `
            <div class="note-card" id="note-${n.id}">
                <div class="note-actions">
                    <button class="note-action-btn" onclick="openNoteEditor(null, ${n.id})"><i data-lucide="edit-2"></i></button>
                    <button class="note-action-btn red" onclick="deleteNote(${n.id})"><i data-lucide="trash-2"></i></button>
                </div>
                <div class="note-card-title">${escHtml(n.title)}</div>
                <div class="note-card-body">${escHtml(n.content)}</div>
                <div class="note-card-date">${new Date(n.created_at).toLocaleDateString('en-IN')}</div>
            </div>
        `).join('');
        lucide.createIcons();
    } catch { list.innerHTML = '<div class="empty-placeholder">Error loading notes.</div>'; }
}

async function openNoteEditor(tripId = null, noteId = null) {
    document.getElementById('editing-note-id').value = noteId || '';
    document.getElementById('note-title-input').value = '';
    document.getElementById('note-content-input').value = '';
    document.getElementById('note-editor-title').textContent = noteId ? 'Edit Note' : 'New Note';

    if (noteId) {
        try {
            const res = await fetch('/api/notes');
            const notes = await res.json();
            const n = notes.find(x => x.id === noteId);
            if (n) {
                document.getElementById('note-title-input').value = n.title || '';
                document.getElementById('note-content-input').value = n.content || '';
                tripId = n.trip_id || tripId;
            }
        } catch { showToast('Could not load note.'); return; }
    }

    populateTripSelect('note-trip-select', tripId);
    document.getElementById('note-editor-modal').classList.remove('hidden');
}

function closeNoteEditor() {
    document.getElementById('note-editor-modal').classList.add('hidden');
}

async function saveNote() {
    const noteId = document.getElementById('editing-note-id').value;
    const title = document.getElementById('note-title-input').value.trim() || 'Untitled';
    const content = document.getElementById('note-content-input').value.trim();
    const trip_id = document.getElementById('note-trip-select').value || null;
    if (!content) { showToast('Write something first!'); return; }
    try {
        if (noteId) {
            await fetch(`/api/notes/${noteId}`, { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ title, content }) });
        } else {
            await fetch('/api/notes', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ title, content, trip_id }) });
        }
        closeNoteEditor();
        showToast('Note saved! ✅');
        loadNotes();
    } catch { showToast('Error saving note.'); }
}

async function deleteNote(noteId) {
    if (!confirm('Delete this note?')) return;
    await fetch(`/api/notes/${noteId}`, { method: 'DELETE' });
    showToast('Note deleted');
    loadNotes();
}

// ─── Memories ─────────────────────────────────────────────────────────────────
async function openMemoriesView() {
    showView('memories-view');
    await loadMemories();
}

async function loadMemories() {
    const grid = document.getElementById('memories-grid');
    grid.innerHTML = '<div class="empty-placeholder">Loading memories...</div>';
    try {
        const res = await fetch('/api/memories');
        const mems = await res.json();
        if (!mems.length) { grid.innerHTML = '<div class="empty-placeholder">No memories yet. Upload your first photo!</div>'; return; }
        grid.innerHTML = mems.map(m => `
            <div class="memory-item" onclick="openLightbox(${m.id}, '${escHtml(m.caption)}')">
                <img src="/api/memories/${m.id}/image-thumb" onerror="this.src='/api/memories/${m.id}/image-inline'" alt="${escHtml(m.caption)}">
                ${m.caption ? `<div class="memory-caption">${escHtml(m.caption)}</div>` : ''}
                <button class="memory-delete" onclick="event.stopPropagation(); deleteMemory(${m.id})">
                    <i data-lucide="x"></i>
                </button>
            </div>
        `).join('');
        lucide.createIcons();
        // Load images
        mems.forEach(m => loadMemoryThumbnail(m.id));
    } catch { grid.innerHTML = '<div class="empty-placeholder">Error loading memories.</div>'; }
}

async function loadMemoryThumbnail(memId) {
    try {
        const res = await fetch(`/api/memories/${memId}/image`);
        const data = await res.json();
        const imgs = document.querySelectorAll(`[onclick*="openLightbox(${memId},"] img`);
        imgs.forEach(img => { img.src = data.image_data; });
    } catch {}
}

function openImageUpload(tripId = null) {
    document.getElementById('img-file-input').value = '';
    document.getElementById('img-caption-input').value = '';
    document.getElementById('upload-preview').innerHTML = `<i data-lucide="image-plus"></i><p>Tap to select photo</p>`;
    lucide.createIcons();
    selectedImageData = null;
    populateTripSelect('img-trip-select', tripId);
    document.getElementById('image-upload-modal').classList.remove('hidden');
}
function closeImageUpload() {
    document.getElementById('image-upload-modal').classList.add('hidden');
    selectedImageData = null;
}

function onImageSelected(event) {
    const file = event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = e => {
        selectedImageData = e.target.result;
        document.getElementById('upload-preview').innerHTML = `<img src="${selectedImageData}" style="max-height:220px;border-radius:12px;object-fit:cover">`;
    };
    reader.readAsDataURL(file);
}

async function saveMemory() {
    if (!selectedImageData) { showToast('Select a photo first!'); return; }
    const caption = document.getElementById('img-caption-input').value.trim();
    const trip_id = document.getElementById('img-trip-select').value || null;
    const filename = document.getElementById('img-file-input').files[0]?.name || 'photo.jpg';
    try {
        await fetch('/api/memories', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ image_data: selectedImageData, caption, trip_id, filename })
        });
        closeImageUpload();
        showToast('Memory saved! 📸');
        loadMemories();
    } catch { showToast('Error saving memory.'); }
}

async function deleteMemory(memId) {
    if (!confirm('Delete this memory?')) return;
    await fetch(`/api/memories/${memId}`, { method: 'DELETE' });
    showToast('Memory deleted');
    loadMemories();
}

async function openLightbox(memId, caption) {
    const lb = document.getElementById('lightbox');
    const img = document.getElementById('lightbox-img');
    const cap = document.getElementById('lightbox-caption');
    img.src = '';
    cap.textContent = caption || '';
    lb.classList.remove('hidden');
    try {
        const res = await fetch(`/api/memories/${memId}/image`);
        const data = await res.json();
        img.src = data.image_data;
    } catch { lb.classList.add('hidden'); }
}
function closeLightbox() {
    document.getElementById('lightbox').classList.add('hidden');
}

// ─── History View ─────────────────────────────────────────────────────────────
async function openHistoryView() {
    showView('history-view');
    const list = document.getElementById('history-list');
    list.innerHTML = '<div class="empty-placeholder">Loading history...</div>';
    // Always re-fetch fresh from server
    try {
        const res = await fetch('/api/trips');
        if (res.status === 401) { showView('login-view'); return; }
        allTrips = await res.json();
    } catch {
        list.innerHTML = '<div class="empty-placeholder">Error loading trips.</div>';
        return;
    }
    if (!allTrips.length) { list.innerHTML = '<div class="empty-placeholder">No trips yet. Start your first adventure!</div>'; return; }
    list.innerHTML = allTrips.map(t => `
        <div class="history-card">
            <div class="history-dest">${escHtml(t.destination)}</div>
            <div class="history-meta">
                <span class="history-tag purpose">${t.purpose}</span>
                ${t.distance > 0 ? `<span class="history-tag date">📍 ${t.distance} km</span>` : ''}
                <span class="history-tag date">${new Date(t.created_at).toLocaleDateString('en-IN')}</span>
            </div>
        </div>
    `).join('');
}

// ─── Planner ──────────────────────────────────────────────────────────────────
let plannerMap, plannerRoutingControl, plannerMarkers = [];
let routeProfile = 'driving';

function showPlanner() {
    showView('planner-view');
    initPlannerMap();
    detectPlannerLocation(false);
}

async function detectPlannerLocation(force = false) {
    const startInput = document.getElementById('p-start');
    if (!startInput || (!force && startInput.value.trim() !== '')) return;

    if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(async (pos) => {
            const { latitude, longitude } = pos.coords;
            try {
                const res = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${latitude}&lon=${longitude}`);
                const data = await res.json();
                if (data && data.display_name) {
                    startInput.value = data.display_name.split(',').slice(0, 3).join(',');
                    updatePlannerRoute();
                }
            } catch (err) {
                console.error("Reverse geocoding error:", err);
            }
        }, (err) => {
            console.warn("Geolocation error:", err);
        });
    }
}

function initPlannerMap() {
    if (plannerMap) { setTimeout(() => plannerMap.invalidateSize(), 150); return; }
    plannerMap = L.map('planner-map', { zoomControl: false }).setView([12.9716, 77.5946], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OSM' }).addTo(plannerMap);
    
    plannerRoutingControl = L.Routing.control({
        waypoints: [],
        router: L.Routing.osrmv1({ serviceUrl: 'https://router.project-osrm.org/route/v1' }),
        lineOptions: { styles: [{ color: '#3b82f6', weight: 6, opacity: 0.8 }] },
        createMarker: () => null,
        addWaypoints: false,
        routeWhileDragging: false
    }).addTo(plannerMap);

    plannerRoutingControl.on('routesfound', function(e) {
        const routes = e.routes;
        const summary = routes[0].summary;
        const distKM = summary.totalDistance / 1000;
        const timeMin = Math.round(summary.totalTime / 60);
        plannerLastRouteGeometry = routes[0].coordinates.map(c => [c.lat, c.lng]);
        
        document.getElementById('p-dist').textContent = distKM.toFixed(1) + ' km';
        document.getElementById('p-time').textContent = timeMin + ' min';
        
        discoverAlongRoute(routes[0].coordinates);
    });
    
    setTimeout(() => plannerMap.invalidateSize(), 150);
}

function addStopInput() {
    const container = document.getElementById('p-stops');
    const div = document.createElement('div');
    div.className = 'waypoint-item';
    div.innerHTML = `
        <i data-lucide="circle" style="width:14px;height:14px"></i>
        <input type="text" placeholder="Add a stop..." onchange="updatePlannerRoute()">
        <button class="icon-btn small red" onclick="this.parentElement.remove(); updatePlannerRoute()"><i data-lucide="minus-circle"></i></button>
    `;
    container.appendChild(div);
    lucide.createIcons();
}

async function updatePlannerRoute() {
    const start = document.getElementById('p-start').value.trim();
    const end = document.getElementById('p-end').value.trim();
    const stopInputs = Array.from(document.getElementById('p-stops').querySelectorAll('input'));
    const stops = stopInputs.map(i => i.value.trim()).filter(v => v.length > 2);

    if (start.length < 3 || end.length < 3) return;

    const allPoints = [start, ...stops, end].filter(v => v.length >= 3);
    const waypoints = [];

    for (let loc of allPoints) {
        try {
            const res = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(loc)}`);
            const data = await res.json();
            if (data && data.length > 0) {
                waypoints.push(L.latLng(data[0].lat, data[0].lon));
            }
            // Small delay to respect Nominatim rate limits
            await new Promise(r => setTimeout(r, 600));
        } catch {}
    }

    if (waypoints.length >= 2) {
        plannerLastStartLatLng = [waypoints[0].lat, waypoints[0].lng];
        plannerLastDestinationLatLng = [waypoints[waypoints.length - 1].lat, waypoints[waypoints.length - 1].lng];
        plannerRoutingControl.setWaypoints(waypoints);
        const bounds = L.latLngBounds(waypoints);
        plannerMap.fitBounds(bounds, { padding: [50, 50] });
    }
}

function toggleFilter(el) {
    el.classList.toggle('active');
    updatePlannerRoute(); // Re-fetch items
}

async function discoverAlongRoute(coords) {
    // Clear old markers
    plannerMarkers.forEach(m => plannerMap.removeLayer(m));
    plannerMarkers = [];
    const list = document.getElementById('itinerary-summary');
    list.innerHTML = '';

    // Sample points along the route to discover items
    const sampleIndices = [0, Math.floor(coords.length/2), coords.length-1];
    const activeCats = Array.from(document.querySelectorAll('.filter-chips .chip.active')).map(c => c.dataset.cat);
    
    if (activeCats.length === 0) return;

    const discovered = new Set();
    const places = [];

    for (let idx of sampleIndices) {
        const pt = coords[idx];
        for (let cat of activeCats) {
            try {
                const res = await fetch(`/api/suggestions?lat=${pt.lat}&lng=${pt.lng}&category=${cat}`);
                const data = await res.json();
                data.forEach(p => {
                    if (!discovered.has(p.name)) {
                        discovered.add(p.name);
                        places.push(p);
                        const marker = L.marker([p.lat, p.lng], {
                            icon: getPlaceIcon(p.type)
                        }).addTo(plannerMap).bindPopup(p.name);
                        plannerMarkers.push(marker);
                    }
                });
            } catch {}
        }
    }

    list.innerHTML = Array.from(discovered).slice(0, 6).map((name, i) => `
        <div class="itinerary-item">
            <div class="itinerary-num">${i+1}</div>
            <div class="itinerary-name">${name}</div>
        </div>
    `).join('');
}

function setRouteProfile(profile) {
    routeProfile = profile;
    document.querySelectorAll('.opt-btn').forEach(b => b.classList.toggle('active', b.onclick.toString().includes(profile)));
    updatePlannerRoute();
}

async function confirmPlannerTrip() {
    const dest = document.getElementById('p-end').value.trim();
    const start = document.getElementById('p-start').value.trim();
    const distText = document.getElementById('p-dist').textContent;
    const distance = parseFloat(distText) || 0;

    if (!dest || distance === 0) { showToast('Plan a valid route first!'); return; }
    await updatePlannerRoute();

    if (!plannerLastDestinationLatLng) {
        const destinationLatLng = await geocodeDestination(dest);
        if (!destinationLatLng) { showToast(`Destination location not found: ${dest}`); return; }
        plannerLastDestinationLatLng = destinationLatLng;
    }

    const activeStartLatLng = getRouteStartLatLng() || plannerLastStartLatLng;
    if (!activeStartLatLng) { showToast('Waiting for live GPS location...'); return; }

    if (!plannerLastRouteGeometry || plannerLastRouteGeometry.length < 2) {
        try {
            const route = await fetchRouteGeometry(activeStartLatLng, plannerLastDestinationLatLng);
            plannerLastRouteGeometry = route?.latLngs || null;
        } catch (err) {
            console.error('[TripMate] Planner route handoff failed:', err);
        }
    }

    saveActiveTripToStorage({
        activeDestinationName: dest,
        activeDestinationLat: plannerLastDestinationLatLng[0],
        activeDestinationLng: plannerLastDestinationLatLng[1],
        activeStartLat: activeStartLatLng[0],
        activeStartLng: activeStartLatLng[1],
        activeRouteGeometry: plannerLastRouteGeometry
    });

    try {
        const res = await fetch('/api/trips', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({
                destination: dest,
                start_loc: start,
                purpose: 'Tour',
                vehicle_type: 'car'
            })
        });
        const data = await res.json();
        currentTripId = data.trip_id;
        
        showToast('Trip Saved! Starting navigation...');
        showView('map-view');
        document.getElementById('trip-status-badge').textContent = 'TOUR ACTIVE';
        document.getElementById('trip-dest-badge').textContent = `TO: ${dest}`;
        activeDestinationName = dest;
        initMap();
        await restoreActiveTripFromStorage();
    } catch { showToast('Error saving trip.'); }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
function populateTripSelect(selectId, selectedTripId = null) {
    const sel = document.getElementById(selectId);
    sel.innerHTML = '<option value="">— No Trip —</option>' + allTrips.map(t =>
        `<option value="${t.id}" ${t.id == selectedTripId ? 'selected' : ''}>${escHtml(t.destination)}</option>`
    ).join('');
}

function escHtml(str) {
    return String(str || '').replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
}

function getPlaceIcon(type) {
    const colors = { attraction: '#3b82f6', food: '#f97316', nature: '#22c55e', essential: '#a855f7', hospital: '#ef4444' };
    const color = colors[type] || '#3b82f6';
    return L.divIcon({
        className: 'custom-marker',
        html: `<div style="background:${color};width:24px;height:24px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:white;box-shadow:0 2px 8px rgba(0,0,0,0.3);border:2px solid white">
                <div style="width:8px;height:8px;background:white;border-radius:50%"></div>
               </div>`,
        iconSize: [24, 24],
        iconAnchor: [12, 12]
    });
}

// ─── Expenses ─────────────────────────────────────────────────────────────────
let selectedExpCat = 'Food';

function openExpensesView() {
    showView('expenses-view');
    populateTripSelect('exp-trip-filter', null);
    loadExpenses();
}

function openExpenseModal(tripId = null) {
    document.getElementById('exp-amount').value = '';
    document.getElementById('exp-desc').value = '';
    selectedExpCat = 'Food';
    document.querySelectorAll('.exp-cat-chip').forEach(c => c.classList.toggle('active', c.dataset.cat === 'Food'));
    populateTripSelect('exp-trip-select', tripId || currentTripId);
    document.getElementById('expense-modal').classList.remove('hidden');
}

function closeExpenseModal() {
    document.getElementById('expense-modal').classList.add('hidden');
}

function selectExpCat(el) {
    document.querySelectorAll('.exp-cat-chip').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    selectedExpCat = el.dataset.cat;
}

async function saveExpense() {
    const amount = parseFloat(document.getElementById('exp-amount').value);
    if (!amount || amount <= 0) { showToast('Enter a valid amount!'); return; }
    const desc = document.getElementById('exp-desc').value.trim();
    const trip_id = document.getElementById('exp-trip-select').value || null;
    try {
        await fetch('/api/expenses', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({ category: selectedExpCat, description: desc, amount, trip_id })
        });
        closeExpenseModal();
        showToast('Expense added! 💸');
        loadExpenses();
    } catch { showToast('Error saving expense.'); }
}

async function loadExpenses() {
    const tripFilter = document.getElementById('exp-trip-filter')?.value || '';
    const list = document.getElementById('expenses-list');
    list.innerHTML = '<div class="empty-placeholder">Loading...</div>';
    try {
        const url = '/api/expenses' + (tripFilter ? `?trip_id=${tripFilter}` : '');
        const res = await fetch(url);
        const expenses = await res.json();

        // Update summary
        const total = expenses.reduce((s, e) => s + e.amount, 0);
        const food = expenses.filter(e => e.category === 'Food').reduce((s, e) => s + e.amount, 0);
        const other = total - food;
        const fmt = n => '₹' + Number(n).toLocaleString('en-IN');
        document.getElementById('exp-total-val').textContent = fmt(total);
        document.getElementById('exp-food-val').textContent = fmt(food);
        document.getElementById('exp-other-val').textContent = fmt(other);

        if (!expenses.length) {
            list.innerHTML = '<div class="empty-placeholder">No expenses yet. Tap + to add one!</div>';
            return;
        }
        const catEmoji = { Food:'🍽', Transport:'🚗', 'Entry Fee':'🎟', Shopping:'🛍', Hotel:'🏨', Other:'💰' };
        const catColor = { Food:'rgba(249,115,22,0.15)', Transport:'rgba(59,130,246,0.15)', 'Entry Fee':'rgba(168,85,247,0.15)', Shopping:'rgba(236,72,153,0.15)', Hotel:'rgba(20,184,166,0.15)', Other:'rgba(107,114,128,0.15)' };
        list.innerHTML = expenses.map(e => `
            <div class="expense-item">
                <div class="exp-icon" style="background:${catColor[e.category]||'rgba(107,114,128,0.15)'}">
                    ${catEmoji[e.category] || '💰'}
                </div>
                <div class="exp-info">
                    <div class="exp-cat-label">${escHtml(e.category)}</div>
                    <div class="exp-desc-text">${escHtml(e.description || e.category)}</div>
                    <div class="exp-date">${new Date(e.created_at).toLocaleDateString('en-IN')}</div>
                </div>
                <div class="exp-amount">−${fmt(e.amount)}</div>
                <button class="exp-delete-btn" onclick="deleteExpense(${e.id})"><i data-lucide="trash-2"></i></button>
            </div>
        `).join('');
        lucide.createIcons();
    } catch { list.innerHTML = '<div class="empty-placeholder">Error loading expenses.</div>'; }
}

async function deleteExpense(expId) {
    if (!confirm('Delete this expense?')) return;
    await fetch(`/api/expenses/${expId}`, { method: 'DELETE' });
    showToast('Expense deleted');
    loadExpenses();
}

// ─── Tourist Spots ────────────────────────────────────────────────────────────
function openSpotsView() {
    showView('spots-view');
    // Pre-fill with last trip destination if available
    if (allTrips.length > 0) {
        document.getElementById('spots-search-input').value = allTrips[0].destination || '';
    }
}

async function searchTouristSpots() {
    const dest = document.getElementById('spots-search-input').value.trim();
    if (!dest) { showToast('Enter a destination first!'); return; }

    const loading = document.getElementById('spots-loading');
    const list = document.getElementById('spots-list');
    loading.classList.remove('hidden');
    list.innerHTML = '';

    try {
        const res = await fetch(`/api/tourist-spots?destination=${encodeURIComponent(dest)}`);
        const spots = await res.json();
        loading.classList.add('hidden');

        if (!spots.length) {
            clearTouristSpotMarkers();
            list.innerHTML = '<div class="empty-placeholder">No tourist spots found. Try a nearby city name! 🗺</div>';
            return;
        }

        addTouristSpotMarkers(spots, dest);

        const typeEmoji = { attraction:'🏛', museum:'🏛', viewpoint:'👁', historic:'🏯', park:'🌿', temple:'🛕' };
        const typeLabel = { attraction:'Attraction', museum:'Museum', viewpoint:'Viewpoint', historic:'Heritage', park:'Park', temple:'Temple' };

        list.innerHTML = spots.map(s => `
            <div class="spot-card">
                <div class="spot-card-body">
                    <div class="spot-header">
                        <div>
                            <div class="spot-name">${typeEmoji[s.type] || '📍'} ${escHtml(s.name)}</div>
                        </div>
                        <span class="spot-type-badge spot-type-${s.type}">${typeLabel[s.type] || s.type}</span>
                    </div>
                    ${s.description ? `<div class="spot-desc">${escHtml(s.description)}</div>` : ''}
                    <div class="spot-meta">
                        ${s.opening_hours ? `<div class="spot-meta-item"><i data-lucide="clock"></i>${escHtml(s.opening_hours)}</div>` : ''}
                        ${s.fee ? `<div class="spot-meta-item"><i data-lucide="ticket"></i>Entry: ${escHtml(s.fee)}</div>` : ''}
                        ${s.wikipedia ? `<div class="spot-meta-item"><i data-lucide="book-open"></i>Wikipedia</div>` : ''}
                    </div>
                </div>
                ${s.lat && s.lng ? `<button class="spot-map-btn" onclick="viewSpotOnMap(${s.lat}, ${s.lng}, '${escHtml(s.name).replace(/'/g,'')}')">
                    <i data-lucide="map-pin" style="width:14px;height:14px"></i> View on Map
                </button>` : ''}
            </div>
        `).join('');
        lucide.createIcons();
    } catch {
        loading.classList.add('hidden');
        list.innerHTML = '<div class="empty-placeholder">Error fetching spots. Try again!</div>';
    }
}

function viewSpotOnMap(lat, lng, name) {
    const latLng = getValidLatLng({ lat, lng });
    if (!latLng) {
        showToast('Map coordinates are unavailable for this spot.');
        return;
    }

    showView('map-view');
    initMap();
    document.getElementById('trip-status-badge').textContent = 'TOURIST SPOT';
    document.getElementById('trip-dest-badge').textContent = `TO: ${name || 'Selected place'}`;
    if (!touristSpotMarkers.length) {
        addTouristSpotMarkers([{ name, lat, lng, type: 'attraction' }]);
    }
    setTimeout(() => {
        map.invalidateSize();
        map.setView(latLng, 16);
    }, 150);
}

// ─── AI Assistant ─────────────────────────────────────────────────────────────
let aiHistory = [];

function openAIAssistant() {
    document.getElementById('ai-panel').classList.remove('hidden');
    document.getElementById('ai-overlay').classList.remove('hidden');
    document.getElementById('ai-input').focus();
}
function closeAIAssistant() {
    document.getElementById('ai-panel').classList.add('hidden');
    document.getElementById('ai-overlay').classList.add('hidden');
}

async function sendAIMessage() {
    const input = document.getElementById('ai-input');
    const msg = input.value.trim();
    if (!msg) return;
    input.value = '';

    const msgsEl = document.getElementById('ai-messages');
    msgsEl.innerHTML += `<div class="ai-msg user">${escHtml(msg)}</div>`;

    const loadingId = 'ai-loading-' + Date.now();
    msgsEl.innerHTML += `<div class="ai-msg ai loading" id="${loadingId}"><div class="ai-typing"><div class="ai-dot"></div><div class="ai-dot"></div><div class="ai-dot"></div></div></div>`;
    msgsEl.scrollTop = msgsEl.scrollHeight;

    aiHistory.push({ role: 'user', content: msg });

    try {
        const response = await fetch('/api/ai/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                messages: aiHistory.slice(-10)
            })
        });

        const data = await response.json();
        const reply = data.content?.map(b => b.type === 'text' ? b.text : '').join('') || 'Sorry, I could not respond right now.';

        aiHistory.push({ role: 'assistant', content: reply });

        const loadingEl = document.getElementById(loadingId);
        if (loadingEl) loadingEl.outerHTML = `<div class="ai-msg ai">${reply.replace(/\n/g, '<br>')}</div>`;
        msgsEl.scrollTop = msgsEl.scrollHeight;
    } catch {
        const loadingEl = document.getElementById(loadingId);
        if (loadingEl) loadingEl.outerHTML = `<div class="ai-msg ai">Sorry, I had trouble connecting. Please try again! 🔄</div>`;
    }
}

// ─── Init ─────────────────────────────────────────────────────────────────────
window.onload = () => {
    checkAuth();
    document.getElementById('ai-input').addEventListener('keydown', e => { if (e.key === 'Enter') sendAIMessage(); });
};
