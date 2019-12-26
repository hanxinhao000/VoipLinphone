package com.dds.voip.frgment;

/*
CallVideoFragment.java
Copyright (C) 2017  Belledonne Communications, Grenoble, France

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.GestureDetector.OnGestureListener;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.dds.tbs.linphonesdk.R;
import com.dds.voip.CallManager;
import com.dds.voip.LinphoneManager;
import com.dds.voip.LinphoneUtils;
import com.dds.voip.VoipActivity;
import com.dds.voip.VoipService;
import com.dds.voip.compatibility.Compatibility;
import com.dds.voip.compatibility.CompatibilityScaleGestureDetector;
import com.dds.voip.compatibility.CompatibilityScaleGestureListener;

import org.linphone.core.LinphoneCall;
import org.linphone.core.LinphoneCore;
import org.linphone.core.VideoSize;
import org.linphone.mediastream.Log;
import org.linphone.mediastream.video.AndroidVideoWindowImpl;
import org.linphone.mediastream.video.capture.hwconf.AndroidCameraConfiguration;

public class CallVideoFragment extends Fragment implements OnGestureListener, OnDoubleTapListener, CompatibilityScaleGestureListener {
    private SurfaceView mVideoView;
    private SurfaceView mCaptureView;
    private AndroidVideoWindowImpl androidVideoWindowImpl;
    private GestureDetector mGestureDetector;
    private float mZoomFactor = 1.f;
    private float mZoomCenterX, mZoomCenterY;
    private CompatibilityScaleGestureDetector mScaleDetector;
    private VoipActivity inCallActivity;
    private int previewX, previewY;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view;
        if (LinphoneManager.getLc().hasCrappyOpenGL()) {
            view = inflater.inflate(R.layout.voip_video_no_opengl, container, false);
        } else {
            view = inflater.inflate(R.layout.voip_video, container, false);
        }
        mVideoView = view.findViewById(R.id.videoSurface);
        mCaptureView = view.findViewById(R.id.videoCaptureSurface);
        mCaptureView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // Warning useless because value is ignored and automatically set by new APIs.

        fixZOrder(mVideoView, mCaptureView);
        androidVideoWindowImpl = new AndroidVideoWindowImpl(mVideoView, mCaptureView, new AndroidVideoWindowImpl.VideoWindowListener() {
            public void onVideoRenderingSurfaceReady(AndroidVideoWindowImpl vw, SurfaceView surface) {
                mVideoView = surface;
                LinphoneManager.getLc().setVideoWindow(vw);
            }

            public void onVideoRenderingSurfaceDestroyed(AndroidVideoWindowImpl vw) {

            }

            public void onVideoPreviewSurfaceReady(AndroidVideoWindowImpl vw, SurfaceView surface) {
                mCaptureView = surface;
                LinphoneManager.getLc().setPreviewWindow(mCaptureView);
                resizePreview();

                initCamera();
            }

            public void onVideoPreviewSurfaceDestroyed(AndroidVideoWindowImpl vw) {

            }
        });
        initTouch();
        return view;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTouch() {

        mVideoView.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (mScaleDetector != null) {
                    mScaleDetector.onTouchEvent(event);
                }

                mGestureDetector.onTouchEvent(event);
                if (inCallActivity != null) {
                    inCallActivity.displayVideoCallControlsIfHidden();
                }
                return true;
            }
        });

        mCaptureView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        previewX = (int) motionEvent.getX();
                        previewY = (int) motionEvent.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        int x = (int) motionEvent.getX();
                        int y = (int) motionEvent.getY();
                        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) mCaptureView.getLayoutParams();
                        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0); // Clears the rule, as there is no removeRule until API 17.
                        lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0);
                        int left = lp.leftMargin + (x - previewX);
                        int top = lp.topMargin + (y - previewY);
                        lp.leftMargin = left;
                        lp.topMargin = top;
                        view.setLayoutParams(lp);
                        break;
                }
                return true;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        inCallActivity = (VoipActivity) getActivity();
        if (inCallActivity != null) {
            inCallActivity.bindVideoFragment(this);
        }
    }

    private void resizePreview() {
        LinphoneCore lc = LinphoneManager.getLc();
        if (lc.getCallsNb() > 0) {
            LinphoneCall call = lc.getCurrentCall();
            if (call == null) {
                call = lc.getCalls()[0];
            }
            if (call == null) return;

            DisplayMetrics metrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
            int screenHeight = metrics.heightPixels;
            int maxHeight = screenHeight / 4; // Let's take at most 1/4 of the screen for the camera preview

            VideoSize videoSize = call.getCurrentParams().getSentVideoSize(); // It already takes care of rotation
            int width = videoSize.width;
            int height = videoSize.height + 1;

            Log.d("Video height is " + height + ", width is " + width);
            width = width * maxHeight / height;
            height = maxHeight;

            mCaptureView.getHolder().setFixedSize(width, height);
            Log.d("Video preview size set to " + width + "x" + height);
        }
    }

    private void fixZOrder(SurfaceView video, SurfaceView preview) {
        video.setZOrderOnTop(false);
        preview.setZOrderOnTop(true);
        preview.setZOrderMediaOverlay(true); // Needed to be able to display control layout over
    }

    public void switchCamera() {
        try {
            int videoDeviceId = LinphoneManager.getLc().getVideoDevice();
            videoDeviceId = (videoDeviceId + 1) % AndroidCameraConfiguration.retrieveCameras().length;
            LinphoneManager.getLc().setVideoDevice(videoDeviceId);
            CallManager.getInstance().updateCall();

            if (mCaptureView != null) {
                LinphoneManager.getLc().setPreviewWindow(mCaptureView);
            }
        } catch (ArithmeticException ae) {
            Log.e("Cannot swtich camera : no camera");
        }
    }

    public void initCamera() {
        try {
            int videoDeviceId = LinphoneManager.getLc().getVideoDevice();
            videoDeviceId = (videoDeviceId) % AndroidCameraConfiguration.retrieveCameras().length;
            LinphoneManager.getLc().setVideoDevice(videoDeviceId);
            CallManager.getInstance().updateCall();

            if (mCaptureView != null) {
                LinphoneManager.getLc().setPreviewWindow(mCaptureView);
            }
        } catch (ArithmeticException ae) {
            Log.e("Cannot swtich camera : no camera");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        VoipService.instance().destroyOverlay();

        if (androidVideoWindowImpl != null) {
            synchronized (androidVideoWindowImpl) {
                LinphoneManager.getLc().setVideoWindow(androidVideoWindowImpl);
            }
        }

        mGestureDetector = new GestureDetector(inCallActivity, this);
        mScaleDetector = Compatibility.getScaleGestureDetector(inCallActivity, this);

        resizePreview();
    }

    @Override
    public void onPause() {
        if (androidVideoWindowImpl != null) {
            synchronized (androidVideoWindowImpl) {
                /*
                 * this call will destroy native opengl renderer which is used by
                 * androidVideoWindowImpl
                 */
                LinphoneManager.getLc().setVideoWindow(null);
            }
        }
        VoipService.instance().createOverlay();

        super.onPause();
    }

    public boolean onScale(CompatibilityScaleGestureDetector detector) {
        mZoomFactor *= detector.getScaleFactor();
        // Don't let the object get too small or too large.
        // Zoom to make the video fill the screen vertically
        float portraitZoomFactor = ((float) mVideoView.getHeight()) / (float) ((3 * mVideoView.getWidth()) / 4);
        // Zoom to make the video fill the screen horizontally
        float landscapeZoomFactor = ((float) mVideoView.getWidth()) / (float) ((3 * mVideoView.getHeight()) / 4);
        mZoomFactor = Math.max(0.1f, Math.min(mZoomFactor, Math.max(portraitZoomFactor, landscapeZoomFactor)));

        LinphoneCall currentCall = LinphoneManager.getLc().getCurrentCall();
        if (currentCall != null) {
            currentCall.zoomVideo(mZoomFactor, mZoomCenterX, mZoomCenterY);
            return true;
        }
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (LinphoneUtils.isCallEstablished(LinphoneManager.getLc().getCurrentCall())) {
            if (mZoomFactor > 1) {
                // Video is zoomed, slide is used to change center of zoom
                if (distanceX > 0 && mZoomCenterX < 1) {
                    mZoomCenterX += 0.01;
                } else if (distanceX < 0 && mZoomCenterX > 0) {
                    mZoomCenterX -= 0.01;
                }
                if (distanceY < 0 && mZoomCenterY < 1) {
                    mZoomCenterY += 0.01;
                } else if (distanceY > 0 && mZoomCenterY > 0) {
                    mZoomCenterY -= 0.01;
                }

                if (mZoomCenterX > 1)
                    mZoomCenterX = 1;
                if (mZoomCenterX < 0)
                    mZoomCenterX = 0;
                if (mZoomCenterY > 1)
                    mZoomCenterY = 1;
                if (mZoomCenterY < 0)
                    mZoomCenterY = 0;

                LinphoneManager.getLc().getCurrentCall().zoomVideo(mZoomFactor, mZoomCenterX, mZoomCenterY);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (LinphoneUtils.isCallEstablished(LinphoneManager.getLc().getCurrentCall())) {
            if (mZoomFactor == 1.f) {
                // Zoom to make the video fill the screen vertically
                float portraitZoomFactor = ((float) mVideoView.getHeight()) / (float) ((3 * mVideoView.getWidth()) / 4);
                // Zoom to make the video fill the screen horizontally
                float landscapeZoomFactor = ((float) mVideoView.getWidth()) / (float) ((3 * mVideoView.getHeight()) / 4);

                mZoomFactor = Math.max(portraitZoomFactor, landscapeZoomFactor);
            } else {
                resetZoom();
            }

            LinphoneManager.getLc().getCurrentCall().zoomVideo(mZoomFactor, mZoomCenterX, mZoomCenterY);
            return true;
        }

        return false;
    }

    private void resetZoom() {
        mZoomFactor = 1.f;
        mZoomCenterX = mZoomCenterY = 0.5f;
    }

    @Override
    public void onDestroy() {
        inCallActivity = null;

        mCaptureView = null;
        if (mVideoView != null) {
            mVideoView.setOnTouchListener(null);
            mVideoView = null;
        }
        if (androidVideoWindowImpl != null) {
            // Prevent linphone from crashing if correspondent hang up while you are rotating
            androidVideoWindowImpl.release();
            androidVideoWindowImpl = null;
        }
        if (mGestureDetector != null) {
            mGestureDetector.setOnDoubleTapListener(null);
            mGestureDetector = null;
        }
        if (mScaleDetector != null) {
            mScaleDetector.destroy();
            mScaleDetector = null;
        }

        super.onDestroy();
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return true; // Needed to make the GestureDetector working
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                           float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }
}
