package org.duofold.live

import android.app.KeyguardManager
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.*
import androidx.savedstate.*

/**
 * Persistent display overlays and lifecycle recovery for Duo fold rendering.
 * Root build: this is a plain object, not an AccessibilityService. The primary-display overlay is a compositor
 * layer created by the registered host (see LayerOverlay / UiHost), so no accessibility service and no
 * overlay permission are required.
 */
class StandaloneService private constructor(base:Context) : ContextWrapper(base), DisplayManager.DisplayListener, StandaloneFoldHost {
    companion object {
        @Volatile var instance: StandaloneService? = null; private set
        @JvmStatic fun start(context:Context):StandaloneService {
            instance?.let{return it}
            val created=StandaloneService(context.applicationContext)
            instance=created
            created.begin()
            return created
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private var view: ComposeView?=null
    private var secondary:SecondaryShade?=null
    private var nativeOverlay:NativeInnerOverlay?=null
    @Volatile private var secondaryReadySnapshot=false
    private var secondaryRetry=0L
    private var secondaryShownAt=0L
    private var secondaryError=""
    private var primaryKey=""
    private var secondaryKey=""
    private var owner: OverlayOwner?=null
    private var windowContext: Context?=null
    private var primaryLayer:LayerOverlay?=null
    private lateinit var displays: DisplayManager
    private var config by mutableStateOf(Configuration())
    private var image by mutableStateOf<Bitmap?>(null)
    private var frameBounds by mutableStateOf(Rect())
    private var capturedWindow=-1
    private val liveVeil=true
    private var sourceWasFresh=false
    private var visibleSince=0L
    /** Safety net: the effect is only meant to be visible during a fold. Visible for 45 s means stuck: turn it off. */
    private fun watchOverlay(){
        val v=view;val now=SystemClock.elapsedRealtime()
        val visible=v!=null && v.alpha>0f
        val safety=getSharedPreferences("safety",0)
        if(visible){
            if(visibleSince==0L){visibleSince=now;safety.edit().putBoolean("overlay_visible",true).apply()}
            else if(now-visibleSince>45000){
                RecoveryLog.add("Overlay watchdog: effect visible for 45 s; hiding it and turning the animation off")
                settings().edit().putBoolean("enabled",false).apply()
                visibleSince=0L;safety.edit().putBoolean("overlay_visible",false).apply()
                restart()
            }
        }else if(visibleSince!=0L){visibleSince=0L;safety.edit().putBoolean("overlay_visible",false).apply()}
    }
    private var active=false
    private var strength=0f
    private var generation=0
    private var pending=false
    private val captureGate=CaptureGate()
    private var captureQueued=false
    private var screenWidth=0
    private var screenHeight=0
    private val history=ArrayDeque<String>()
    var status="Service starting"; private set
    private fun note(message:String){status=message;history.addLast("${SystemClock.uptimeMillis()} $message");while(history.size>50)history.removeFirst()}
    private val captureTask=Runnable { captureQueued=false }
    private val screenReceiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
        RecoveryLog.add("Overlay received ${i.action}")
        if(i.action==Intent.ACTION_SCREEN_OFF){GlassFrames.suspendCapture();handler.postDelayed({if(!usable())HandoffFrames.clear()},300);owner?.registry?.currentState=Lifecycle.State.CREATED;clearUnavailableEffect()}
        else if(i.action==Intent.ACTION_USER_PRESENT){
            // Recreate the composition after unlock: old window IDs and stopped frame clocks are invalid.
            handler.postDelayed({if(instance===this@StandaloneService && settings().getBoolean("enabled",false)){restart();RecoveryLog.add("Overlay rebuilt after unlock")}},150)
        } else {updateDisplay();resumeIfUsable()}
    }}
    private fun usable()=::displays.isInitialized && displays.getDisplay(Display.DEFAULT_DISPLAY)?.state==Display.STATE_ON && !getSystemService(KeyguardManager::class.java).isKeyguardLocked()
    private fun previewMode()=AnimationModePolicy.mirrors(settings().getString("animation_mode",AnimationModePolicy.DEFAULT)) && LiveAngles.effectAllowed && settings().getBoolean("cover_preview",true) && !settings().getBoolean("dual",false)
    private fun settings()=getSharedPreferences("standalone",0)
    internal fun begin(){
        RecoveryLog.init(this);RecoveryLog.add("Root window host object created");handler.post(sourceGuard);displays=getSystemService(DisplayManager::class.java)
        displays.registerDisplayListener(this,handler)
        registerReceiver(screenReceiver,IntentFilter().apply {addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)},Context.RECEIVER_NOT_EXPORTED)
        restart()
    }
    fun restart(){
        removeHost()
        if(!settings().getBoolean("enabled",false)){note("Off — turn on Enable fold animation");return}
        runCatching { startForegroundService(Intent(this, FoldBackgroundService::class.java)) }.onFailure { note("Open Duo and tap Start background animation") }
        if(!UiHost.ready()){note("Waiting for the root window host to start");return}
        val display=displays.getDisplay(Display.DEFAULT_DISPLAY)?:return
        primaryKey=physicalKey(display)
        val panel=LayerOverlay(this,display,"Duo Fold Live");primaryLayer=panel
        val context=panel.context
        windowContext=context
        config=Configuration(context.resources.configuration)
        val size=panel.size();screenWidth=size[0];screenHeight=size[1]
        val lifecycle=OverlayOwner();owner=lifecycle
        lifecycle.registry.currentState=Lifecycle.State.CREATED
        val compose=ComposeView(context);view=compose;compose.alpha=0f
        compose.viewTreeObserver.addOnDrawListener{
            val inner=minOf(screenWidth,screenHeight).toFloat()/maxOf(screenWidth,screenHeight)>.7f
            // The glass SurfaceView supplies its own frame-bound completion.
            val glass=AnimationModePolicy.mirrors(settings().getString("animation_mode",AnimationModePolicy.DEFAULT)) && !settings().getBoolean("debug_mode",false)
            if(compose.alpha>0f && (!inner || !glass))HandoffFadeFrames.drawn(inner)
        }
        compose.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        compose.setViewTreeLifecycleOwner(lifecycle);compose.setViewTreeSavedStateRegistryOwner(lifecycle)
        val intensity=settings().getFloat("intensity",1f)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides config, LocalHinge provides rememberWindowHinge(context)) {
                DuoLiveShade(this@StandaloneService,intensity)
            }
        }
        try {panel.show(compose);if(previewMode())PreviewTransition.markAnimation(compose);resumeIfUsable();requestCapture();note("Ready — Duo live shading running (compositor layer)")}
        catch(e:Exception){removeHost();note("Could not attach overlay layer: ${e.javaClass.simpleName}: ${e.message}")}
    }
    private fun resumeIfUsable(){if(usable()){GlassFrames.resumeCapture();owner?.registry?.currentState=Lifecycle.State.RESUMED}}
    override fun onMovement(){requestCapture()}
    override fun onFrame(active:Boolean,strength:Float){
        val changed=this.active!=active;this.active=active;this.strength=strength
        if(!usable() || !LiveAngles.fresh() || !LiveAngles.effectAllowed){hide();this.strength=0f;return}
        view?.alpha=if(active && (liveVeil || image!=null))1f else 0f
        if(changed && !active)SupportPrompts.recordUse(this)
        if(changed)note(if(active)"Duo transition active" else "Ready — Duo transition complete")
        if(active)requestCapture()
    }
    fun clearUnavailableEffect(){
        dismissSecondary();hide();strength=0f;generation++;pending=false;image=null
    }
    private val sourceGuard=object:Runnable {override fun run(){
        val fresh=LiveAngles.fresh()
        if(!fresh && (active || sourceWasFresh)){hide();note("Effect paused — waiting for fresh angles; panel session retained")}
        sourceWasFresh=fresh
        watchOverlay()
        refreshSecondary()
        handler.postDelayed(this,250)
    }}
    private fun hide(){view?.alpha=0f;active=false;handler.removeCallbacks(captureTask);captureQueued=false}
    private fun requestCapture()=Unit // Retained host callback; this release never captures pixels.
    private fun updateDisplay(){
        if(view==null)return
        val display=displays.getDisplay(0)?:return
        if(physicalKey(display)!=primaryKey){RecoveryLog.add("Primary physical panel changed; rebuilding overlay");restart();return}
        val next=Configuration(windowContext!!.resources.configuration)
        val size=primaryLayer?.size()?:return
        if(screenWidth!=size[0]||screenHeight!=size[1]){
            screenWidth=size[0];screenHeight=size[1];generation++;pending=false;image=null;view?.alpha=0f
        }
        primaryLayer?.resize()
        // Keep the composition alive: Duo retains its timeline and both snapshot-morph images.
        config=next;requestCapture()
    }
    override fun onDisplayChanged(id:Int){handler.post{if(id==Display.DEFAULT_DISPLAY){updateDisplay();resumeIfUsable()};if(secondary?.display?.displayId==id)secondary?.relayout();refreshSecondary()}}
    override fun onDisplayAdded(id:Int){handler.post{refreshSecondary()}}
    override fun onDisplayRemoved(id:Int){handler.post{refreshSecondary()}}
    private fun removeHost(){
        dismissSecondary();generation++;pending=false;hide();FoldBridgeActivity.cancel()
        owner?.registry?.currentState=Lifecycle.State.DESTROYED
        view?.disposeComposition()
        primaryLayer?.close();primaryLayer=null
        view=null;owner=null;image=null;windowContext=null
    }
    fun secondaryReady():Boolean=secondaryReadySnapshot
    private fun dismissSecondary(){nativeOverlay?.dismiss();nativeOverlay=null;secondaryReadySnapshot=false;val old=secondary;secondary=null;runCatching{old?.dismiss()}}
    fun refreshSecondary(){
        if(!::displays.isInitialized)return
        val current=displays.getDisplay(0)
        val primaryInner=current?.mode?.let{minOf(it.physicalWidth,it.physicalHeight).toFloat()/maxOf(it.physicalWidth,it.physicalHeight)>.7f}?:false
        PreviewTransition.configure(this)
        PreviewTransition.update(!LiveAngles.nativeInner && previewMode() && settings().getBoolean("enabled",false) && !getSystemService(KeyguardManager::class.java).isKeyguardLocked(),primaryInner)
        val native=LiveAngles.continuityNative && !primaryInner
        val preview=!settings().getBoolean("dual",false) && settings().getBoolean("cover_preview",true) && LiveAngles.coverPreview
        if(!LiveAngles.effectAllowed||!settings().getBoolean("enabled",false)||(!native && !preview && (!settings().getBoolean("dual",false)||!LiveAngles.dualActive))||!usable()){dismissSecondary();return}
        val target=displays.displays.firstOrNull{it.displayId==1 && BuiltInPanel.accepts(it)}
        if(target==null){secondaryError="Concurrent mode has not exposed the second built-in panel";dismissSecondary();return}
        if(native){
            val key=physicalKey(target)+":native-overlay"
            if(secondaryKey==key && nativeOverlay?.attached==true){secondaryReadySnapshot=nativeOverlay!!.draws>0;return}
            if(SystemClock.elapsedRealtime()<secondaryRetry)return
            dismissSecondary()
            try{
                val overlay=NativeInnerOverlay(this,target,settings().getFloat("intensity",1f));nativeOverlay=overlay
                overlay.show();secondaryKey=key;secondaryError="";RecoveryLog.add("Native inner accessibility overlay attached on display 1")
            }catch(e:Exception){dismissSecondary();secondaryError="${e.javaClass.simpleName}: ${e.message}";secondaryRetry=SystemClock.elapsedRealtime()+1500;RecoveryLog.add("Native inner overlay error: $secondaryError")}
            return
        }
        if(nativeOverlay!=null)dismissSecondary()
        if(secondaryKey==physicalKey(target)+":"+preview+":"+native && secondary?.display?.displayId==target.displayId && secondary?.isShowing==true){
            if(SystemClock.elapsedRealtime()-secondaryShownAt>20000){RecoveryLog.add("Second-panel watchdog: dismissed after 20 s");dismissSecondary();secondaryRetry=SystemClock.elapsedRealtime()+10000;return}
            secondary?.refresh();secondaryReadySnapshot=secondary?.ready==true;return}
        if(SystemClock.elapsedRealtime()<secondaryRetry)return
        dismissSecondary()
        try{
            val next=SecondaryShade(this,target,settings().getFloat("intensity",1f),preview,native){RecoveryLog.add(it)}
            next.setOnDismissListener{RecoveryLog.add("Secondary Presentation dismissed");if(secondary===next){secondary=null;secondaryReadySnapshot=false}}
            secondary=next;secondaryKey=physicalKey(target)+":"+preview+":"+native;next.show();secondaryShownAt=SystemClock.elapsedRealtime();secondaryError="";RecoveryLog.add("Secondary Presentation shown, display ${target.displayId}")
        }catch(e:Exception){dismissSecondary();secondaryError="${e.javaClass.simpleName}: ${e.message}";secondaryRetry=SystemClock.elapsedRealtime()+1500;RecoveryLog.add("Secondary Presentation error: $secondaryError")}
    }
    private fun physicalKey(d:Display)="${d.displayId}:${d.mode.physicalWidth}x${d.mode.physicalHeight}"
    private fun displayReport()=if(!::displays.isInitialized)"unavailable" else displays.displays.joinToString("; "){d->
        "id=${d.displayId} state=${d.state} mode=${d.mode.physicalWidth}x${d.mode.physicalHeight} rotation=${d.rotation}"
    }
    fun report()="Duo Fold Live ${BuildConfig.VERSION_NAME}\nSelectable glass / live angles\n${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n$status\n${LiveAngles.status}\n${FoldAwakeDefault.status}\n${FoldAwakeGuard.status}\n${RotationMigration.status}\nCustom wallpaper: ${org.duofold.live.wallpaperlayer.WallpaperRestore.status}\nBackground service: ${FoldBackgroundService.running}\n${FoldBackgroundService.connectionReport()}\n${LiveAngles.latencyReport()}\nAngle age: ${LiveAngles.ageMs()} ms\nCover preview: ${settings().getBoolean("cover_preview",true)}\nPreview bridge: ${LiveAngles.expansionStatus}\nPreview preparation: ${PreviewTransition.status}\nBridge trace: ${LiveAngles.bridgeTrace}\nSmoothing: ${settings().getFloat("smoothing_ms",30f)} ms; full-resolution glass: ${settings().getBoolean("full_resolution_glass",false)}\nMode: ${if(settings().getBoolean("debug_mode",false)) "Debug black fade" else "Projected glass"}\n${LiveAngles.handoffStatus}\n${LiveAngles.handoffFade}\n${LiveAngles.rotationHold}\n${LiveAngles.continuityStatus}\n${InnerDecorRecovery.apiStatus}\n${InnerDecorRecovery.status}\n${LiveAngles.continuityTrace}\nScreen placement: ${if(settings().getBoolean("window_reveal_v2",true)) "V2 Window Reveal" else "Original"}\nCover startup easing: ${settings().getBoolean("startup_easing",true)}\nCover early stretch (%): ${settings().getFloat("early_stretch",2.7f)*100f/3f}; enhanced end=${settings().getBoolean("enhanced_end_stretch",true)}; end stretch=${settings().getFloat("end_stretch",1.25f)*100f}%\nInner startup easing: ${settings().getBoolean("inner_startup_easing",true)}; early stretch=${settings().getFloat("inner_early_stretch",.9f)*100f/3f}%; enhanced end=${settings().getBoolean("inner_enhanced_end_stretch",true)}; end stretch=${settings().getFloat("inner_end_stretch",.45f)*100f}%\nVertical compression: cover=${settings().getFloat("cover_vertical_compression",1.15f)*100f}%; inner=${settings().getFloat("inner_vertical_compression",.6f)*100f}%\nAA method: ${settings().getInt("antialias_method_v2",0)} (0=lightweight, 1=edge post-process, 2=4x buffer); enabled=${settings().getBoolean("antialias_enabled",true)}; strength=${settings().getFloat("antialias_strength",.35f)}\nGlass: ${GlassFrames.status}\nHandoff: ${HandoffFrames.status}\nSecondary ready: ${secondaryReady()}; draws: ${nativeOverlay?.draws ?: secondary?.draws ?: 0}\nSecondary layer: ${if(nativeOverlay!=null)"Native inner accessibility overlay" else secondary?.layer ?: "none"}; strength: ${nativeOverlay?.strength ?: secondary?.strength ?: 0f}\nSecondary error: $secondaryError\nDisplays: ${displayReport()}\nAnimation: ${AnimationModePolicy.label(settings().getString("animation_mode",AnimationModePolicy.DEFAULT))}\n${FrameTelemetry.report()}\nStrength: $strength\n"+history.joinToString("\n")+"\nLifecycle / connection events:\n"+RecoveryLog.report()+"\n24-hour health samples (app process only):\n"+HealthTrace.report(this)
    /** The registered host ended: its compositor permissions, and therefore the layer, are gone. */
    fun hostLost(){removeHost();note("Root window host stopped; waiting for it to restart")}
    fun shutdown(){PreviewTransition.update(false,false);removeHost();if(::displays.isInitialized)displays.unregisterDisplayListener(this);runCatching {unregisterReceiver(screenReceiver)};handler.removeCallbacksAndMessages(null);instance=null}
}
internal class OverlayOwner:LifecycleOwner,SavedStateRegistryOwner {
    val registry=LifecycleRegistry(this)
    private val controller=SavedStateRegistryController.create(this)
    override val lifecycle:Lifecycle get()=registry
    override val savedStateRegistry:SavedStateRegistry get()=controller.savedStateRegistry
    init {controller.performAttach();controller.performRestore(null)}
}
