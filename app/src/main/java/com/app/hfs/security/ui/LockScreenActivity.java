package com.hfs.security.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.utils.FaceAuthHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.SmsHelper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * This activity launches whenever a protected app is opened.
 * 1. Runs 1x1 invisible camera for Face Match.
 * 2. Provides Fingerprint (Biometric) Unlock fallback.
 * 3. Provides PIN Unlock fallback.
 */
public class LockScreenActivity extends AppCompatActivity {

    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    private boolean isProcessing = false;
    private boolean isLocked = false;

    // Biometric Authentication Variables
    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep overlay on top of everything
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initial invisible state for background face scanning
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        // Initialize Biometric Prompt
        setupFingerprintScanner();

        // Start the silent camera verification
        startInvisibleCamera();

        // UI Listeners
        binding.btnUnlockPin.setOnClickListener(v -> verifyBackupPin());
        binding.btnFingerprint.setOnClickListener(v -> biometricPrompt.authenticate(promptInfo));
    }

    private void setupFingerprintScanner() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(LockScreenActivity.this,
                biometricExecutor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // SUCCESS: Fingerprint matched system owner
                finish();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(LockScreenActivity.this, "Fingerprint not recognized", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security Verification")
                .setSubtitle("Use fingerprint to unlock app")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFaceFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFaceFrame(@NonNull ImageProxy imageProxy) {
        if (isProcessing || isLocked) {
            imageProxy.close();
            return;
        }

        isProcessing = true;
        faceAuthHelper.authenticate(imageProxy, new FaceAuthHelper.AuthCallback() {
            @Override
            public void onMatchFound() {
                runOnUiThread(() -> finish());
            }

            @Override
            public void onMismatchFound() {
                handleIntrusionDetection(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    private void handleIntrusionDetection(ImageProxy imageProxy) {
        if (isLocked) return;
        isLocked = true;

        runOnUiThread(() -> {
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);
            
            FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);
            String appName = getIntent().getStringExtra("TARGET_APP_NAME");
            SmsHelper.sendAlertSms(LockScreenActivity.this, appName);
            
            // Automatically trigger fingerprint prompt if available
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void verifyBackupPin() {
        String enteredPin = binding.etPinInput.getText().toString();
        if (enteredPin.equals(db.getMasterPin())) {
            finish();
        } else {
            binding.tvErrorMsg.setText("Incorrect Security PIN");
            binding.etPinInput.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Back button disabled to prevent bypass
    }
}