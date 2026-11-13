package org.nuvio.gen5w.gate;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Optional Android 4.4 GPS movement soft lock.
 * Prevents video playback while vehicle is in motion if motion lockout is enabled in Settings.
 */
public final class MotionGuard implements LocationListener {
  public interface Listener {
    void onMotionStateChanged(boolean locked, String reason);
  }

  public static final float LIMIT_MPH=2.0f;
  private static final float LIMIT_MPS=0.89408f;
  private static final long LOCATION_TIMEOUT_MS=12000L;
  private static final long STATIONARY_MS=8000L;
  private final LocationManager manager;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Listener listener;
  private boolean active=false,locked=true;
  private long startedAt=0,lastGoodFix=0,stationarySince=0;
  private String reason="Waiting for GPS...";
  private float speedMph=0f;

  public MotionGuard(Context context,Listener callback){
    manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
    listener=callback;
  }

  public boolean isLocked(){return locked;}
  public String getReason(){return reason;}
  public float getSpeedMph(){return speedMph;}

  private void report(boolean newLocked,String message){
    if(!active)return;
    boolean changed=(newLocked!=locked)||!reason.equals(message);
    locked=newLocked;
    reason=message;
    if(changed&&listener!=null)listener.onMotionStateChanged(locked,reason);
  }

  private final Runnable watchdog=new Runnable(){
    @Override public void run(){
      if(!active)return;
      long now=SystemClock.elapsedRealtime();
      if(lastGoodFix==0){
        if(now-startedAt>15000L)report(true,"GPS unavailable. Check location reception or switch off Motion lockout in Settings.");
      }else if(now-lastGoodFix>LOCATION_TIMEOUT_MS){
        stationarySince=0;
        report(true,"GPS signal lost. Playback will remain paused until a stable location fix returns.");
      }
      handler.postDelayed(this,3000L);
    }
  };

  public synchronized void start(){
    if(active)return;
    active=true;locked=true;startedAt=SystemClock.elapsedRealtime();
    lastGoodFix=0;stationarySince=0;reason="Acquiring GPS fix...";speedMph=0f;
    try{
      if(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,1.0f,this,Looper.getMainLooper());
      }
    }catch(SecurityException e){report(true,"Location permission required for Motion Lockout.");return;}
    catch(Exception ignored){}
    handler.postDelayed(watchdog,3000L);
  }

  public synchronized void stop(){
    if(!active)return;
    active=false;handler.removeCallbacks(watchdog);
    try{manager.removeUpdates(this);}catch(Exception ignored){}
  }

  @Override public void onLocationChanged(Location location){
    if(!active||location==null)return;
    long now=SystemClock.elapsedRealtime();
    lastGoodFix=now;
    float mps=location.hasSpeed()?location.getSpeed():0f;
    speedMph=mps*2.23694f;
    if(mps>LIMIT_MPS){
      stationarySince=0;
      report(true,String.format(java.util.Locale.US,"Vehicle moving (%.1f mph). Park to resume video.",speedMph));
    }else{
      if(stationarySince==0)stationarySince=now;
      if(now-stationarySince>=STATIONARY_MS){
        report(false,"Parked");
      }else{
        report(true,"Waiting for vehicle to come to a full stop...");
      }
    }
  }

  @Override public void onStatusChanged(String provider,int status,Bundle extras){}
  @Override public void onProviderEnabled(String provider){}
  @Override public void onProviderDisabled(String provider){
    if(LocationManager.GPS_PROVIDER.equals(provider))report(true,"GPS disabled on device.");
  }
}
