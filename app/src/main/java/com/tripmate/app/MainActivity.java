package com.tripmate.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.util.Log;
import android.view.Gravity;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
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
    private static final int COLOR_BG = Color.rgb(234, 237, 243);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(26, 29, 46);
    private static final int COLOR_SUBTLE = Color.rgb(124, 128, 151);
    private static final int COLOR_BLUE = Color.rgb(59, 130, 246);
    private static final int COLOR_BLUE_DARK = Color.rgb(29, 78, 216);
    private static final int COLOR_PURPLE = Color.rgb(168, 85, 247);
    private static final int COLOR_ORANGE = Color.rgb(249, 115, 22);
    private static final int COLOR_RED = Color.rgb(239, 68, 68);
    private static final int COLOR_GREEN = Color.rgb(34, 197, 94);

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
            checkVerifiedAndOpen(currentUser, null);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
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
        webView = null;
        webViewLoaded = false;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        View blueBlob = createBlob(COLOR_BLUE, 340, 340, 0.16f);
        FrameLayout.LayoutParams blueBlobParams = new FrameLayout.LayoutParams(dp(340), dp(340));
        blueBlobParams.leftMargin = dp(-90);
        blueBlobParams.topMargin = dp(-120);
        root.addView(blueBlob, blueBlobParams);

        View purpleBlob = createBlob(COLOR_PURPLE, 280, 280, 0.15f);
        FrameLayout.LayoutParams purpleBlobParams = new FrameLayout.LayoutParams(dp(280), dp(280), Gravity.BOTTOM | Gravity.RIGHT);
        purpleBlobParams.rightMargin = dp(-70);
        purpleBlobParams.bottomMargin = dp(-70);
        root.addView(purpleBlob, purpleBlobParams);

        View orangeBlob = createBlob(COLOR_ORANGE, 200, 200, 0.12f);
        FrameLayout.LayoutParams orangeBlobParams = new FrameLayout.LayoutParams(dp(200), dp(200), Gravity.BOTTOM | Gravity.LEFT);
        orangeBlobParams.leftMargin = dp(30);
        orangeBlobParams.bottomMargin = dp(120);
        root.addView(orangeBlob, orangeBlobParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        FrameLayout centered = new FrameLayout(this);
        centered.setPadding(dp(20), dp(32), dp(20), dp(32));
        scrollView.addView(centered, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(32), dp(28), dp(28));
        card.setBackground(createRoundRect(COLOR_CARD, dp(28)));
        card.setElevation(dp(14));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardParams.leftMargin = dp(4);
        cardParams.rightMargin = dp(4);
        centered.addView(card, cardParams);

        TextView logoIcon = new TextView(this);
        logoIcon.setText("TM");
        logoIcon.setTextColor(Color.WHITE);
        logoIcon.setTextSize(18);
        logoIcon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logoIcon.setGravity(Gravity.CENTER);
        logoIcon.setBackground(createGradientRect(COLOR_BLUE, COLOR_PURPLE, dp(20)));
        logoIcon.setElevation(dp(8));
        LinearLayout.LayoutParams logoIconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        logoIconParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoIconParams.bottomMargin = dp(12);
        card.addView(logoIcon, logoIconParams);

        TextView brand = new TextView(this);
        brand.setText("TripMate");
        brand.setTextColor(COLOR_TEXT);
        brand.setTextSize(28);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tagline = new TextView(this);
        tagline.setText("Your intelligent travel companion");
        tagline.setTextColor(COLOR_SUBTLE);
        tagline.setTextSize(13);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        taglineParams.topMargin = dp(4);
        taglineParams.bottomMargin = dp(28);
        card.addView(tagline, taglineParams);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(createRoundRect(COLOR_BG, dp(12)));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        tabsParams.bottomMargin = dp(24);
        card.addView(tabs, tabsParams);

        Button signInTab = createTabButton("Sign In", !createAccount);
        signInTab.setOnClickListener(view -> {
            Log.i(LOG_TAG, "Sign In tab clicked");
            showAuthScreen(false, "");
        });
        tabs.addView(signInTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        Button createTab = createTabButton("Create Account", createAccount);
        createTab.setOnClickListener(view -> showAuthScreen(true, ""));
        tabs.addView(createTab, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        EditText fullName = null;
        if (createAccount) {
            fullName = createInput("Full Name", "Enter your full name", InputType.TYPE_CLASS_TEXT);
            card.addView(createField("Full Name", fullName, "user"));
        }
        EditText email = createInput("Email", "you@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(createField("Email", email, "mail"));
        EditText password = createInput("Password", createAccount ? "create password" : "password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(createField("Password", password, "lock"));

        TextView status = new TextView(this);
        status.setText(message);
        status.setTextColor(message.toLowerCase().contains("could not")
                || message.toLowerCase().contains("error")
                || message.toLowerCase().contains("failed") ? COLOR_RED : COLOR_BLUE);
        status.setTextSize(13);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(dp(14), message.isEmpty() ? 0 : dp(10), dp(14), message.isEmpty() ? 0 : dp(10));
        status.setBackground(message.isEmpty() ? null : createRoundRect(adjustAlpha(status.getCurrentTextColor(), 0.12f), dp(12)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, message.isEmpty() ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.bottomMargin = message.isEmpty() ? 0 : dp(12);
        card.addView(status, statusParams);

        Button submit = new Button(this);
        submit.setAllCaps(false);
        submit.setText(createAccount ? "Create Account  ->" : "Sign In");
        submit.setTextColor(Color.WHITE);
        submit.setTextSize(16);
        submit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        submit.setBackground(createGradientRect(COLOR_BLUE, COLOR_BLUE_DARK, dp(12)));
        submit.setPadding(dp(16), 0, dp(16), 0);
        submit.setElevation(dp(8));
        EditText finalFullName = fullName;
        submit.setOnClickListener(view -> {
            String emailValue = email.getText().toString().trim().toLowerCase();
            String passwordValue = password.getText().toString();
            if (createAccount) {
                Log.i(LOG_TAG, "create account clicked email=" + emailValue
                        + " fullNameProvided=" + !finalFullName.getText().toString().trim().isEmpty()
                        + " passwordProvided=" + !passwordValue.isEmpty());
                createAccount(finalFullName.getText().toString().trim(), emailValue, passwordValue, status, submit);
            } else {
                Log.i(LOG_TAG, "Login button clicked email=" + emailValue
                        + " passwordProvided=" + !passwordValue.isEmpty());
                signIn(emailValue, passwordValue, status, submit);
            }
        });
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        submitParams.topMargin = dp(8);
        card.addView(submit, submitParams);

        root.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private LinearLayout createField(String labelText, EditText input, String iconText) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        groupParams.bottomMargin = dp(16);
        group.setLayoutParams(groupParams);

        TextView label = new TextView(this);
        label.setText(labelText.toUpperCase());
        label.setTextColor(COLOR_SUBTLE);
        label.setTextSize(11);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = dp(6);
        group.addView(label, labelParams);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(createRoundRect(COLOR_BG, dp(12)));
        wrap.setPadding(0, 0, 0, 0);
        TextView icon = new TextView(this);
        icon.setText(iconText);
        icon.setTextColor(COLOR_SUBTLE);
        icon.setTextSize(11);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(42), dp(50), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        wrap.addView(icon, iconParams);
        wrap.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(50)));
        group.addView(wrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        return group;
    }

    private EditText createInput(String hint, String placeholder, int inputType) {
        EditText input = new EditText(this);
        input.setHint(placeholder);
        input.setHintTextColor(Color.rgb(148, 152, 170));
        input.setTextColor(COLOR_TEXT);
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(42), 0, dp(14), 0);
        return input;
    }

    private Button createTabButton(String text, boolean active) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(active ? COLOR_TEXT : COLOR_SUBTLE);
        button.setBackground(active ? createRoundRect(COLOR_CARD, dp(9)) : createRoundRect(Color.TRANSPARENT, dp(9)));
        button.setElevation(active ? dp(3) : 0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private View createBlob(int color, int widthDp, int heightDp, float alpha) {
        View view = new View(this);
        view.setAlpha(alpha);
        view.setBackground(createOval(color));
        return view;
    }

    private GradientDrawable createOval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable createRoundRect(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private GradientDrawable createGradientRect(int startColor, int endColor, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { startColor, endColor });
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private int adjustAlpha(int color, float factor) {
        return Color.argb(Math.round(Color.alpha(color) * factor), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createAccount(String fullName, String email, String password, TextView status, Button submit) {
        if (fullName.isEmpty() || email.isEmpty() || password.isEmpty()) {
            showAuthStatus(status, "Full name, email, and password are required.", COLOR_RED);
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showAuthStatus(status, "Enter a valid email address, for example yourname@gmail.com.", COLOR_RED);
            return;
        }
        if (password.length() < 6) {
            showAuthStatus(status, "Password must be at least 6 characters.", COLOR_RED);
            return;
        }
        Log.i(LOG_TAG, "signup request started email=" + email
                + " fullNameLength=" + fullName.length()
                + " passwordLength=" + password.length());
        submit.setEnabled(false);
        submit.setText("Creating account...");
        showAuthStatus(status, "Creating account...", COLOR_BLUE);
        firebaseAuth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || firebaseAuth.getCurrentUser() == null) {
                submit.setEnabled(true);
                submit.setText("Create Account  ->");
                Exception exception = task.getException();
                Log.e(LOG_TAG, "signup error email=" + email
                        + " message=" + getErrorMessage(exception, "Could not create account."), exception);
                showAuthStatus(status, getErrorMessage(exception, "Could not create account."), COLOR_RED);
                return;
            }
            FirebaseUser user = firebaseAuth.getCurrentUser();
            Log.i(LOG_TAG, "signup success uid=" + user.getUid() + " email=" + user.getEmail());
            UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                    .setDisplayName(fullName)
                    .build();
            user.updateProfile(profile).addOnCompleteListener(profileTask -> {
                if (!profileTask.isSuccessful()) {
                    Exception exception = profileTask.getException();
                    Log.e(LOG_TAG, "signup error while saving profile email=" + email
                            + " message=" + getErrorMessage(exception, "Could not save your full name."), exception);
                    firebaseAuth.signOut();
                    showAuthScreen(true, "Could not save your full name. Please try again.");
                    return;
                }
                showAuthStatus(status, "Account created. Sending verification email/OTP...", COLOR_BLUE);
                user.sendEmailVerification().addOnCompleteListener(emailTask -> {
                        firebaseAuth.signOut();
                        if (emailTask.isSuccessful()) {
                            Log.i(LOG_TAG, "signup success verification email sent uid="
                                    + user.getUid() + " email=" + user.getEmail());
                            showAuthScreen(false, "Verification email/OTP sent. Open it, then sign in.");
                        } else {
                            Exception exception = emailTask.getException();
                            Log.e(LOG_TAG, "signup error while sending verification email="
                                    + email + " message=" + getErrorMessage(exception,
                                    "Verification email could not be sent."), exception);
                            showAuthScreen(false, "Account created, but verification email/OTP could not be sent: "
                                    + getErrorMessage(exception, "Try signing in again."));
                        }
                });
            });
        });
    }

    private void showAuthStatus(TextView status, String message, int color) {
        status.setText(message);
        status.setTextColor(color);
        status.setBackground(createRoundRect(adjustAlpha(color, 0.12f), dp(12)));
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) status.getLayoutParams();
        if (params != null) {
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            params.bottomMargin = dp(12);
            status.setLayoutParams(params);
        }
    }

    private void signIn(String email, String password, TextView status, Button submit) {
        if (email.isEmpty() || password.isEmpty()) {
            showAuthStatus(status, "Email and password are required.", COLOR_RED);
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showAuthStatus(status, "Enter a valid email address.", COLOR_RED);
            return;
        }
        Log.i(LOG_TAG, "Login request started email=" + email
                + " passwordProvided=" + !password.isEmpty()
                + " passwordLength=" + password.length());
        submit.setEnabled(false);
        submit.setText("Signing in...");
        showAuthStatus(status, "Signing in...", COLOR_BLUE);
        firebaseAuth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || firebaseAuth.getCurrentUser() == null) {
                submit.setEnabled(true);
                submit.setText("Sign In");
                Exception exception = task.getException();
                logAuthFailure("Firebase signInWithEmailAndPassword failed email=" + email, exception);
                Log.e(LOG_TAG, "Login error email=" + email
                        + " message=" + getErrorMessage(exception, "Could not sign in."), exception);
                showAuthStatus(status, getErrorMessage(exception,
                        "Email or password is incorrect. Please try again."), COLOR_RED);
                return;
            }
            FirebaseUser currentUser = firebaseAuth.getCurrentUser();
            Log.i(LOG_TAG, "Firebase signInWithEmailAndPassword succeeded uid="
                    + currentUser.getUid() + " email=" + currentUser.getEmail());
            Log.i(LOG_TAG, "Firebase login success uid=" + currentUser.getUid()
                    + " email=" + currentUser.getEmail());
            Log.i(LOG_TAG, "Login success uid=" + currentUser.getUid()
                    + " email=" + currentUser.getEmail());
            showAuthStatus(status, "Sign in successful. Opening TripMate...", COLOR_BLUE);
            checkVerifiedAndOpen(currentUser, status);
        });
    }

    private String getErrorMessage(Exception exception, String fallback) {
        return exception == null || exception.getMessage() == null ? fallback : exception.getMessage();
    }

    private void logAuthFailure(String context, Exception exception) {
        if (exception instanceof FirebaseAuthInvalidCredentialsException) {
            Log.e(LOG_TAG, context + " type=FirebaseAuthInvalidCredentialsException message="
                    + exception.getMessage(), exception);
        } else if (exception instanceof FirebaseAuthInvalidUserException) {
            Log.e(LOG_TAG, context + " type=FirebaseAuthInvalidUserException message="
                    + exception.getMessage(), exception);
        } else if (exception instanceof FirebaseAuthException) {
            FirebaseAuthException authException = (FirebaseAuthException) exception;
            Log.e(LOG_TAG, context + " type=FirebaseAuthException errorCode="
                    + authException.getErrorCode() + " message=" + authException.getMessage(), authException);
        } else {
            Log.e(LOG_TAG, context + " type="
                    + (exception == null ? "unknown" : exception.getClass().getSimpleName())
                    + " message=" + getErrorMessage(exception, "No exception message"), exception);
        }
    }

    private void checkVerifiedAndOpen(FirebaseUser user, TextView status) {
        Log.i(LOG_TAG, "Reloading Firebase user uid=" + user.getUid() + " email=" + user.getEmail());
        user.reload().addOnCompleteListener(task -> {
            FirebaseUser refreshedUser = firebaseAuth.getCurrentUser();
            if (!task.isSuccessful() || refreshedUser == null) {
                Exception exception = task.getException();
                logAuthFailure("Firebase currentUser.reload failed", exception);
                firebaseAuth.signOut();
                showAuthScreen(false, getErrorMessage(exception, "Could not refresh your account. Please sign in again."));
                return;
            }
            String verificationStatus = "Firebase reload succeeded. Email verified: "
                    + refreshedUser.isEmailVerified() + ".";
            Log.i(LOG_TAG, verificationStatus + " uid=" + refreshedUser.getUid()
                    + " email=" + refreshedUser.getEmail());
            Log.i(LOG_TAG, "Firebase emailVerified value=" + refreshedUser.isEmailVerified()
                    + " uid=" + refreshedUser.getUid());
            if (status != null) {
                status.setText(verificationStatus);
            }
            if (!refreshedUser.isEmailVerified()) {
                refreshedUser.sendEmailVerification();
                firebaseAuth.signOut();
                showAuthScreen(false, verificationStatus
                        + " Please verify your email first. A new verification link has been sent.");
                return;
            }
            exchangeFirebaseToken(refreshedUser);
        });
    }

    private void exchangeFirebaseToken(FirebaseUser user) {
        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Exception exception = task.getException();
                logAuthFailure("Firebase getIdToken failed", exception);
                firebaseAuth.signOut();
                showAuthScreen(false, getErrorMessage(exception,
                        "Could not start your TripMate session. Please sign in again."));
                return;
            }
            String idToken = task.getResult().getToken();
            Log.i(LOG_TAG, "Firebase token generated uid=" + user.getUid()
                    + " tokenPresent=" + (idToken != null && !idToken.isEmpty())
                    + " tokenLength=" + (idToken == null ? 0 : idToken.length()));
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
            Log.i(LOG_TAG, "Backend session response status=" + statusCode + " body=" + responseBody);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException(extractBackendError(responseBody, statusCode));
            }
            storeCookies(connection.getHeaderFields());
            runOnUiThread(this::showTripMateWebView);
        } catch (Exception exception) {
            String backendError = getErrorMessage(exception, "Backend session endpoint failed.");
            Log.e(LOG_TAG, "Backend session exchange failed realError=" + backendError
                    + ". Verified Firebase user will open WebView directly.", exception);
            runOnUiThread(this::showTripMateWebView);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractBackendError(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "Backend session failed with HTTP " + statusCode + " and an empty response.";
        }
        try {
            JSONObject error = new JSONObject(responseBody);
            String message = error.optString("error",
                    error.optString("message", responseBody));
            return "Backend session failed with HTTP " + statusCode + ": " + message;
        } catch (Exception ignored) {
            return "Backend session failed with HTTP " + statusCode + ": " + responseBody;
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
        Log.i(LOG_TAG, "WebView opened url=" + TRIPMATE_URL);
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
