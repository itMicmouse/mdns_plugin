package com.mutablelogic.mdns_plugin;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class MDNSPlugin implements MethodChannel.MethodCallHandler,EventChannel.StreamHandler {

  private NsdManager nsdManager  = null;
  private EventChannel.EventSink sink  = null;
  private Activity activity  = null;
  private DiscoveryListener discoveryListener  = null;
  private HashMap<String, NsdServiceInfo> services   = new HashMap<String,NsdServiceInfo>();

  public MDNSPlugin(PluginRegistry.Registrar registrar) {
    nsdManager = (NsdManager)registrar.activeContext().getSystemService(Context.NSD_SERVICE);
    activity = registrar.activity();
    new EventChannel(registrar.messenger(), "mdns_plugin_delegate").setStreamHandler(this);
  }

  public  static void registerWith(PluginRegistry.Registrar registrar) {
    new MethodChannel(registrar.messenger(), "mdns_plugin").setMethodCallHandler(new MDNSPlugin(registrar));
  }

  public  static HashMap<String,Object> mapFromServiceInfo(String method,NsdServiceInfo serviceInfo) {
    HashMap<String, Object> map = new HashMap<String, Object>();
    map.put("method",method);
    if(serviceInfo!=null){
      InetAddress host = serviceInfo.getHost();
      map.put("name",serviceInfo.getServiceName());
      map.put("type",serviceInfo.getServiceType() + "." );
      map.put("port",serviceInfo.getPort());
      map.put("txt",serviceInfo.getAttributes());

      if(host!=null){
        map.put("hostName",host.getHostName());
        String hostAddress = host.getHostAddress();
        map.put("addr",hostAddress+serviceInfo.getPort());
      }
    }
    return map;
  }


  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    switch (call.method){
      case "getPlatformVersion":
        result.success("Android"+ Build.VERSION.RELEASE);
        break;
      case "startDiscovery":
        String serviceType = call.argument("serviceType");
        Boolean enableUpdating = call.argument("enableUpdating");
        if(serviceType==null){
          serviceType = "";
        }
        if(enableUpdating==null){
          enableUpdating = false;
        }
        startDiscovery(result,serviceType,enableUpdating);
        break;
      case "stopDiscovery":
        stopDiscovery(result);
        break;
      case "resolveService":

        String name = call.argument("name");
        Boolean resolve = call.argument("resolve");
        if(name==null){
          name = "";
        }
        if(resolve==null){
          resolve = false;
        }
        resolveService(result,name,resolve);
        break;
      default:
        result.notImplemented();
    }
  }

  private void resolveService(MethodChannel.Result result, String name, Boolean resolve) {
    if(services.containsKey(name)) {
      if(resolve) {
        if(nsdManager!=null) {
          nsdManager.resolveService(services.get(name),new ResolveListener());
        }
      } else {
        services.remove(name);
      }
    } else {
      Log.w("MDNSPlugin", "resolveService: missing service with name "+name);
    }
  }

  private void stopDiscovery(MethodChannel.Result result) {
    if(nsdManager!=null){
      nsdManager.stopServiceDiscovery(discoveryListener);
    }
    discoveryListener = null;
    services.clear();
    result.success(null);
  }

  private void startDiscovery(MethodChannel.Result result,String serviceType,Boolean enableUpdating) {
    if(enableUpdating) {
      Log.w("MDNSPlugin", "startDiscovery: enableUpdating is currently ignored on the Android platform");
    }
    if(discoveryListener!=null&&nsdManager!=null){
      nsdManager.stopServiceDiscovery(discoveryListener);
    }
    discoveryListener = new DiscoveryListener();
    services.clear();
    if(nsdManager!=null) {
      nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }
    result.success(null);
  }

  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    this.sink = events;
  }

  @Override
  public void onCancel(Object arguments) {
    this.sink = null;
  }


  class ResolveListener implements NsdManager.ResolveListener {

    @Override
    public void onResolveFailed(final NsdServiceInfo serviceInfo, int errorCode) {

      switch (errorCode){
        case NsdManager.FAILURE_ALREADY_ACTIVE :{
          // Resolve again after a short delay
          TimerTask task = new TimerTask(){
            public void run(){
              if(discoveryListener!=null){
                discoveryListener.onServiceFound(serviceInfo);
              }
            }
          };
          Timer timer = new Timer();
          timer.schedule(task,20);
          break;
        }
        default:{
          Log.d("MDNSPlugin", "onResolveFailed: Error $errorCode: $serviceInfo");
        }
      }
    }

    @Override
    public void onServiceResolved(NsdServiceInfo serviceInfo) {
      final HashMap<String, Object> onServiceResolved = MDNSPlugin.mapFromServiceInfo("onServiceResolved", serviceInfo);
      if(activity!=null) {
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(sink!=null){
              sink.success(onServiceResolved);
            }
          }
        });
      }
    }
  }

  class DiscoveryListener implements NsdManager.DiscoveryListener {

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
      Log.d("MDNSPlugin", "onStartDiscoveryFailed:"+serviceType+errorCode);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
      Log.d("MDNSPlugin", "onStopDiscoveryFailed: "+serviceType+errorCode);
    }

    @Override
    public void onDiscoveryStarted(String serviceType) {
      if(activity!=null){
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(sink!=null){
              HashMap hashMap = new HashMap();
              hashMap.put("method","onDiscoveryStarted");
              sink.success(hashMap);
            }
          }
        });
      }
    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
      if(activity!=null){
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(sink!=null){
              HashMap hashMap = new HashMap();
              hashMap.put("method","onDiscoveryStopped");
              sink.success(hashMap);
            }
          }
        });
      }
    }

    @Override
    public void onServiceFound(NsdServiceInfo serviceInfo) {
      final HashMap<String, Object> serviceMap = mapFromServiceInfo("onServiceFound", serviceInfo);
      String name = serviceInfo.getServiceName();
      if(name!=null){
        services.put(name,serviceInfo);
      }
      if(activity!=null){
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(sink!=null){
              sink.success(serviceMap);
            }
          }
        });
      }
    }

    @Override
    public void onServiceLost(NsdServiceInfo serviceInfo) {
      final HashMap<String, Object> serviceMap = mapFromServiceInfo("onServiceRemoved",serviceInfo);
      services.remove(serviceInfo.getServiceName());
      if(activity!=null){
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if(sink!=null){
              sink.success(serviceMap);
            }
          }
        });
      }
    }
  }
}
