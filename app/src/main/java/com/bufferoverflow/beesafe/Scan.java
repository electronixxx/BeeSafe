package com.bufferoverflow.beesafe;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import androidx.core.app.ActivityCompat;

import com.bufferoverflow.beesafe.AuxTools.AuxCrowd;
import com.bufferoverflow.beesafe.BackgroundService.AppPersistentNotificationManager;
import com.bufferoverflow.beesafe.BackgroundService.BackgroundScanWork;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleScanCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.scan.BleScanRuleConfig;
import com.google.android.gms.awareness.Awareness;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.hsr.geohash.GeoHash;


/*
 * This static class scans the nearby devices using a tracing algorithm, and
 * upload the data on the database. Before uploading the data, the algorithm filters out
 * devices that are away (has a low signal) and devices non mobile phones. This is not always
 * guaranteed based on different factors, but it tries to minimize these errors
 */


public class Scan {

    private static final List<String> blacklist = Arrays.asList( //Blacklist of non mobile devices based on names
            "TV", "Mi Band", "Airpods", "Buds"
    );
    private final static int SCAN_DURATION = 5; //In seconds
    private final static int RSSI_RANGE_FILTER = -90; //RSSI Signal Bound
    public static CountDownLatch scanLatch; //For sync purposes

    /* This should be called by the  backgrounf service every certain minutes to do a scan and upload data */
    public static void tracingAlgorithm(Context c) {
        scanLatch = new CountDownLatch(1);
        if (!safeActivityRecognition(c)) //Controls if users Activity is safe to begin scan
            return;
        screenStatusWait(c); //Wait until screen turns on
        Map<String, BleDevice> devices = scan(); //Initiate the scan process
        filterManufacturer(devices); //Filter out manufacturers
        filterRange(devices); //Filter out not nearby devices

        //Updating the notification content
        AuxCrowd.Crowded type = AuxCrowd.crowdType(devices.size());
        if (BackgroundScanWork.isBluetoothEnabled()) {
            if (type == AuxCrowd.Crowded.SAFE)
                AppPersistentNotificationManager.getInstance(c).updateNotification("BeeSafe Is Active", "Current location is safe. \uD83D\uDE00 ");
            else
                AppPersistentNotificationManager.getInstance(c).updateNotification("BeeSafe Is Active", "Crowd! Approximately " + devices.size() + " people \uD83D\uDE37 ");
        }

        uploadResult(c, devices.size()); //upload the scan to database

    }

