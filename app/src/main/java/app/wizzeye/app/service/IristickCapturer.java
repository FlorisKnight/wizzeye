/* Copyright (c) 2018 The Wizzeye Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.wizzeye.app.service;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.camera.CameraCharacteristics;
import com.iristick.smartglass.core.camera.CameraDevice;
import com.iristick.smartglass.core.camera.CaptureRequest;
import com.iristick.smartglass.core.camera.CaptureSession;

import org.webrtc.CameraVideoCapturer;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.wizzeye.app.R;

class IristickCapturer implements CameraVideoCapturer {

    private static final String TAG = "IristickCapturer";

    private static final DateFormat PICTURE_FILENAME = new SimpleDateFormat("'IMG_'yyyyMMdd_HHmmssSSS'.jpg'", Locale.US);

    /* Initialized by constructor */
    private final Context mContext;
    private final Headset mHeadset;
    private final CameraEventsHandler mEvents;
    private final String[] mCameraNames;

    /* Initialized by initialize() */
    private SurfaceTextureHelper mSurfaceHelper;
    private Context mAppContext;
    private CapturerObserver mObserver;
    private Handler mCameraThreadHandler;
    private ImageReader mImageReader;

    /* State objects guarded by mStateLock */
    private final Object mStateLock = new Object();
    private boolean mSessionOpening;
    private boolean mStopping;
    private int mFailureCount;
    private int mCameraIdx = 0;
    private int mWidth;
    private int mHeight;
    private int mFramerate;
    private CameraDevice mCamera;
    private Surface mSurface;
    private CaptureSession mCaptureSession;
    private boolean mFirstFrameObserved;
    private int mZoom = 0;
    private boolean mTorch = false;
    private LaserMode mLaser = LaserMode.OFF;

    IristickCapturer(Context context, Headset headset, CameraEventsHandler eventsHandler) {
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                @Override
                public void onCameraError(String s) {}
                @Override
                public void onCameraDisconnected() {}
                @Override
                public void onCameraFreezed(String s) {}
                @Override
                public void onCameraOpening(String s) {}
                @Override
                public void onFirstFrameAvailable() {}
                @Override
                public void onCameraClosed() {}
            };
        }
        mContext = context;
        mHeadset = headset;
        mEvents = eventsHandler;
        mCameraNames = headset.getCameraIdList();
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                           CapturerObserver capturerObserver) {
        mSurfaceHelper = surfaceTextureHelper;
        mAppContext = applicationContext;
        mObserver = capturerObserver;
        mCameraThreadHandler = surfaceTextureHelper.getHandler();

        Size[] sizes = mHeadset.getCameraCharacteristics(mCameraNames[0])
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            .getSizes(CaptureRequest.FORMAT_JPEG);
        mImageReader = ImageReader.newInstance(sizes[0].getWidth(), sizes[0].getHeight(),
            ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(mImageReaderListener, mCameraThreadHandler);
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        Logging.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);

        if (mAppContext == null)
            throw new IllegalStateException("CameraCapturer must be initialized before calling startCapture");

        synchronized (mStateLock) {
            if (mSessionOpening || mCaptureSession != null) {
                Logging.w(TAG, "Capture already started");
                return;
            }

            mWidth = width;
            mHeight = height;
            mFramerate = framerate;

            openCamera(true);
        }
    }

    @Override
    public void stopCapture() {
        Logging.d(TAG, "stopCapture");

        synchronized (mStateLock) {
            mStopping = true;
            while (mSessionOpening) {
                Logging.d(TAG, "stopCapture: Waiting for session to open");
                try {
                    mStateLock.wait();
                } catch (InterruptedException e) {
                    Logging.w(TAG, "stopCapture: Interrupted while waiting for session to open");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (mCaptureSession != null) {
                closeCamera();
                mObserver.onCapturerStopped();
            } else {
                Logging.d(TAG, "stopCapture: No session open");
            }
            mStopping = false;
        }

        Logging.d(TAG, "stopCapture: Done");
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        synchronized (mStateLock) {
            stopCapture();
            startCapture(width, height, framerate);
        }
    }

    @Override
    public void dispose() {
        stopCapture();
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    @Override
    public void switchCamera(final CameraSwitchHandler cameraSwitchHandler) {
        throw new UnsupportedOperationException();
    }

    public int getZoom() {
        return mZoom;
    }

    public void setZoom(final int zoom) {
        mCameraThreadHandler.post(() -> {
            synchronized (mStateLock) {
                mZoom = Math.max(0, Math.min(mCameraNames.length >= 2 ? 3 : 0, zoom));
                applyParametersInternal();
            }
        });
    }

    public boolean getTroch() {
        return mTorch;
    }

    public void setTorch(final boolean torch) {
        mCameraThreadHandler.post(() -> {
            synchronized (mStateLock) {
                mTorch = torch;
                applyParametersInternal();
            }
        });
    }

    public LaserMode getLaser() {
        return mLaser;
    }

    public void setLaser(final LaserMode laser) {
        mCameraThreadHandler.post(() -> {
            synchronized (mStateLock) {
                mLaser = laser;
                applyParametersInternal();
            }
        });
    }

    public void triggerAF() {
        mCameraThreadHandler.post(this::triggerAFInternal);
    }

    public void takePicture() {
        mCameraThreadHandler.post(this::takePictureInternal);
    }

    private void openCamera(boolean resetFailures) {
        synchronized (mStateLock) {
            if (resetFailures)
                mFailureCount = 0;
            mSessionOpening = true;
            mCameraThreadHandler.post(() -> {
                synchronized (mStateLock) {
                    if (mCameraIdx >= mCameraNames.length) {
                        mEvents.onCameraError("Headset has no camera index " + mCameraIdx);
                        return;
                    }
                    final String name = mCameraNames[mCameraIdx];
                    mEvents.onCameraOpening(name);
                    try {
                        mHeadset.openCamera(name, mCameraListener, mCameraThreadHandler);
                    } catch (IllegalArgumentException e) {
                        mEvents.onCameraError("Unknown camera: " + name);
                    }
                }
            });
        }
    }

    private void closeCamera() {
        synchronized (mStateLock) {
            final CameraDevice camera = mCamera;
            final Surface surface = mSurface;
            mCameraThreadHandler.post(() -> {
                mSurfaceHelper.stopListening();
                try {
                    camera.close();
                } catch (IllegalStateException e) {
                    // ignore
                }
                surface.release();
            });
            mCaptureSession = null;
            mSurface = null;
            mCamera = null;
        }
    }

    private void checkIsOnCameraThread() {
        if(Thread.currentThread() != mCameraThreadHandler.getLooper().getThread()) {
            Logging.e(TAG, "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    private void handleFailure(String error) {
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening) {
                if (mCamera != null) {
                    mCamera.close();
                    mCamera = null;
                    mSurface.release();
                    mSurface = null;
                }
                mObserver.onCapturerStarted(false);
                mSessionOpening = false;
                mStateLock.notifyAll();
            }
            if ("Disconnected".equals(error)) {
                mEvents.onCameraDisconnected();
                if (!mStopping)
                    stopCapture();
            } else if (mFailureCount < 3 && !mStopping) {
                mFailureCount++;
                mCameraThreadHandler.postDelayed(() -> openCamera(false), 200);
            } else {
                mEvents.onCameraError(error);
                if (!mStopping)
                    stopCapture();
            }
        }
    }

    private void setupCaptureRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.SCALER_ZOOM, (float)(1 << Math.max(0, mZoom - 1)));
        builder.set(CaptureRequest.FLASH_MODE, mTorch ? CaptureRequest.FLASH_MODE_ON : CaptureRequest.FLASH_MODE_OFF);
        switch (mLaser) {
        case OFF:
            builder.set(CaptureRequest.LASER_MODE, CaptureRequest.LASER_MODE_OFF);
            break;
        case ON:
            builder.set(CaptureRequest.LASER_MODE, CaptureRequest.LASER_MODE_ON);
            break;
        case AUTO:
            builder.set(CaptureRequest.LASER_MODE, mZoom == 0 ? CaptureRequest.LASER_MODE_OFF : CaptureRequest.LASER_MODE_ON);
            break;
        }
        if (mCameraIdx == 1)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    private void applyParametersInternal() {
        Logging.d(TAG, "applyParametersInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening || mStopping || mCaptureSession == null)
                return;

            if ((mZoom == 0 && mCameraIdx != 0) || (mZoom > 0 && mCameraIdx != 1)) {
                Logging.d(TAG, "Switching cameras");
                closeCamera();
                mCameraIdx = (mCameraIdx + 1) % 2;
                mFailureCount = 0;
                openCamera(true);
            } else {
                CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(mSurface);
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L / mFramerate);
                setupCaptureRequest(builder);
                mCaptureSession.setRepeatingRequest(builder.build(), null, null);
            }
        }
    }

    private void triggerAFInternal() {
        Logging.d(TAG, "triggerAFInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mCameraIdx != 1 || mSessionOpening || mStopping || mCaptureSession == null)
                return;

            CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mSurface);
            setupCaptureRequest(builder);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            mCaptureSession.capture(builder.build(), null, null);
        }
    }

    private void takePictureInternal() {
        Logging.d(TAG, "takePictureInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening || mStopping || mCaptureSession == null)
                return;

            CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());
            setupCaptureRequest(builder);
            mCaptureSession.capture(builder.build(), null, null);
        }
    }

    /* Called on camera thread */
    private final CameraDevice.Listener mCameraListener = new CameraDevice.Listener() {
        @Override
        public void onOpened(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mCamera = device;

                final SurfaceTexture surfaceTexture = mSurfaceHelper.getSurfaceTexture();
                surfaceTexture.setDefaultBufferSize(mWidth, mHeight);
                mSurface = new Surface(surfaceTexture);

                List<Surface> outputs = new ArrayList<>();
                outputs.add(mSurface);
                outputs.add(mImageReader.getSurface());
                mCamera.createCaptureSession(outputs, mCaptureSessionListener, mCameraThreadHandler);
            }
        }

        @Override
        public void onClosed(CameraDevice device) {}

        @Override
        public void onDisconnected(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCamera == device || mCamera == null)
                    handleFailure("Disconnected");
                else
                    Logging.w(TAG, "onDisconnected from another CameraDevice");
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCamera == device || mCamera == null)
                    handleFailure("Camera device error " + error);
                else
                    Logging.w(TAG, "onError from another CameraDevice");
            }
        }
    };

    private final CaptureSession.Listener mCaptureSessionListener = new CaptureSession.Listener() {
        @Override
        public void onConfigured(CaptureSession session) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mSurfaceHelper.startListening(mFrameAvailableListener);
                mObserver.onCapturerStarted(true);
                mSessionOpening = false;
                mCaptureSession = session;
                mFirstFrameObserved = false;
                mStateLock.notifyAll();
                if (mCameraIdx == 1) {
                    mCameraThreadHandler.removeCallbacks(IristickCapturer.this::triggerAFInternal);
                    mCameraThreadHandler.postDelayed(IristickCapturer.this::triggerAFInternal, 500);
                }
                applyParametersInternal();
            }
        }

        @Override
        public void onConfigureFailed(CaptureSession session) {
            handleFailure("Error while creating capture session");
        }

        @Override
        public void onClosed(CaptureSession session) {}

        @Override
        public void onActive(CaptureSession session) {}

        @Override
        public void onCaptureQueueEmpty(CaptureSession session) {}

        @Override
        public void onReady(CaptureSession session) {}
    };

    private final SurfaceTextureHelper.OnTextureFrameAvailableListener mFrameAvailableListener = new SurfaceTextureHelper.OnTextureFrameAvailableListener() {
        @Override
        public void onTextureFrameAvailable(int oesTextureId, float[] transformMatrix, long timestampNs) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCaptureSession == null) {
                    mSurfaceHelper.returnTextureFrame();
                } else {
                    if (!mFirstFrameObserved) {
                        mEvents.onFirstFrameAvailable();
                        mFirstFrameObserved = true;
                    }
                    VideoFrame.Buffer buffer = mSurfaceHelper.createTextureBuffer(mWidth, mHeight,
                            RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix));
                    final VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
                    mObserver.onFrameCaptured(frame);
                    frame.release();
                }
            }
        }
    };

    private final ImageReader.OnImageAvailableListener mImageReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Logging.d(TAG, "onImageAvailable");
            try (final Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    Logging.w(TAG, "No image available in callback");
                    return;
                }

                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    mContext.getString(R.string.app_name));
                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        Logging.e(TAG, "Failed to create directory " + dir.getPath());
                        return;
                    }
                }

                File file = new File(dir, PICTURE_FILENAME.format(new Date()));
                try (OutputStream os = new FileOutputStream(file)) {
                    Channels.newChannel(os).write(image.getPlanes()[0].getBuffer());
                } catch (IOException e) {
                    Logging.e(TAG, "Failed to write capture to " + file.getPath(), e);
                    return;
                }
                MediaScannerConnection.scanFile(mContext, new String[] { file.toString() }, null, null);
            }
        }
    };
}
