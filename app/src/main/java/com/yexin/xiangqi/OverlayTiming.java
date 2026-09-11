/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Scheduling targets, not promises about a phone's processing or animation speed. */
public final class OverlayTiming {
    public static final int DEFAULT_RANDOM_MODE=2;
    public static final int ACTIVE_CAPTURE_MS=160;
    public static final int RETRY_CAPTURE_MS=350;
    public static final int PAUSED_CAPTURE_MS=900;
    public static final int FRESH_FRAME_MS=1400;
    public static final int PROBABILITY_EVAL_MS=250;
    private static final int[] RANDOM_1={1000,3000,5000};
    private static final int[] RANDOM_2={800,1000,1200,1500};
    private OverlayTiming(){}
    public static int captureInterval(boolean paused,boolean retrying) {
        return paused?PAUSED_CAPTURE_MS:retrying?RETRY_CAPTURE_MS:ACTIVE_CAPTURE_MS;
    }
    public static int tapDuration(boolean fast){return fast?50:75;}
    public static int tapGap(boolean fast){return fast?60:180;}
    public static int randomThinkMillis(int mode) {
        int[] choices=mode==1?RANDOM_1:RANDOM_2;
        return choices[ThreadLocalRandom.current().nextInt(choices.length)];
    }
    public static String randomLabel(int mode){return mode==1?"随机1":"随机2";}
    public static String randomDescription(int mode){return mode==1?"每步随机选择 1 / 3 / 5 秒":"每步随机选择 0.8 / 1 / 1.2 / 1.5 秒";}
    public static String seconds(int millis){
        return millis%1000==0?Integer.toString(millis/1000):String.format(Locale.CHINA,"%.1f",millis/1000.0);
    }
}
