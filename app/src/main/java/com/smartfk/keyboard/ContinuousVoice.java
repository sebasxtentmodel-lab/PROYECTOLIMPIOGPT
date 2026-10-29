package com.smartfk.keyboard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Microphone is a producer ONLY; recognition is a completely separate consumer.
 * Silences never stop/restart the AudioRecord session. The queue is bounded so a
 * slow neural backend cannot exhaust RAM or stall the microphone.
 */
final class ContinuousVoice {
    interface Events {
        void status(String message);
        void audioLevel(int averagePcm, boolean androidSilenced);
        void partial(String source, String text);
        void finalWords(String source, String text);
        void error(String message);
    }
    private static final int SAMPLE_RATE=16000;
    private static final int FRAMES=1600;
    private static final int QUEUE_CAPACITY=120; // 12 seconds of PCM, ~375 KiB
    private final Context context;
    private final Events events;
    private final ArrayBlockingQueue<short[]> audioQueue=new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile AudioRecord microphone;
    private volatile boolean running, suppressRecognition, resetRecognizer;
    private volatile long lastVoskAt;
    private volatile String activeMode="";
    private Thread captureThread, recognizerThread;
    private boolean restartPending;
    private Thread pendingRestarter;
    private String previousLanguage="tr", lastFinalText="";
    private long lastFinalAt,lastEmittedEndMs;
    private int droppedFrames;

    ContinuousVoice(Context context,Events events){
        this.context=context.getApplicationContext();
        this.events=events;
    }
    private String currentMode() {
        String mode=context.getSharedPreferences("smartfk",Context.MODE_PRIVATE)
            .getString("source_language","tr");
        return "es".equals(mode)||"auto".equals(mode) ? mode:"tr";
    }

