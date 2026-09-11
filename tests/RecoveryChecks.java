package com.yexin.xiangqi;

import java.io.*;
import java.util.*;

/** Exercises production recovery policy; these are not Android device tests. */
public final class RecoveryChecks {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static final BoardRecognizer.Geometry GEO=new BoardRecognizer.Geometry(7,382,700,1146);
    private static BoardRecognizer.Observation observed(EngineState state,boolean redBottom){return new BoardRecognizer.Observation(GEO,state.position.squares,redBottom,1);}
    public static void main(String[] args) throws Exception {
        try(PikafishClient engine=new PikafishClient()) {
            engine.start(new File(args[0]),new File(args[1]),1);
            EngineState initial=engine.state(Collections.emptyList());
            String move="b2e2";EngineState after=engine.state(Arrays.asList(move));
            BoardRecognizer.Observation opening=observed(initial,true),middle=observed(after,true);
            RecognitionRecovery r=new RecognitionRecovery();
            check(!r.active()&&!r.retrying()&&!r.stable(),"inactive by default");
            r.start(false);
            check(r.active()&&!r.retrying(),"start enables observation only");
            check(r.failed(100,true),"first failed frame requests cancellation of stale work");
            boolean repeatedCancellation=false;
            for(int i=1;i<20000;i++)repeatedCancellation|=r.failed(100+i*350L,true);
            check(!repeatedCancellation,"one cancellation per continuous retry episode");
            check(r.active()&&r.retrying()&&r.failures()==20000,"no five-frame or finite retry limit");
            check(!r.stable(),"missing frames cannot permit action");
            check(!r.observe(opening,7000200),"first recovered frame is insufficient");
            check(!r.observe(opening,7000200),"duplicate frame timestamp is not a second observation");
            check(!r.observe(opening,6999999),"older captured frame cannot confirm recovery");
            check(r.observe(opening,7000400),"two fresh equal frames permit game validation");
            check(r.retrying(),"detection alone does not clear game-validation failure");
            r.accepted();check(!r.retrying()&&r.failures()==0&&r.stable(),"validated recovery clears failures");
            check(r.failed(7000500,false),"a later invalid position cancels stale work again");
            check(!r.stable(),"legal-position uncertainty invalidates stability");
            r.stop();check(!r.active()&&!r.retrying()&&!r.stable(),"manual pause disables recovery");
            check(!r.failed(8000000,true)&&r.failures()==0,"failed frames cannot restart a paused session");
            check(!r.observe(opening,8001000)&&!r.observe(opening,8002000),"clear frames cannot restart a paused session");
            check(!r.startsNewGame(opening,null,8003000),"pause prevents automatic next game");

            r.start(false);r.observe(opening,100);
            BoardRecognizer.Observation shifted=new BoardRecognizer.Observation(new BoardRecognizer.Geometry(47,422,740,1186),initial.position.squares,true,1);
            check(!r.observe(shifted,300),"shifted board requires two matching geometry samples");
            check(r.observe(shifted,500),"new stable geometry is available for validation");
            r.requireFreshFrames();check(!r.stable(),"completed gesture invalidates pre-gesture frames");
            check(!r.observe(shifted,500),"old pre-gesture sample cannot be reused");
            check(!r.observe(shifted,700)&&r.observe(shifted,900),"post-gesture confirmation requires two new samples");

            ScreenGame played=new ScreenGame();played.reset(opening,null);played.state=initial;
            played.expect(move,after);played.observe(middle);
            check(played.moves().size()==1,"test fixture has a real committed move");
            r.start(false);r.failed(100,true);
            r.observe(opening,300);r.observe(opening,500);
            check(!r.startsNewGame(opening,played,500),"short animation gap cannot reset game history");
            r.observe(opening,2000);
            check(r.startsNewGame(opening,played,2000),"standard opening after missing result page can begin next game");
            check(played.moves().size()==1,"recovery detection itself never mutates transcript");
            r.start(false);r.failed(100,false);r.observe(opening,2000);r.observe(opening,2200);
            check(!r.startsNewGame(opening,played,2200),"unknown position without board absence cannot reset history");
            r.start(false);r.failed(100,true);r.observe(middle,2000);r.observe(middle,2200);
            check(!r.startsNewGame(middle,played,2200),"arbitrary midgame never silently becomes a new game");
            ScreenGame untouched=new ScreenGame();untouched.reset(opening,null);untouched.state=initial;
            r.start(false);r.failed(100,true);r.observe(opening,2000);r.observe(opening,2200);
            check(!r.startsNewGame(opening,untouched,2200),"unchanged opening is not repeatedly reset");

            r.waitForOpening();check(r.waitingOpening()&&r.retrying()&&!r.stable(),"terminal state waits instead of pausing");
            r.observe(middle,2400);r.observe(middle,2600);
            check(!r.startsNewGame(middle,played,2600),"old finished/midgame board cannot restart automation");
            BoardRecognizer.Observation blackOpening=observed(initial,false);
            r.observe(blackOpening,2800);check(!r.startsNewGame(blackOpening,played,2800),"next game still requires two new frames");
            r.observe(blackOpening,3000);check(r.startsNewGame(blackOpening,played,3000),"new opening may reverse player orientation");
            played.reset(blackOpening,null);played.state=engine.state(played.initialFen,played.moves());r.newGameStarted();
            check(!r.waitingOpening()&&!r.retrying(),"new game clears waiting status");
            check(!played.ownRed&&!played.ourTurn()&&played.moves().isEmpty(),"next game resets transcript and waits when playing black");
            r.waitForOpening();r.stop();r.observe(opening,4000);r.observe(opening,4200);
            check(!r.startsNewGame(opening,played,4200),"pause during result page remains paused in next game");
            r.start(true);check(r.waitingOpening(),"explicit resume may wait for next opening");

            ScreenGame pending=new ScreenGame();pending.reset(opening,null);pending.state=initial;pending.expect(move,after);
            r.start(false);for(int i=0;i<20;i++)r.failed(i*350,true);
            check(pending.hasPending()&&pending.moves().isEmpty(),"retry never treats click dispatch as a committed move");
            r.observe(middle,8000);r.observe(middle,8200);
            check(pending.observe(middle)==ScreenGame.Change.PENDING_CONFIRMED,"actual board can confirm pending move after a recognition gap");
            check(pending.moves().equals(Arrays.asList(move))&&!pending.hasPending(),"recovery commits once and never resends a pending click");

            check(OverlayTiming.captureInterval(false,false)==160,"active capture target is 160 ms");
            check(OverlayTiming.captureInterval(false,true)==350,"retry remains throttled rather than busy-looping");
            check(OverlayTiming.captureInterval(true,true)==900,"pause overrides high-rate capture");
            check(2*OverlayTiming.tapDuration(true)+OverlayTiming.tapGap(true)==160,"fast gesture configured total is 160 ms");
            check(2*OverlayTiming.tapDuration(false)+OverlayTiming.tapGap(false)==330,"compatible gesture retains previous timing");
            check(OverlayTiming.DEFAULT_RANDOM_MODE==2&&OverlayTiming.seconds(800).equals("0.8"),"random2 default and fractional-second label");
            check(RecognitionRecovery.REQUIRED_STABLE_FRAMES==2&&OverlayTiming.FRESH_FRAME_MS==1400,"speed changes do not remove freshness/stability gates");
        }
        System.out.println("PASS "+checks+" recovery/timing checks: indefinite retries, pause, fresh frames, next opening, pending confirmation, fast/compatible timing");
    }
}
