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
    private ContinuousVoice microphone;
    private OfflineTranslation translate;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speaking;
    private String utteranceId = "";
    private boolean active;
    private long session = 0;
    private long partialSerial = 0;
    private long lastPartialRequest = 0;
    private final ArrayList<Section> sections = new ArrayList<>();
    private final ArrayDeque<Recognition> queue = new ArrayDeque<>();
    private boolean translating;
    private Section current;

    @Override public void onCreate() {
        super.onCreate();
        translate = new OfflineTranslation(this);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) { completedSpeech(id); }
            @Override public void onError(String id) { completedSpeech(id); }
        });
        microphone = new ContinuousVoice(this, new ContinuousVoice.Events() {
            @Override public void partial(String lang, String words) {
                ui.post(() -> acceptPartial(lang,words));
            }
            @Override public void finalWords(String lang, String words) {
                ui.post(() -> acceptFinal(lang,words));
            }
            @Override public void error(String detail) {
                // Setup activity is responsible for diagnostics, keeping the IME text-only.
                android.util.Log.e("SmartFk", detail);
            }
        });
    }

    @Override public View onCreateInputView() {
        panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.rgb(16,16,16));
        panel.setLayoutParams(new ViewGroup.LayoutParams(-1, dp(PANEL_DP)));
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(Color.TRANSPARENT);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setPadding(dp(5),dp(7),dp(5),dp(6));
        scroller.addView(history,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroller,new FrameLayout.LayoutParams(-1,dp(PANEL_DP)));
        View touchSurface = new View(this);
        touchSurface.setBackgroundColor(Color.TRANSPARENT);
        touchSurface.setOnTouchListener(new ThreeZoneGestures());
        panel.addView(touchSurface,new FrameLayout.LayoutParams(-1,-1));
        for (Section s : sections) drawSection(s);
        return panel;
    }

    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info,restarting);
        active = true;
        session++;
        final long token = session;
        translate.prepare(new OfflineTranslation.Callback() {
            @Override public void onSuccess(String text) {
                if (active && session==token) android.util.Log.i("SmartFk",text);
            }
            @Override public void onFailure(String err) {
                android.util.Log.e("SmartFk",err);
            }
        });
        microphone.start();
    }

    private void acceptPartial(String lang,String words) {
        if (!active || words.trim().isEmpty() || speaking) return;
        long now=SystemClock.elapsedRealtime();
        if (now - lastPartialRequest < 750) return;
        lastPartialRequest=now;
        final long serial=++partialSerial;
        final long token=session;
        translate.translate(lang, words,new OfflineTranslation.Callback() {
            @Override public void onSuccess(String translation) {
                ui.post(() -> {
                    if (!active || session!=token || serial!=partialSerial || speaking) return;
                    Section s = ensureSection(lang);
                    s.preview = translation.trim();
                    render(s);
                });
            }
            @Override public void onFailure(String err) {}
        });
    }

    private void acceptFinal(String lang,String words) {
        if (!active || speaking || words.trim().isEmpty()) return;
        partialSerial++;
        final long token=session;
        queue.add(new Recognition(lang,words,token));
        processQueue();
    }

    private void processQueue() {
        if (translating || queue.isEmpty()) return;
        Recognition item=queue.poll();
        if (item.session!=session || !active) { processQueue(); return; }
        translating=true;
        translate.translate(item.source,item.text,new OfflineTranslation.Callback() {
            @Override public void onSuccess(String value) {
                ui.post(() -> {
                    translating=false;
                    if (active && session==item.session && !value.trim().isEmpty()) {
                        Section s=ensureSection(item.source);
                        if (s.text.length()>0) s.text.append(' ');
                        s.text.append(value.trim());
                        s.preview="";
                        render(s);
                    }
                    processQueue();
                });
            }
            @Override public void onFailure(String msg) {
                ui.post(() -> { translating=false; processQueue(); });
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
        boolean nearBottom=scroller==null || scroller.getChildAt(0)==null ||
            scroller.getScrollY()+scroller.getHeight()+dp(36)>=history.getHeight();
        s.view.setText(full);
        if (nearBottom && scroller!=null)
            scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private Section displayedSection() {
        if (sections.isEmpty()) return null;
        if (scroller==null) return sections.get(sections.size()-1);
        int center=scroller.getScrollY()+scroller.getHeight()/2;
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

    private void playOrStop() {
        if (speaking) {
            // AudioRecord remains open: only ASR decoding was suppressed.
            speaking=false;
            microphone.setSpeechPlayback(false);
            utteranceId="";
            if (tts!=null) tts.stop();
            return;
        }
        Section s=displayedSection();
        if (s==null || s.text.length()==0 || !ttsReady) return;
        Locale lang="tr".equals(s.source) ? new Locale("es","ES") : new Locale("tr","TR");
        if (!selectOfflineVoice(lang)) return;
        speaking=true;
        microphone.setSpeechPlayback(true);
        final String id="smartfk-"+SystemClock.uptimeMillis();
        utteranceId=id;
        int rc=tts.speak(s.text.toString(),TextToSpeech.QUEUE_FLUSH,null,id);
        if (rc==TextToSpeech.ERROR) completedSpeech(id);
    }

    private boolean selectOfflineVoice(Locale locale) {
        Set<Voice> voices=tts.getVoices();
        if (voices==null) return false;
        for (Voice v : voices) {
            if (!v.isNetworkConnectionRequired() &&
                v.getLocale()!=null &&
                locale.getLanguage().equals(v.getLocale().getLanguage())) {
                return tts.setVoice(v)==TextToSpeech.SUCCESS;
            }
        }
        return false;
    }

    private void completedSpeech(String id) {
        ui.post(() -> {
            if (!id.equals(utteranceId)) return;
            utteranceId="";
            speaking=false;
            if (microphone!=null) microphone.setSpeechPlayback(false);
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
                        scroller.scrollBy(0,Math.round(lastY-e.getY()));
                    }
                    lastY=e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (longPress!=null) ui.removeCallbacks(longPress);
                    if (!moved && !held && zone==0) playOrStop();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (longPress!=null) ui.removeCallbacks(longPress);
                    return true;
            }
            return true;
        }
    }

    private int dp(int pixels) {
        return (int)(pixels*getResources().getDisplayMetrics().density+0.5f);
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public void onFinishInputView(boolean finishingInput) {
        active=false; session++; queue.clear(); partialSerial++;
        speaking=false; utteranceId="";
        if (tts!=null) tts.stop();
        microphone.setSpeechPlayback(false);
        microphone.stop();
        super.onFinishInputView(finishingInput);
    }
    @Override public void onDestroy() {
        if (microphone!=null) microphone.stop();
        if (tts!=null) { tts.stop();tts.shutdown(); }
        if (translate!=null) translate.close();
        super.onDestroy();
    }
    private static final class Recognition {
        final String source,text; final long session;
        Recognition(String source,String text,long session) {
            this.source=source;this.text=text;this.session=session;
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
