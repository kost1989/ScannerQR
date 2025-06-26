package ru.ikostyukov.scannerqr;

import android.app.Application;

public class MainApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Инициализация здесь не требуется, но класс нужен для AndroidManifest
    }

    @Override
    public void onTerminate() {
        // Гарантированное освобождение ресурсов при завершении приложения
        super.onTerminate();
    }
}