    synchronized void start() {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                !=PackageManager.PERMISSION_GRANTED) {
            events.error("Falta el permiso del micrófono");
            return;
        }
        if(running){
            if(!currentMode().equals(activeMode)){
                restartPending=true;
                stopCaptureOnly();
                awaitOldSession();
            }
            return;
        }
        if((captureThread!=null && captureThread.isAlive())||
           (recognizerThread!=null && recognizerThread.isAlive())){
            restartPending=true;
            awaitOldSession();
            return;
        }
        activeMode=currentMode();
        audioQueue.clear();
        droppedFrames=0;
        lastVoskAt=0;
        resetRecognizer=false;
        running=true;
        recognizerThread=new Thread(()->decode(activeMode),"smartfk-asr");
        captureThread=new Thread(this::capture,"smartfk-microphone");
        recognizerThread.start();
        captureThread.start();
    }

    synchronized void stop() {
        restartPending=false;
        stopCaptureOnly();
    }
    /**
     * A rapid keyboard switch may call start() while the old decoder is still
     * closing. Wait off the UI thread and restart only if the IME remains active.
     */
    private synchronized void awaitOldSession(){
        if(pendingRestarter!=null && pendingRestarter.isAlive())return;
        final Thread oldCapture=captureThread, oldDecoder=recognizerThread;
        pendingRestarter=new Thread(()->{
            try{
                if(oldCapture!=null)oldCapture.join();
                if(oldDecoder!=null)oldDecoder.join();
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return;
            }
            synchronized(ContinuousVoice.this){
                if(restartPending){
                    restartPending=false;
                    start();
                }
            }
        },"smartfk-voice-restart");
        pendingRestarter.start();
    }
    private void stopCaptureOnly(){
        running=false;
        audioQueue.clear(); // Never delay the next IME session draining stale audio
        AudioRecord r=microphone;
        if(r!=null)try{r.stop();}catch(Exception ignored){}
    }

    void setSpeechPlayback(boolean playing){
        suppressRecognition=playing;
        if(playing)resetRecognizer=true;
    }
    private void capture(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord r=null;
        try{
            int minimum=AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("16 kHz PCM no disponible");
            int size=Math.max(minimum*4,FRAMES*8);
            // Some Android Go vendor audio HALs do not expose VOICE_RECOGNITION
            // in 16 kHz PCM. A normal MIC fallback is better than a silent IME.
            try {
                r=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
            } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                android.util.Log.w("SmartFk","VOICE_RECOGNITION source unavailable",ex);
            }
            if(r==null || r.getState()!=AudioRecord.STATE_INITIALIZED) {
                if(r!=null){r.release();r=null;}
                r=new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
                events.status("Micrófono Android normal habilitado (compatibilidad).");
            }
            if(r.getState()!=AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("No se inicializó ningún origen de micrófono");
            microphone=r;
            if(!running)return;
            r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Android no permitió grabar");
            events.status("Micrófono activo. Preparando reconocimiento offline…");
            long lastReport=0;
            short[] buffer=new short[FRAMES];
            while(running){
                int length=r.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if(length<0){
                    if(running)throw new IllegalStateException("AudioRecord read: "+length);
                    break;
                }
                if(length==0)continue;
                long now=SystemClock.elapsedRealtime();
                if(now-lastReport>1250){
                    long sum=0;
                    for(int i=0;i<length;i++)sum+=Math.abs((int)buffer[i]);
                    android.media.AudioRecordingConfiguration config=r.getActiveRecordingConfiguration();
                    events.audioLevel((int)(sum/length),config!=null && config.isClientSilenced());
                    lastReport=now;
                }
                if(suppressRecognition)continue;
                short[] copy=new short[length];
                System.arraycopy(buffer,0,copy,0,length);
                if(!audioQueue.offer(copy)){
                    // Drop oldest audio rather than blocking the microphone thread.
                    audioQueue.poll();
                    audioQueue.offer(copy);
                    droppedFrames++;
                    if(droppedFrames==1 || droppedFrames%150==0)
                        events.error("Reconocimiento atrasado: "+(droppedFrames/10.0f)
                            +" s de audio descartado. Cierra apps pesadas.");
                }
            }
        }catch(Throwable error){
            if(running)events.error("Micrófono: "+error.getClass().getSimpleName()
                +": "+error.getMessage());
        }finally{
            if(r!=null){
                try{if(r.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)r.stop();}
                catch(Exception ignored){}
                r.release();
            }
            microphone=null;
            running=false;
        }
    }

    private void decode(String mode){
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        WhisperFallback whisper=null;
        try{
            events.status("Abriendo modelos Vosk locales…");
            boolean useTr=!"es".equals(mode),useEs=!"tr".equals(mode);
            File trDir=useTr?materialize("models/tr"):null;
            File esDir=useEs?materialize("models/es"):null;
            try(Model trModel=useTr?new Model(trDir.getAbsolutePath()):null;
                Model esModel=useEs?new Model(esDir.getAbsolutePath()):null;
                Recognizer tr=useTr?new Recognizer(trModel,SAMPLE_RATE):null;
                Recognizer es=useEs?new Recognizer(esModel,SAMPLE_RATE):null){
                if(tr!=null)tr.setWords(true);
                if(es!=null)es.setWords(true);
                whisper=new WhisperFallback(context,events,mode,()->lastVoskAt);
                events.status("");
                long lastPreview=0;
                while(running || !audioQueue.isEmpty()){
                    short[] chunk=audioQueue.poll(200,TimeUnit.MILLISECONDS);
                    if(chunk==null)continue;
                    if(suppressRecognition){audioQueue.clear();continue;}
                    if(resetRecognizer){
                        if(tr!=null)tr.reset();
                        if(es!=null)es.reset();
                        whisper.reset();
                        resetRecognizer=false;
                    }
                    // Whisper gets its own background worker and never blocks this decoder.
                    whisper.feed(chunk,chunk.length,lastVoskAt);
                    boolean trEnd=tr!=null&&tr.acceptWaveForm(chunk,chunk.length);
                    boolean esEnd=es!=null&&es.acceptWaveForm(chunk,chunk.length);
                    if(trEnd||esEnd){
                        Candidate candidate=select(
                            candidate(trEnd?tr.getResult():"","tr"),
                            candidate(esEnd?es.getResult():"","es"));
                        // When Turkish-specific Sherpa ASR is available, let it
                        // decide final Turkish words. Vosk remains a fast preview
                        // source, never adding inaccurate duplicate final text.
                        if (!("tr".equals(candidate.language) &&
                                whisper.prefersTurkishFinals())) {
                            emitFinal(candidate,mode);
                        }
                    }else if(SystemClock.elapsedRealtime()-lastPreview>=350){
                        Candidate candidate=select(
                            candidate(tr==null?"":tr.getPartialResult(),"tr"),
                            candidate(es==null?"":es.getPartialResult(),"es"));
                        if(!candidate.text.isEmpty()){
                            lastVoskAt=SystemClock.elapsedRealtime();
                            events.partial(candidate.language,candidate.text);
                        }
                        lastPreview=SystemClock.elapsedRealtime();
                    }
                }
                // Deliver the last unfinished utterance on clean stop, only if still visible.
                if(running){
                    Candidate last=select(
                        candidate(tr==null?"":tr.getFinalResult(),"tr"),
                        candidate(es==null?"":es.getFinalResult(),"es"));
                    emitFinal(last,mode);
                }
            }
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }catch(Throwable error){
            if(running)events.error("Vosk: "+error.getClass().getSimpleName()
                +": "+error.getMessage());
        }finally{
            if(whisper!=null)whisper.close();
            synchronized(this){recognizerThread=null;}
        }
    }

    private void emitFinal(Candidate candidate,String mode){
        if(candidate.text.isEmpty())return;
        long now=SystemClock.elapsedRealtime();
        String normalized=candidate.text.toLowerCase(Locale.ROOT).trim();
        if(normalized.equals(lastFinalText)&&now-lastFinalAt<2600)return;
        if("auto".equals(mode)&&now-lastEmittedEndMs<850)return;
        lastFinalText=normalized;
        lastFinalAt=now;
        previousLanguage=candidate.language;
        lastVoskAt=now;
        lastEmittedEndMs=now;
        events.finalWords(candidate.language,candidate.text);
    }

    private Candidate select(Candidate a,Candidate b){
        if(a.text.isEmpty())return b;
        if(b.text.isEmpty())return a;
        double sa=a.score+(a.language.equals(previousLanguage)?0.035:0);
        double sb=b.score+(b.language.equals(previousLanguage)?0.035:0);
        return sa>=sb?a:b;
    }
    private Candidate candidate(String json,String lang){
        try{
            JSONObject object=new JSONObject(json);
            String words=object.optString("text",object.optString("partial","")).trim();
            if(words.isEmpty())return new Candidate(lang,"",0);
            JSONArray result=object.optJSONArray("result");
            double confidence=0.5;
            if(result!=null && result.length()>0){
                double sum=0;
                for(int i=0;i<result.length();i++)
                    sum+=result.getJSONObject(i).optDouble("conf",0.4);
                confidence=sum/result.length();
            }
            return new Candidate(lang,words,confidence+Math.min(0.12,words.length()/450.0));
        }catch(Exception ignored){return new Candidate(lang,"",0);}
    }

    private File materialize(String assetPath)throws Exception{
        File dir=new File(context.getNoBackupFilesDir(),assetPath);
        File ready=new File(dir,".ready");
        if(ready.isFile())return dir;
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("No creó carpeta Vosk");
        String[] children=context.getAssets().list(assetPath);
        if(children==null||children.length==0)
            throw new IllegalStateException("Falta modelo de voz "+assetPath);
        copyAssetDir(assetPath,dir);
        if(!ready.createNewFile())throw new IllegalStateException("Copia incompleta de "+assetPath);
        return dir;
    }
    private void copyAssetDir(String asset,File destination)throws Exception{
        String[] children=context.getAssets().list(asset);
        if(children==null)throw new IllegalStateException("Asset ilegible: "+asset);
        if(children.length==0){
            File parent=destination.getParentFile();
            if(parent!=null&&!parent.exists()&&!parent.mkdirs())
                throw new IllegalStateException("Sin espacio para "+asset);
            try(InputStream in=context.getAssets().open(asset);
                FileOutputStream out=new FileOutputStream(destination)){
                byte[] bytes=new byte[32768];
                int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            }
        }else{
            if(!destination.exists()&&!destination.mkdirs())
                throw new IllegalStateException("Sin espacio para "+asset);
            for(String child:children)
                copyAssetDir(asset+"/"+child,new File(destination,child));
        }
    }
    private static final class Candidate{
        final String language,text;
        final double score;
        Candidate(String lang,String text,double confidence){
            this.language=lang;this.text=text;this.score=confidence;
        }
    }
}