    private static Map<String, BleDevice> scan() {
        Map<String, BleDevice> devices = new HashMap<>();
        BleManager bleManager = BleManager.getInstance();
        BleScanRuleConfig scanRuleConfig = new BleScanRuleConfig.Builder()
                .setScanTimeOut(SCAN_DURATION * 1000)
                .build();
        bleManager.initScanRule(scanRuleConfig);
        bleManager.scan(new BleScanCallback() {
            @Override
            public void onScanFinished(List<BleDevice> scanResultList) {
                for (BleDevice b : scanResultList)
                    devices.put(b.getMac(), b);
                Log.d("TRACING", "Scan finished : " + devices.size() + " devices");
                Log.d("TRACING", "Devices: ");
                for (Map.Entry<String, BleDevice> entry : devices.entrySet())
                    Log.d("TRACING", "MAC: " + entry.getValue().getMac() + " | Name: " + entry.getValue().getName());
                scanLatch.countDown();
            }

            @Override
            public void onScanStarted(boolean success) {
                Log.d("TRACING", "Scan Started");
            }

            @Override
            public void onScanning(BleDevice bleDevice) {
                Log.d("TRACING", "MAC: " + bleDevice.getMac() + " | Name: " + bleDevice.getName() + " | Signal : " + bleDevice.getRssi());
            }
        });
        try {
            scanLatch.await(SCAN_DURATION + 2, TimeUnit.SECONDS); //Wait for scan to be finished
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        scanLatch = new CountDownLatch(1); //Reset the latch
        return devices;
    }

    /* Checks if use is on car, public transport, general vehicle, bicycle or running.
     * If this is the case then we return false to skip the scan this time
     * If after a minute the algorithm isn't able to recognise the users activity, we return true
     */
    private static boolean safeActivityRecognition(Context context) {
        AtomicBoolean safeActivity = new AtomicBoolean(true);

        Awareness.getSnapshotClient(context).getDetectedActivity()
                .addOnSuccessListener(activityResponse -> {
                    ActivityRecognitionResult arr = activityResponse.getActivityRecognitionResult();
                    // getMostProbableActivity() is good enough for basic Activity detection.
                    DetectedActivity probableActivity = arr.getMostProbableActivity();

                    int currentActivity = probableActivity.getType();
                    if (currentActivity == DetectedActivity.IN_VEHICLE ||
                            currentActivity == DetectedActivity.ON_BICYCLE ||
                            currentActivity == DetectedActivity.RUNNING)
                        safeActivity.set(false);

                    int confidence = probableActivity.getConfidence();
                    String activityStr = probableActivity.toString();
                    Log.d("TRACING", "Activity: " + activityStr + ", Confidence: " + confidence + "/100");
                    scanLatch.countDown();
                });
        //Wait until activity recognition finishes
        try {
            scanLatch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        scanLatch = new CountDownLatch(1);
        return safeActivity.get();
    }

    /* Tries to minimize the errors of 1:1 correspondence between a user and a mobile phone.
     * It runs through a filter to remove devices which contain a certain pattern which
     * is a string containing common non mobile phone manufactures
     */
    private static void filterManufacturer(Map<String, BleDevice> devices) {
        for (String type : blacklist)
            for (Iterator<Map.Entry<String, BleDevice>> it = devices.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, BleDevice> b = it.next();
                if (b.getValue() != null && b.getValue().getName() != null && b.getValue().getName().contains(type))
                    it.remove();
            }
        Log.d("TRACING", "Manufacture filter finished, current devices : " + devices.size());
    }

    /* Filters out devices that are far away*/
    private static void filterRange(Map<String, BleDevice> devices) {
        for (Iterator<Map.Entry<String, BleDevice>> it = devices.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, BleDevice> b = it.next();
            if (b.getValue().getRssi() <= RSSI_RANGE_FILTER)
                it.remove();
        }
        Log.d("TRACING", "Range filtering finished, current devices : " + devices.size());
    }


    /* Uploads location to database */
    private static void uploadResult(Context context, int nrDevices) {
        Log.d("TRACING", "Calling upload procedure");
        FusedLocationProviderClient client;
        client = LocationServices.getFusedLocationProviderClient(context);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Task<android.location.Location> task = client.getLastLocation();
            task.addOnSuccessListener(location -> {
                if (location != null) {
                    String geoHash = GeoHash.withCharacterPrecision(location.getLatitude(), location.getLongitude(), Location.PRECISION).toBase32();
                    Location l = new Location(geoHash, nrDevices);
                    String areaGeoHash = l.getCoordinates().substring(0, Area.PRECISION);
                    String locationGeoHash = l.getCoordinates();

                    //Gets a node reference for the current 4Precision GeoHash
                    DatabaseReference mDatabase = FirebaseDatabase.getInstance().getReference().child(areaGeoHash).child(locationGeoHash);
                    mDatabase.setValue(l);
                    Log.d("TRACING", "Successfully uploaded data to location | " + areaGeoHash + " | " + locationGeoHash);
                }
            });
        } else {
            Log.d("TRACING", "ACCESS_FINE_LOCATION : " + ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION));
            Log.d("TRACING", "ACCESS_COARSE_LOCATION : " + ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION));
        }
    }

    /* Blocking until screen turn on
     * If screen is on during the method call, it returns immediately
     * If 10 minutes are passed without the user turning the screen on, it returns automatically
     * It uses a latch to implement this mechanism
     */
    public static void screenStatusWait(Context c) {
        //Filters for Screen On and Off to register on Broadcast
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);

        //Getting current Screen Status
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        for (Display display : dm.getDisplays())
            if (display.getState() == Display.STATE_ON) {
                scanLatch.countDown(); //releasing the latch immediately if screen is on
            } else { //screen is off
                c.registerReceiver(new BroadcastReceiver() { //setting a broadcast receiver
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) { //when screen turns on
                            c.unregisterReceiver(this); //unregister the receiver
                            scanLatch.countDown(); //releasing the latch
                        }
                    }
                }, intentFilter);
            }

        try {
            scanLatch.await(4, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        scanLatch = new CountDownLatch(1); //Reset the latch
    }

}
