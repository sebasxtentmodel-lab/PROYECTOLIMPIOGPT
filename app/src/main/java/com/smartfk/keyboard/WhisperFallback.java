package com.smartfk.keyboard;

import android.content.Context;
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Real offline speech decoder on a dedicated worker.
 * Microphone AudioRecord NEVER waits for Sherpa/Whisper inference.
 * A 0.65-second speech pause ends a segment; long speech is processed
 * every ~6 seconds without closing or reopening the microphone.
 * Inference and capture are separated by a bounded two-segment queue.
 */
final class WhisperFallback {
    private static final int RATE=16000;
    private static final int FRAME=RATE/10; // 100 ms RMS windows
    private static final int MIN_SPEECH=RATE*3/10; // allow short replies: evet, hayır, sí, no
    private static final int END_SILENCE=RATE*7/10;
    private static final int PRE_ROLL=RATE/4;
    private static final int MAX_SEGMENT=RATE*6;
    private static final int QUIET_RMS=80;
    private static final String WHISPER_ASSET="whisper/ggml-base-q5_1.bin";

    private final Context context;
    private final ContinuousVoice.Events events;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"smartfk-speech-worker");
        t.setPriority(Thread.NORM_PRIORITY-1);
        return t;
    });
    private final ArrayBlockingQueue<Segment> segments=new ArrayBlockingQueue<>(2);
    private final short[] buffer=new short[MAX_SEGMENT];
    private final String configuredSource;
    private final LongSupplier latestVoskResult;
    private final boolean specializedTurkish;
    private int used, speechSamples, silenceSamples;
    private volatile boolean closed, processing, specializedWorking=true;
    private TurkishSherpa turkishEngine;
    private long modelHandle;

    private static final class Segment {
        final short[] pcm;
        final long voskAt;
        Segment(short[] pcm,long voskAt){this.pcm=pcm;this.voskAt=voskAt;}
    }

    WhisperFallback(Context context,ContinuousVoice.Events events,String configuredSource,
                    LongSupplier latestVoskResult){
        this.context=context.getApplicationContext();
        this.events=events;
        this.configuredSource=configuredSource;
        this.latestVoskResult=latestVoskResult;
        specializedTurkish=!"es".equals(configuredSource);
    }
    boolean prefersTurkishFinals(){return specializedTurkish && specializedWorking;}

    void feed(short[] audio,int count,long lastVoskAt){
        if(closed || audio==null || count<=0)return;
        int n=Math.min(count,audio.length);
        for(int start=0;start<n;start+=FRAME){
            int take=Math.min(FRAME,n-start);
            long sum=0;
            for(int i=start;i<start+take;i++)sum+=Math.abs((int)audio[i]);
            int average=(int)(sum/take);
            boolean voiced=average>=QUIET_RMS;
            if(!voiced && speechSamples==0){
                // Keep at most 250ms before first speech. Never transcribe
                // endless silence or fill a long window with background noise.
                if(used+take>PRE_ROLL) used=0;
                System.arraycopy(audio,start,buffer,used,take);
                used+=take;
                continue;
            }
            if(used+take>buffer.length){
                flush(lastVoskAt);
            }
            System.arraycopy(audio,start,buffer,used,take);
            used+=take;
            if(voiced){
                speechSamples+=take;
                silenceSamples=0;
            }else if(speechSamples>0){
                silenceSamples+=take;
            }
            if((speechSamples>=MIN_SPEECH && silenceSamples>=END_SILENCE)
                    || used>=MAX_SEGMENT){
                flush(lastVoskAt);
            }
        }
    }

    private void flush(long lastVoskAt){
        if(speechSamples>=MIN_SPEECH && !closed){
            Segment item=new Segment(Arrays.copyOf(buffer,used),lastVoskAt);
            if(!segments.offer(item)){
                segments.poll(); // newest utterances are more important than stale ones
                segments.offer(item);
                events.error("Motor de voz atrasado: se descartó un segmento antiguo");
            }
            schedule();
        }
        used=0;speechSamples=0;silenceSamples=0;
    }

    private synchronized void schedule(){
        if(processing || closed)return;
        processing=true;
        decoder.execute(this::drain);
    }
    private void drain(){
        try{
            Segment item;
            while(!closed && (item=segments.poll())!=null){
                transcribe(item);
            }
        }finally{
            synchronized(this){
                processing=false;
                if(!closed && !segments.isEmpty())schedule();
            }
        }
    }
    private void transcribe(Segment segment){
        try{
            if(closed)return;
            String source,words;
            if(specializedTurkish && specializedWorking){
                if(turkishEngine==null)turkishEngine=new TurkishSherpa(context);
                // The ONNX Whisper backend needs at least 1 s even for a
                // 300-ms word. Pad with silence, never throw away short replies.
                short[] pcm=segment.pcm.length<RATE?
                    Arrays.copyOf(segment.pcm,RATE):segment.pcm;
                words=turkishEngine.transcribe(pcm);
                source="tr";
            }else{
                String result=runWhisperCpp(segment.pcm);
                int sep=result.indexOf('\n');
                String detected=sep<0?"":result.substring(0,sep).trim();
                words=sep<0?result.trim():result.substring(sep+1).trim();
                source="es".equals(detected)?"es":
                        "tr".equals(detected)?"tr":configuredSource;
                if(latestVoskResult.getAsLong()>segment.voskAt)return;
            }
            if(closed||words.isEmpty()||!("tr".equals(source)||"es".equals(source)))return;
            if(words.matches("(?i).*(\\[(music|noise|silence)\\]|al\\u0131nt\\u0131|m\\u00fczik).*"))
                return;
            events.finalWords(source,words);
        }catch(Throwable error){
            if(specializedTurkish)specializedWorking=false;
            android.util.Log.e("SmartFk","Offline ASR error",error);
            events.error("Motor de voz: "+error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }
    private String runWhisperCpp(short[] pcm)throws Exception{
        if(modelHandle==0){
            File target=new File(context.getNoBackupFilesDir(),"smartfk-whisper-base-q5_1.bin");
            if(!target.isFile()||target.length()<50000000L){
                File partial=new File(target.getAbsolutePath()+".partial");
                try(InputStream in=context.getAssets().open(WHISPER_ASSET);
                    FileOutputStream out=new FileOutputStream(partial)){
                    byte[] buf=new byte[32768];int n;
                    while((n=in.read(buf))!=-1)out.write(buf,0,n);
                }
                if(!partial.renameTo(target))
                    throw new IllegalStateException("Error guardando Whisper");
            }
            modelHandle=WhisperCpp.nativeOpen(target.getAbsolutePath());
            if(modelHandle==0)throw new IllegalStateException("No cargó Whisper");
        }
        float[] input=new float[pcm.length];
        for(int i=0;i<pcm.length;i++)input[i]=pcm[i]/32768f;
        String[] languages="auto".equals(configuredSource)?
            new String[]{"tr","es"}:new String[]{configuredSource};
        byte[] result=WhisperCpp.nativeTranscribe(modelHandle,input,languages,false,null,1,2);
        return result==null?"":new String(result,StandardCharsets.UTF_8);
    }
    void reset(){
        used=0;speechSamples=0;silenceSamples=0;segments.clear();
    }
    void close(){
        closed=true;
        segments.clear();
        decoder.execute(()->{
            if(turkishEngine!=null){turkishEngine.close();turkishEngine=null;}
            if(modelHandle!=0){WhisperCpp.nativeClose(modelHandle);modelHandle=0;}
        });
        decoder.shutdown();
    }
}
