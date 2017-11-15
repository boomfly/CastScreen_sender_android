/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import static android.renesas.castscreendemo.Config.DEFAULT_VIDEO_FORMAT;

/**
 * Encodes video in a fixed-size circular buffer.
 * <p>
 * The obvious way to do this would be to store each packet in its own buffer and hook it
 * into a linked list.  The trouble with this approach is that it requires constant
 * allocation, which means we'll be driving the GC to distraction as the frame rate and
 * bit rate increase.  Instead we create fixed-size pools for video data and metadata,
 * which requires a bit more work for us but avoids allocations in the steady state.
 * <p>
 * Video must always start with a sync frame (a/k/a key frame, a/k/a I-frame).  When the
 * circular buffer wraps around, we either need to delete all of the data between the frame at
 * the head of the list and the next sync frame, or have the file save function know that
 * it needs to scan forward for a sync frame before it can start saving data.
 * <p>
 * When we're told to save a snapshot, we create a MediaMuxer, write all the frames out,
 * and then go back to what we were doing.
 */
public class CircularEncoder  {
    private static final String TAG = "CircularEncoder";
    private static final boolean VERBOSE = true;

    private static final int IFRAME_INTERVAL = 1;           // sync frame every second
    private final CircularEncoderBuffer mCircularBuffer;

    private OutputStream mOS;

    private EncoderThread mEncoderThread;
    private Surface mInputSurface;
    private MediaCodec mEncoder;


