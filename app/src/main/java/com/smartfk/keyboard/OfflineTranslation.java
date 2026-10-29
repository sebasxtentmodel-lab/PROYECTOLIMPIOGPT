package com.smartfk.keyboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.wasimaster.wmkeyboard.core.translate.BergamotNative;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real, fully local neural translator from WM Keyboard's Firefox Bergamot backend.
 * Model assets are bundled: never downloads or invokes ML Kit.
 * Only one route (two models) is resident to fit a 4 GB Android device.
 */
final class OfflineTranslation {
    interface Callback { void onSuccess(String value); void onFailure(String reason); }
    private final Context app;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"smartfk-bergamot");t.setPriority(Thread.NORM_PRIORITY-1);return t;
    });
    private final Handler main=new Handler(Looper.getMainLooper());
    private long service, first, second;
    private String loadedRoute="";
    private volatile boolean closed;
    private volatile boolean libraryOk;

    OfflineTranslation(Context context) {
        app=context.getApplicationContext();
        try {
            Class.forName("com.wasimaster.wmkeyboard.core.translate.BergamotNative");
            libraryOk=true;
        } catch (Throwable e) {
            android.util.Log.e("SmartFk","Bergamot library could not load",e);
        }
    }
    void prepare(Callback cb) {
        prepareSource("tr",cb);
        prepareSource("es",cb);
    }
    void prepareSource(String src,Callback cb) {
        if (!libraryOk) { error(cb,"Motor Bergamot no cargó"); return; }
        worker.execute(()->{
            try {
                if(closed) return;
                setup(src);
                ok(cb,src.equals("tr")?"Modelo neuronal turco → español listo":
                    "Modelo neuronal español → turco listo");
            }catch(Throwable ex){error(cb,describe(ex));}
        });
    }
    void translate(String source,String phrase,Callback cb) {
        if(phrase==null || phrase.trim().isEmpty())return;
        if(!libraryOk) { error(cb,"Falta biblioteca nativa Bergamot");return; }
        final String requested="es".equals(source)?"es":"tr";
        worker.execute(()->{
            if(closed)return;
            try{
                setup(requested);
                byte[][] input={phrase.getBytes(StandardCharsets.UTF_8)};
                byte[][] result=BergamotNative.nativePivot(service,first,second,input);
                if(result==null||result.length==0||result[0]==null)
                    throw new IllegalStateException("Bergamot devolvió resultado vacío");
                String out=new String(result[0],StandardCharsets.UTF_8).trim();
                if(out.isEmpty())throw new IllegalStateException("Modelo sin salida");
                ok(cb,out);
            }catch(Throwable e){error(cb,describe(e));}
        });
    }
    private void setup(String source) throws Exception {
        if(source.equals(loadedRoute)&&first!=0&&second!=0)return;
        releaseRoute();
        if(service==0)service=BergamotNative.nativeNewService(128);
        if(service==0)throw new IllegalStateException("No pudo abrir servicio Bergamot");
        String firstKey=source.equals("tr")?"tr-en":"es-en";
        String secondKey=source.equals("tr")?"en-es":"en-tr";
        first=load(firstKey);
        second=load(secondKey);
        if(first==0||second==0)throw new IllegalStateException("Modelo Bergamot vacío");
        loadedRoute=source;
    }
    private long load(String lang) throws Exception {
        File dir=new File(app.getNoBackupFilesDir(),"smartfk-models/"+lang);
        File model=copy(lang,dir,"model.bin");
        File lex=copy(lang,dir,"lex.bin");
        File vocab=copy(lang,dir,"vocab.spm");
        return BergamotNative.nativeLoadModel(model.getAbsolutePath(),
            lex.getAbsolutePath(),new String[]{vocab.getAbsolutePath()},false);
    }
    private File copy(String lang,File dir,String name) throws Exception {
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Sin espacio: "+lang);
        String asset="bergamot/"+lang+"/"+name;
        File dest=new File(dir,name);
        // All assets originate from SHA-256 verified upstream build packages.
        long size=app.getAssets().openFd(asset).getLength();
        if(dest.isFile()&&dest.length()==size)return dest;
        File temp=new File(dir,name+".partial");
        try(InputStream in=app.getAssets().open(asset);
            FileOutputStream out=new FileOutputStream(temp)){
            byte[] buffer=new byte[32768];
            int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        if(temp.length()!=size||!temp.renameTo(dest))
            throw new IllegalStateException("Error al copiar modelo "+asset);
        return dest;
    }
    private void releaseRoute() {
        if(first!=0){BergamotNative.nativeFreeModel(first);first=0;}
        if(second!=0){BergamotNative.nativeFreeModel(second);second=0;}
        loadedRoute="";
    }
    private void ok(Callback cb,String msg){main.post(()->{if(!closed)cb.onSuccess(msg);});}
    private void error(Callback cb,String msg){main.post(()->{if(!closed)cb.onFailure(msg);});}
    private String describe(Throwable e){return e.getClass().getSimpleName()+": "+e.getMessage();}
    void close(){
        closed=true;
        worker.execute(()->{
            try{releaseRoute();
                if(service!=0){BergamotNative.nativeFreeService(service);service=0;}
            }catch(Throwable ignored){}
        });
        worker.shutdown();
    }
}
