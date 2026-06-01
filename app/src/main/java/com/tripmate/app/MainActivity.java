package com.tripmate.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "TripMate";
    private static final String TRIPMATE_URL = "https://trip-y62q.onrender.com";
    private static final int REQUEST_APP_PERMISSIONS = 10;
    private static final int REQUEST_GEOLOCATION_PERMISSION = 11;
    private static final int REQUEST_FILE_CHOOSER = 12;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private FirebaseAuth firebaseAuth;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private Bundle pendingWebViewState;
    private boolean webViewLoaded;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastNativeLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        pendingWebViewState = savedInstanceState;

        if (FirebaseApp.initializeApp(this) == null) {
            showSetupError();
            return;
        }
        firebaseAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = firebaseAuth.getCurrentUser();
        if (currentUser == null) {
            showAuthScreen(false, "");
        } else {
            checkVerifiedAndOpen(currentUser);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showSetupError() {
        TextView message = new TextView(this);
        message.setPadding(48, 80, 48, 48);
        message.setTextSize(18);
        message.setText("Firebase is not configured. Add app/google-services.json and rebuild TripMate.");
        setContentView(message);
    }

    private void showAuthScreen(boolean createAccount, String message) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 80, 48, 48);
        scrollView.addView(form);

        TextView title = new TextView(this);
        title.setText(createAccount ? "Create your TripMate account" : "Sign in to TripMate");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(15, 23, 42));
        form.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(createAccount
                ? "Firebase will email you a verification link before your first sign in."
                : "Use your verified email and password to continue.");
        subtitle.setPadding(0, 16, 0, 24);
        form.addView(subtitle);

        EditText fullName = createInput("Full name", InputType.TYPE_CLASS_TEXT);
        fullName.setVisibility(createAccount ? View.VISIBLE : View.GONE);
        form.addView(fullName);
        EditText email = createInput("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(email);
        EditText password = createInput("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(password);

        TextView status = new TextView(this);
        status.setText(message);
        status.setTextColor(Color.rgb(30, 64, 175));
        status.setPadding(0, 16, 0, 16);
        form.addView(status);

        Button submit = new Button(this);
        submit.setText(createAccount ? "Create account" : "Sign in");
        submit.setOnClickListener(view -> {
            String emailValue = email.getText().toString().trim().toLowerCase();
            String passwordValue = password.getText().toString();
            if (createAccount) {
                createAccount(fullName.getText().toString().trim(), emailValue, passwordValue, status, submit);
            } else {
                signIn(emailValue, passwordValue, status, submit);
            }
        });
        form.addView(submit);

        Button toggle = new Button(this);
        toggle.setText(createAccount ? "Already have an account? Sign in" : "New here? Create account");
        toggle.setOnClickListener(view -> showAuthScreen(!createAccount, ""));
        form.addView(toggle);
        setContentView(scrollView);
    }

    private EditText createInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setPadding(0, 16, 0, 16);
        return input;
    }

    private void createAccount(String fullName, String email, String password, TextView status, Button submit) {
        if (fullName.isEmpty() || email.isEmpty() || password.isEmpty()) {
            status.setText("Full name, email, and password are required.");
            return;
        }
        submit.setEnabled(false);
        status.setText("Creating account...");
        firebaseAuth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || firebaseAuth.getCurrentUser() == null) {
                submit.setEnabled(true);
                status.setText(getErrorMessage(task.getException(), "Could not create account."));
                return;
            }
            FirebaseUser user = firebaseAuth.getCurrentUser();
            UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                    .setDisplayName(fullName)
                    .build();
            user.updateProfile(profile).addOnCompleteListener(profileTask -> {
                if (!profileTask.isSuccessful()) {
                    firebaseAuth.signOut();
                    showAuthScreen(true, "Could not save your full name. Please try again.");
                    return;
                }
                user.sendEmailVerification().addOnCompleteListener(emailTask -> {
                        firebaseAuth.signOut();
                        if (emailTask.isSuccessful()) {
                            showAuthScreen(false, "Verification email sent. Open the link, then sign in.");
                        } else {
                            showAuthScreen(false, "Account created, but the verification email could not be sent. Try signing in again.");
                        }
                });
            });
        });
    }

    private void signIn(String email, String password, TextView status, Button submit) {
        if (email.isEmpty() || password.isEmpty()) {
            status.setText("Email and password are required.");
            return;
        }
        submit.setEnabled(false);
        status.setText("Signing in...");
        firebaseAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || firebaseAuth.getCurrentUser() == null) {
                submit.setEnabled(true);
                status.setText(getErrorMessage(task.getException(), "Could not sign in."));
                return;
            }
            checkVerifiedAndOpen(firebaseAuth.getCurrentUser());
        });
    }

    private String getErrorMessage(Exception exception, String fallback) {
        return exception == null || exception.getMessage() == null ? fallback : exception.getMessage();
    }

    private void checkVerifiedAndOpen(FirebaseUser user) {
        user.reload().addOnCompleteListener(task -> {
            FirebaseUser refreshedUser = firebaseAuth.getCurrentUser();
            if (!task.isSuccessful() || refreshedUser == null) {
                firebaseAuth.signOut();
                showAuthScreen(false, "Could not refresh your account. Please sign in again.");
                return;
            }
            if (!refreshedUser.isEmailVerified()) {
                refreshedUser.sendEmailVerification();
                firebaseAuth.signOut();
                showAuthScreen(false, "Please verify your email first. A new verification link has been sent.");
                return;
            }
            exchangeFirebaseToken(refreshedUser);
        });
    }

    private void exchangeFirebaseToken(FirebaseUser user) {
        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                firebaseAuth.signOut();
                showAuthScreen(false, "Could not start your TripMate session. Please sign in again.");
                return;
            }
            String idToken = task.getResult().getToken();
            networkExecutor.execute(() -> createBackendSession(idToken));
        });
    }

    private void createBackendSession(String idToken) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(TRIPMATE_URL + "/api/auth/firebase-session").openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("id_token", idToken);
            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream())) {
                writer.write(body.toString());
            }
            int statusCode = connection.getResponseCode();
            String responseBody = readResponse(statusCode < 400 ? connection.getInputStream() : connection.getErrorStream());
            if (statusCode < 200 || statusCode >= 300) {
                JSONObject error = new JSONObject(responseBody);
                throw new IllegalStateException(error.optString("error", "Could not start TripMate session."));
            }
            storeCookies(connection.getHeaderFields());
            runOnUiThread(this::showTripMateWebView);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Backend session exchange failed", exception);
            firebaseAuth.signOut();
            runOnUiThread(() -> showAuthScreen(false, getErrorMessage(exception, "Could not start TripMate session.")));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readResponse(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private void storeCookies(Map<String, List<String>> headers) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && "Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                for (String cookie : entry.getValue()) {
                    cookieManager.setCookie(TRIPMATE_URL, cookie);
                }
            }
        }
        cookieManager.flush();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void showTripMateWebView() {
        if (webView != null) {
            setContentView(webView);
            return;
        }
        webView = new WebView(this);
        webView.addJavascriptInterface(new AndroidAuthBridge(), "TripMateAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                sendLocationToWebView(lastNativeLocation);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(LOG_TAG, message.messageLevel() + ": " + message.message());
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    geolocationOrigin = origin;
                    geolocationCallback = callback;
                    requestPermissions(new String[] {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQUEST_GEOLOCATION_PERMISSION);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER);
                } catch (Exception exception) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        setContentView(webView);
        setupNativeLocationBridge();
        if (!requestInitialPermissions()) {
            loadTripMate();
            startNativeLocationUpdates();
        }
    }

    private class AndroidAuthBridge {
        @JavascriptInterface
        public void showLogin() {
            runOnUiThread(() -> {
                firebaseAuth.signOut();
                CookieManager.getInstance().removeAllCookies(null);
                webView = null;
                webViewLoaded = false;
                showAuthScreen(false, "");
            });
        }
    }

    private void setupNativeLocationBridge() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                sendLocationToWebView(location);
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };
    }

    private boolean requestInitialPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        String mediaPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (checkSelfPermission(mediaPermission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(mediaPermission);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (permissions.isEmpty()) {
            return false;
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_APP_PERMISSIONS);
        return true;
    }

    private void loadTripMate() {
        if (webViewLoaded) {
            return;
        }
        webViewLoaded = true;
        if (pendingWebViewState == null || webView.restoreState(pendingWebViewState) == null) {
            webView.loadUrl(TRIPMATE_URL);
        }
    }

    @SuppressLint("MissingPermission")
    private void startNativeLocationUpdates() {
        if (locationManager == null || locationListener == null || !hasLocationPermission()) {
            return;
        }
        Location lastKnown = null;
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, locationListener);
            lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, locationListener);
            Location networkLastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (lastKnown == null || (networkLastKnown != null && networkLastKnown.getTime() > lastKnown.getTime())) {
                lastKnown = networkLastKnown;
            }
        }
        if (lastKnown != null) {
            sendLocationToWebView(lastKnown);
        }
    }

    private void sendLocationToWebView(Location location) {
        if (location == null || webView == null) {
            return;
        }
        lastNativeLocation = location;
        runOnUiThread(() -> webView.evaluateJavascript(
                "if (window.updateNativeLocation) { window.updateNativeLocation("
                        + location.getLatitude() + "," + location.getLongitude() + "); }", null));
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if ((requestCode == REQUEST_GEOLOCATION_PERMISSION || requestCode == REQUEST_APP_PERMISSIONS)
                && geolocationCallback != null) {
            geolocationCallback.invoke(geolocationOrigin, hasLocationPermission(), false);
            geolocationCallback = null;
            geolocationOrigin = null;
        }
        if (requestCode == REQUEST_APP_PERMISSIONS) {
            loadTripMate();
            startNativeLocationUpdates();
        } else if (requestCode == REQUEST_GEOLOCATION_PERMISSION) {
            startNativeLocationUpdates();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER || filePathCallback == null) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                results = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < results.length; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[] { data.getData() };
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startNativeLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
