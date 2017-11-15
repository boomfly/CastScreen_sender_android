 /*
 * Copyright (C) 2016 Jones Chi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.renesas.castscreendemo;

 import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.StrictMode;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.renesas.castscreendemo.Config.CAST_DISPLAY_NAME;

 public class MyCastService extends Service implements CircularEncoder.Callback {
    private final String TAG = "CastService";
    private final int NT_ID_CASTING = 0;
    private Handler mHandler = new Handler(new ServiceHandlerCallback());
    private Messenger mMessenger = new Messenger(mHandler);
    private ArrayList<Messenger> mClients = new ArrayList<Messenger>();
    private IntentFilter mBroadcastIntentFilter;

    private static final String HTTP_MESSAGE_TEMPLATE = "POST /api/v1/h264 HTTP/1.1\r\n" +
            "Connection: close\r\n" +
            "X-WIDTH: %1$d\r\n" +
            "X-HEIGHT: %2$d\r\n" +
            "\r\n";


    private MediaProjectionManager mMediaProjectionManager;
    private String mReceiverIp;
    private int mResultCode;
    private Intent mResultData;
    private String mSelectedFormat;
    private int mSelectedWidth;
    private int mSelectedHeight;
    private int mSelectedDpi;
    private int mSelectedBitrate;
    private String mSelectedEncoderName;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;
     private DisplayManager mDisplayManager;
     //private Recorder mRecorder;
     CircularEncoder mCircularEncoder;

     private ServerSocket mServerSocket;
     private Socket mSocket;
     private OutputStream mSocketOutputStream;


     @Override
     public void bufferStatus(long totalTimeMsec) {
         Log.w(TAG, "bufferStatus(" + totalTimeMsec + ")");


         if(totalTimeMsec > 1000) {
             boolean res= mCircularEncoder.writeChunk();
             if(!res) {
                 Log.e(TAG, "bufferStatus: error" );
                 stopScreenCapture();
             }
         }

     }

     private class ServiceHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            Log.d(TAG, "Handler got event, what: " + msg.what);
            switch (msg.what) {
                case Config.MSG_REGISTER_CLIENT: {
                    mClients.add(msg.replyTo);
                    break;
                }
                case Config.MSG_UNREGISTER_CLIENT: {
                    mClients.remove(msg.replyTo);
                    break;
                }
                case Config.MSG_STOP_CAST: {
                    stopScreenCapture();
                    closeSocket(true);
                    stopSelf();
                }
            }
            return false;
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Service receive broadcast action: " + action);
            if (action == null) {
                return;
            }
            if (Config.ACTION_STOP_CAST.equals(action)) {
                stopScreenCapture();
                closeSocket(true);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mBroadcastIntentFilter = new IntentFilter();
        mBroadcastIntentFilter.addAction(Config.ACTION_STOP_CAST);
        registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroy service");
        stopScreenCapture();
        closeSocket(true);
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        mReceiverIp = intent.getStringExtra(Config.EXTRA_RECEIVER_IP);
        mResultCode = intent.getIntExtra(Config.EXTRA_RESULT_CODE, -1);
        mResultData = intent.getParcelableExtra(Config.EXTRA_RESULT_DATA);
        Log.d(TAG, "Remove IP: " + mReceiverIp);
        if (mReceiverIp == null) {
            return START_NOT_STICKY;
        }
        mSelectedWidth = intent.getIntExtra(Config.EXTRA_SCREEN_WIDTH, Config.DEFAULT_SCREEN_WIDTH);
        mSelectedHeight = intent.getIntExtra(Config.EXTRA_SCREEN_HEIGHT, Config.DEFAULT_SCREEN_HEIGHT);
        mSelectedDpi = intent.getIntExtra(Config.EXTRA_SCREEN_DPI, Config.DEFAULT_SCREEN_DPI);
        mSelectedBitrate = intent.getIntExtra(Config.EXTRA_VIDEO_BITRATE, Config.DEFAULT_VIDEO_BITRATE);
        mSelectedFormat = intent.getStringExtra(Config.EXTRA_VIDEO_FORMAT);
        mSelectedEncoderName = intent.getStringExtra(Config.EXTRA_VIDEO_ENCODER_NAME);
        if (mSelectedFormat == null) {
            mSelectedFormat = Config.DEFAULT_VIDEO_FORMAT;
        }
        if (mReceiverIp.length() <= 0) {
            Log.e(TAG, "ERROR NO RECEIVER");
            stopSelf();
        } else {
            Log.d(TAG, "Start with client mode");
            if (!createSocket()) {
                Log.e(TAG, "Failed to create socket to receiver, ip: " + mReceiverIp);
                return START_NOT_STICKY;
            }
            startScreenCapture();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private void showNotification() {
        final Intent notificationIntent = new Intent(Config.ACTION_STOP_CAST);
        PendingIntent notificationPendingIntent = PendingIntent.getBroadcast(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setDefaults(Notification.DEFAULT_ALL)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.casting_screen))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), notificationPendingIntent);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NT_ID_CASTING, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NT_ID_CASTING);
    }

    private void startScreenCapture() {
        if(mDisplayManager==null) mDisplayManager=(DisplayManager) getSystemService(DISPLAY_SERVICE);
        if(mMediaProjection==null) mMediaProjection = mMediaProjectionManager.getMediaProjection(mResultCode, mResultData);
        prepareVideoEncoder();
        Log.w(TAG, "startScreenCapture");
        prepareVirtualDisplay();
        showNotification();

    }
    private void prepareVirtualDisplay(){
        if (mResultCode != 0 && mResultData != null) {
            if(mVirtualDisplay==null){
                mVirtualDisplay = mMediaProjection.createVirtualDisplay(CAST_DISPLAY_NAME, mSelectedWidth,
                        mSelectedHeight, mSelectedDpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mInputSurface,
                        null, null);
            }else {
                Log.e(TAG, "prepareVirtualDisplayMP: Display is already created" );
            }
        }
    }


     private void prepareVideoEncoder() {
        MediaFormat format = MediaFormat.createVideoFormat(mSelectedFormat, mSelectedWidth, mSelectedHeight);
        int frameRate = Config.DEFAULT_VIDEO_FPS;
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mSelectedBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        try {
            if(mSelectedEncoderName==null){
                Log.d(TAG, "prepareVideoEncoder: mSelectedEncoderName==null");
                mSelectedEncoderName=Utils.getEncoderName(mSelectedFormat);
            }
            Log.w(TAG, "prepareVideoEncoder: using "+ mSelectedEncoderName);
            mCircularEncoder = new CircularEncoder(mSocketOutputStream,mSelectedEncoderName,mSelectedWidth, mSelectedHeight, mSelectedBitrate,
                    frameRate, Config.DEFAULT_BUFFER_LENGTH, this);
            mInputSurface= mCircularEncoder.getInputSurface();
        } catch (IOException e) {
            e.printStackTrace();
            stopScreenCapture();
        }
     }


     private void stopScreenCapture() {
         Log.w(TAG, "stopScreenCapture");
        dismissNotification();
         closeSocket();
         mCircularEncoder.shutdown();
        if(mMediaProjection!=null){
            mMediaProjection.stop();
            mMediaProjection=null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

    }




    private boolean createSocket() {
        Log.w(TAG, "createSocket" );
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress serverAddr = InetAddress.getByName(mReceiverIp);

                    mSocket = new Socket(serverAddr, Config.VIEWER_PORT);
                    mSocketOutputStream = mSocket.getOutputStream();
                    OutputStreamWriter osw = new OutputStreamWriter(mSocketOutputStream);
                    String format =String.format(HTTP_MESSAGE_TEMPLATE, mSelectedWidth, mSelectedHeight);
                    Log.w(TAG, "format="+format );
                    osw.write(format);
                    osw.flush();
                    mSocketOutputStream.flush();
                    return;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mSocket = null;
                mSocketOutputStream = null;
            }
        });
        th.start();
        try {
            th.join();
            if (mSocket != null && mSocketOutputStream != null) {
                return true;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void closeSocket() {
        closeSocket(false);
    }

    private void closeSocket(boolean closeServerSocket) {
        Log.w(TAG, "closeSocket" );
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (closeServerSocket) {
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            mServerSocket = null;
        }
        mSocket = null;
        mSocketOutputStream = null;
    }


}