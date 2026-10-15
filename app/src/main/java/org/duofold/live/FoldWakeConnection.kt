package org.duofold.live

import android.content.*
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.duofold.live.root.Shizuku

/** One closing episode owns one warm connection; no settings writes or continuous wake loop. */
internal class FoldWakeConnection(context:Context):AutoCloseable {
 companion object {
  private val history=java.util.ArrayDeque<String>()
  @Synchronized private fun note(message:String){
   history.addLast("${SystemClock.elapsedRealtime()}: $message")
   while(history.size>32)history.removeFirst()
   RecoveryLog.add(message)
  }
  @Synchronized fun report()="Prepared fold wake trace (elapsed-realtime ms; software observations):\n"+history.joinToString("\n")
 }
 private val main=Handler(Looper.getMainLooper())
 private val active=AtomicBoolean(true)
 private val worker=Executors.newSingleThreadExecutor()
 private var binder:IBinder?=null
 private var ready=false
 private var used=false
 private var waiting:(()->Unit)?=null
 private val started=SystemClock.elapsedRealtime()
 private var binding:HelperBinding?=null
 init {
  note("PREPARE requested; separate closing-episode connection")
  val args=Shizuku.UserServiceArgs(ComponentName(context.applicationContext,FoldWakeService::class.java))
   .daemon(false).processNameSuffix("fold_wake").version(BuildConfig.VERSION_CODE)
  val connection=object:ServiceConnection {
   override fun onServiceConnected(name:ComponentName,service:IBinder){
    if(!active.get())return
    binder=service
    transact("fold_prepare",null){result->
     if(result.getBoolean("ok")){
      ready=true
      note("READY; preparation=${SystemClock.elapsedRealtime()-started} ms")
      binding?.stage("READY at elapsed=${SystemClock.elapsedRealtime()}; preparation=${SystemClock.elapsedRealtime()-started} ms; ${result.getString("value")}")
      waiting?.also{waiting=null;it()}
     }else {binding?.stage("prepare failed: ${result.getString("error")}");close()}
    }
   }
   override fun onServiceDisconnected(name:ComponentName){close()}
  }
  binding=HelperBinding(args,"Fold wake prepared helper",connection){message->note(message);close()}
  binding?.start()
 }
 fun wake(armed:Long,deadline:Long,done:(Bundle)->Unit){
  if(used || !active.get()){
   done(Bundle().apply{putString("error","Prepared wake connection unavailable; no wake requested")});return
  }
  used=true
  val detected=SystemClock.elapsedRealtime()
  note("Fold wake SLEEP_OBSERVED elapsed=$detected; helperReady=$ready; leaseRemaining=${deadline-SystemClock.uptimeMillis()} ms")
  val call={
   if(active.get() && FoldWakePolicy.validLease(armed,SystemClock.uptimeMillis(),deadline)){
    note("WAKE_DISPATCH; detection-to-dispatch=${SystemClock.elapsedRealtime()-detected} ms")
    binding?.stage("WAKE_DISPATCH elapsed=${SystemClock.elapsedRealtime()}; detection-to-dispatch=${SystemClock.elapsedRealtime()-detected} ms")
    transact("fold_wake","$armed:$deadline"){result->
     note("Fold wake REPLY elapsed=${SystemClock.elapsedRealtime()}; ${result.getString("value")?:result.getString("error")}")
     done(result)
    }
   }else done(Bundle().apply{putString("error","Closing lease expired before wake dispatch")})
  }
  if(ready)call() else waiting=call
 }
 private fun transact(action:String,argument:String?,done:(Bundle)->Unit){
  val service=binder?:return
  worker.execute {
   if(!active.get())return@execute
   val request=Parcel.obtain();val reply=Parcel.obtain()
   val result=try {
    request.writeInterfaceToken(FoldSettingsService.TOKEN);request.writeString(action);request.writeString(argument)
    check(service.transact(1,request,reply,0)){"Wake helper unavailable"};reply.readException()
    reply.readBundle(FoldWakeConnection::class.java.classLoader)?:Bundle().apply{putString("error","Empty wake reply")}
   }catch(e:Exception){Bundle().apply{putString("error",e.toString())}}
   finally{request.recycle();reply.recycle()}
   main.post {if(active.get())done(result)}
  }
 }
 override fun close(){
  if(!active.getAndSet(false))return
  note("CLOSE; ready=$ready; attempted=$used; age=${SystemClock.elapsedRealtime()-started} ms")
  waiting=null;ready=false;binder=null
  binding?.close();binding=null;worker.shutdownNow()
 }
}
