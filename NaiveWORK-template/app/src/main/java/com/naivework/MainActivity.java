package com.naivework;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.content.Intent;
import android.net.Uri;
import android.widget.ImageButton;
import android.widget.ImageView;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import com.naivebasic.BasicInterpreter;
import com.naivebasic.BasicScreenView;

public class MainActivity extends Activity implements BasicInterpreter.ScreenProvider, SensorEventListener {
    private volatile float giroXValue=0f;
    private volatile float giroYValue=0f;

    private int lastKey=0;
    private volatile boolean programRunning=false;
    private volatile BasicInterpreter runningInterpreter=null;
    private volatile Thread runningThread=null;

    private volatile AudioTrack activePlayTrack=null;
    private volatile Thread activePlayThread=null;

    private RandomAccessFile activeMassiveFile=null;
    private int massiveWidth=0;
    private int massiveHeight=0;
    private String massiveName=null;

    private int touchX=0;
    private int touchY=0;
    private boolean touchDown=false;
    private int giro=0;
    private int screenMode=0;

    private BasicScreenView runScreen;
    private View aboutScreen;
    private String pendingInput="";

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private final Handler backHandler=new Handler();
    private boolean backHeld=false;
    private final Runnable longBack=new Runnable(){
        public void run(){
            if(backHeld && programRunning){
                backHeld=false;
                stopProgram();
            }
        }
    };

