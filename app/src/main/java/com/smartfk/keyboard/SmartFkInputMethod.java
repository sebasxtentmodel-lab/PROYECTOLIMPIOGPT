package com.smartfk.keyboard;

import android.content.Context;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** A text-only rolling interpreter IME, with three invisible touch regions. */
public final class SmartFkInputMethod extends InputMethodService {
    private static final int PANEL_DP = 250;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout panel;
    private ScrollView scroller;
    private LinearLayout history;
    private TextView diagnostic;
    private ContinuousVoice microphone;
    private OfflineTranslation translate;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsInitializing;
    private Section pendingSpeech;
    private boolean speaking;
    private String utteranceId = "";
    private final ArrayDeque<String> speechChunks = new ArrayDeque<>();
    private long translationRequestId = 0;
    private boolean followingLatest = true;
    private boolean active;
    private long session = 0;
    private long partialSerial = 0;
    private long lastPartialRequest = 0;
    private long lastRecognizedAudio = 0;
    private final ArrayList<Section> sections = new ArrayList<>();
    private final ArrayDeque<Recognition> queue = new ArrayDeque<>();
    private boolean translating;
    private Section current;

    @Override public void onCreate() {
        super.onCreate();
        translate = new OfflineTranslation(this);
        microphone = new ContinuousVoice(this, new ContinuousVoice.Events() {
            @Override public void status(String state) {
                ui.post(() -> {
                    if (!active) return;
                    if (state.isEmpty()) {
                        String mode=getSharedPreferences("smartfk",Context.MODE_PRIVATE)
                            .getString("source_language","tr");
                        showDiagnostic("Escuchando " + ("es".equals(mode)?"español":
                            "auto".equals(mode)?"turco o español":"turco") +
                            "… Habla para iniciar la traducción.");
                    } else showDiagnostic(state);
                });
            }
            @Override public void audioLevel(int averagePcm, boolean androidSilenced) {
                getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
                    .putInt("last_pcm_average", averagePcm)
                    .putBoolean("audio_client_silenced",androidSilenced)
                    .putLong("last_audio_heartbeat",System.currentTimeMillis())
                    .apply();
                ui.post(() -> {
                    if (!active || speaking || SystemClock.elapsedRealtime()-lastRecognizedAudio<9000) return;
                    if (androidSilenced) {
                        showDiagnostic("Android está silenciando la entrada del micrófono. "
                            +"Cierra cualquier otra app que esté grabando audio.");
                    } else if (averagePcm<35) {
                        showDiagnostic("Micrófono abierto, pero el audio recibido es casi silencio. "
                            +"Comprueba el acceso al micrófono del sistema.");
                    } else {
                        showDiagnostic("El micrófono recibe sonido, pero Vosk todavía no detecta palabras. "
                            +"Comprueba el idioma y la pronunciación.");
                    }
                });
            }
            @Override public void partial(String lang, String words) {
                if (!words.isEmpty()) {
                    getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
                        .putString("last_asr_text",words).putString("last_asr_language",lang).apply();
                }
                ui.post(() -> acceptPartial(lang,words));
            }
            @Override public void finalWords(String lang, String words) {
                if (!words.isEmpty()) {
                    getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
                        .putString("last_asr_text",words).putString("last_asr_language",lang).apply();
                }
                ui.post(() -> acceptFinal(lang,words));
            }
            @Override public void error(String detail) {
                // Setup activity is responsible for diagnostics, keeping the IME text-only.
                android.util.Log.e("SmartFk", detail);
                ui.post(() -> showDiagnostic("Error de audio: " + detail
                    + ". Comprueba el estado en la aplicación SmartFk."));
                getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit().putString("last_asr_error",detail).apply();
            }
        });
    }

