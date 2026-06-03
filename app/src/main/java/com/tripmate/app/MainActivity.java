package com.tripmate.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final String LOG_TAG = "TripMate";
    private static final String TRIPMATE_URL = "https://trip-y62q.onrender.com";
    private static final int REQUEST_APP_PERMISSIONS = 10;
    private static final int REQUEST_GEOLOCATION_PERMISSION = 11;
    private static final int REQUEST_FILE_CHOOSER = 12;
    private static final int REQUEST_CAMERA_MEMORIES = 13;
    private static final String MEMORIES_PREFS = "tripmate_camera_memories";
    private static final String MEMORIES_KEY = "photos";
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
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
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
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private boolean nativeMemoryScreenOpen;
    private String lastKnownDestinationName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Log.i(LOG_TAG, "App launch started");
            configureWindow();
            showLoadingScreen("Starting TripMate...");
            pendingWebViewState = savedInstanceState;

            if (FirebaseApp.initializeApp(this) == null) {
                Log.e(LOG_TAG, "Firebase config missing or invalid");
                showSetupError();
                return;
            }
            Log.i(LOG_TAG, "Firebase config loaded");
            firebaseAuth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = firebaseAuth.getCurrentUser();
            if (currentUser == null) {
                Log.i(LOG_TAG, "No existing Firebase user. Showing auth screen.");
                showAuthScreen(false, "");
            } else {
                Log.i(LOG_TAG, "Existing Firebase user found uid=" + currentUser.getUid()
                        + " email=" + currentUser.getEmail());
                checkVerifiedAndOpen(currentUser, null);
            }
        } catch (Exception exception) {
            Log.e(LOG_TAG, "App launch failed", exception);
            showAuthScreen(false, getErrorMessage(exception, "TripMate could not start. Please try again."));
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

    private void showLoadingScreen(String messageText) {
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(32), dp(32), dp(32), dp(32));
        message.setText(messageText);
        message.setTextColor(COLOR_TEXT);
        message.setTextSize(16);
        message.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        message.setBackgroundColor(COLOR_BG);
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
        if (status == null) {
            showLoadingScreen("Checking your TripMate session...");
        }
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
                showAuthStatus(status, verificationStatus, COLOR_BLUE);
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
        Log.i(LOG_TAG, "Firebase token exchange started uid=" + user.getUid());
        showLoadingScreen("Opening TripMate...");
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
        try {
            webView = new WebView(this);
            webView.addJavascriptInterface(new AndroidAuthBridge(), "TripMateAndroid");
        } catch (Exception exception) {
            Log.e(LOG_TAG, "WebView creation failed", exception);
            showAuthScreen(false, getErrorMessage(exception, "Could not open TripMate."));
            return;
        }
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
                Log.i(LOG_TAG, "WebView page finished url=" + url);
                sendLocationToWebView(lastNativeLocation);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    Log.e(LOG_TAG, "WebView main-frame error code=" + error.getErrorCode()
                            + " description=" + error.getDescription());
                }
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
                Log.i(LOG_TAG, "WebView geolocation prompt origin=" + origin);
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    geolocationOrigin = origin;
                    geolocationCallback = callback;
                    try {
                        requestPermissions(new String[] {
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        }, REQUEST_GEOLOCATION_PERMISSION);
                    } catch (Exception exception) {
                        Log.e(LOG_TAG, "Geolocation permission request failed", exception);
                        callback.invoke(origin, false, false);
                    }
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

    private void openCameraMemories() {
        Log.i(LOG_TAG, "Camera memories requested");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission is needed to capture memories.", Toast.LENGTH_LONG).show();
            try {
                requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA_MEMORIES);
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Camera permission request failed", exception);
                Toast.makeText(this, "Camera permission request failed.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        showCameraScreen();
    }

    private void showCameraScreen() {
        nativeMemoryScreenOpen = true;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(24), dp(18), dp(18));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = createNativeHeader("Travel Memories", "Capture this moment");
        content.addView(header);

        PreviewView previewView = new PreviewView(this);
        previewView.setBackgroundColor(Color.BLACK);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        previewParams.topMargin = dp(16);
        previewParams.bottomMargin = dp(14);
        previewView.setBackground(createRoundRect(Color.BLACK, dp(18)));
        content.addView(previewView, previewParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button galleryButton = createNativeButton("My Trip Memories", COLOR_TEXT);
        galleryButton.setOnClickListener(view -> showCameraGalleryScreen());
        Button captureButton = createNativeButton("Capture Photo", COLOR_BLUE);
        captureButton.setOnClickListener(view -> captureTravelMemory(captureButton));
        actions.addView(galleryButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(0, dp(54), 1);
        captureParams.leftMargin = dp(10);
        actions.addView(captureButton, captureParams);
        content.addView(actions);

        setContentView(root);
        startCameraPreview(previewView);
    }

    private LinearLayout createNativeHeader(String titleText, String subtitleText) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = createTabButton("<", false);
        back.setTextColor(COLOR_TEXT);
        back.setTextSize(20);
        back.setOnClickListener(view -> returnToTripMateWebView());
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText(subtitleText);
        subtitle.setTextColor(COLOR_SUBTLE);
        subtitle.setTextSize(13);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return header;
    }

    private Button createNativeButton(String text, int color) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(color == COLOR_BLUE ? Color.WHITE : COLOR_TEXT);
        button.setBackground(color == COLOR_BLUE
                ? createGradientRect(COLOR_BLUE, COLOR_BLUE_DARK, dp(14))
                : createRoundRect(COLOR_CARD, dp(14)));
        button.setElevation(dp(5));
        return button;
    }

    private void startCameraPreview(PreviewView previewView) {
        Log.i(LOG_TAG, "Starting CameraX preview");
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                Log.i(LOG_TAG, "CameraX preview ready");
            } catch (Exception exception) {
                Log.e(LOG_TAG, "CameraX preview failed", exception);
                Toast.makeText(this, "Could not open camera.", Toast.LENGTH_LONG).show();
                showCameraGalleryScreen();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureTravelMemory(Button captureButton) {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera is still starting. Try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        captureButton.setEnabled(false);
        captureButton.setText("Saving...");
        File outputFile = createMemoryImageFile();
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        Log.i(LOG_TAG, "Capturing travel memory file=" + outputFile.getAbsolutePath());
        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                try {
                    compressImageFile(outputFile);
                    saveMemoryMetadata(outputFile);
                    runOnUiThread(() -> {
                        captureButton.setEnabled(true);
                        captureButton.setText("Capture Photo");
                        Toast.makeText(MainActivity.this, "Travel memory saved.", Toast.LENGTH_LONG).show();
                        showCameraGalleryScreen();
                    });
                } catch (Exception exception) {
                    Log.e(LOG_TAG, "Saving captured memory failed", exception);
                    runOnUiThread(() -> {
                        captureButton.setEnabled(true);
                        captureButton.setText("Capture Photo");
                        Toast.makeText(MainActivity.this, "Could not save photo.", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                Log.e(LOG_TAG, "Camera capture failed", exception);
                runOnUiThread(() -> {
                    captureButton.setEnabled(true);
                    captureButton.setText("Capture Photo");
                    Toast.makeText(MainActivity.this, "Photo capture failed.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private File createMemoryImageFile() {
        File dir = new File(getFilesDir(), "travel_memories");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(LOG_TAG, "Could not create memories directory");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, "tripmate_memory_" + timestamp + ".jpg");
    }

    private void compressImageFile(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while ((bounds.outWidth / sample) > 1600 || (bounds.outHeight / sample) > 1600) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } finally {
            bitmap.recycle();
        }
        Log.i(LOG_TAG, "Compressed memory image bytes=" + file.length());
    }

    private void saveMemoryMetadata(File file) throws Exception {
        JSONArray array = readMemoryMetadataArray();
        JSONObject item = new JSONObject();
        long now = System.currentTimeMillis();
        item.put("path", file.getAbsolutePath());
        item.put("created_at", now);
        Location location = lastNativeLocation;
        if (location != null) {
            item.put("lat", location.getLatitude());
            item.put("lng", location.getLongitude());
        }
        item.put("place", lastKnownDestinationName == null || lastKnownDestinationName.trim().isEmpty()
                ? "Current trip" : lastKnownDestinationName.trim());
        array.put(item);
        getMemoryPrefs().edit().putString(MEMORIES_KEY, array.toString()).apply();
        Log.i(LOG_TAG, "Saved memory metadata count=" + array.length());
    }

    private SharedPreferences getMemoryPrefs() {
        return getSharedPreferences(MEMORIES_PREFS, MODE_PRIVATE);
    }

    private JSONArray readMemoryMetadataArray() {
        try {
            return new JSONArray(getMemoryPrefs().getString(MEMORIES_KEY, "[]"));
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Memory metadata parse failed", exception);
            return new JSONArray();
        }
    }

    private List<JSONObject> readMemoryItems() {
        JSONArray array = readMemoryMetadataArray();
        List<JSONObject> items = new ArrayList<>();
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && new File(item.optString("path")).exists()) {
                items.add(item);
            }
        }
        return items;
    }

    private void showCameraGalleryScreen() {
        nativeMemoryScreenOpen = true;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(24), dp(18), 0);
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(createNativeHeader("My Trip Memories", "Photos captured on your journey"));

        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setPadding(0, dp(16), 0, dp(88));
        recyclerView.setClipToPadding(false);
        List<JSONObject> items = readMemoryItems();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No memories yet. Tap Camera to capture your first travel photo.");
            empty.setTextColor(COLOR_SUBTLE);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            recyclerView.setAdapter(new MemoriesAdapter(items));
            content.addView(recyclerView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(18), dp(10), dp(18), dp(18));
        bottom.setBackgroundColor(COLOR_BG);
        Button camera = createNativeButton("Camera", COLOR_BLUE);
        camera.setOnClickListener(view -> openCameraMemories());
        bottom.addView(camera, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(88), Gravity.BOTTOM);
        root.addView(bottom, bottomParams);
        setContentView(root);
    }

    private void showFullScreenMemory(JSONObject item) {
        nativeMemoryScreenOpen = true;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(decodeBitmap(item.optString("path"), 2200));
        root.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(18), dp(14), dp(18), dp(24));
        details.setBackgroundColor(Color.argb(200, 0, 0, 0));
        TextView title = new TextView(this);
        title.setText(item.optString("place", "Current trip"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView meta = new TextView(this);
        meta.setText(formatMemoryDetails(item));
        meta.setTextColor(Color.rgb(226, 232, 240));
        meta.setTextSize(13);
        details.addView(title);
        details.addView(meta);
        root.addView(details, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        Button close = createTabButton("<", false);
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setBackground(createRoundRect(Color.argb(120, 255, 255, 255), dp(12)));
        close.setOnClickListener(view -> showCameraGalleryScreen());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT);
        closeParams.leftMargin = dp(18);
        closeParams.topMargin = dp(24);
        root.addView(close, closeParams);
        setContentView(root);
    }

    private String formatMemoryDetails(JSONObject item) {
        long createdAt = item.optLong("created_at", System.currentTimeMillis());
        String date = new SimpleDateFormat("dd MMM yyyy", Locale.US).format(new Date(createdAt));
        String time = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date(createdAt));
        if (item.has("lat") && item.has("lng")) {
            return date + " • " + time + "\nLocation: "
                    + String.format(Locale.US, "%.5f, %.5f", item.optDouble("lat"), item.optDouble("lng"));
        }
        return date + " • " + time + "\nLocation: Not available";
    }

    private Bitmap decodeBitmap(String path, int maxSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while ((bounds.outWidth / sample) > maxSize || (bounds.outHeight / sample) > maxSize) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    private void returnToTripMateWebView() {
        nativeMemoryScreenOpen = false;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        showTripMateWebView();
    }

    private class MemoriesAdapter extends RecyclerView.Adapter<MemoriesAdapter.MemoryViewHolder> {
        private final List<JSONObject> items;

        MemoriesAdapter(List<JSONObject> items) {
            this.items = items;
        }

        @Override
        public MemoryViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(10));
            card.setBackground(createRoundRect(COLOR_CARD, dp(16)));
            card.setElevation(dp(4));
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(dp(6), dp(6), dp(6), dp(10));
            card.setLayoutParams(cardParams);

            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
            TextView details = new TextView(parent.getContext());
            details.setTextColor(COLOR_SUBTLE);
            details.setTextSize(12);
            details.setPadding(dp(4), dp(8), dp(4), 0);
            card.addView(details);
            return new MemoryViewHolder(card, image, details);
        }

        @Override
        public void onBindViewHolder(MemoryViewHolder holder, int position) {
            JSONObject item = items.get(position);
            holder.image.setImageBitmap(decodeBitmap(item.optString("path"), 500));
            holder.details.setText(item.optString("place", "Current trip") + "\n" + formatMemoryDetails(item));
            holder.itemView.setOnClickListener(view -> showFullScreenMemory(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class MemoryViewHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView details;

            MemoryViewHolder(View itemView, ImageView image, TextView details) {
                super(itemView);
                this.image = image;
                this.details = details;
            }
        }
    }

    private class AndroidAuthBridge {
        @JavascriptInterface
        public void showLogin() {
            runOnUiThread(() -> {
                Log.i(LOG_TAG, "Web requested Android login");
                firebaseAuth.signOut();
                CookieManager.getInstance().removeAllCookies(null);
                webView = null;
                webViewLoaded = false;
                showAuthScreen(false, "");
            });
        }

        @JavascriptInterface
        public boolean isFirebaseAuthenticated() {
            FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
            boolean authenticated = user != null && user.isEmailVerified();
            Log.i(LOG_TAG, "Web checked Firebase auth fallback authenticated=" + authenticated);
            return authenticated;
        }

        @JavascriptInterface
        public String getFirebaseDisplayName() {
            FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
            if (user == null) {
                return "Traveler";
            }
            String displayName = user.getDisplayName();
            if (displayName != null && !displayName.trim().isEmpty()) {
                return displayName.trim();
            }
            String email = user.getEmail();
            if (email != null && email.contains("@")) {
                return email.substring(0, email.indexOf('@'));
            }
            return "Traveler";
        }

        @JavascriptInterface
        public void openCameraMemories() {
            runOnUiThread(MainActivity.this::openCameraMemories);
        }

        @JavascriptInterface
        public void openTripMemoriesGallery() {
            runOnUiThread(MainActivity.this::showCameraGalleryScreen);
        }

        @JavascriptInterface
        public void setCurrentDestination(String destination) {
            lastKnownDestinationName = destination == null ? "" : destination;
            Log.i(LOG_TAG, "Current destination from WebView=" + lastKnownDestinationName);
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
        Log.i(LOG_TAG, "Requesting initial permissions count=" + permissions.size());
        try {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_APP_PERMISSIONS);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Initial permission request failed", exception);
            return false;
        }
        return true;
    }

    private void loadTripMate() {
        if (webViewLoaded) {
            return;
        }
        webViewLoaded = true;
        if (pendingWebViewState == null || webView.restoreState(pendingWebViewState) == null) {
            Log.i(LOG_TAG, "Loading TripMate URL " + TRIPMATE_URL);
            webView.loadUrl(TRIPMATE_URL);
        }
    }

    @SuppressLint("MissingPermission")
    private void startNativeLocationUpdates() {
        if (locationManager == null || locationListener == null || !hasLocationPermission()) {
            Log.i(LOG_TAG, "Native location updates skipped permission=" + hasLocationPermission());
            return;
        }
        try {
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
                Log.i(LOG_TAG, "Native last known location lat=" + lastKnown.getLatitude()
                        + " lng=" + lastKnown.getLongitude());
                sendLocationToWebView(lastKnown);
            }
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Native location updates failed", exception);
        }
    }

    private void sendLocationToWebView(Location location) {
        if (location == null || webView == null) {
            return;
        }
        lastNativeLocation = location;
        runOnUiThread(() -> {
            if (webView == null) return;
            try {
                webView.evaluateJavascript(
                        "if (window.updateNativeLocation) { window.updateNativeLocation("
                                + location.getLatitude() + "," + location.getLongitude() + "); }", null);
            } catch (Exception exception) {
                Log.e(LOG_TAG, "Sending native location to WebView failed", exception);
            }
        });
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.i(LOG_TAG, "Permission result requestCode=" + requestCode
                + " hasLocationPermission=" + hasLocationPermission());
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
        } else if (requestCode == REQUEST_CAMERA_MEMORIES) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Log.i(LOG_TAG, "Camera permission granted");
                showCameraScreen();
            } else {
                Log.w(LOG_TAG, "Camera permission denied");
                Toast.makeText(this, "Camera permission denied. You can still view saved memories.", Toast.LENGTH_LONG).show();
                showCameraGalleryScreen();
            }
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
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        networkExecutor.shutdownNow();
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (nativeMemoryScreenOpen) {
            returnToTripMateWebView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