    public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.main);

        runScreen=(BasicScreenView)findViewById(R.id.run_screen);
        aboutScreen=findViewById(R.id.about_screen);

        ImageButton coffeeLink=(ImageButton)findViewById(R.id.coffee_link);
        coffeeLink.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://samlib.ru/z/zwerew_m_a/")));}
                catch(Exception ignored){}
            }
        });
        coffeeLink.setScaleType(ImageView.ScaleType.FIT_CENTER);

        findViewById(R.id.pda_link).setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://4pda.to/forum/index.php?showtopic=1125557")));}
                catch(Exception ignored){}
            }
        });
        runScreen.setFocusableInTouchMode(true);

        runScreen.setOnKeyListener(new View.OnKeyListener(){
            public boolean onKey(View v,int keyCode,KeyEvent event){
                if(event.getAction()==KeyEvent.ACTION_DOWN)
                    lastKey=spectrumKeyCode(keyCode);
                return false;
            }
        });
        runScreen.setOnTouchListener(new View.OnTouchListener(){
            public boolean onTouch(View v,MotionEvent event){
                touchX=runScreen.logicalX((int)event.getX());
                touchY=runScreen.logicalY((int)event.getY());
                touchDown=event.getAction()!=MotionEvent.ACTION_UP &&
                           event.getAction()!=MotionEvent.ACTION_CANCEL;
                return true;
            }
        });

        sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE);
        if(sensorManager!=null)
            accelerometer=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try{setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);}catch(Exception ignored){}

        runScreen.resetScreen();
        runScreen.requestFocus();

        startBundledProgram();
    }

    private String loadBundledProgram() throws Exception {
        InputStream in=getAssets().open("programs/program.bas");
        try{
            byte[] buf=new byte[4096];
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            int n;
            while((n=in.read(buf))>0) out.write(buf,0,n);
            return new String(out.toByteArray(),"UTF-8");
        }finally{
            in.close();
        }
    }

    private void startBundledProgram(){
        final String source;
        try{
            source=loadBundledProgram();
        }catch(Exception e){
            runScreen.printText("ERROR: "+e.getMessage());
            runScreen.flushDisplay();
            return;
        }

        lastKey=0;
        touchDown=false;
        screenMode=0;
        programRunning=true;
        runScreen.resetScreen();
        runScreen.requestFocus();

        Thread rt=new Thread(new Runnable(){
            public void run(){
                final BasicInterpreter bi=new BasicInterpreter(MainActivity.this);
                runningInterpreter=bi;
                final String result=bi.run(source);
                runOnUiThread(new Runnable(){
                    public void run(){
                        if(runningInterpreter==bi){
                            runningInterpreter=null;
                            runningThread=null;
                        }
                        programRunning=false;
                        if(result!=null && result.length()>0 && !runScreen.hasOutput(result))
                            runScreen.printText(result);
                        runScreen.invalidate();
                        showAbout();
                    }
                });
            }
        },"NaiveBASIC-RUN");
        runningThread=rt;
        rt.start();
    }

    private void stopProgram(){
        stopPlay();
        programRunning=false;
        BasicInterpreter bi=runningInterpreter;
        if(bi!=null) bi.requestStop();
        Thread rt=runningThread;
        if(rt!=null) rt.interrupt();
        closeMassiveFile();
    }

    public void onConfigurationChanged(Configuration c){
        super.onConfigurationChanged(c);
        if(runScreen!=null)runScreen.invalidate();
    }

    protected void onResume(){
        super.onResume();
        if(sensorManager!=null && accelerometer!=null)
            sensorManager.registerListener(this,accelerometer,SensorManager.SENSOR_DELAY_UI);
    }

    protected void onPause(){
        if(sensorManager!=null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()!=Sensor.TYPE_ACCELEROMETER)return;
        float x=e.values[0],y=e.values[1];
        if(Math.abs(x)>Math.abs(y))giro=x<0?0:2;
        else giro=y<0?1:3;
        giroXValue=y/9.81f;
        giroYValue=-x/9.81f;
        if(giroXValue>1f)giroXValue=1f;
        if(giroXValue<-1f)giroXValue=-1f;
        if(giroYValue>1f)giroYValue=1f;
        if(giroYValue<-1f)giroYValue=-1f;
    }
    public void onAccuracyChanged(Sensor s,int a){}

    public int getKey(){int k=lastKey;lastKey=0;return k;}
    public boolean isTouch(){return touchDown;}
    public int getTouchX(){return touchX;}
    public int getTouchY(){return touchY;}
    public int getScreenX(){return screenMode==1||screenMode==3?240:320;}
    public int getScreenY(){return screenMode==1||screenMode==3?320:240;}
    public int getGiro(){return giro;}
    public double giroX(){return giroXValue;}
    public double giroY(){return giroYValue;}

    public void setScreen(final int mode,final int charset)throws Exception{
        if(mode<0||mode>3)throw new Exception("Bad SCREEN mode");
        if(charset<0||charset>1)throw new Exception("Bad SCREEN charset");
        screenMode=mode;
        final CountDownLatch done=new CountDownLatch(1);
        runOnUiThread(new Runnable(){
            public void run(){
                try{
                    if(!programRunning)return;
                    int o;
                    if(mode==0)o=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    else if(mode==1)o=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    else if(mode==2)o=ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    else o=ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    setRequestedOrientation(o);
                    runScreen.setScreenMode(mode,charset==1);
                }finally{done.countDown();}
            }
        });
        try{done.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }

    public void setInk(int color){if(runScreen!=null)runScreen.setInkColor(color);}
    public void setPaper(int color){if(runScreen!=null)runScreen.setPaperColor(color);}
    public void tab(int count){if(runScreen!=null)runScreen.tab(count);}
    public void plot(int x,int y,int color){if(runScreen!=null)runScreen.plotPoint(x,y,color);}
    public void draw(int dx,int dy,int color)throws Exception{if(runScreen!=null)runScreen.draw(dx,dy,color);}
    public void drawLine(int x1,int y1,int x2,int y2)throws Exception{if(runScreen!=null)runScreen.drawLine(x1,y1,x2,y2);}
    public void drawLineTo(int x2,int y2)throws Exception{if(runScreen!=null)runScreen.drawLineTo(x2,y2);}
    public void circle(int xc,int yc,int rx,int ry,boolean filled)throws Exception{if(runScreen!=null)runScreen.circle(xc,yc,rx,ry,filled);}
    public void triangle(int x1,int y1,int x2,int y2,int x3,int y3,boolean filled)throws Exception{if(runScreen!=null)runScreen.triangle(x1,y1,x2,y2,x3,y3,filled);}
    public void paintArea(int x,int y)throws Exception{if(runScreen!=null)runScreen.paintArea(x,y);}
    public int point(int x,int y)throws Exception{if(runScreen==null)throw new Exception("POINT unavailable");return runScreen.point(x,y);}
    public void clearScreen(){
        if(runScreen==null)return;
        final CountDownLatch done=new CountDownLatch(1);
        runOnUiThread(new Runnable(){public void run(){try{runScreen.clearScreen();}finally{done.countDown();}}});
        try{done.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    public void scrollView(int dx,int dy){if(runScreen!=null)runScreen.scrollView(dx,dy);}
    public void rollView(int dx,int dy){if(runScreen!=null)runScreen.rollView(dx,dy);}
    public void flushDisplay(){if(runScreen!=null)runScreen.flushDisplay();}
    public void print(String s){if(runScreen!=null)runScreen.printText(s);}
    public void printAt(int row,int col,String s){if(runScreen!=null)runScreen.printAt(row,col,s);}

    private String massiveFileName(String raw)throws Exception{
        String name=raw==null?"":raw.trim();
        if(name.length()==0)throw new Exception("Bad NBM name");
        if(name.toLowerCase().endsWith(".nbm"))name=name.substring(0,name.length()-4);
        if(name.length()==0||name.length()>64||!name.matches("[A-Za-z0-9._-]+"))
            throw new Exception("Bad NBM name");
        return name+".nbm";
    }
    private void closeMassiveFile(){
        try{if(activeMassiveFile!=null)activeMassiveFile.close();}catch(Exception ignored){}
        activeMassiveFile=null;massiveWidth=0;massiveHeight=0;massiveName=null;
    }
    public boolean makeMassive(int x,int y,String rawName)throws Exception{
        String name=massiveFileName(rawName);
        File f=new File(getFilesDir(),name);
        closeMassiveFile();
        RandomAccessFile raf=new RandomAccessFile(f,"rw");
        raf.setLength((long)(x+1)*(long)(y+1));
        activeMassiveFile=raf;massiveWidth=x;massiveHeight=y;massiveName=name;
        return true;
    }
    public int openMassive(int x,int y,String rawName)throws Exception{
        String name=massiveFileName(rawName);
        File f=new File(getFilesDir(),name);
        long need=(long)(x+1)*(long)(y+1);
        if(!f.exists()||f.length()<need)return 0;
        closeMassiveFile();
        activeMassiveFile=new RandomAccessFile(f,"rw");
        massiveWidth=x;massiveHeight=y;massiveName=name;
        return 1;
    }
    public synchronized int peekMassive(int x,int y)throws Exception{
        if(activeMassiveFile==null)throw new Exception("No NBM is open");
        if(x<0||x>massiveWidth||y<0||y>massiveHeight)throw new Exception("NBM coordinate out of range");
        activeMassiveFile.seek((long)y*(long)(massiveWidth+1)+x);
        return activeMassiveFile.readUnsignedByte();
    }
    public synchronized void pokeMassive(int x,int y,int value)throws Exception{
        if(activeMassiveFile==null)throw new Exception("No NBM is open");
        if(x<0||x>massiveWidth||y<0||y>massiveHeight)throw new Exception("NBM coordinate out of range");
        if(value<0||value>255)throw new Exception("POKE byte 0..255");
        activeMassiveFile.seek((long)y*(long)(massiveWidth+1)+x);
        activeMassiveFile.write(value);
    }

    private int spectrumKeyCode(int k){
        switch(k){
            case KeyEvent.KEYCODE_0:return 48;case KeyEvent.KEYCODE_1:return 49;case KeyEvent.KEYCODE_2:return 50;
            case KeyEvent.KEYCODE_3:return 51;case KeyEvent.KEYCODE_4:return 52;case KeyEvent.KEYCODE_5:return 53;
            case KeyEvent.KEYCODE_6:return 54;case KeyEvent.KEYCODE_7:return 55;case KeyEvent.KEYCODE_8:return 56;
            case KeyEvent.KEYCODE_9:return 57;case KeyEvent.KEYCODE_DPAD_LEFT:return 8;case KeyEvent.KEYCODE_DPAD_RIGHT:return 9;
            case KeyEvent.KEYCODE_DPAD_UP:return 10;case KeyEvent.KEYCODE_DPAD_DOWN:return 11;
            case KeyEvent.KEYCODE_ENTER:return 13;case KeyEvent.KEYCODE_DEL:return 127;case KeyEvent.KEYCODE_SPACE:return 32;
            default:return k;
        }
    }

    private void playTone(double frequency,double duration,double volume)throws Exception{
        if(duration<=0||frequency<=0)return;
        ArrayList<NoteEvent> tone=new ArrayList<NoteEvent>();
        tone.add(new NoteEvent(0.0,duration,frequency,Math.max(0.0,Math.min(1.0,volume)),0));
        renderPlay(tone);
    }
    public void beep(double duration,double pitch)throws Exception{
        playTone(261.625565*Math.pow(2.0,pitch/12.0),duration,1.0);
    }
    public boolean isPlayPlaying(){
        Thread pt=activePlayThread;AudioTrack track=activePlayTrack;
        return (pt!=null&&pt.isAlive())||track!=null;
    }
    public void play(String[] patterns,final boolean wait)throws Exception{
        if(patterns==null||patterns.length==0)return;
        stopPlay();
        ArrayList<NoteEvent> all=new ArrayList<NoteEvent>();
        for(int i=0;i<patterns.length&&i<8;i++)parsePlayPattern(patterns[i],all,i);
        if(wait)renderPlay(all);
        else{
            final ArrayList<NoteEvent> asyncEvents=all;
            Thread pt=new Thread(new Runnable(){public void run(){try{renderPlay(asyncEvents);}catch(Exception ignored){}}},
                                 "NaiveBASIC-PLAY");
            activePlayThread=pt;
            pt.start();
        }
    }
    private static class NoteEvent{
        double start,duration,freq,volume;int instrument;
        NoteEvent(double s,double d,double f,double v,int ins){start=s;duration=d;freq=f;volume=v;instrument=ins;}
    }
    private void parsePlayPattern(String src,ArrayList<NoteEvent> out,int channel)throws Exception{
        if(src==null)return;
        String s=src.trim();
        int octave=4,length=5,tempo=120,volume=15,instrument=0;
        double time=0;
        java.util.ArrayList<Double> repeatStart=new java.util.ArrayList<Double>();
        java.util.ArrayList<Integer> repeatPos=new java.util.ArrayList<Integer>();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c==' '||c=='\t')continue;
            if(c=='!'){int j=s.indexOf('!',i+1);if(j<0)throw new Exception("PLAY comment");i=j;continue;}
            if(c=='('){repeatPos.add(Integer.valueOf(i));repeatStart.add(Double.valueOf(time));continue;}
            if(c==')'){
                if(!repeatPos.isEmpty()){
                    int open=repeatPos.remove(repeatPos.size()-1).intValue();
                    double st=repeatStart.remove(repeatStart.size()-1).doubleValue();
                    String sub=s.substring(open+1,i);
                    ArrayList<NoteEvent> tmp=new ArrayList<NoteEvent>();
                    parsePlayPattern(sub,tmp,channel);
                    double shift=time-st;
                    for(NoteEvent e:tmp)out.add(new NoteEvent(st+e.start+shift,e.duration,e.freq,e.volume,e.instrument));
                    time+=shift;
                }
                continue;
            }
            if(c=='T'||c=='t'){int[] nr=readNumber(s,i+1);if(nr[0]<0)throw new Exception("PLAY tempo expected");tempo=nr[0];if(tempo<60||tempo>240)throw new Exception("PLAY tempo 60..240");i=nr[1]-1;continue;}
            if(c=='O'||c=='o'){int[] nr=readNumber(s,i+1);if(nr[0]<0)throw new Exception("PLAY octave expected");octave=nr[0];if(octave<0||octave>8)throw new Exception("PLAY octave 0..8");i=nr[1]-1;continue;}
            if(c=='I'||c=='i'){int[] nr=readNumber(s,i+1);if(nr[0]<0)throw new Exception("PLAY instrument expected");instrument=nr[0];if(instrument<0||instrument>10)throw new Exception("PLAY instrument 0..10");i=nr[1]-1;continue;}
            if(c=='V'||c=='v'){int[] nr=readNumber(s,i+1);if(nr[0]<0)throw new Exception("PLAY volume expected");volume=nr[0];if(volume<0||volume>15)throw new Exception("PLAY volume 0..15");i=nr[1]-1;continue;}
            if(Character.isDigit(c)){int[] nr=readNumber(s,i);int n=nr[0];if(n<1||n>12)throw new Exception("PLAY note length 1..12");length=n;i=nr[1]-1;continue;}
            if(c=='&'){time+=playLength(length,tempo);continue;}
            if(c=='N')continue;
            if(c=='H')break;
            int accidental=0;
            if(c=='#'||c=='$'){accidental=c=='#'?1:-1;i++;if(i>=s.length())throw new Exception("PLAY note expected");c=s.charAt(i);}
            int semitone=noteSemitone(c);
            if(semitone<0)throw new Exception("Unknown PLAY note: "+c);
            int baseOct=Character.isUpperCase(c)?octave+1:octave;
            double pitch=12.0*(baseOct-4)+semitone+accidental;
            double freq=261.625565*Math.pow(2.0,pitch/12.0);
            double dur=playLength(length,tempo);
            out.add(new NoteEvent(time,dur,freq,volume/15.0,instrument));
            time+=dur;
        }
    }
    private int[] readNumber(String s,int p){
        if(p>=s.length()||!Character.isDigit(s.charAt(p)))return new int[]{-1,p};
        int n=0,i=p;while(i<s.length()&&Character.isDigit(s.charAt(i))){n=n*10+(s.charAt(i)-'0');i++;}
        return new int[]{n,i};
    }
    private int noteSemitone(char c){
        switch(Character.toLowerCase(c)){
            case 'c':return 0;case 'd':return 2;case 'e':return 4;case 'f':return 5;case 'g':return 7;case 'a':return 9;case 'b':return 11;
            default:return -1;
        }
    }
    private double playLength(int n,int tempo){
        double beats;
        switch(n){
            case 1:beats=.25;break;case 2:beats=.375;break;case 3:beats=.5;break;case 4:beats=.75;break;case 5:beats=1;break;case 6:beats=1.5;break;
            case 7:beats=2;break;case 8:beats=3;break;case 9:beats=4;break;case 10:beats=.3333333333;break;case 11:beats=.6666666667;break;
            case 12:beats=1.3333333333;break;default:beats=1;break;
        }
        return beats*60.0/tempo;
    }
    private double instrumentSample(int ins,double f,double t){
        double ph=2*Math.PI*f*t;
        switch(ins){
            case 1:return .70*Math.sin(ph)+.20*Math.sin(2*ph)+.10*Math.sin(3*ph);
            case 2:return Math.sin(ph)*Math.exp(-3.5*t)+.18*Math.sin(2*ph)*Math.exp(-5.0*t);
            case 3:return Math.sin(ph)+.16*Math.sin(2*ph)+.05*Math.sin(3*ph);
            case 4:return .50*Math.sin(ph)+.30*Math.sin(2*ph)+.14*Math.sin(3*ph)+.06*Math.sin(4*ph);
            case 5:return .94*Math.sin(ph)+.06*Math.sin(2*ph);
            case 6:return .60*Math.sin(ph)+.28*Math.sin(3*ph)+.12*Math.sin(5*ph);
            case 7:return .62*Math.sin(ph)+.22*Math.sin(2*ph)+.11*Math.sin(3*ph)+.05*Math.sin(4*ph);
            case 8:return Math.sin(ph)>=0?1.0:-1.0;
            case 9:{double x=(f*t)-Math.floor(f*t);return 2.0*x-1.0;}
            case 10:{long n=(long)(t*22050.0)+((long)(f*1000.0)*31);n=(n<<13)^n;return 1.0-((n*(n*n*15731L+789221L)+1376312589L)&0x7fffffff)/1073741824.0;}
            default:return Math.sin(ph);
        }
    }
    private void renderPlay(ArrayList<NoteEvent> events)throws Exception{
        if(events.isEmpty())return;
        final int rate=22050;
        double end=0;for(NoteEvent e:events)end=Math.max(end,e.start+e.duration);
        int count=(int)Math.ceil(end*rate)+1;if(count<=1)return;
        short[] pcm=new short[count];
        for(NoteEvent e:events){
            int a=(int)(e.start*rate),b=Math.min(count,(int)((e.start+e.duration)*rate));
            for(int i=a;i<b;i++){
                double t=(i-a)/(double)rate;
                double env=Math.min(1.0,t*80.0)*Math.min(1.0,(e.duration-t)*80.0);
                double sample=instrumentSample(e.instrument,e.freq,t)*e.volume*.35*env;
                int v=pcm[i]+(int)(sample*32767);
                pcm[i]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,v));
            }
        }
        AudioTrack track=new AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,pcm.length*2,AudioTrack.MODE_STATIC);
        activePlayTrack=track;
        try{
            track.write(pcm,0,pcm.length);track.play();
            long waitMs=(long)(end*1000)+100;Thread.sleep(waitMs);
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{
            try{track.stop();}catch(Exception ignored){}
            try{track.release();}catch(Exception ignored){}
            if(activePlayTrack==track)activePlayTrack=null;
            if(activePlayThread==Thread.currentThread())activePlayThread=null;
        }
    }
    public void stopPlay(){
        Thread pt=activePlayThread;if(pt!=null)pt.interrupt();
        AudioTrack track=activePlayTrack;
        if(track!=null){try{track.stop();}catch(Exception ignored){}}
    }

    public String input(final String prompt){
        final Object lock=this;
        pendingInput="";
        runOnUiThread(new Runnable(){
            public void run(){
                final android.widget.EditText field=new android.widget.EditText(MainActivity.this);
                field.setSingleLine(true);
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("INPUT").setMessage(prompt).setView(field)
                    .setPositiveButton("OK",new android.content.DialogInterface.OnClickListener(){
                        public void onClick(android.content.DialogInterface d,int w){
                            synchronized(lock){pendingInput=field.getText().toString();lock.notify();}
                        }})
                    .setNegativeButton("CANCEL",new android.content.DialogInterface.OnClickListener(){
                        public void onClick(android.content.DialogInterface d,int w){
                            synchronized(lock){pendingInput="";lock.notify();}
                        }})
                    .setOnCancelListener(new android.content.DialogInterface.OnCancelListener(){
                        public void onCancel(android.content.DialogInterface d){synchronized(lock){lock.notify();}}
                    }).show();
            }
        });
        synchronized(lock){try{lock.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        return pendingInput;
    }

    public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_DOWN && programRunning){
                if(event.getRepeatCount()==0){
                    backHeld=true;backHandler.removeCallbacks(longBack);backHandler.postDelayed(longBack,5000);
                }
                return true;
            }
            if(event.getAction()==KeyEvent.ACTION_UP && programRunning){
                backHeld=false;backHandler.removeCallbacks(longBack);return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showAbout(){
        if(runScreen!=null)runScreen.setVisibility(View.GONE);
        if(aboutScreen!=null)aboutScreen.setVisibility(View.VISIBLE);
    }

    public void onBackPressed(){
        if(aboutScreen!=null && aboutScreen.getVisibility()==View.VISIBLE){
            finish();
            return;
        }
        if(programRunning)return;
        super.onBackPressed();
    }

    protected void onDestroy(){
        stopProgram();
        super.onDestroy();
    }
}
