/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.Locale;

/** Unaltered Pikafish WDL per-mille values, always oriented to the user. */
public final class WdlProbabilities {
    public static final String[] LABELS={"胜棋","和棋","输棋"};
    public final int win,draw,loss;
    private WdlProbabilities(int win,int draw,int loss){this.win=win;this.draw=draw;this.loss=loss;}

    public static WdlProbabilities parse(String line,boolean sideToMoveIsUser) {
        if(line==null)return null;
        String[] p=line.trim().split("\\s+");
        if(p.length<5||!p[0].equals("info"))return null;
        for(int i=0;i<p.length;i++) {
            if(p[i].equals("upperbound")||p[i].equals("lowerbound"))return null;
            if(p[i].equals("multipv")&&(i+1==p.length||!p[i+1].equals("1")))return null;
        }
        for(int i=1;i+3<p.length;i++)if(p[i].equals("wdl")) {
            try {
                int w=Integer.parseInt(p[i+1]),d=Integer.parseInt(p[i+2]),l=Integer.parseInt(p[i+3]);
                if(w<0||w>1000||d<0||d>1000||l<0||l>1000||w+d+l!=1000)return null;
                return sideToMoveIsUser?new WdlProbabilities(w,d,l):new WdlProbabilities(l,d,w);
            } catch(NumberFormatException ignored){return null;}
        }
        return null;
    }
    public static WdlProbabilities finished(String result,boolean userRed) {
        if("draw".equals(result))return new WdlProbabilities(0,1000,0);
        if(!"red".equals(result)&&!"black".equals(result))return null;
        boolean won="red".equals(result)==userRed;
        return won?new WdlProbabilities(1000,0,0):new WdlProbabilities(0,0,1000);
    }
    public String percent(int index) {
        int n=index==0?win:index==1?draw:loss;
        return String.format(Locale.CHINA,"%.1f%%",n/10.0);
    }
}
