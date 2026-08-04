package dev.alastorkaneki.vizzywrapper;

import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.view.Surface;

/** EGL window surface backed by a MediaCodec encoder input surface. */
public final class CodecInputSurface {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private Surface surface;
    private android.opengl.EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext context = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    public CodecInputSurface(Surface surface) {
        if (surface == null) throw new NullPointerException("surface");
        this.surface = surface;
        setup();
    }

    private void setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("Unable to get EGL display.");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("Unable to initialize EGL.");

        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.length, count, 0) || count[0] <= 0) {
            throw new RuntimeException("Unable to find a recordable EGL config.");
        }
        int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
        check("eglCreateContext");
        int[] surfaceAttributes = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surfaceAttributes, 0);
        check("eglCreateWindowSurface");
    }

    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw new RuntimeException("eglMakeCurrent failed.");
        }
    }

    public void swapBuffers() {
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new RuntimeException("eglSwapBuffers failed.");
    }

    public void setPresentationTime(long nanoseconds) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds);
    }

    public void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, eglSurface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
        if (surface != null) surface.release();
        surface = null;
    }

    private void check(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) throw new RuntimeException(operation + " failed: 0x" + Integer.toHexString(error));
    }
}