    @Override public View onCreateInputView() {
        panel = new FixedHeightPanel(this,dp(PANEL_DP));
        panel.setBackgroundColor(Color.rgb(16,16,16));
        panel.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_DP)));
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(Color.TRANSPARENT);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setPadding(dp(5),dp(7),dp(5),dp(6));
        diagnostic = new TextView(this);
        diagnostic.setTextColor(0xFFDDDDDD);
        diagnostic.setTextSize(15);
        diagnostic.setPadding(dp(6),dp(5),dp(6),dp(5));
        diagnostic.setVisibility(View.GONE);
        history.addView(diagnostic, new LinearLayout.LayoutParams(-1,-2));
        scroller.addView(history,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroller,new FrameLayout.LayoutParams(-1,dp(PANEL_DP)));
        View touchSurface = new View(this);
        touchSurface.setBackgroundColor(Color.TRANSPARENT);
        touchSurface.setOnTouchListener(new ThreeZoneGestures());
        panel.addView(touchSurface,new FrameLayout.LayoutParams(-1,dp(PANEL_DP)));
        for (Section s : sections) { s.view = null; drawSection(s); }
        return panel;
    }

    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info,restarting);
        active = true;
        showDiagnostic("Preparando reconocimiento y traducción local…");
        session++;
        // No unconfirmed words or in-flight translations may leak across IME sessions.
        queue.clear();
        translating = false;
        translationRequestId++;
        partialSerial++;
        lastPartialRequest = 0;
        lastRecognizedAudio = SystemClock.elapsedRealtime();
        current = null;
        followingLatest = true;
        // Do not load Vosk and neural translation models simultaneously on a
        // 4 GB device. The translator lazily opens only after ASR finds words.
        getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
            .remove("last_asr_error").remove("last_translate_error")
            .remove("last_asr_text").apply();
        microphone.start();
    }

    private void acceptPartial(String lang,String words) {
        if (!active || words.trim().isEmpty() || speaking) return;
        lastRecognizedAudio = SystemClock.elapsedRealtime();
        if (current==null || (current.view!=null && current.text.length()==0))
            showDiagnostic("Voz detectada. Traduciendo al idioma contrario…");
        long now=SystemClock.elapsedRealtime();
        if (now - lastPartialRequest < 750) return;
        lastPartialRequest=now;
        final long serial=++partialSerial;
        final long token=session;
        translate.translatePreview(lang, words,new OfflineTranslation.Callback() {
            @Override public void onSuccess(String translation) {
                ui.post(() -> {
                    if (!active || session!=token || serial!=partialSerial || speaking) return;
                    // A provisional hypothesis must never replace a different speaker's block.
                    if (current != null && !current.source.equals(lang)) return;
                    Section s = ensureSection(lang);
                    s.preview = translation.trim();
                    clearDiagnostic();
                    render(s);
                });
            }
            @Override public void onFailure(String err) {
                ui.post(() -> {
                    if (session!=token || !active) return;
                    showDiagnostic("Reconocí voz, pero no pude traducir: " + err);
                    getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
                        .putString("last_translate_error",err).apply();
                });
            }
        });
    }

    private void acceptFinal(String lang,String words) {
        if (!active || speaking || words.trim().isEmpty()) return;
        lastRecognizedAudio = SystemClock.elapsedRealtime();
        showDiagnostic("Traduciendo frase reconocida…");
        partialSerial++;
        final long token=session;
        if (current != null) { current.preview = ""; render(current); }
        Section destination=ensureSection(lang);
        queue.add(new Recognition(lang,words,token,destination));
        processQueue();
    }

    private void processQueue() {
        if (translating || queue.isEmpty()) return;
        Recognition item=queue.poll();
        if (item.session!=session || !active) { processQueue(); return; }
        translating=true;
        final long reqId = ++translationRequestId;
        translate.translate(item.source,item.text,new OfflineTranslation.Callback() {
            @Override public void onSuccess(String value) {
                ui.post(() -> {
                    if (reqId != translationRequestId) return;
                    translating=false;
                    if (active && session==item.session && !value.trim().isEmpty()) {
                        Section s=item.destination;
                        if (s.text.length()>0) s.text.append(' ');
                        s.text.append(value.trim());
                        clearDiagnostic();
                        s.preview="";
                        render(s);
                    }
                    processQueue();
                });
            }
            @Override public void onFailure(String msg) {
                ui.post(() -> {
                    if (reqId != translationRequestId) return;
                    translating=false;
                    android.util.Log.e("SmartFk","Error al traducir: "+msg);
                    showDiagnostic("Reconocimiento correcto, traducción falló: "+msg);
                    getSharedPreferences("smartfk",Context.MODE_PRIVATE).edit()
                        .putString("last_translate_error",msg).apply();
                    processQueue();
                });
            }
        });
    }

    private Section ensureSection(String source) {
        if (current != null && current.source.equals(source)) return current;
        current = new Section(source);
        sections.add(current);
        drawSection(current);
        return current;
    }

    private void drawSection(Section s) {
        if (history==null || s.view!=null) return;
        TextView text=new TextView(this);
        text.setTextColor(0xFFF8F8F8);
        text.setTextSize(19);
        text.setLineSpacing(dp(1),1.08f);
        text.setPadding(dp(3),dp(4),dp(3),dp(12));
        text.setGravity(android.view.Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        history.addView(text,params);
        s.view=text;
        render(s);
    }

    private void render(Section s) {
        if (s.view==null) return;
        String full=s.text.toString();
        if (!s.preview.isEmpty()) full += (full.isEmpty()?"":" ") + s.preview;
        boolean nearBottom = followingLatest && (scroller == null ||
            scroller.getScrollY()+scroller.getHeight()+dp(48)>=history.getHeight());
        s.view.setText(full);
        if (nearBottom && scroller!=null)
            scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private Section displayedSection(float touchY) {
        if (sections.isEmpty()) return null;
        if (scroller==null) return sections.get(sections.size()-1);
        int center=scroller.getScrollY()+(int)touchY;
        Section closest=sections.get(sections.size()-1);
        int distance=Integer.MAX_VALUE;
        for (Section s : sections) {
            if (s.view==null) continue;
            int middle=s.view.getTop()+s.view.getHeight()/2;
            int delta=Math.abs(middle-center);
            if (delta<distance) { distance=delta; closest=s; }
        }
        return closest;
    }

    private void playOrStop(float touchY) {
        if (speaking) {
            // AudioRecord remains open: only ASR decoding was suppressed.
            speaking=false;
            utteranceId="";
            speechChunks.clear();
            if (tts!=null) tts.stop();
            microphone.setSpeechPlayback(false);
            return;
        }
        Section s=displayedSection(touchY);
        if (s==null || s.text.length()==0) return;
        if (tts==null && !ttsInitializing) {
            // TTS is optional and must never prevent this keyboard from starting.
            // Loading it only after a user taps text prevents launch failures
            // when Android's speech engine is disabled/unavailable.
            pendingSpeech=s;
            ttsInitializing=true;
            try {
                tts=new TextToSpeech(this,status -> ui.post(() -> {
                    ttsInitializing=false;
                    ttsReady=status==TextToSpeech.SUCCESS && tts!=null;
                    if (ttsReady) {
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {}
                            @Override public void onDone(String id) { completedSpeech(id); }
                            @Override public void onError(String id) { completedSpeech(id); }
                        });
                    }
                    Section pending=pendingSpeech;
                    pendingSpeech=null;
                    if(ttsReady && active && pending!=null) startSpeech(pending);
                    else if(active && !ttsReady) showDiagnostic(
                        "La voz de lectura es opcional y no está instalada. La traducción continúa.");
                }));
            }catch(Throwable error){
                ttsInitializing=false;
                ttsReady=false;
                pendingSpeech=null;
                android.util.Log.w("SmartFk","Sistema TTS no disponible",error);
            }
            return;
        }
        if (ttsInitializing) {pendingSpeech=s;return;}
        if (!ttsReady) return;
        startSpeech(s);
    }

    private void startSpeech(Section s) {
        if (!ttsReady || tts==null || !active) return;
        Locale lang="tr".equals(s.source) ? new Locale("es","ES") : new Locale("tr","TR");
        if (!selectOfflineVoice(lang)) return;
        speaking=true;
        microphone.setSpeechPlayback(true);
        speechChunks.clear();
        splitForSpeech(s.text.toString());
        speakNextChunk();
    }

    private boolean selectOfflineVoice(Locale locale) {
        try {
            Set<Voice> voices=tts.getVoices();
            if (voices==null) return false;
            for (Voice v : voices) {
                if (!v.isNetworkConnectionRequired() &&
                    v.getLocale()!=null &&
                    locale.getLanguage().equals(v.getLocale().getLanguage())) {
                    return tts.setVoice(v)==TextToSpeech.SUCCESS;
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("SmartFk","Offline voice not available",e);
        }
        return false;
    }

    private void splitForSpeech(String text) {
        int max = Math.min(2000,TextToSpeech.getMaxSpeechInputLength()-16);
        String remaining = text.trim();
        while (!remaining.isEmpty()) {
            int len = Math.min(max,remaining.length());
            if (len < remaining.length()) {
                int split = remaining.lastIndexOf(' ',len);
                if (split >= len / 2) len = split;
            }
            speechChunks.add(remaining.substring(0,len).trim());
            remaining = remaining.substring(len).trim();
        }
    }

    private void speakNextChunk() {
        if (!speaking) return;
        String next=speechChunks.pollFirst();
        if (next==null) {
            finishSpeech();
            return;
        }
        final String id="smartfk-"+SystemClock.uptimeMillis();
        utteranceId=id;
        if (tts.speak(next,TextToSpeech.QUEUE_FLUSH,null,id)==TextToSpeech.ERROR)
            finishSpeech();
    }

    private void finishSpeech() {
        utteranceId="";
        speechChunks.clear();
        speaking=false;
        if (microphone!=null) microphone.setSpeechPlayback(false);
    }

    private void completedSpeech(String id) {
        ui.post(() -> {
            if (!id.equals(utteranceId)) return;
            speakNextChunk();
        });
    }

    private void switchBack() {
        if (!active) return;
        if (!switchToPreviousInputMethod()) {
            InputMethodManager im=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im!=null) im.showInputMethodPicker();
        }
    }

    private final class ThreeZoneGestures implements View.OnTouchListener {
        private final int slop=ViewConfiguration.get(SmartFkInputMethod.this).getScaledTouchSlop();
        private float xStart,yStart,lastY;
        private boolean moved,held;
        private int zone;
        private Runnable longPress;
        @Override public boolean onTouch(View v,MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    xStart=e.getX(); yStart=e.getY(); lastY=yStart; moved=false; held=false;
                    float ratio=xStart/Math.max(1f,v.getWidth());
                    zone=ratio<0.36f?0:ratio>0.64f?2:1;
                    if (zone==1) {
                        longPress=() -> { if (!moved) {held=true;switchBack();} };
                        ui.postDelayed(longPress,2000L);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=Math.abs(e.getX()-xStart);
                    float dy=Math.abs(e.getY()-yStart);
                    if (dx>slop || dy>slop) {
                        moved=true;
                        if (longPress!=null) ui.removeCallbacks(longPress);
                    }
                    if (zone==2 && scroller!=null) {
                        int amount=Math.round(lastY-e.getY());
                        scroller.scrollBy(0,amount);
                        // Never auto-jump to newest while the user reads older sections.
                        followingLatest = false;
                    }
                    lastY=e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (longPress!=null) ui.removeCallbacks(longPress);
                    if (zone==2 && scroller!=null) {
                        followingLatest = scroller.getScrollY()+scroller.getHeight()+dp(48)
                            >= history.getHeight();
                    }
                    if (!moved && !held && zone==0) playOrStop(e.getY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (longPress!=null) ui.removeCallbacks(longPress);
                    return true;
            }
            return true;
        }
    }

    private void showDiagnostic(String message) {
        if (!active || diagnostic == null) return;
        diagnostic.setText(message);
        diagnostic.setVisibility(View.VISIBLE);
    }
    private void clearDiagnostic() {
        if (diagnostic != null) diagnostic.setVisibility(View.GONE);
    }

    /**
     * The Android IME host attaches input views with WRAP_CONTENT.
     * Every child must have an explicit height, including the invisible touch layer.
     * Clamp the root itself to keyboard-row height, never to the screen height.
     */
    private static final class FixedHeightPanel extends FrameLayout {
        private final int desiredHeight;
        FixedHeightPanel(Context context, int height) {
            super(context);
            desiredHeight=height;
            setMinimumHeight(height);
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode=View.MeasureSpec.getMode(heightMeasureSpec);
            int available=View.MeasureSpec.getSize(heightMeasureSpec);
            int height=(mode==View.MeasureSpec.UNSPECIFIED || available<=0)
                ?desiredHeight:Math.min(desiredHeight,available);
            super.onMeasure(widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(Math.max(1,height),View.MeasureSpec.EXACTLY));
        }
    }

    private int dp(int pixels) {
        return (int)(pixels*getResources().getDisplayMetrics().density+0.5f);
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
    // Even when Android asks about extract/fullscreen layouts, keep this IME in
    // the lower keyboard region and leave the host app visible.
    @Override public void onConfigureWindow(android.view.Window window,
            boolean fullscreen, boolean candidatesOnly) {
        super.onConfigureWindow(window,false,candidatesOnly);
    }
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public void onFinishInputView(boolean finishingInput) {
        active=false; session++; queue.clear(); partialSerial++;
        translationRequestId++; translating=false; current=null;
        speaking=false; utteranceId=""; speechChunks.clear();
        pendingSpeech=null;
        if (tts!=null) try{tts.stop();}catch(Throwable ignored){}
        microphone.setSpeechPlayback(false);
        microphone.stop();
        super.onFinishInputView(finishingInput);
    }
    @Override public void onDestroy() {
        if (microphone!=null) microphone.stop();
        if (tts!=null) {
            try{tts.stop();tts.shutdown();}catch(Throwable ignored){}
        }
        if (translate!=null) translate.close();
        super.onDestroy();
    }
    private static final class Recognition {
        final String source,text; final long session; final Section destination;
        Recognition(String source,String text,long session,Section destination) {
            this.source=source;this.text=text;this.session=session;
            this.destination=destination;
        }
    }
    private static final class Section {
        final String source;
        final StringBuilder text=new StringBuilder();
        String preview="";
        TextView view;
        Section(String source) {this.source=source;}
    }
}
