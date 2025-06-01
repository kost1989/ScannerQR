package com.example.scannerqr;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QRScanner";
    private androidx.camera.view.PreviewView previewView;
    private TextView resultText;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;
    private boolean isScanning = true;
    private Camera camera;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview_view);
        resultText = findViewById(R.id.result_text);

        // Initialize ML Kit Barcode Scanner
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Request camera permissions
        requestCameraPermission();
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
                Toast.makeText(this, "Camera init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            return;
        }

        try {
            // Unbind existing use cases
            cameraProvider.unbindAll();

            // Camera selector - default back camera
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            // Preview
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Image analysis
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

            // Bind to lifecycle
            camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class) private void analyzeImage(ImageProxy imageProxy) {
        if (!isScanning || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        Barcode barcode = barcodes.get(0);
                        String rawValue = barcode.getRawValue();
                        if (rawValue != null) {
                            isScanning = false;
                            runOnUiThread(() -> displayResult(rawValue));
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Barcode scanning failed", e);
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void displayResult(String text) {
        if (text.startsWith("http://") || text.startsWith("https://")) {
            makeTextClickable(text);
        } else {
            resultText.setText(text);
        }
    }

    private void makeTextClickable(String url) {
        SpannableString spannable = new SpannableString(url);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                openWebPage(url);
            }
        };
        spannable.setSpan(clickableSpan, 0, url.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        resultText.setText(spannable);
        resultText.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid URL: " + url, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset scanning when returning to app
        isScanning = true;
        resultText.setText(R.string.scanning_hint);

        if (cameraProvider == null) {
            requestCameraPermission();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isScanning = false;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shutdown camera executor
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        // Close barcode scanner
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }
}