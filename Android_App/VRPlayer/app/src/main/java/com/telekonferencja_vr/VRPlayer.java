package com.telekonferencja_vr;

import java.io.File;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import org.freedesktop.gstreamer.GStreamer;

import com.telekonferencja_vr.GStreamerSurfaceView;


public class VRPlayer extends Activity implements SurfaceHolder.Callback {

    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativeSetUri(String uri); // Set the URI of the media to play
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativeSetPosition(int milliseconds); // Seek to the indicated position, in milliseconds
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface); // A new surface is available
    private native void nativeSurfaceFinalize(); // Surface about to be destroyed
    private long native_custom_data;      // Native code will use this to keep private data

    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING
    private int position;                 // Current position, reported by native code
    private int duration;                 // Current clip duration, reported by native code
    private boolean is_local_media;       // Whether this clip is stored locally or is being streamed
    private int desired_position;         // Position where the users wants to seek to
    private String mediaUri;              // URI of the clip being played

    private final String defaultMediaUri = "https://www.freedesktop.org/software/gstreamer-sdk/data/media/sintel_trailer-480p.ogv";

    static private final int PICK_FILE_CODE = 1;
    private String last_folder;

    private PowerManager.WakeLock wake_lock;

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mediaUri = getIntent().getStringExtra("VideoURL");

        //Remove title bar
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        //Remove notification bar
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Initialize GStreamer and warn if it fails
        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.main);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake_lock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "VRPlayer");
        wake_lock.setReferenceCounted(false);



        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        // Retrieve our previous state, or initialize it to default values
        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            position = savedInstanceState.getInt("position");
            duration = savedInstanceState.getInt("duration");
            mediaUri = savedInstanceState.getString("mediaUri");
            last_folder = savedInstanceState.getString("last_folder");
            Log.i ("GStreamer", "Activity created with saved state:");
        } else {
            is_playing_desired = false;
            position = duration = 0;
            last_folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath();
            Intent intent = getIntent();
            android.net.Uri uri = intent.getData();
            if (uri == null)
                mediaUri = defaultMediaUri;
            else {
                Log.i ("GStreamer", "Received URI: " + uri);
                if (uri.getScheme().equals("content")) {
                    android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    cursor.moveToFirst();
                    mediaUri = "file://" + cursor.getString(cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA));
                    cursor.close();
                } else
                    mediaUri = uri.toString();
            }
            Log.i ("GStreamer", "Activity created with no saved state:");
        }
        is_local_media = false;
        Log.i ("GStreamer", "  playing:" + is_playing_desired + " position:" + position +
                " duration: " + duration + " uri: " + mediaUri);

        nativeInit();

        //start odtwarzania przy starcie
        is_playing_desired = true;
        wake_lock.acquire();
        nativePlay();
    }

    protected void onSaveInstanceState (Bundle outState) {
        Log.d ("GStreamer", "Saving state, playing:" + is_playing_desired + " position:" + position +
                " duration: " + duration + " uri: " + mediaUri);
        outState.putBoolean("playing", is_playing_desired);
        outState.putInt("position", position);
        outState.putInt("duration", duration);
        outState.putString("mediaUri", mediaUri);
        outState.putString("last_folder", last_folder);
    }

    protected void onDestroy() {
        nativeFinalize();
        if (wake_lock.isHeld())
            wake_lock.release();
        super.onDestroy();
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) { }

    // Set the URI to play, and record whether it is a local or remote file
    private void setMediaUri() {
        nativeSetUri (mediaUri);
        is_local_media = mediaUri.startsWith("file://");
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized () {
        Log.i ("GStreamer", "GStreamer initialized:");
        Log.i ("GStreamer", "  playing:" + is_playing_desired + " position:" + position + " uri: " + mediaUri);

        // Restore previous playing state
        setMediaUri ();
        nativeSetPosition (position);
        if (is_playing_desired) {
            nativePlay();
            wake_lock.acquire();
        } else {
            nativePause();
            wake_lock.release();
        }
    }


    // Called from native code - nie usuwac
    private void setCurrentPosition(final int position, final int duration) { }

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("tutorial-5");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit (holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize ();
    }

    // Called from native code when the size of the media changes or is first detected.
    // Inform the video surface about the new size and recalculate the layout.
    private void onMediaSizeChanged (int width, int height) {
        Log.i ("GStreamer", "Media size changed to " + width + "x" + height);
        final GStreamerSurfaceView gsv = (GStreamerSurfaceView) this.findViewById(R.id.surface_video);
        gsv.media_width = width;
        gsv.media_height = height;
        runOnUiThread(new Runnable() {
            public void run() {
                gsv.requestLayout();
            }
        });
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (resultCode == RESULT_OK && requestCode == PICK_FILE_CODE) {
            position = 0;
            Log.i("GStreamer", "Setting last_folder to " + last_folder);
            setMediaUri();
        }
    }
}