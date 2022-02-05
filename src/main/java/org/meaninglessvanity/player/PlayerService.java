package org.meaninglessvanity.player;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.meaninglessvanity.AbstractService;
import org.meaninglessvanity.DatabaseHelper;
import org.meaninglessvanity.EmptyPlaylistException;
import org.meaninglessvanity.R;
import org.meaninglessvanity.SaveMyPlace;
import org.meaninglessvanity.SavedPlaylist;

public class PlayerService extends AbstractService implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, OnCompletionListener {

    public static final int SEEK_MESSAGE = 0;
    public static final int STOP_MESSAGE = 1;
    public static final int TRACK_MESSAGE = 2;
    public static final int SET_POSITION = 3;
    public static final int REPEAT_TOGGLE = 6;
    public static final int SHUFFLE_TOGGLE = 7;
    public static final int MOVE_TO_TRACK = 8;
    public static final int START_PLAYING = 9;

    MediaPlayer mp;
    Cursor playListCursor;
    long curSongDuration;
    boolean isRepeat;
    boolean isShuffle;
    SQLiteDatabase myDB;
    long curPlayListId;
    int startLocation;
    String playListName;
    AudioEffect mCompression;
    AudioManager audioManager;
    MediaSession msc;
    OnAudioFocusChangeListener audioListener = new OnAudioFocusChangeListener() {

        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    // resume playback
                    if (mp == null)
                        return;
                    else if (!mp.isPlaying())
                        mp.start();
                    mp.setVolume(1.0f, 1.0f);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS:
                    // Lost focus for an unbounded amount of time: stop playback and
                    // release media player
                    if (mp != null) {
                        if (mp.isPlaying())
                            mp.stop();
                    }
                    if (mCompression != null) {
                        mCompression.release();
                    }
                    if (mp != null) {
                        mp.release();
                    }
                    mp = null;
                    sendActivityMessage(SaveMyPlace.END_OF_PLAYLIST, null);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Lost focus for a short time, but we have to stop
                    // playback. We don't release the media player because playback
                    // is likely to resume
                    if (mp.isPlaying())
                        mp.pause();
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Lost focus for a short time, but it's ok to keep playing
                    // at an attenuated level
                    if (mp.isPlaying())
                        mp.setVolume(0.1f, 0.1f);
                    break;
            }
        }
    };
    MusicIntentReceiver mir;
    /**
     * Background Runnable thread
     */
    private Runnable mUpdateTimeTask = new Runnable() {
        public void run() {
            Map<String, String> titles = SavedPlaylist.getTrackTitles(playListCursor);
            if (mp == null) {
                return;
            }
            long totalDuration = curSongDuration;
            long currentDuration = mp.getCurrentPosition();
            String songTitle = playListCursor.getString(2);
            String songArtist = playListCursor.getString(1);
            ArrayList<String> msgMap = new ArrayList<>();
            msgMap.add(songTitle);
            msgMap.add(Long.toString(totalDuration));
            msgMap.add(Long.toString(currentDuration));
            msgMap.add(Boolean.toString(isShuffle));
            msgMap.add(Boolean.toString(isRepeat));
            msgMap.add(playListName);
            msgMap.add(songArtist);
            msgMap.add(titles.get(SavedPlaylist.PREV_ARTIST_KEY));
            msgMap.add(titles.get(SavedPlaylist.PREV_TITLE_KEY));
            msgMap.add(titles.get(SavedPlaylist.NEXT_ARTIST_KEY));
            msgMap.add(titles.get(SavedPlaylist.NEXT_TITLE_KEY));
            msgMap.add(Long.toString(playListCursor.getLong(7)));
            Bundle bundleToSend = new Bundle();
            bundleToSend.putStringArrayList("list", msgMap);


            sendActivityMessage(SaveMyPlace.PLAYBACK_STATUS, bundleToSend);

            Bundle bundleToSend2 = new Bundle();
            bundleToSend2.putStringArrayList("list", SavedPlaylist.getTitleList(playListCursor));
            sendActivityMessage(SaveMyPlace.PLAYBACK_LIST, bundleToSend2);

            SavedPlaylist.updateDbEntry(playListCursor, curPlayListId, myDB, mp.getCurrentPosition(), isRepeat);

            // Running this thread every second so that the UI gets updated only when it needs to

            mHandler.postDelayed(this, 1000);
            msc.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, playListCursor.getString(2))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, playListCursor.getString(1))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, totalDuration).build());
            msc.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, currentDuration, 1f).build());
        }
    };

    public PlayerService() {
        super();
        messageWhats.addAll(Arrays.asList(SEEK_MESSAGE, STOP_MESSAGE, TRACK_MESSAGE, SET_POSITION, REPEAT_TOGGLE, SHUFFLE_TOGGLE, MOVE_TO_TRACK, START_PLAYING));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        myDB = DatabaseHelper.getInstance(getApplicationContext()).getWritableDatabase();
        audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        curPlayListId = intent.getLongExtra("playListId", -1);
        isShuffle = intent.getBooleanExtra("isShuffle", false);

        return START_STICKY;
    }

    public void startPlaying() {
        int position;
        Log.e(getPackageName(), "start playing received");
        if (curPlayListId == -1) {
            sendActivityMessage(SaveMyPlace.PLAYER_CANNOT_START, null);
            stopService();
        }
        SavedPlaylist sp;
        try {
            sp = new SavedPlaylist.Builder(getApplicationContext(), curPlayListId, myDB).build();
        } catch (EmptyPlaylistException epe) {
            Log.e(this.getPackageName(), "got empty playlist exception");
            sendActivityMessage(SaveMyPlace.PLAYER_CANNOT_START, null);
            Log.e(this.getPackageName(), "sent message stopping self");
            stopService();
            return;
        }
        if ((sp == null) || (!sp.isValid())) {
            Log.w(this.getPackageName(), "Log");
            sendActivityMessage(SaveMyPlace.PLAYER_CANNOT_START, null);
            stopService();
            return;
        } else {
            playListName = sp.getName();
            playListCursor = sp.getPlaylistCursor();
            position = sp.getPosition();
            isShuffle = sp.isShuffle();
            isRepeat = sp.isRepeat();
            Log.e(getPackageName(), "player service new playlist is " + ((isRepeat) ? " " : "not ") + "repeated");
        }

        myDB.execSQL("update settings set value = '" + Long.toString(curPlayListId) + "' where name = 'LastPlaylist'");

        // Request audio focus for playback
        int result = audioManager.requestAudioFocus(audioListener,
                // Use the music stream.
                AudioManager.STREAM_MUSIC,
                // Request permanent focus.
                AudioManager.AUDIOFOCUS_GAIN);

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            startForeground(42, makeNotificationBuilder().build());
            msc.setActive(true);
            playSong(position);
        } else {
            sendActivityMessage(SaveMyPlace.PLAYER_CANNOT_START, null);
        }

    }

    private NotificationCompat.Builder makeNotificationBuilder() {
        NotificationCompat.Builder mBuilder;
        mBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.img_btn_notification)
                .setContentTitle("Save My Place")
                .setContentText(playListCursor.getString(1) + " - " + playListCursor.getString(2));
        Intent i = new Intent(this, SaveMyPlace.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                i, 0);
        mBuilder.setContentIntent(pi);
        return mBuilder;
    }

    private void updateNotification() {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(42, makeNotificationBuilder().build());
    }

    @Override
    public void onCompletion(MediaPlayer arg0) {
        curSongDuration = 0;
        mHandler.removeCallbacks(mUpdateTimeTask);
        // check for repeat is ON or OFF
        if (isRepeat) {
            // repeat is on start the playlist over
            if ((playListCursor != null) && (!playListCursor.moveToNext())) {
                playListCursor.moveToFirst();
            }
            updateNotification();
            playSong(0);
        } else {
            // no repeat - play next song
            if (playListCursor != null) {
                if ((playListCursor.moveToNext())) { // if there's another song on the list
                    updateNotification();
                    playSong(0);
                } else {
                    // delete the bookmark if we reached the end
                    myDB.execSQL("delete from playlists where playlist_id = " + Long.toString(curPlayListId));
                    mp.stop();
                    sendActivityMessage(SaveMyPlace.END_OF_PLAYLIST, null);
                    stopService();
                }
            }
        }
    }

    /**
     * Function to play a song - uses the play list cursor
     *
     * @param position - index of song
     */
    public void playSong(int position) {
        // Play song
        try {
            mHandler.removeCallbacks(mUpdateTimeTask);
            mp.stop();
            mp.reset();
            if (playListCursor == null) {
                return;
            }

            int duration = (int) playListCursor.getLong(6);

            if (position == duration) {
                playListCursor.moveToNext();
            }

            // figure out who to match up the cursor with the args of setDataSource
            mp.setDataSource(playListCursor.getString(4));

            // Displaying Song title

            // send a broadcast to the ui

            startLocation = position;
            mp.prepareAsync();

        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onError(MediaPlayer arg0, int arg1, int arg2) {
        mHandler.removeCallbacks(mUpdateTimeTask);
        Log.e("error", "playback error");
        Log.w(this.getPackageName(), "setting isRunning to false");

        mp.reset();
        // send error broadcast to activity
        return true;
    }

    public void onPrepared(MediaPlayer arg0) {
        mp.start();
        mp.seekTo(startLocation);
        curSongDuration = mp.getDuration();
        mHandler.postDelayed(mUpdateTimeTask, 100);
    }

    public void releaseAudioFocus() {
        audioManager.abandonAudioFocus(audioListener);
    }

    @Override
    public void onCreate() {

        super.onCreate();
        mp = new MediaPlayer();
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        mp.setOnPreparedListener(this);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        mir = new MusicIntentReceiver();
        registerReceiver(mir, filter);
        msc = new MediaSession(getApplicationContext(), "bjt-msc");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mUpdateTimeTask);
        Log.w(getPackageName(), "onDestroy update repeat to " + Boolean.toString(isRepeat));
        SavedPlaylist.updateDbEntry(playListCursor, curPlayListId, myDB, mp.getCurrentPosition(), isRepeat);
        unregisterReceiver(mir);
        mp.stop();
        mp.release();
        if (playListCursor != null) {
            playListCursor.close();
        }
        stopForeground(true);
        releaseAudioFocus();
    }

    public final void stopService() {
        msc.release();
        stopSelf();
    }


    public void handleIncomingMessage(Message msg) {
        int newPos;
        Log.e(getPackageName(), "got message number " + Integer.toString(msg.what));
        switch (msg.what) {
            case SEEK_MESSAGE:
                Log.w(getPackageName(), "got seek message");
                int pos = mp.getCurrentPosition();
                newPos = pos + msg.arg1;
                if ((newPos < mp.getDuration()) && (newPos > 0)) {
                    mp.seekTo(newPos);
                }
                break;
            case STOP_MESSAGE:
                Log.w(getPackageName(), "got stop message");
                stopService();
                break;
            case TRACK_MESSAGE:
                Log.w(getPackageName(), "received track message");
                if (SavedPlaylist.cursorTrackMotion(playListCursor, msg.arg1, isRepeat)) {
                    updateNotification();
                    playSong(0);
                }
                break;
            case MOVE_TO_TRACK:
                Log.w(getPackageName(), "received move to track message");
                if (SavedPlaylist.cursorTrackMotion(playListCursor, (msg.arg1 - playListCursor.getPosition()), isRepeat)) {
                    updateNotification();
                    playSong(0);
                }
                break;
            case SET_POSITION:
                int dur = mp.getDuration();
                float percentage = msg.arg1 / (float) 100;
                newPos = (int) (dur * percentage);
                mp.seekTo(newPos);
                break;
            case REPEAT_TOGGLE:
                isRepeat = (msg.arg1 == 1);
                break;
            case SHUFFLE_TOGGLE:
                isShuffle = (msg.arg1 == 1);
                if (!isShuffle) {
                    myDB.execSQL("delete from shuffle_mapping where playlist_id=" + curPlayListId);
                    Log.w(getPackageName(), "shuffle off current track is " + playListCursor.getString(2));
                }
                int curTrack = playListCursor.getInt(0);
                playListCursor.close();
                try {
                    playListCursor = new SavedPlaylist.Builder(getApplicationContext(), curPlayListId, myDB)
                            .shuffle(isShuffle)
                            .currentTrack(curTrack)
                            .build().getPlaylistCursor();
                } catch (EmptyPlaylistException epe) {
                    // not much we can do here!
                }
                break;

            case START_PLAYING:
                if (!mp.isPlaying()) {
                    startPlaying();  // the UI may tell us to start playing if it comes back into focus, but if we're already playing ignore it.
                }
        }
    }

    private class MusicIntentReceiver extends android.content.BroadcastReceiver {

        public void onReceive(Context ctx, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.w(getPackageName(), "got noisy message!");
                sendActivityMessage(SaveMyPlace.SERVICE_STOP, null);
                stopService();
            }
        }
    }

}
