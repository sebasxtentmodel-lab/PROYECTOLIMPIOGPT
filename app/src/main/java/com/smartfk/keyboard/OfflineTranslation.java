package com.smartfk.keyboard;

import android.content.Context;
import com.google.mlkit.nl.translate.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/** ML Kit inference is on-device AFTER the pair's first model download. */
final class OfflineTranslation {
    interface Callback { void onSuccess(String value); void onFailure(String reason); }
    private final Translator trEs;
    private final Translator esTr;
    private volatile boolean turkishReady;
    private volatile boolean spanishReady;

    OfflineTranslation(Context context) {
        trEs = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TURKISH)
            .setTargetLanguage(TranslateLanguage.SPANISH).build());
        esTr = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.TURKISH).build());
    }

    void prepare(Callback cb) {
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        trEs.downloadModelIfNeeded(conditions)
            .addOnSuccessListener(v -> {
                turkishReady = true;
                cb.onSuccess("Modelo turco → español listo");
            })
            .addOnFailureListener(e -> cb.onFailure("Turco → español: " + e.getMessage()));
        esTr.downloadModelIfNeeded(conditions)
            .addOnSuccessListener(v -> {
                spanishReady = true;
                cb.onSuccess("Modelo español → turco listo");
            })
            .addOnFailureListener(e -> cb.onFailure("Español → turco: " + e.getMessage()));
    }

    void translate(String source, String phrase, Callback cb) {
        if (phrase == null || phrase.trim().isEmpty()) return;
        if ("tr".equals(source)) {
            if (!turkishReady) { cb.onFailure("Modelo de traducción turco no disponible"); return; }
            trEs.translate(phrase).addOnSuccessListener(cb::onSuccess)
                .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
        } else {
            if (!spanishReady) { cb.onFailure("Modelo de traducción español no disponible"); return; }
            esTr.translate(phrase).addOnSuccessListener(cb::onSuccess)
                .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
        }
    }

    void close() { trEs.close(); esTr.close(); }
}
