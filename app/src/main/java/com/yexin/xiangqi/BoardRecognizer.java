/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.io.*;
import java.util.*;

/** Offline recognizer for the supplied upright wooden-board skin. No Android dependency. */
public final class BoardRecognizer {
    public static final int SIDE=28, FEATURE=SIDE*SIDE;
    public static final String START="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
    private final List<Template> templates=new ArrayList<>();

    public static final class Frame {
        public final int width,height;
        public final int[] pixels;
        public Frame(int width,int height,int[] pixels) {
            if(width<1||height<1||pixels.length!=width*height)throw new IllegalArgumentException("Invalid frame");
            this.width=width;this.height=height;this.pixels=pixels;
        }
        public int pixel(float x,float y) {
            int ix=Math.max(0,Math.min(width-1,Math.round(x))),iy=Math.max(0,Math.min(height-1,Math.round(y)));
            return pixels[iy*width+ix];
        }
        public float green(float x,float y) {
            x=Math.max(0,Math.min(width-1,x));y=Math.max(0,Math.min(height-1,y));
            int ix=(int)x,iy=(int)y,ix1=Math.min(width-1,ix+1),iy1=Math.min(height-1,iy+1);
            float fx=x-ix,fy=y-iy;
            float top=((pixels[iy*width+ix]>>8)&255)*(1-fx)+((pixels[iy*width+ix1]>>8)&255)*fx;
            float bottom=((pixels[iy1*width+ix]>>8)&255)*(1-fx)+((pixels[iy1*width+ix1]>>8)&255)*fx;
            return top*(1-fy)+bottom*fy;
        }
    }
    public static final class Geometry {
        public final float left,top,right,bottom,x0,y0,dx,dy;
        public Geometry(float left,float top,float right,float bottom) {
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
            x0=left+(right-left)*.066f;dx=(right-left)*.868f/8;
            // The square lattice is determined by board width. The 3-D bottom shadow can
            // disconnect from the wood at different resampling scales and is not a grid edge.
            dy=dx;y0=top+dx*.568f;
        }
        public float x(int square,boolean redBottom) {int s=redBottom?square:89-square;return x0+(s%9)*dx;}
        public float y(int square,boolean redBottom) {int s=redBottom?square:89-square;return y0+(s/9)*dy;}
        public boolean near(Geometry other) {
            return other!=null&&Math.abs(x0-other.x0)<dx*.055f&&Math.abs(y0-other.y0)<dy*.055f
                &&Math.abs(dx-other.dx)<dx*.025f&&Math.abs(dy-other.dy)<dy*.025f;
        }
    }
    public static final class Observation {
        public final Geometry geometry;
        public final char[] squares;
        public final boolean redBottom;
        public final float confidence;
        public final String placement;
        /** Canonical square; -1 means no selection evidence, -2 means ambiguous selection. */
        public final int selectedSquare;
        public final boolean[] moveHints;
        public Observation(Geometry geometry,char[] squares,boolean redBottom,float confidence) {
            this(geometry,squares,redBottom,confidence,-1,new boolean[90]);
        }
        public Observation(Geometry geometry,char[] squares,boolean redBottom,float confidence,int selectedSquare,boolean[] moveHints) {
            this.geometry=geometry;this.squares=squares.clone();this.redBottom=redBottom;
            this.confidence=confidence;this.placement=placement(squares);
            if(selectedSquare<-2||selectedSquare>=90||moveHints.length!=90)throw new IllegalArgumentException("Invalid selection");
            this.selectedSquare=selectedSquare;this.moveHints=moveHints.clone();
        }
        public boolean opening() {return placement.equals(START);}
        public boolean same(Observation other) {
            return other!=null&&redBottom==other.redBottom&&placement.equals(other.placement)&&geometry.near(other.geometry);
        }
        public boolean sameInteraction(Observation other) {
            return same(other)&&selectedSquare==other.selectedSquare&&Arrays.equals(moveHints,other.moveHints);
        }
        public boolean hasSelectionEvidence() {
            if(selectedSquare!=-1)return true;
            for(boolean hint:moveHints)if(hint)return true;
            return false;
        }
    }
    private static final class Template {
        char piece;float[] shape;
        Template(char piece,float[] shape){this.piece=piece;this.shape=shape;}
    }
    public static final class RecognitionException extends Exception {
        public int square=-1;
        public float best,alternative;
        public RecognitionException(String message){super(message);}
        public RecognitionException(String message,int square,float best,float alternative){super(message);this.square=square;this.best=best;this.alternative=alternative;}
    }
    public BoardRecognizer(InputStream input) throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(input))) {
            if(in.readInt()!=0x58515631||in.readInt()!=SIDE)throw new IOException("识别模板不匹配");
            int count=in.readInt();if(count<14||count>300)throw new IOException("识别模板数量异常");
            for(int i=0;i<count;i++) {
                char c=in.readChar();float[] shape=new float[FEATURE];
                if("rnbakcpRNBAKCP".indexOf(c)<0)throw new IOException("无效识别模板");
                for(int j=0;j<FEATURE;j++)shape[j]=in.readUnsignedByte()/255f;
                templates.add(new Template(c,shape));
            }
        }
    }
    /** Training utility shared by desktop validation; only cropped glyph features are serialized. */
    public static void train(List<Frame> frames,List<Boolean> redBottom,OutputStream output) throws Exception {
        List<Template> samples=new ArrayList<>();
        char[] initial=new Position(START+" w - - 0 1").squares;
        for(int k=0;k<frames.size();k++) {
            Frame f=frames.get(k);Geometry g=locate(f);float background=background(f,g);
            for(int s=0;s<90;s++)if(initial[s]!='.') {
                int screen=redBottom.get(k)?s:89-s;
                samples.add(new Template(initial[s],feature(f,g,screen,background,0,0)));
            }
        }
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(output))) {
            out.writeInt(0x58515631);out.writeInt(SIDE);out.writeInt(samples.size());
            for(Template t:samples) {
                out.writeChar(t.piece);for(float v:t.shape)out.writeByte(Math.round(v*255));
            }
        }
    }

    public static Geometry locate(Frame f) throws RecognitionException {
        if(f.width>f.height||f.width<240)throw new RecognitionException("请使用竖屏完整画面");
        int step=Math.max(1,f.width/360);
        // Connected wood finds the board independently of margins or its position on screen.
        int gw=(f.width+step-1)/step,gh=(f.height+step-1)/step;
        boolean[] mask=new boolean[gw*gh];int[] queue=new int[mask.length];
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++)mask[y*gw+x]=wood(f.pixels[y*step*f.width+x*step]);
        int area=0,woodLeft=0,woodRight=0,woodTop=0,woodBottom=0;
        for(int seed=0;seed<mask.length;seed++)if(mask[seed]) {
            int head=0,tail=0,minX=gw,maxX=0,minY=gh,maxY=0;queue[tail++]=seed;mask[seed]=false;
            while(head<tail) {
                int p=queue[head++],x=p%gw,y=p/gw;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                if(x>0&&mask[p-1]){mask[p-1]=false;queue[tail++]=p-1;}
                if(x+1<gw&&mask[p+1]){mask[p+1]=false;queue[tail++]=p+1;}
                if(y>0&&mask[p-gw]){mask[p-gw]=false;queue[tail++]=p-gw;}
                if(y+1<gh&&mask[p+gw]){mask[p+gw]=false;queue[tail++]=p+gw;}
            }
            if(tail>area){area=tail;woodLeft=minX*step;woodRight=maxX*step;woodTop=minY*step;woodBottom=maxY*step;}
        }
        if(area<gw*gh*.10f||woodRight-woodLeft<f.width*.60f)throw new RecognitionException("未找到这套木纹棋盘");
        int bestStart=-1,bestEnd=-1,start=-1,gaps=0;
        for(int y=woodTop;y<=woodBottom;y+=step) {
            int count=0,total=0;
            for(int x=woodLeft;x<=woodRight;x+=step){total++;if(wood(f.pixels[y*f.width+x]))count++;}
            if(count>total*.61f) {
                if(start<0)start=y;gaps=0;
            } else if(start>=0&&++gaps>3) {
                int end=y-gaps*step;
                if(end-start>bestEnd-bestStart){bestStart=start;bestEnd=end;}
                start=-1;gaps=0;
            }
        }
        if(start>=0&&woodBottom-gaps*step-start>bestEnd-bestStart){bestStart=start;bestEnd=woodBottom-gaps*step;}
        if(bestStart<0||bestEnd-bestStart<f.width*.60f)throw new RecognitionException("未找到这套木纹棋盘");
        int left=f.width,right=0;
        for(int x=woodLeft;x<=woodRight;x+=step) {
            int count=0,total=0;
            for(int y=bestStart;y<=bestEnd;y+=step){total++;if(wood(f.pixels[y*f.width+x]))count++;}
            if(count>total*.66f){left=Math.min(left,x);right=Math.max(right,x);}
        }
        float ratio=(right-left)/(float)(bestEnd-bestStart);
        if(right-left<f.width*.60f||ratio<.84f||ratio>1.01f)throw new RecognitionException("棋盘边界不完整或被遮挡");
        return new Geometry(left,bestStart,right,bestEnd);
    }
    private static boolean wood(int color) {
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        return r>140&&g>100&&b>50&&r-g>12&&g-b>15;
    }
    private static float background(Frame f,Geometry g) {
        int[] values=new int[441];int count=0;
        for(int y=0;y<21;y++)for(int x=0;x<21;x++) {
            int c=f.pixel(g.left+(g.right-g.left)*(x+.5f)/21,g.top+(g.bottom-g.top)*(y+.5f)/21);
            values[count++]=(c>>8)&255;
        }
        Arrays.sort(values);return values[count*2/3];
    }
    private static float[] feature(Frame f,Geometry g,int s,float bg,float shiftX,float shiftY) {
        return feature(f,g,s,bg,shiftX,shiftY,1,false);
    }
    private static float[] feature(Frame f,Geometry g,int s,float bg,float shiftX,float shiftY,float scale,boolean hint) {
        float[] result=new float[FEATURE];float cx=g.x0+s%9*g.dx+shiftX*g.dx,cy=g.y0+s/9*g.dy+shiftY*g.dy;
        for(int y=0;y<SIDE;y++)for(int x=0;x<SIDE;x++) {
            float px=cx+((x+.5f)/SIDE-.5f)*g.dx*.60f*scale;
            float py=cy+(((y+.5f)/SIDE-.5f)*.60f-.075f)*g.dy*scale;
            if(hint&&maskedHint(f,g,s,px,py)){result[y*SIDE+x]=-1;continue;}
            float green=f.green(px,py);
            result[y*SIDE+x]=Math.max(0,Math.min(1,(bg*.64f-green)/(bg*.25f)));
        }
        return result;
    }
    private static float ink(float[] shape) {float n=0;int count=0;for(float v:shape)if(v>=0){n+=v;count++;}return count==0?0:n/count;}
    private static boolean greenHintPixel(int c) {
        int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
        return g>70&&g-r>7&&g-b>25;
    }
    private static boolean hintAt(Frame f,Geometry g,int s) {
        float x=g.x0+s%9*g.dx,y=g.y0+s/9*g.dy;int center=0,outer=0;
        for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++)
            if(greenHintPixel(f.pixel(x+ox*g.dx*.028f,y+oy*g.dy*.028f)))center++;
        for(int i=0;i<4;i++)if(greenHintPixel(f.pixel(x+(i<2?(i==0?-1:1)*g.dx*.19f:0),y+(i>=2?(i==2?-1:1)*g.dy*.19f:0))))outer++;
        // Only compact, intersection-centred dots, not arbitrary green occlusion.
        return center>=7&&outer==0;
    }
    private static boolean maskedHint(Frame f,Geometry g,int s,float x,float y) {
        float dx=(x-g.x0-s%9*g.dx)/g.dx,dy=(y-g.y0-s/9*g.dy)/g.dy;
        return dx*dx+dy*dy<.15f*.15f&&greenHintPixel(f.pixel(x,y));
    }
    private static boolean clearEmptyCell(Frame f,Geometry g,int s,boolean hint) {
        int tan=0;
        for(int y=0;y<10;y++)for(int x=0;x<10;x++) {
            float px=g.x0+s%9*g.dx+((x+.5f)/10-.5f)*g.dx*.60f;
            float py=g.y0+s/9*g.dy+((y+.5f)/10-.5f)*g.dy*.60f;
            if(wood(f.pixel(px,py))||(hint&&maskedHint(f,g,s,px,py)))tan++;
        }
        return tan>=78;
    }
    private static float redInk(Frame f,Geometry g,int s,float bg,boolean hint) {
        float red=0,total=0;
        for(int y=0;y<SIDE;y++)for(int x=0;x<SIDE;x++) {
            float px=g.x0+s%9*g.dx+((x+.5f)/SIDE-.5f)*g.dx*.50f;
            float py=g.y0+s/9*g.dy+((y+.5f)/SIDE-.5f)*g.dy*.50f-g.dy*.075f;
            if(hint&&maskedHint(f,g,s,px,py))continue;
            int c=f.pixel(px,py),r=(c>>16)&255,gr=(c>>8)&255;
            float w=Math.max(0,bg*.62f-gr);
            total+=w;if(r-gr>22&&r>gr*1.30f)red+=w;
        }
        return total<1?.5f:red/total;
    }
    private static float distance(float[] a,float[] b) {
        float result=0;int count=0;
        for(int i=0;i<FEATURE;i++)if(a[i]>=0&&b[i]>=0){float d=a[i]-b[i];result+=d*d;count++;}
        return count<FEATURE*.82f?Float.MAX_VALUE:result/count;
    }
    private static final class Match {
        final float[] scores=new float[128],shiftX=new float[128],shiftY=new float[128],scale=new float[128];
        char piece='?',runner='?';float first=Float.MAX_VALUE,second=Float.MAX_VALUE;
        Match(){Arrays.fill(scores,Float.MAX_VALUE);}
        void finish() {
            first=second=Float.MAX_VALUE;piece=runner='?';
            for(char c:"rnbakcpRNBAKCP".toCharArray()) {
                if(scores[c]<first){second=first;runner=piece;first=scores[c];piece=c;}
                else if(scores[c]<second){second=scores[c];runner=c;}
            }
        }
        boolean acceptable(){return first<=.125f&&second-first>=.012f;}
    }
    private void compare(Match match,float[] shape,boolean red) {
        compare(match,shape,red,0,0,1);
    }
    private void compare(Match match,float[] shape,boolean red,float shiftX,float shiftY,float scale) {
        for(Template t:templates)if(Character.isUpperCase(t.piece)==red) {
            float d=distance(shape,t.shape);char c=t.piece;
            if(d<match.scores[c]){match.scores[c]=d;match.shiftX[c]=shiftX;match.shiftY[c]=shiftY;match.scale[c]=scale;}
        }
    }
    public Observation recognize(Frame frame) throws RecognitionException {
        Geometry g=locate(frame);float bg=background(frame,g);
        boolean[] screenHints=new boolean[90];int hintCount=0;
        for(int s=0;s<90;s++)if(screenHints[s]=hintAt(frame,g,s))hintCount++;
        int selected=-1,liftedCount=0;
        char[] screen=new char[90];Arrays.fill(screen,'.');float worst=0;
        for(int s=0;s<90;s++) {
            float[] shape=feature(frame,g,s,bg,0,0,1,screenHints[s]);float amount=ink(shape);
            if(amount<.078f) {
                if(!clearEmptyCell(frame,g,s,screenHints[s]))throw new RecognitionException("棋盘空位被遮挡，请先关闭弹窗");
                continue;
            }
            float color=redInk(frame,g,s,bg,screenHints[s]);
            if(color>.26f&&color<.72f)throw new RecognitionException("棋子颜色不确定，请等待画面稳定");
            boolean red=color>=.72f;
            Match normal=new Match();compare(normal,shape,red);normal.finish();
            if(normal.first>.024f) {
                for(int oy=-1;oy<=1;oy++)for(int ox=-1;ox<=1;ox++) {
                    if(ox==0&&oy==0)continue;
                    compare(normal,feature(frame,g,s,bg,ox*.023f,oy*.023f,1,screenHints[s]),red);
                }
                normal.finish();
            }
            Match match=normal;
            if(!normal.acceptable()||(hintCount>0&&normal.first>.055f)) {
                Match lifted=new Match();
                for(float scale:new float[]{1.06f,1.10f,1.14f,1.18f,1.22f,1.26f,1.30f})
                    for(float oy:new float[]{-.22f,-.18f,-.14f,-.10f,-.06f,-.02f,.02f})
                        for(float ox:new float[]{-.04f,0,.04f})
                            compare(lifted,feature(frame,g,s,bg,ox,oy,scale,screenHints[s]),red,ox,oy,scale);
                lifted.finish();
                // A half-step refinement avoids scale/translation phase gaps after capture
                // resizing. Refine both leading classes, preserving a meaningful margin.
                if(lifted.piece!='?'&&lifted.runner!='?') {
                    float[][] centers=new float[2][3];char[] leaders={lifted.piece,lifted.runner};
                    for(int i=0;i<2;i++)centers[i]=new float[]{lifted.shiftX[leaders[i]],lifted.shiftY[leaders[i]],lifted.scale[leaders[i]]};
                    for(float[] center:centers)for(int ds=-1;ds<=1;ds++)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                        float ox=center[0]+dx*.02f,oy=center[1]+dy*.02f,scale=center[2]+ds*.02f;
                        compare(lifted,feature(frame,g,s,bg,ox,oy,scale,screenHints[s]),red,ox,oy,scale);
                    }
                }
                lifted.finish();
                // Selected pieces grow/lift in this skin. Require a stronger match, not
                // a relaxed threshold; arbitrary dialog/animation pixels remain invalid.
                if(lifted.first<=.09f&&lifted.second-lifted.first>=.020f&&lifted.first<normal.first-.020f) {
                    match=lifted;selected=s;liftedCount++;
                }
            }
            // A weak match or a close rival is never interpreted as a piece.
            if(!match.acceptable())throw new RecognitionException("棋子识别不确定，请关闭遮挡或换回原棋盘",s,match.first,match.second);
            screen[s]=match.piece;worst=Math.max(worst,match.first);
        }
        int redKing=-1,blackKing=-1,rk=0,bk=0;
        for(int s=0;s<90;s++){if(screen[s]=='K'){redKing=s;rk++;}if(screen[s]=='k'){blackKing=s;bk++;}}
        if(rk!=1||bk!=1)throw new RecognitionException("将帅不完整，可能有遮挡或对局已经结束");
        boolean redBottom;
        if(redKing/9>=7&&blackKing/9<=2)redBottom=true;
        else if(redKing/9<=2&&blackKing/9>=7)redBottom=false;
        else throw new RecognitionException("棋盘朝向不确定");
        if(redKing%9<3||redKing%9>5||blackKing%9<3||blackKing%9>5)throw new RecognitionException("将帅位置异常");
        char[] canonical=new char[90];boolean[] hints=new boolean[90];
        for(int s=0;s<90;s++){int index=redBottom?s:89-s;canonical[index]=screen[s];hints[index]=screenHints[s];}
        validateCounts(canonical);
        int selection=hintCount==0&&liftedCount==0?-1:hintCount>0&&liftedCount==1?(redBottom?selected:89-selected):-2;
        return new Observation(g,canonical,redBottom,Math.max(0,1-worst),selection,hints);
    }
    private static void validateCounts(char[] squares) throws RecognitionException {
        String pieces="rnbakcpRNBAKCP";
        for(char c:pieces.toCharArray()) {
            int count=0;for(char p:squares)if(p==c)count++;
            int limit=Character.toLowerCase(c)=='p'?5:Character.toLowerCase(c)=='k'?1:2;
            if(count>limit)throw new RecognitionException("棋子数量不符合象棋规则");
        }
    }
    public static String placement(char[] squares) {
        StringBuilder b=new StringBuilder();
        for(int row=0;row<10;row++) {
            if(row>0)b.append('/');int empty=0;
            for(int col=0;col<9;col++) {
                char c=squares[row*9+col];
                if(c=='.')empty++;else{if(empty>0){b.append(empty);empty=0;}b.append(c);}
            }
            if(empty>0)b.append(empty);
        }
        return b.toString();
    }
}
