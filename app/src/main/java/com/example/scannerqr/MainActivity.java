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
import android.os.Handler;
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
    private Handler resetHandler = new Handler();
    private Runnable resetRunnable;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                    // Не закрываем приложение, а показываем сообщение
                    resultText.setText("Camera permission required");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview_view);
        resultText = findViewById(R.id.result_text);

        // Инициализация обработчика сброса
        resetRunnable = () -> {
            isScanning = true;
            resultText.setText(R.string.scanning_hint);
        };

        // Инициализация сканера QR-кодов
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        // Инициализация исполнителя камеры
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Запрос разрешений камеры
        requestCameraPermission();
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            // Отложить запуск камеры до получения разрешения
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
            // Отвязать все предыдущие use cases
            cameraProvider.unbindAll();

            // Выбор камеры (задняя)
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            // Настройка предпросмотра
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Настройка анализа изображения
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

            // Привязка к жизненному циклу
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

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
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
                            isScanning = false; // Временно останавливаем сканирование
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
        // Отменить предыдущий сброс, если он был запланирован
        resetHandler.removeCallbacks(resetRunnable);

        if (text.startsWith("http://") || text.startsWith("https://")) {
            makeTextClickable(text);
        } else {
            resultText.setText(text);
        }

        // Автоматический сброс через 5 секунд для продолжения сканирования
        resetHandler.postDelayed(resetRunnable, 5000);
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
        // Сбросить состояние при возврате в приложение
        isScanning = true;
        resultText.setText(R.string.scanning_hint);

        // Перезапустить камеру, если разрешение есть
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {

            if (cameraProvider != null) {
                bindCameraUseCases();
            } else {
                startCamera();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isScanning = false;
        resetHandler.removeCallbacks(resetRunnable);

        // Освободить ресурсы камеры
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Очистить обработчики
        resetHandler.removeCallbacks(resetRunnable);

        // Завершить исполнитель камеры
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        // Закрыть сканер штрих-кодов
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }
}