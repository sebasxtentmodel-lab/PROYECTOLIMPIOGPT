package com.smartfk.keyboard;

import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * On-device ML Kit adapter. Models require initial download.
 * Before this fix, early recognized words were dropped whenever preparation had
 * not finished. Every translation now waits on the SAME cached preparation task.
 */
final class OfflineTranslation {
    interface Callback {
        void onSuccess(String value);
        void onFailure(String reason);
    }
    private final Translator trEs;
    private final Translator esTr;
    private Task<Void> prepareTr;
    private Task<Void> prepareEs;
    private boolean closed;

    OfflineTranslation(Context context) {
        trEs = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TURKISH)
            .setTargetLanguage(TranslateLanguage.SPANISH).build());
        esTr = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.TURKISH).build());
    }

    private synchronized Task<Void> ensureModel(String source) {
        if ("tr".equals(source)) {
            if (prepareTr == null) prepareTr = trEs.downloadModelIfNeeded(
                new DownloadConditions.Builder().build());
            return prepareTr;
        }
        if (prepareEs == null) prepareEs = esTr.downloadModelIfNeeded(
            new DownloadConditions.Builder().build());
        return prepareEs;
    }

    void prepare(Callback cb) {
        prepareSource("tr",new Callback() {
            @Override public void onSuccess(String value) { cb.onSuccess(value); }
            @Override public void onFailure(String reason) { cb.onFailure(reason); }
        });
        prepareSource("es",new Callback() {
            @Override public void onSuccess(String value) { cb.onSuccess(value); }
            @Override public void onFailure(String reason) { cb.onFailure(reason); }
        });
    }

    void prepareSource(String source, Callback cb) {
        if (closed) { cb.onFailure("Motor de traducción cerrado");return; }
        ensureModel(source)
            .addOnSuccessListener(v -> cb.onSuccess("tr".equals(source)
                ? "Modelo turco → español listo" : "Modelo español → turco listo"))
            .addOnFailureListener(e -> cb.onFailure(
                "Modelo "+source+": "+message(e)));
    }

    void translate(String source, String phrase, Callback cb) {
        if (phrase == null || phrase.trim().isEmpty()) {
            cb.onFailure("El reconocedor devolvió texto vacío");
            return;
        }
        if (closed) { cb.onFailure("Motor de traducción cerrado");return; }
        final boolean turkish = "tr".equals(source);
        Translator engine=turkish?trEs:esTr;
        ensureModel(turkish?"tr":"es").addOnSuccessListener(v -> {
            if (closed) return;
            engine.translate(phrase)
                .addOnSuccessListener(cb::onSuccess)
                .addOnFailureListener(e -> cb.onFailure("Inferencia: "+message(e)));
        }).addOnFailureListener(e -> cb.onFailure(
            "Modelo offline no preparado: "+message(e)));
    }

    private static String message(Exception e) {
        if (e==null) return "desconocido";
        return e.getClass().getSimpleName()+": "+e.getMessage();
    }
    synchronized void close() {
        closed=true;
        trEs.close();
        esTr.close();
    }
}