    public synchronized boolean writeChunk() {
                Log.d(TAG, "writeChunk() called");
                int index = mCircularBuffer.getFirstIndex();
                if (index < 0) {
                    Log.e(TAG, "Unable to get first index");
                    return false;
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int result = -1;
                try {

                    do {
                        ByteBuffer data = mCircularBuffer.getChunk(index, info);
                        data.position(info.offset);
                        int size = data.remaining();
                        final byte[] buffer = new byte[info.size];
                        Log.d(TAG, "will write offset="+info.offset+" info.size="+ info.size+" size("+size+")");

                        data.get(buffer);

                        mOS.write(buffer, 0,info.size);
                        mOS.flush();


                        index = mCircularBuffer.getNextIndex(index);
                    } while (index >= 0);
                    result = 0;
                } catch (Exception ioe) {
                    Log.e(TAG, "muxer failed", ioe);
                    result = 2;
                } finally {
                   // this.frameAvailableSoon();


                }

                if (VERBOSE) {
                    Log.d(TAG, "muxer stopped, result=" + result);
                }



        return true;
    }

    public interface Callback {
        void bufferStatus(long totalTimeMsec);
    }
    public CircularEncoder(OutputStream outputStream, String encoderName, int width, int height, int bitRate, int frameRate, int desiredSpanSec,
                           Callback cb) throws IOException {
        // The goal is to size the buffer so that we can accumulate N seconds worth of video,
        // where N is passed in as "desiredSpanSec".  If the codec generates data at roughly
        // the requested bit rate, we can compute it as time * bitRate / bitsPerByte.
        //
        // Sync frames will appear every (frameRate * IFRAME_INTERVAL) frames.  If the frame
        // rate is higher or lower than expected, various calculations may not work out right.
        //
        // Since we have to start muxing from a sync frame, we want to ensure that there's
        // room for at least one full GOP in the buffer, preferrably two.
        this.mOS = outputStream;
        if (desiredSpanSec < IFRAME_INTERVAL * 2) {
            throw new RuntimeException("Requested time span is too short: " + desiredSpanSec +
                    " vs. " + (IFRAME_INTERVAL * 2));
        }
        mCircularBuffer = new CircularEncoderBuffer(bitRate, frameRate,
                desiredSpanSec);

        MediaFormat format = MediaFormat.createVideoFormat(DEFAULT_VIDEO_FORMAT, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createByCodecName(encoderName);
        //mEncoder.setCallback(this);
        mEncoder.configure(format, mInputSurface, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();


        // Start the encoder thread last.  That way we're sure it can see all of the state
        // we've initialized.
        mEncoderThread = new EncoderThread(mEncoder, mCircularBuffer, cb);
        mEncoderThread.start();
        mEncoderThread.waitUntilReady();
        frameAvailableSoon();
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN));

        try {
            mEncoderThread.join();
        } catch (InterruptedException ie) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie);
        }

        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    public void frameAvailableSoon() {
        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE_SOON));

    }

    /**
     * Object that encapsulates the encoder thread.
     * <p>
     * We want to sleep until there's work to do.  We don't actually know when a new frame
     * arrives at the encoder, because the other thread is sending frames directly to the
     * input surface.  We will see data appear at the decoder output, so we can either use
     * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
     * calling app wake us.  It's very useful to have all of the buffer management local to
     * this thread -- avoids synchronization -- so we want to do the file muxing in here.
     * So, it's best to sleep on an object and do something appropriate when awakened.
     * <p>
     * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
     * should be fully started before the thread is created, and not shut down until this
     * thread has been joined.
     */
    private static class EncoderThread extends Thread {
        private MediaCodec mEncoder;
        private MediaFormat mEncodedFormat;
        private MediaCodec.BufferInfo mBufferInfo;

        private EncoderHandler mHandler;
        private CircularEncoderBuffer mEncBuffer;
        private Callback mCallback;
        private int mFrameNum;

        private final Object mLock = new Object();
        private volatile boolean mReady = false;

        public Handler mDrainHandler = new Handler();
        public Runnable mDrainEncoderRunnable = new Runnable() {
            @Override
            public void run() {
                frameAvailableSoon();
            }
        };


        public EncoderThread(MediaCodec mediaCodec, CircularEncoderBuffer encBuffer,
                Callback callback) {
            mEncoder = mediaCodec;
            mEncBuffer = encBuffer;
            mCallback = callback;
            mBufferInfo = new MediaCodec.BufferInfo();
        }

        /**
         * Thread entry point.
         * <p>
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new EncoderHandler(this);    // must create on encoder thread
            Log.d(TAG, "encoder thread ready");
            synchronized (mLock) {
                Log.d(TAG, "synchronized (mLock) ready");
                mReady = true;
                mLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            synchronized (mLock) {
                mReady = false;
                mHandler = null;
            }
            Log.d(TAG, "looper quit");
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitUntilReady() {
            synchronized (mLock) {
                while (!mReady) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         */
        public EncoderHandler getHandler() {
            synchronized (mLock) {
                // Confirm ready state.
                if (!mReady) {
                    throw new RuntimeException("not ready");
                }
            }
            return mHandler;
        }

        /**
         * Drains all pending output from the decoder, and adds it to the circular buffer.
         */
        public void drainEncoder() {
            //Log.w(TAG, "drainEncoder");

            final int TIMEOUT_USEC = 0;     // no timeout -- check for buffers, bail if none

            ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
            while (true) {
                int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    Log.e(TAG, "drainEncoder: INFO_TRY_AGAIN_LATER");
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = mEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    mEncodedFormat = mEncoder.getOutputFormat();
                    Log.e(TAG, "encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0) {
                    Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    // let's ignore it
                } else {

                    Log.d(TAG, "drainEncoder() OK");
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    /*if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        mBufferInfo.size = 0;
                    }*/

                    Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                            mBufferInfo.presentationTimeUs);
                    if (mBufferInfo.size != 0) {
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        mEncBuffer.add(encodedData, mBufferInfo.flags,
                                mBufferInfo.presentationTimeUs);
                        mCallback.bufferStatus(mEncBuffer.computeTimeSpanUsec());
                        Log.w(TAG, "computeTimeSpanUsec()="+mEncBuffer.computeTimeSpanUsec() );





                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                    mBufferInfo.presentationTimeUs);
                        }
                    }

                    mEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                        break;      // out of while
                    }
                }
            }
            mDrainHandler.postDelayed(mDrainEncoderRunnable,100);
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link CircularEncoder#frameAvailableSoon()}.
         */
        void frameAvailableSoon() {
            Log.d(TAG, "frameAvailableSoon");
            drainEncoder();

            mFrameNum++;
            if ((mFrameNum % 10) == 0) {        // TODO: should base off frame rate or clock?
                mCallback.bufferStatus(mEncBuffer.computeTimeSpanUsec());
            }
        }

        /**
         * Tells the Looper to quit.
         */
        void shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         */
        private static class EncoderHandler extends Handler {
            public static final int MSG_FRAME_AVAILABLE_SOON = 1;
            public static final int MSG_SAVE_VIDEO = 2;
            public static final int MSG_SHUTDOWN = 3;

            // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
            // but no real harm in it.
            private WeakReference<EncoderThread> mWeakEncoderThread;

            /**
             * Constructor.  Instantiate object from encoder thread.
             */
            public EncoderHandler(EncoderThread et) {
                mWeakEncoderThread = new WeakReference<EncoderThread>(et);
            }

            @Override  // runs on encoder thread
            public void handleMessage(Message msg) {
                int what = msg.what;
                if (VERBOSE) {
                    Log.w(TAG, "EncoderHandler: what=" + what+": "+msg.toString());
                }

                EncoderThread encoderThread = mWeakEncoderThread.get();
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                    return;
                }

                switch (what) {
                    case MSG_FRAME_AVAILABLE_SOON:
                        encoderThread.frameAvailableSoon();
                        break;
                    case MSG_SAVE_VIDEO:
                        //encoderThread.saveVideo();
                        break;
                    case MSG_SHUTDOWN:
                        encoderThread.shutdown();
                        break;
                    default:
                        throw new RuntimeException("unknown message " + what);
                }
            }
        }
    }
}
