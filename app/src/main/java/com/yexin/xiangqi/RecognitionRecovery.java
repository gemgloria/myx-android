/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

/** Retry policy shared by Android and host tests. Failure never authorizes a tap. */
public final class RecognitionRecovery {
    public static final int REQUIRED_STABLE_FRAMES=2;
    public static final long NEW_OPENING_GAP_MS=1500;
    private boolean active,waitingOpening;
    private long failures,missingSince=-1,lastObservedAt=-1;
    private int stableFrames;
    private BoardRecognizer.Observation previous;

    public void start(boolean finishedGame) {
        active=true;waitingOpening=finishedGame;lastObservedAt=-1;accepted();invalidateSamples();
    }
    public void stop() {active=false;waitingOpening=false;accepted();invalidateSamples();}
    public boolean active(){return active;}
    public boolean waitingOpening(){return active&&waitingOpening;}
    public boolean retrying(){return active&&(failures>0||waitingOpening);}
    public long failures(){return failures;}
    public boolean stable(){return active&&stableFrames>=REQUIRED_STABLE_FRAMES;}

    /** Returns true only on entry into a retry episode, to cancel stale work once. */
    public boolean failed(long at,boolean missingBoard) {
        if(!active)return false;
        boolean first=failures==0;
        if(failures<Long.MAX_VALUE)failures++;
        if(missingBoard&&missingSince<0)missingSince=at;
        lastObservedAt=Math.max(lastObservedAt,at);
        invalidateSamples();
        return first;
    }
    public boolean observe(BoardRecognizer.Observation observation,long at) {
        if(!active||at<=lastObservedAt)return false;
        lastObservedAt=at;
        stableFrames=observation.sameInteraction(previous)?Math.min(REQUIRED_STABLE_FRAMES,stableFrames+1):1;
        previous=observation;
        return stable();
    }
    /** Clear retry evidence only after the recognized position passes game validation. */
    public void accepted(){failures=0;missingSince=-1;}
    public void waitForOpening() {
        if(!active)return;
        waitingOpening=true;accepted();invalidateSamples();
    }
    public void newGameStarted(){waitingOpening=false;accepted();}
    public void requireFreshFrames(){invalidateSamples();}
    public boolean startsNewGame(BoardRecognizer.Observation observation,ScreenGame game,long at) {
        if(!stable()||!observation.opening())return false;
        if(waitingOpening)return true;
        // A result page can replace the board before the engine sees a terminal position.
        // Only a complete standard opening after a real recognition gap may reset history.
        return missingSince>=0&&at-missingSince>=NEW_OPENING_GAP_MS&&game!=null
            &&(game.initialFen!=null||!game.moves().isEmpty())
            &&(game.state==null||!BoardRecognizer.placement(game.state.position.squares).equals(BoardRecognizer.START));
    }
    private void invalidateSamples(){stableFrames=0;previous=null;}
}
