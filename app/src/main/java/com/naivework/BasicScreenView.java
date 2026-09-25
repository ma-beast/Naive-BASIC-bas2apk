package com.naivework;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class BasicScreenView extends View {
    private final Paint paint = new Paint();
    private Bitmap bitmap;

    private int mode=0;
    private boolean compact=false;
    private int frameWidth=320, frameHeight=240;
    private int cols=40, rows=30, cellW=8, cellH=8;
    private int cursorX=0, cursorY=0;
    // Visible viewport origin inside the cyclic screen bitmap.
    private int viewX=0, viewY=0;
    private int plotX=0, plotY=0;
    private int inkColor=255, paperColor=0;

    // Frame presentation is driven by Android's display timing when Choreographer
    // exists (API 16+). On older Android, use a small UI-timer fallback.
    private volatile long dirtyVersion=1;
    private volatile long presentedVersion=0;
    private Object choreographer;
    private Method postFrameCallbackMethod;
    private Method removeFrameCallbackMethod;
    private Object frameCallback;
    private Handler frameHandler;
    private Runnable fallbackFrameRunnable;
    private boolean framePacerRunning=false;

    // Bitmap is the screen. No persistent second framebuffer exists.
    private byte[] colorIndexBuffer = new byte[320*320];
    private int[] cellPixels = new int[64];
    private int[] fillQueueX = new int[320*320];
    private int[] fillQueueY = new int[320*320];

    public BasicScreenView(Context c, AttributeSet a){
        super(c,a);
        paint.setAntiAlias(false);
        paint.setFilterBitmap(false);
        setFocusable(true);
        configureFrame();
        clear();
    }

    public BasicScreenView(Context c){this(c,null);}

    private int viewportW(){ return (mode==1||mode==3)?240:320; }
    private int viewportH(){ return (mode==1||mode==3)?320:240; }
    private float scaleX(){ return getWidth()/(float)frameWidth; }
    private float scaleY(){ return getHeight()/(float)frameHeight; }
    private int clamp(int v,int a,int b){ return v<a?a:(v>b?b:v); }

    private void refresh(){
        dirtyVersion++;
    }

    public void flushDisplay(){
        // Explicit display barrier used by PAUSE/END and other control paths.
        if(Looper.myLooper()==Looper.getMainLooper()) invalidate();
        else postInvalidate();
    }

    private void startFramePacer(){
        if(framePacerRunning) return;
        framePacerRunning=true;
        if(startChoreographerPacer()) return;

        // Android 2.3–4.x have no Choreographer. Keep compatibility with a
        // lightweight timer; the old platform cannot provide true VSync callbacks.
        frameHandler=new Handler(Looper.getMainLooper());
        fallbackFrameRunnable=new Runnable(){
            public void run(){
                if(!framePacerRunning) return;
                presentIfDirty();
                frameHandler.postDelayed(this,16);
            }
        };
        frameHandler.post(fallbackFrameRunnable);
    }

    private boolean startChoreographerPacer(){
        if(android.os.Build.VERSION.SDK_INT < 16) return false;
        try{
            final Class<?> chClass=Class.forName("android.view.Choreographer");
            final Class<?> cbClass=Class.forName("android.view.Choreographer$FrameCallback");
            Method getInstance=chClass.getMethod("getInstance");
            choreographer=getInstance.invoke(null);
            postFrameCallbackMethod=chClass.getMethod("postFrameCallback",cbClass);
            removeFrameCallbackMethod=chClass.getMethod("removeFrameCallback",cbClass);
            final BasicScreenView self=this;
            frameCallback=Proxy.newProxyInstance(cbClass.getClassLoader(),new Class[]{cbClass},new InvocationHandler(){
                public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
                    if("doFrame".equals(method.getName())){
                        self.presentIfDirty();
                        if(self.framePacerRunning) postNextFrame();
                    }
                    return null;
                }
            });
            postNextFrame();
            return true;
        }catch(Throwable ignored){
            choreographer=null; postFrameCallbackMethod=null; removeFrameCallbackMethod=null; frameCallback=null;
            return false;
        }
    }

    private void postNextFrame(){
        if(!framePacerRunning || choreographer==null || postFrameCallbackMethod==null || frameCallback==null) return;
        try{ postFrameCallbackMethod.invoke(choreographer,frameCallback); }catch(Throwable ignored){}
    }

    private void stopFramePacer(){
        framePacerRunning=false;
        if(frameHandler!=null && fallbackFrameRunnable!=null) frameHandler.removeCallbacks(fallbackFrameRunnable);
        if(choreographer!=null && removeFrameCallbackMethod!=null && frameCallback!=null){
            try{ removeFrameCallbackMethod.invoke(choreographer,frameCallback); }catch(Throwable ignored){}
        }
    }

    private void presentIfDirty(){
        if(dirtyVersion!=presentedVersion){
            invalidate();
        }
    }

    private void configureFrame(){
        frameWidth=viewportW();
        frameHeight=viewportH();
        updateGrid();
        if(bitmap!=null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap=Bitmap.createBitmap(frameWidth,frameHeight,Bitmap.Config.ARGB_8888);
        colorIndexBuffer=new byte[320*320];
    }

    private void updateGrid(){
        if(mode==1||mode==3){
            cols=compact?60:30;
            rows=40;
        }else{
            cols=compact?80:40;
            rows=30;
        }
        cellW=compact?4:8;
        cellH=8;
    }

    public void resetScreen(){ setScreenMode(0,compact); }
    public void setScreenMode(int m){ setScreenMode(m,compact); }

    public synchronized void setScreenMode(int m, boolean c){
        mode=m;
        compact=c;
        configureFrame();
        cursorX=0; cursorY=0; plotX=0; plotY=0; viewX=0; viewY=0;
        clear();
    }

    public synchronized void setCompact(boolean on){
        compact=on;
        configureFrame();
        cursorX=0; cursorY=0; plotX=0; plotY=0; viewX=0; viewY=0;
        clear();
    }

    public synchronized void clear(){
        bitmap.eraseColor(palette(paperColor));
        java.util.Arrays.fill(colorIndexBuffer,(byte)(paperColor&255));
        cursorX=0; cursorY=0; plotX=0; plotY=0; viewX=0; viewY=0;
        refresh();
    }

    public synchronized void clearScreen(){ clear(); }

    public synchronized void setInkColor(int c){ inkColor=clamp(c,0,255); }
    public synchronized void setPaperColor(int c){ paperColor=clamp(c,0,255); }

    public synchronized void tab(int n){
        if(n<0)n=0;
        cursorX+=n;
        while(cursorX>=cols) newline();
        refresh();
    }

    public synchronized void printAt(int row,int col,String s){
        cursorY=clamp(row,0,rows-1);
        cursorX=clamp(col,0,cols-1);
        printText(s);
    }

    public synchronized void printText(String s){
        if(s==null)return;
        String[] parts=s.replace("\r","").split("\n",-1);
        for(int p=0;p<parts.length;p++){
            String line=parts[p];
            for(int i=0;i<line.length();i++){
                char ch=line.charAt(i);
                if(ch=='\t'){
                    cursorX=(cursorX+4)&~3;
                    if(cursorX>=cols)newline();
                    continue;
                }
                putChar(ch);
            }
            if(p<parts.length-1)newline();
        }
        refresh();
    }

    private int wrapX(int x){
        int v=x%frameWidth;
        return v<0?v+frameWidth:v;
    }

    private int wrapY(int y){
        int v=y%frameHeight;
        return v<0?v+frameHeight:v;
    }

    // BASIC coordinates are always viewport coordinates. SCROLL changes
    // the camera origin, so drawing is written into the corresponding
    // position in the cyclic bitmap.
    private int bitmapX(int x){ return wrapX(viewX+x); }
    private int bitmapY(int y){ return wrapY(viewY+y); }

    private void setLogicalPixel(int x,int y,int color){
        if(x<0||x>=frameWidth||y<0||y>=frameHeight)return;
        int bx=bitmapX(x), by=bitmapY(y);
        int cc=clamp(color,0,255);
        bitmap.setPixel(bx,by,palette(cc));
        colorIndexBuffer[by*320+bx]=(byte)cc;
    }

    private int getLogicalPixel(int x,int y){
        if(x<0||x>=frameWidth||y<0||y>=frameHeight)return 0;
        int bx=bitmapX(x), by=bitmapY(y);
        return colorIndexBuffer[by*320+bx]&255;
    }

    private void putChar(char ch){
        if(cursorX>=cols)newline();
        if(cursorY>=rows)cursorY=rows-1;

        int code=encode(ch);
        int baseX=cursorX*cellW;
        int baseY=cursorY*cellH;
        int[] glyph=compact ? Font4x8.glyph(code) : Font8x8.glyph(code);

        // Write the glyph in viewport coordinates. This deliberately
        // handles the cyclic bitmap one pixel at a time so text can cross
        // the physical edge of the backing bitmap after SCROLL.
        for(int ry=0;ry<8;ry++){
            int bits=glyph[ry];
            for(int rx=0;rx<cellW;rx++){
                boolean on;
                if(compact){
                    on=(bits&(1<<(3-rx)))!=0;
                }else{
                    on=(bits&(1<<(7-rx)))!=0;
                }
                setLogicalPixel(baseX+rx,baseY+ry,on?inkColor:paperColor);
            }
        }

        cursorX++;
        if(cursorX>=cols)newline();
    }

    private void newline(){
        cursorX=0;
        cursorY++;
        if(cursorY>=rows){
            // Text scrolling uses the same SCROLL mechanism as explicit BASIC
            // SCROLL commands. The visible coordinate system moves with the
            // picture instead of physically copying the whole bitmap.
            scrollView(0,cellH);
            cursorY=rows-1;
        }
    }

    public synchronized void scrollView(int dx,int dy){
        int w=frameWidth, h=frameHeight;
        if(w<=0 || h<=0)return;

        // A scroll equal to or larger than one screen dimension exposes
        // no useful old picture in that direction, so treat it as a clear.
        if(Math.abs(dx)>=w || Math.abs(dy)>=h){
            bitmap.eraseColor(palette(paperColor));
            java.util.Arrays.fill(colorIndexBuffer,(byte)(paperColor&255));
            viewX=0; viewY=0;
            refresh();
            return;
        }

        // Positive X/Y move the visible window right/down through the
        // cyclic bitmap, making the picture appear to move left/up.
        // Clear the pixels that leave the opposite edge; because the
        // bitmap is cyclic, those exact pixels become the newly exposed
        // strip after the origin changes.
        int oldX=viewX, oldY=viewY;
        int paperRgb=palette(paperColor);

        if(dx>0){
            for(int xx=0;xx<dx;xx++){
                int x=(oldX+xx)%w;
                for(int y=0;y<h;y++){
                    bitmap.setPixel(x,y,paperRgb);
                    colorIndexBuffer[y*320+x]=(byte)(paperColor&255);
                }
            }
        }else if(dx<0){
            for(int xx=w+dx;xx<w;xx++){
                int x=(oldX+xx)%w;
                for(int y=0;y<h;y++){
                    bitmap.setPixel(x,y,paperRgb);
                    colorIndexBuffer[y*320+x]=(byte)(paperColor&255);
                }
            }
        }

        if(dy>0){
            for(int yy=0;yy<dy;yy++){
                int y=(oldY+yy)%h;
                for(int x=0;x<w;x++){
                    bitmap.setPixel(x,y,paperRgb);
                    colorIndexBuffer[y*320+x]=(byte)(paperColor&255);
                }
            }
        }else if(dy<0){
            for(int yy=h+dy;yy<h;yy++){
                int y=(oldY+yy)%h;
                for(int x=0;x<w;x++){
                    bitmap.setPixel(x,y,paperRgb);
                    colorIndexBuffer[y*320+x]=(byte)(paperColor&255);
                }
            }
        }

        viewX=(viewX+dx)%w; if(viewX<0)viewX+=w;
        viewY=(viewY+dy)%h; if(viewY<0)viewY+=h;
        refresh();
    }

    // ROLL is the cyclic counterpart of SCROLL: move the viewport origin
    // by the same pixel offsets, but do not clear the newly exposed area.
    // This makes the backing screen act like a toroidal/cyclic surface.
    public synchronized void rollView(int dx,int dy){
        int w=frameWidth, h=frameHeight;
        if(w<=0 || h<=0)return;
        viewX=(viewX+dx)%w; if(viewX<0)viewX+=w;
        viewY=(viewY+dy)%h; if(viewY<0)viewY+=h;
        refresh();
    }

    public synchronized void plotPoint(int x,int y,int color){
        if(x<0||y<0||x>=frameWidth||y>=frameHeight)return;
        int cc=clamp(color,0,255);
        setLogicalPixel(x,y,cc);
        plotX=x; plotY=y;
        refresh();
    }

    public synchronized void draw(int dx,int dy,int color)throws Exception{
        int x1=plotX+dx,y1=plotY+dy;
        if(x1<0||x1>=frameWidth||y1<0||y1>=frameHeight)
            throw new Exception("DRAW coordinate out of range");
        drawLineInternal(plotX,plotY,x1,y1,color);
    }

    public synchronized void drawLine(int x1,int y1,int x2,int y2){
        drawLineInternal(x1,y1,x2,y2,inkColor);
    }

    public synchronized void drawLineTo(int x2,int y2){
        drawLineInternal(plotX,plotY,x2,y2,inkColor);
    }

    private void drawLineInternal(int x1,int y1,int x2,int y2,int color){
        if(x1<0||x1>=frameWidth||y1<0||y1>=frameHeight||
           x2<0||x2>=frameWidth||y2<0||y2>=frameHeight)return;

        int dx=Math.abs(x2-x1),sx=x1<x2?1:-1;
        int dy=-Math.abs(y2-y1),sy=y1<y2?1:-1;
        int err=dx+dy;
        int cc=clamp(color,0,255);

        while(true){
            setLogicalPixel(x1,y1,cc);
            if(x1==x2&&y1==y2)break;
            int e2=2*err;
            if(e2>=dy){err+=dy;x1+=sx;}
            if(e2<=dx){err+=dx;y1+=sy;}
        }
        plotX=x2;plotY=y2;
        refresh();
    }

    public synchronized void circle(int xc,int yc,int rx,int ry,boolean filled)throws Exception{
        rx=Math.abs(rx);
        ry=Math.abs(ry);
        int rgb=palette(inkColor);

        // Clipping is intentional: a circle/ellipse may extend outside SCREEN.
        if(rx==0 && ry==0){
            if(xc>=0 && xc<frameWidth && yc>=0 && yc<frameHeight){
                setLogicalPixel(xc,yc,inkColor);
            }
        }else if(filled){
            int y0=Math.max(0,yc-ry);
            int y1=Math.min(frameHeight-1,yc+ry);
            for(int y=y0;y<=y1;y++){
                double q=ry==0?1.0:
                    1.0-(double)((y-yc)*(y-yc))/(double)(ry*ry);
                if(q<0)continue;
                int span=rx==0?0:(int)Math.round(rx*Math.sqrt(q));
                int x0=Math.max(0,xc-span);
                int x1=Math.min(frameWidth-1,xc+span);
                for(int x=x0;x<=x1;x++){
                    setLogicalPixel(x,y,inkColor);
                }
            }
        }else{
            if(rx==0){
                int y0=Math.max(0,yc-ry);
                int y1=Math.min(frameHeight-1,yc+ry);
                for(int y=y0;y<=y1;y++){
                    if(xc>=0&&xc<frameWidth){
                        setLogicalPixel(xc,y,inkColor);
                    }
                }
            }else if(ry==0){
                int x0=Math.max(0,xc-rx);
                int x1=Math.min(frameWidth-1,xc+rx);
                if(yc>=0&&yc<frameHeight){
                    for(int x=x0;x<=x1;x++){
                        setLogicalPixel(x,yc,inkColor);
                    }
                }
            }else{
                for(int dx=-rx;dx<=rx;dx++){
                    double q=1.0-(double)(dx*dx)/(double)(rx*rx);
                    if(q<0)continue;
                    int dy=(int)Math.round(ry*Math.sqrt(q));
                    int x=xc+dx;
                    int yTop=yc-dy;
                    int yBottom=yc+dy;
                    if(x>=0&&x<frameWidth){
                        if(yTop>=0&&yTop<frameHeight){
                            setLogicalPixel(x,yTop,inkColor);
                        }
                        if(yBottom>=0&&yBottom<frameHeight){
                            setLogicalPixel(x,yBottom,inkColor);
                        }
                    }
                }
                for(int dy=-ry;dy<=ry;dy++){
                    double q=1.0-(double)(dy*dy)/(double)(ry*ry);
                    if(q<0)continue;
                    int dx=(int)Math.round(rx*Math.sqrt(q));
                    int y=yc+dy;
                    int xLeft=xc-dx;
                    int xRight=xc+dx;
                    if(y>=0&&y<frameHeight){
                        if(xLeft>=0&&xLeft<frameWidth){
                            setLogicalPixel(xLeft,y,inkColor);
                        }
                        if(xRight>=0&&xRight<frameWidth){
                            setLogicalPixel(xRight,y,inkColor);
                        }
                    }
                }
            }
        }
        refresh();
    }

    public synchronized void triangle(int x1,int y1,int x2,int y2,int x3,int y3,boolean filled)throws Exception{
        if(x1<0||x1>=frameWidth||y1<0||y1>=frameHeight||
           x2<0||x2>=frameWidth||y2<0||y2>=frameHeight||
           x3<0||x3>=frameWidth||y3<0||y3>=frameHeight)
            throw new Exception("TRIANGLE coordinate out of range");

        if(!filled){
            drawLineInternal(x1,y1,x2,y2,inkColor);
            drawLineInternal(x2,y2,x3,y3,inkColor);
            drawLineInternal(x3,y3,x1,y1,inkColor);
        }else{
            int minY=Math.min(y1,Math.min(y2,y3));
            int maxY=Math.max(y1,Math.max(y2,y3));
            for(int y=minY;y<=maxY;y++){
                int[] xs=new int[3];
                int count=0;
                count=addIntersection(xs,count,x1,y1,x2,y2,y);
                count=addIntersection(xs,count,x2,y2,x3,y3,y);
                count=addIntersection(xs,count,x3,y3,x1,y1,y);
                if(count>=2){
                    java.util.Arrays.sort(xs,0,count);
                    for(int x=xs[0];x<=xs[count-1];x++) setLogicalPixel(x,y,inkColor);
                }
            }
            plotX=x3;plotY=y3;
            refresh();
        }
    }

    private int addIntersection(int[] xs,int count,int x1,int y1,int x2,int y2,int y){
        if(y1==y2)return count;
        int ymin=Math.min(y1,y2),ymax=Math.max(y1,y2);
        if(y<ymin||y>=ymax)return count;
        int x=(int)Math.round(x1+(double)(y-y1)*(x2-x1)/(double)(y2-y1));
        if(count<3)xs[count++]=x;
        return count;
    }

    public synchronized int point(int x,int y)throws Exception{
        if(x<0||x>=frameWidth||y<0||y>=frameHeight)
            throw new Exception("POINT coordinate out of range");
        return getLogicalPixel(x,y);
    }

    public synchronized void paintArea(int x,int y)throws Exception{
        if(x<0||x>=frameWidth||y<0||y>=frameHeight)
            throw new Exception("PAINT coordinate out of range");

        int target=getLogicalPixel(x,y);
        int replacement=inkColor&255;
        if(target==replacement)return;

        int w=frameWidth,h=frameHeight;
        int max=w*h;
        if(fillQueueX.length<max){
            fillQueueX=new int[max];
            fillQueueY=new int[max];
        }

        int head=0,tail=0;
        fillQueueX[tail]=x;fillQueueY[tail]=y;tail++;
        setLogicalPixel(x,y,replacement);

        while(head<tail){
            int cx=fillQueueX[head],cy=fillQueueY[head++];

            if(cx>0&&getLogicalPixel(cx-1,cy)==target){
                setLogicalPixel(cx-1,cy,replacement);
                fillQueueX[tail]=cx-1;fillQueueY[tail++]=cy;
            }
            if(cx+1<w&&getLogicalPixel(cx+1,cy)==target){
                setLogicalPixel(cx+1,cy,replacement);
                fillQueueX[tail]=cx+1;fillQueueY[tail++]=cy;
            }
            if(cy>0&&getLogicalPixel(cx,cy-1)==target){
                setLogicalPixel(cx,cy-1,replacement);
                fillQueueX[tail]=cx;fillQueueY[tail++]=cy-1;
            }
            if(cy+1<h&&getLogicalPixel(cx,cy+1)==target){
                setLogicalPixel(cx,cy+1,replacement);
                fillQueueX[tail]=cx;fillQueueY[tail++]=cy+1;
            }
        }
        refresh();
    }

    public boolean hasOutput(String s){ return s!=null && s.length()>0; }

    public int logicalX(int px){ return clamp((int)(px/scaleX()),0,frameWidth-1); }
    public int logicalY(int py){ return clamp((int)(py/scaleY()),0,frameHeight-1); }

    private int encode(char c){
        // Characters <= 255 are raw charset bytes: leave them unchanged.
        // Only real Unicode characters above 255 are translated to our charset.
        if(c<=255)return c;
        switch(c){
            case '─':return 0x80; case '│':return 0x81; case '┌':return 0x82; case '┐':return 0x83;
            case '└':return 0x84; case '┘':return 0x85; case '├':return 0x86; case '┤':return 0x87;
            case '┬':return 0x88; case '┴':return 0x89; case '┼':return 0x8A; case '▀':return 0x8B;
            case '▄':return 0x8C; case '█':return 0x8D; case '▌':return 0x8E; case '▐':return 0x8F;
            case '░':return 0x90; case '▒':return 0x91; case '▓':return 0x92; case '⌠':return 0x93;
            case '■':return 0x94; case '∙':return 0x95; case '√':return 0x96; case '≈':return 0x97;
            case '≤':return 0x98; case '≥':return 0x99; case '⌡':return 0x9B;
            case '═':return 0xA0; case '║':return 0xA1; case '╒':return 0xA2; case 'ё':return 0xA3;
            case '╓':return 0xA4; case '╔':return 0xA5; case '╕':return 0xA6; case '╖':return 0xA7;
            case '╗':return 0xA8; case '╘':return 0xA9; case '╙':return 0xAA; case '╚':return 0xAB;
            case '╛':return 0xAC; case '╜':return 0xAD; case '╝':return 0xAE; case '╞':return 0xAF;
            case '╟':return 0xB0; case '╠':return 0xB1; case '╡':return 0xB2; case 'Ё':return 0xB3;
            case '╢':return 0xB4; case '╣':return 0xB5; case '╤':return 0xB6; case '╥':return 0xB7;
            case '╦':return 0xB8; case '╧':return 0xB9; case '╨':return 0xBA; case '╩':return 0xBB;
            case '╪':return 0xBC; case '╫':return 0xBD; case '╬':return 0xBE;
            case 'ю':return 0xC0; case 'а':return 0xC1; case 'б':return 0xC2; case 'ц':return 0xC3;
            case 'д':return 0xC4; case 'е':return 0xC5; case 'ф':return 0xC6; case 'г':return 0xC7;
            case 'х':return 0xC8; case 'и':return 0xC9; case 'й':return 0xCA; case 'к':return 0xCB;
            case 'л':return 0xCC; case 'м':return 0xCD; case 'н':return 0xCE; case 'о':return 0xCF;
            case 'п':return 0xD0; case 'я':return 0xD1; case 'р':return 0xD2; case 'с':return 0xD3;
            case 'т':return 0xD4; case 'у':return 0xD5; case 'ж':return 0xD6; case 'в':return 0xD7;
            case 'ь':return 0xD8; case 'ы':return 0xD9; case 'з':return 0xDA; case 'ш':return 0xDB;
            case 'э':return 0xDC; case 'щ':return 0xDD; case 'ч':return 0xDE; case 'ъ':return 0xDF;
            case 'Ю':return 0xE0; case 'А':return 0xE1; case 'Б':return 0xE2; case 'Ц':return 0xE3;
            case 'Д':return 0xE4; case 'Е':return 0xE5; case 'Ф':return 0xE6; case 'Г':return 0xE7;
            case 'Х':return 0xE8; case 'И':return 0xE9; case 'Й':return 0xEA; case 'К':return 0xEB;
            case 'Л':return 0xEC; case 'М':return 0xED; case 'Н':return 0xEE; case 'О':return 0xEF;
            case 'П':return 0xF0; case 'Я':return 0xF1; case 'Р':return 0xF2; case 'С':return 0xF3;
            case 'Т':return 0xF4; case 'У':return 0xF5; case 'Ж':return 0xF6; case 'В':return 0xF7;
            case 'Ь':return 0xF8; case 'Ы':return 0xF9; case 'З':return 0xFA; case 'Ш':return 0xFB;
            case 'Э':return 0xFC; case 'Щ':return 0xFD; case 'Ч':return 0xFE; case 'Ъ':return 0xFF;
            default:return '?';
        }
    }

    @Override
    protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        startFramePacer();
    }

    @Override
    protected void onDetachedFromWindow(){
        stopFramePacer();
        super.onDetachedFromWindow();
    }

    @Override
    protected synchronized void onDraw(Canvas c){
        super.onDraw(c);
        if(bitmap==null || bitmap.isRecycled()) return;
        drawWrapped(c);
        presentedVersion=dirtyVersion;
    }

    private void drawWrapped(Canvas c){
        int w=frameWidth, h=frameHeight;
        int x0=viewX, y0=viewY;
        int[] xs = (x0==0) ? new int[]{0} : new int[]{x0,0};
        int[] xl = (x0==0) ? new int[]{w} : new int[]{w-x0,x0};
        int[] xd = (x0==0) ? new int[]{0} : new int[]{0,w-x0};
        int[] ys = (y0==0) ? new int[]{0} : new int[]{y0,0};
        int[] yl = (y0==0) ? new int[]{h} : new int[]{h-y0,y0};
        int[] yd = (y0==0) ? new int[]{0} : new int[]{0,h-y0};

        float sx=scaleX(), sy=scaleY();
        for(int yi=0; yi<ys.length; yi++){
            for(int xi=0; xi<xs.length; xi++){
                if(xl[xi]<=0 || yl[yi]<=0) continue;
                android.graphics.Rect src=new android.graphics.Rect(
                    xs[xi], ys[yi], xs[xi]+xl[xi], ys[yi]+yl[yi]);
                android.graphics.RectF dst=new android.graphics.RectF(
                    xd[xi]*sx, yd[yi]*sy,
                    (xd[xi]+xl[xi])*sx, (yd[yi]+yl[yi])*sy);
                c.drawBitmap(bitmap,src,dst,paint);
            }
        }
    }

    private int palette(int index){
        int v=index&255;

        // Final RGB palette layout:
        // 128/64 = RED
        // 32/16  = GREEN
        // 8/4    = BLUE
        // 2/1    = COMMON BRIGHTNESS
        //
        // Each RGB channel is 8 bits:
        //   [2 colour bits][0000][2 brightness bits expanded]
        //
        // Common brightness expansion:
        //   00 -> 000000
        //   01 -> 011111
        //   10 -> 100000
        //   11 -> 111111
        //
        // Therefore:
        //   R = RR0000LL
        //   G = GG0000LL
        //   B = BB0000LL
        //
        // 0x00 -> #000000
        // 0xFF -> #FFFFFF
        int rPair=(v>>6)&3;
        int gPair=(v>>4)&3;
        int bPair=(v>>2)&3;
        int lumPair=v&3;

        int low;
        switch(lumPair){
            case 0:  low=0;  break;   // 000000
            case 1:  low=31; break;  // 011111
            case 2:  low=32; break;  // 100000
            default: low=63; break;  // 111111
        }

        int r=(rPair<<6)|low;
        int g=(gPair<<6)|low;
        int b=(bPair<<6)|low;

        return Color.rgb(r,g,b);
    }
}
