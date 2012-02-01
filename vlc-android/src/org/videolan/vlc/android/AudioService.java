/*****************************************************************************
 * AudioService.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.videolan.vlc.android.widget.VLCAppWidgetProvider;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.RemoteViews;

public class AudioService extends Service {

    private static final String TAG = "VLC/AudioService";

    private static final int SHOW_PROGRESS = 0;

    private LibVLC mLibVLC;
    private ArrayList<Media> mMediaList;
    private ArrayList<Media> mPlayedMedia;
    private Stack<Media> mPrevious;
    private Media mCurrentMedia;
    private ArrayList<IAudioServiceCallback> mCallback;
    private EventManager mEventManager;
    private Notification mNotification;
    private boolean mShuffling = false;
    private RepeatType mRepeating = RepeatType.None;
    private boolean mDetectHeadset = true;

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

        // Get libVLC instance
        try {
            mLibVLC = LibVLC.getInstance();
        } catch (LibVlcException e) {
            e.printStackTrace();
        }

        mCallback = new ArrayList<IAudioServiceCallback>();
        mMediaList = new ArrayList<Media>();
        mPlayedMedia = new ArrayList<Media>();
        mPrevious = new Stack<Media>();
        mEventManager = EventManager.getIntance();
        updateWidget(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mInterface;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(VLCAppWidgetProvider.ACTION_WIDGET_BACKWARD);
        filter.addAction(VLCAppWidgetProvider.ACTION_WIDGET_PLAY);
        filter.addAction(VLCAppWidgetProvider.ACTION_WIDGET_STOP);
        filter.addAction(VLCAppWidgetProvider.ACTION_WIDGET_FORWARD);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(serviceReciever, filter);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(serviceReciever);
        super.onDestroy();
    }

    private BroadcastReceiver serviceReciever = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int state = intent.getIntExtra("state", 0);

            if (action.equalsIgnoreCase(VLCAppWidgetProvider.ACTION_WIDGET_PLAY)) {
                if (mLibVLC.isPlaying() && mCurrentMedia != null) {
                    pause();
                } else if (!mLibVLC.isPlaying() && mCurrentMedia != null) {
                    play();
                } else {
                    Intent iVlc = new Intent(context, MainActivity.class);
                    iVlc.putExtra(MainActivity.START_FROM_NOTIFICATION, "");
                    iVlc.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(iVlc);
                }
            } else if (action.equalsIgnoreCase(VLCAppWidgetProvider.ACTION_WIDGET_BACKWARD)) {
                previous();
            }
            else if (action.equalsIgnoreCase(VLCAppWidgetProvider.ACTION_WIDGET_STOP)) {
                stop();
            }
            else if (action.equalsIgnoreCase(VLCAppWidgetProvider.ACTION_WIDGET_FORWARD)) {
                next();
            }

            if (mDetectHeadset) {
                if (action.equalsIgnoreCase(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                    Log.i(TAG, "Headset Removed.");
                    if (mLibVLC.isPlaying() && mCurrentMedia != null)
                        pause();
                }
                else if (action.equalsIgnoreCase(Intent.ACTION_HEADSET_PLUG) && state != 0) {
                    Log.i(TAG, "Headset Inserted.");
                    if (!mLibVLC.isPlaying() && mCurrentMedia != null)
                        play();
                }
            }
        }
    };

    /**
     * Handle libvlc asynchronous events
     */
    private Handler mEventHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.getData().getInt("event")) {
                case EventManager.MediaPlayerPlaying:
                    Log.i(TAG, "MediaPlayerPlaying");
                    break;
                case EventManager.MediaPlayerPaused:
                    Log.i(TAG, "MediaPlayerPaused");
                    executeUpdate();
                    // also hide notification if phone ringing
                    hideNotification();
                    break;
                case EventManager.MediaPlayerStopped:
                    Log.i(TAG, "MediaPlayerStopped");
                    executeUpdate();
                    break;
                case EventManager.MediaPlayerEndReached:
                    Log.i(TAG, "MediaPlayerEndReached");
                    executeUpdate();
                    next();
                    break;
                default:
                    Log.e(TAG, "Event not handled");
                    break;
            }
        }
    };

    private void executeUpdate() {
        executeUpdate(true);
    }

    private void executeUpdate(Boolean updateWidget) {
        for (int i = 0; i < mCallback.size(); i++) {
            try {
                mCallback.get(i).update();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        if (updateWidget)
            updateWidget(this);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW_PROGRESS:
                    int pos = (int) mLibVLC.getTime();
                    if (mCallback.size() > 0) {
                        executeUpdate(false);
                        mHandler.removeMessages(SHOW_PROGRESS);
                        sendEmptyMessageDelayed(SHOW_PROGRESS, 1000 - (pos % 1000));
                    }
                    break;
            }
        }
    };

    private void showNotification() {
        // add notification to status bar
        if (mNotification == null) {
            mNotification = new Notification(R.drawable.icon, null,
                    System.currentTimeMillis());
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.putExtra(MainActivity.START_FROM_NOTIFICATION, "");
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        mNotification.setLatestEventInfo(this, mCurrentMedia.getTitle(),
                mCurrentMedia.getArtist() + " - " + mCurrentMedia.getAlbum(), pendingIntent);
        startForeground(3, mNotification);

    }

    private void hideNotification() {
        mNotification = null;
        stopForeground(true);
    }

    private void pause() {
        mHandler.removeMessages(SHOW_PROGRESS);
        // hideNotification(); <-- see event handler
        mLibVLC.pause();
    }

    private void play() {
        mLibVLC.play();
        mHandler.sendEmptyMessage(SHOW_PROGRESS);
        showNotification();
        updateWidget(this);
    }

    private void stop() {
        mEventManager.removeHandler(mEventHandler);
        mLibVLC.stop();
        mCurrentMedia = null;
        mMediaList.clear();
        mPlayedMedia.clear();
        mPrevious.clear();
        mHandler.removeMessages(SHOW_PROGRESS);
        hideNotification();
        executeUpdate();
    }

    private void next() {
        int index = mMediaList.indexOf(mCurrentMedia);
        mPrevious.push(mCurrentMedia);
        if (mRepeating == RepeatType.Once)
            mCurrentMedia = mMediaList.get(index);
        else if (mShuffling && mPlayedMedia.size() < mMediaList.size()) {
            while (mPlayedMedia.contains(mCurrentMedia = mMediaList
                           .get((int) (Math.random() * mMediaList.size()))))
                ;
        } else if (index < mMediaList.size() - 1) {
            mCurrentMedia = mMediaList.get(index + 1);
        } else {
            if (mRepeating == RepeatType.All)
                mCurrentMedia = mMediaList.get(0);
            else {
                stop();
                return;
            }
        }
        mLibVLC.readMedia(mCurrentMedia.getPath());
        showNotification();
    }

    private void previous() {
        int index = mMediaList.indexOf(mCurrentMedia);
        if (mPrevious.size() > 0)
            mCurrentMedia = mPrevious.pop();
        else if (index > 0)
            mCurrentMedia = mMediaList.get(index - 1);
        else
            return;
        mLibVLC.readMedia(mCurrentMedia.getPath());
        showNotification();
    }

    private void shuffle() {
        if (mShuffling)
            mPlayedMedia.clear();
        mShuffling = !mShuffling;
    }

    private void setRepeatType(int t) {
        mRepeating = RepeatType.values()[t];
    }

    private Bitmap getCover() {
        try {
            ContentResolver contentResolver = getContentResolver();
            Uri uri = android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
            Cursor cursor = contentResolver.query(uri, new String[] {
                           MediaStore.Audio.Albums.ALBUM,
                           MediaStore.Audio.Albums.ALBUM_ART },
                           MediaStore.Audio.Albums.ALBUM + " LIKE ?",
                           new String[] { mCurrentMedia.getAlbum() }, null);
            if (cursor == null) {
                // do nothing
            } else if (!cursor.moveToFirst()) {
                // do nothing
                cursor.close();
            } else {
                int titleColumn = cursor.getColumnIndex(android.provider.MediaStore.Audio.Albums.ALBUM_ART);
                String albumArt = cursor.getString(titleColumn);
                cursor.close();
                Bitmap b = BitmapFactory.decodeFile(albumArt);
                if (b != null)
                    return b;
            }
            File f = new File(mCurrentMedia.getPath());
            for (File s : f.getParentFile().listFiles()) {
                if (s.getAbsolutePath().endsWith("png") ||
                        s.getAbsolutePath().endsWith("jpg"))
                    return BitmapFactory.decodeFile(s.getAbsolutePath());
            }
        } catch (Exception e) {
        }
        return null;
    }

    private IAudioService.Stub mInterface = new IAudioService.Stub() {

        @Override
        public String getCurrentMediaPath() throws RemoteException {
            return mCurrentMedia.getPath();
        }

        @Override
        public void pause() throws RemoteException {
            AudioService.this.pause();
        }

        @Override
        public void play() throws RemoteException {
            AudioService.this.play();
        }

        @Override
        public void stop() throws RemoteException {
            AudioService.this.stop();
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            return mLibVLC.isPlaying();
        }

        @Override
        public boolean isShuffling() {
            return mShuffling;
        }

        @Override
        public int getRepeatType() {
            return mRepeating.ordinal();
        }

        @Override
        public boolean hasMedia() throws RemoteException {
            return mMediaList.size() != 0;
        }

        @Override
        public String getAlbum() throws RemoteException {
            if (mCurrentMedia != null)
                return mCurrentMedia.getAlbum();
            else
                return null;
        }

        @Override
        public String getArtist() throws RemoteException {
            if (mCurrentMedia != null)
                return mCurrentMedia.getArtist();
            else
                return null;
        }

        @Override
        public String getTitle() throws RemoteException {
            if (mCurrentMedia != null)
                return mCurrentMedia.getTitle();
            else
                return null;
        }

        public Bitmap getCover() {
            if (mCurrentMedia != null) {
                return AudioService.this.getCover();
            }
            return null;
        }

        @Override
        public void addAudioCallback(IAudioServiceCallback cb)
                throws RemoteException {
            mCallback.add(cb);
            executeUpdate();
        }

        @Override
        public void removeAudioCallback(IAudioServiceCallback cb)
                throws RemoteException {
            if (mCallback.contains(cb)) {
                mCallback.remove(cb);
            }
        }

        @Override
        public int getTime() throws RemoteException {
            return (int) mLibVLC.getTime();
        }

        @Override
        public int getLength() throws RemoteException {
            return (int) mLibVLC.getLength();
        }

        @Override
        public void load(List<String> mediaPathList, int position)
                throws RemoteException {
            mEventManager.addHandler(mEventHandler);
            mMediaList.clear();
            mPlayedMedia.clear();
            mPrevious.clear();
            DatabaseManager db = DatabaseManager.getInstance();
            for (int i = 0; i < mediaPathList.size(); i++) {
                String path = mediaPathList.get(i);
                Media media = db.getMedia(path);
                mMediaList.add(media);
            }

            if (mMediaList.size() > position) {
                mCurrentMedia = mMediaList.get(position);
            }

            if (mCurrentMedia != null)
                mLibVLC.readMedia(mCurrentMedia.getPath());
            mHandler.sendEmptyMessage(SHOW_PROGRESS);
            showNotification();

        }

        @Override
        public void next() throws RemoteException {
            AudioService.this.next();
        }

        @Override
        public void previous() throws RemoteException {
            AudioService.this.previous();
        }

        public void shuffle() throws RemoteException {
            AudioService.this.shuffle();
        }

        @Override
        public void setRepeatType(int t) throws RemoteException {
            AudioService.this.setRepeatType(t);
        }

        @Override
        public void setTime(long time) throws RemoteException {
            mLibVLC.setTime(time);
        }

        @Override
        public boolean hasNext() throws RemoteException {
            if (mRepeating == RepeatType.Once)
                return false;
            int index = mMediaList.indexOf(mCurrentMedia);
            if (mShuffling && mPlayedMedia.size() < mMediaList.size() ||
                    index < mMediaList.size() - 1)
                return true;
            else
                return false;
        }

        @Override
        public boolean hasPrevious() throws RemoteException {
            if (mRepeating == RepeatType.Once)
                return false;
            int index = mMediaList.indexOf(mCurrentMedia);
            if (mPrevious.size() > 0 || index > 0)
                return true;
            else
                return false;
        }

        @Override
        public void detectHeadset(boolean enable) throws RemoteException {
            mDetectHeadset = enable;
        }
    };

    private void updateWidget(Context context)
    {
        Log.d(TAG, "Updating widget");
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.vlcwidget);
        Bitmap cover = null;

        if (mCurrentMedia != null) {
            views.setTextViewText(R.id.songName, mCurrentMedia.getTitle());
            views.setTextViewText(R.id.artist, mCurrentMedia.getArtist());
            cover = Util.scaleDownBitmap(context, getCover(), 64);
        }
        else {
            views.setTextViewText(R.id.songName, "VLC mini player");
            views.setTextViewText(R.id.artist, "");
            cover = null;
        }

        if (cover != null)
            views.setImageViewBitmap(R.id.imageView1, cover);
        else
            views.setImageViewResource(R.id.imageView1, R.drawable.cone);

        views.setImageViewResource(R.id.play_pause, mLibVLC.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        /* commands */
        Intent iBackward = new Intent();
        iBackward.setAction(VLCAppWidgetProvider.ACTION_WIDGET_BACKWARD);
        Intent iPlay = new Intent();
        iPlay.setAction(VLCAppWidgetProvider.ACTION_WIDGET_PLAY);
        Intent iStop = new Intent();
        iStop.setAction(VLCAppWidgetProvider.ACTION_WIDGET_STOP);
        Intent iForward = new Intent();
        iForward.setAction(VLCAppWidgetProvider.ACTION_WIDGET_FORWARD);
        Intent iVlc = new Intent(context, MainActivity.class);
        iVlc.putExtra(MainActivity.START_FROM_NOTIFICATION, "");

        PendingIntent piBackward = PendingIntent.getBroadcast(context, 0, iBackward, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent piPlay = PendingIntent.getBroadcast(context, 0, iPlay, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent piStop = PendingIntent.getBroadcast(context, 0, iStop, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent piForward = PendingIntent.getBroadcast(context, 0, iForward, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent piVlc = PendingIntent.getActivity(context, 0, iVlc, PendingIntent.FLAG_UPDATE_CURRENT);

        views.setOnClickPendingIntent(R.id.backward, piBackward);
        views.setOnClickPendingIntent(R.id.play_pause, piPlay);
        views.setOnClickPendingIntent(R.id.stop, piStop);
        views.setOnClickPendingIntent(R.id.forward, piForward);
        views.setOnClickPendingIntent(R.id.linearLayout1, piVlc);

        /* update widget */
        ComponentName widget = new ComponentName(context, VLCAppWidgetProvider.class);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidget(widget, views);
    }
}
