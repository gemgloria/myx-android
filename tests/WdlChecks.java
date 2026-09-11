package com.yexin.xiangqi;

import java.util.HashSet;
import java.util.Set;

public final class WdlChecks {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args) {
        WdlProbabilities ours=WdlProbabilities.parse("info depth 12 multipv 1 score cp 43 wdl 421 337 242 nodes 99",true);
        check(ours!=null,"parse");
        check(ours.win+ours.draw+ours.loss==1000,"per-mille sum");
        check(ours.percent(0).equals("42.1%")&&ours.percent(1).equals("33.7%")&&ours.percent(2).equals("24.2%"),"lossless percent labels");
        WdlProbabilities theirs=WdlProbabilities.parse("info depth 12 score cp 43 wdl 421 337 242 nodes 99",false);
        check(theirs.win==242&&theirs.draw==337&&theirs.loss==421,"user perspective inversion");
        for(String line:new String[]{null,"","info depth 1 score cp 0","info depth 1 wdl 0 0", "info depth 1 wdl 1 1 1", "info depth 1 wdl -1 1001 0", "info depth 1 wdl 2147483647 2 1", "info depth 1 wdl bad 0 0", "info depth 1 multipv 2 wdl 0 1000 0", "info depth 1 score cp 10 lowerbound wdl 0 1000 0"}) {
            check(WdlProbabilities.parse(line,true)==null,"invalid/incomplete WDL ignored");
        }
        check(WdlProbabilities.finished("red",true).win==1000,"red user wins");
        check(WdlProbabilities.finished("red",false).loss==1000,"black user loses");
        check(WdlProbabilities.finished("black",false).win==1000,"black user wins");
        check(WdlProbabilities.finished("black",true).loss==1000,"red user loses");
        check(WdlProbabilities.finished("draw",false).draw==1000,"draw");
        check(WdlProbabilities.finished("ongoing",true)==null,"no invented outcome");
        for(int w=0;w<=1000;w+=5)for(int d=0;d<=1000-w;d+=5) {
            WdlProbabilities p=WdlProbabilities.parse("info depth 1 wdl "+w+" "+d+" "+(1000-w-d),true);
            check(p!=null&&p.win+p.draw+p.loss==1000,"valid triplets retain 100 percent");
        }
        Set<Integer> group1=new HashSet<>(),group2=new HashSet<>();
        for(int i=0;i<1000;i++) {
            int a=OverlayTiming.randomThinkMillis(1),b=OverlayTiming.randomThinkMillis(2);
            check(a==1000||a==3000||a==5000,"random1 set");
            check(b==800||b==1000||b==1200||b==1500,"random2 set");group1.add(a);group2.add(b);
        }
        check(group1.size()==3&&group2.size()==4,"both groups used");
        check(OverlayTiming.DEFAULT_RANDOM_MODE==2,"random2 default");
        check(OverlayTiming.randomLabel(1).equals("随机1")&&OverlayTiming.randomLabel(2).equals("随机2"),"labels");
        check(OverlayTiming.seconds(800).equals("0.8")&&OverlayTiming.seconds(1200).equals("1.2")&&OverlayTiming.seconds(1500).equals("1.5"),"fractional seconds");
        System.out.println("PASS "+checks+" WDL/random checks");
    }
}
