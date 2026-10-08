package org.duofold.live;
import android.app.Application;
import android.content.SharedPreferences;
/** Applies the release's requested mode once; later user selections remain intact. */
public final class DuoApplication extends Application {
 @Override public void onCreate(){
  super.onCreate();
  safeModeCheck();
  org.duofold.live.root.Shizuku.init(this);
  FoldAwakeDefault.persist(this);
  SharedPreferences prefs=getSharedPreferences("standalone",MODE_PRIVATE);
  if(!prefs.getBoolean("defaults_170_applied",false)){
   prefs.edit().putBoolean("cover_preview",true).putBoolean("dual",false)
    .putBoolean("defaults_170_applied",true).apply();
  }
  // Owner-requested 3.5.1 upgrade: apply only these two values once.
  // Runs before activities/services read their settings; later edits persist.
  if(!prefs.getBoolean("stretch_glass_defaults_351",false)){
   prefs.edit().putFloat("end_stretch",1.25f).putFloat("intensity",1f)
    .putBoolean("stretch_glass_defaults_351",true).apply();
  }
  // Set once; never overwrite a saved mode or the user's fade tuning on updates.
  if(!prefs.contains("animation_mode"))prefs.edit().putString("animation_mode",AnimationModePolicy.DEFAULT).apply();
  if(!prefs.getBoolean("preview_default_203",false))prefs.edit().putBoolean("cover_preview",true).putBoolean("preview_default_203",true).apply();
  new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> org.duofold.live.wallpaperlayer.WallpaperRestore.resume(this));
 }
 /**
  * If the phone was restarted while the effect was on screen, or this is the first run of a build with new overlay code,
  * start with the animation OFF so it can never lock the user out. The user turns it on again in the app.
  */
 private void safeModeCheck(){
  try{
   android.content.SharedPreferences safety=getSharedPreferences("safety",0);
   android.content.SharedPreferences standalone=getSharedPreferences("standalone",0);
   int boot=android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,0);
   boolean rebooted=safety.getInt("boot_count",-1)!=boot;
   boolean stuckAtReboot=rebooted&&safety.getBoolean("overlay_visible",false);
   boolean firstRunOfThisBuild=!safety.getBoolean("overlay_safe_v3",false);
   if(stuckAtReboot||firstRunOfThisBuild){
    standalone.edit().putBoolean("enabled",false).apply();
    RecoveryLog.init(this);
    RecoveryLog.add(stuckAtReboot?"Safe mode: the effect was on screen when the phone restarted; animation turned off":"Safe mode: first run of this build; animation turned off");
   }
   String primaryMode=standalone.getString("primary_mode","wm");
   if(!"wm".equals(primaryMode)&&!"free".equals(primaryMode))standalone.edit().putString("primary_mode","wm").apply(); // "window" was removed
   safety.edit().putInt("boot_count",boot).putBoolean("overlay_visible",false).putBoolean("overlay_safe_v3",true).apply();
  }catch(Throwable ignored){}
 }
}
