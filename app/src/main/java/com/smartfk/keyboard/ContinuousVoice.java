package com.smartfk.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Single AudioRecord session. Does NOT restart on silence or while TTS is playing. */
final class ContinuousVoice {
    interface Events {
        void status(String state);
        void audioLevel(int averagePcm, boolean androidSilenced);
        void partial(String source, String words);
        void finalWords(String source, String words);
        void error(String message);
    }

    private final Context context;
    private final Events events;
    private volatile boolean running;
    private volatile boolean suppressRecognition;
    private volatile AudioRecord recorder;
    private Thread worker;
    private boolean pendingStart;
    private String previousLanguage = "tr";
    private volatile boolean resetAfterPlayback;
    private long lastEmittedEndMs;

    ContinuousVoice(Context context, Events events) {
        this.context = context.getApplicationContext();
        this.events = events;
    }

    synchronized void start() {
        if (running) return;
        if (worker != null && worker.isAlive()) { pendingStart = true; return; }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            events.error("Falta permiso de micrófono"); return;
        }
        running = true;
        worker = new Thread(this::capture, "smartfk-capture");
        worker.start();
    }

    synchronized void stop() {
        running = false;
        pendingStart = false;
        AudioRecord r = recorder;
        if (r != null) try { r.stop(); } catch (Exception ignored) {}
    }

    void setSpeechPlayback(boolean playing) {
        suppressRecognition = playing;
        if (playing) resetAfterPlayback = true;
    }

    private void capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord r = null;
        try {
            events.status("Cargando motores de voz sin Internet…");
            String mode = context.getSharedPreferences("smartfk",Context.MODE_PRIVATE)
                .getString("source_language","tr");
            boolean useTr = !"es".equals(mode);
            boolean useEs = !"tr".equals(mode);
            File turkish = useTr ? materialize("models/tr") : null;
            File spanish = useEs ? materialize("models/es") : null;
            try (Model mTr = useTr ? new Model(turkish.getAbsolutePath()) : null;
                 Model mEs = useEs ? new Model(spanish.getAbsolutePath()) : null;
                 Recognizer tr = useTr ? new Recognizer(mTr, 16000.0f) : null;
                 Recognizer es = useEs ? new Recognizer(mEs, 16000.0f) : null) {
                if (tr != null) tr.setWords(true);
                if (es != null) es.setWords(true);
                int buffer = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (buffer <= 0) throw new IllegalStateException("Micrófono PCM16 de 16kHz no disponible");
                r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(buffer * 2, 8192));
                if (r.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("No se inicializó AudioRecord");
                recorder = r;
                if (!running) return;
                r.startRecording();
                if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("No se pudo iniciar la grabación");
                events.status("");
                short[] chunk = new short[1600];
                long lastPartial = 0;
                long lastAudioReport = 0;
                while (running) {
                    int count = r.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Error AudioRecord: " + count);
                    if (count == 0) continue;
                    long time = android.os.SystemClock.elapsedRealtime();
                    if (time - lastAudioReport > 1200) {
                        long sum = 0;
                        for (int k = 0; k < count; k++) sum += Math.abs((int)chunk[k]);
                        int average=(int)(sum / count);
                        android.media.AudioRecordingConfiguration conf =
                            r.getActiveRecordingConfiguration();
                        boolean systemSilenced=conf != null && conf.isClientSilenced();
                        events.audioLevel(average, systemSilenced);
                        lastAudioReport=time;
                    }
                    if (suppressRecognition) continue;
                    if (resetAfterPlayback) {
                        // Discard the half-recognized sentence heard before TTS playback.
                        if (tr != null) tr.reset();
                        if (es != null) es.reset();
                        resetAfterPlayback = false;
                        lastEmittedEndMs = 0;
                    }
                    boolean trEnd = tr != null && tr.acceptWaveForm(chunk, count);
                    boolean esEnd = es != null && es.acceptWaveForm(chunk, count);
                    if (trEnd || esEnd) {
                        Candidate a = candidate(trEnd ? tr.getResult() : "", "tr");
                        Candidate b = candidate(esEnd ? es.getResult() : "", "es");
                        Candidate choice = select(a,b);
                        if (!choice.words.isEmpty()) {
                            long now = android.os.SystemClock.elapsedRealtime();
                            // Opposite-language recognizer may finalize the SAME sound later.
                            if (!"auto".equals(mode) || now - lastEmittedEndMs >= 1050) {
                                previousLanguage = choice.lang;
                                events.finalWords(choice.lang, choice.words);
                                lastEmittedEndMs = now;
                            }
                        }
                    } else if (android.os.SystemClock.elapsedRealtime() - lastPartial >= 400) {
                        Candidate a = candidate(tr == null ? "" : tr.getPartialResult(), "tr");
                        Candidate b = candidate(es == null ? "" : es.getPartialResult(), "es");
                        Candidate choice = select(a,b);
                        if (!choice.words.isEmpty()) events.partial(choice.lang, choice.words);
                        lastPartial = android.os.SystemClock.elapsedRealtime();
                    }
                }
            }
        } catch (Exception e) {
            if (running) events.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (r != null) {
                try { if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop(); }
                catch (Exception ignored) {}
                r.release();
            }
            synchronized (this) {
                recorder = null;
                worker = null;
                running = false;
                if (pendingStart) {
                    pendingStart = false;
                    start();
                }
            }
        }
    }

    private Candidate select(Candidate a, Candidate b) {
        if (a.words.isEmpty()) return b;
        if (b.words.isEmpty()) return a;
        double sa = a.score + (a.lang.equals(previousLanguage) ? 0.035 : 0);
        double sb = b.score + (b.lang.equals(previousLanguage) ? 0.035 : 0);
        return sa >= sb ? a : b;
    }

    private Candidate candidate(String json, String lang) {
        try {
            JSONObject o = new JSONObject(json);
            String words = o.optString("text", o.optString("partial", "")).trim();
            if (words.isEmpty()) return new Candidate(lang,"",0);
            JSONArray results = o.optJSONArray("result");
            double c = 0.5;
            if (results != null && results.length() > 0) {
                double sum = 0;
                for (int i=0; i<results.length(); i++)
                    sum += results.getJSONObject(i).optDouble("conf", 0.4);
                c = sum / results.length();
            }
            return new Candidate(lang, words, c + Math.min(0.12,words.length()/450.0));
        } catch (Exception ignored) { return new Candidate(lang,"",0); }
    }

    private File materialize(String assetPath) throws Exception {
        File root = new File(context.getFilesDir(), assetPath);
        File marker = new File(root, ".ready");
        if (marker.isFile()) return root;
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("No se creó carpeta modelo");
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0)
            throw new IllegalStateException("Modelo offline ausente en APK: " + assetPath);
        copyAssetDir(assetPath,root);
        if (!marker.createNewFile()) throw new IllegalStateException("Modelo no finalizado");
        return root;
    }

    private void copyAssetDir(String relative, File dest) throws Exception {
        String[] list = context.getAssets().list(relative);
        if (list == null) throw new IllegalStateException("Asset ilegible");
        if (list.length == 0) {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new IllegalStateException("Carpeta asset");
            try (InputStream in = context.getAssets().open(relative);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n=in.read(buf))!=-1) out.write(buf,0,n);
            }
        } else {
            if (!dest.exists() && !dest.mkdirs()) throw new IllegalStateException("Carpeta asset");
            for (String item : list) copyAssetDir(relative + "/" + item,new File(dest,item));
        }
    }

    private static final class Candidate {
        final String lang;
        final String words;
        final double score;
        Candidate(String lang, String words, double score) {
            this.lang=lang; this.words=words; this.score=score;
        }
    }
}
