package org.meaninglessvanity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.meaninglessvanity.player.PlayerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/*
 * TODO - Add new service for SYNC!!
 */
public class SaveMyPlace extends Activity implements SeekBar.OnSeekBarChangeListener {

    public static final int PLAYER_CANNOT_START = 0;
    public static final int END_OF_PLAYLIST = 1;
    public static final int PLAYBACK_STATUS = 2;
    public static final int SERVICE_STOP = 3;
    public static final int PLAYBACK_LIST = 4;

    public static final int SYNC_COMPLETE = 5;
    public static final int SYNC_ERROR = 6;

    private static final int PLAYLIST_ACTIVITY = 100;
    private static final int TRACK_CHOOSER_ACTIVITY = 101;

    SQLiteDatabase myDB;

    Cursor playListCursor;
    SavedPlaylist savedPlaylist;
    ArrayList<String> playingList = null;
    private ImageButton btnPlay;
    private ImageButton btnRepeat;
    private ImageButton btnShuffle;
    private ImageView albumArt;
    private SeekBar songProgressBar;
    private TextView songTitleLabel;
    private TextView songCurrentDurationLabel;
    private TextView songTotalDurationLabel;
    private TextView songArtistLabel;
    private TextView prevTitleLabel;
    private TextView prevArtistLabel;
    private TextView nextTitleLabel;
    private TextView nextArtistLabel;
    private TextView playListLabel;
    private ImageButton btnForward;
    private ImageButton btnBackward;
    private ImageButton btnNext;
    private ImageButton btnPrevious;
    private ImageButton btnPlaylist;
    private Button btnSync;
    private Utilities utils;
    private final int seekForwardTime = 5000; // 5000 milliseconds
    private final int seekBackwardTime = 5000; // 5000 milliseconds
    private boolean isShuffle = false;
    private boolean isRepeat = false;
    private long curPlayListId;
    GenericServiceConnection playerServiceConnection;
    GenericServiceConnection syncServiceConnection;
    private boolean noPlaylistsFound;


    private void moveBookmark(int direction, int position) {
        // don't update anything if we're at the start or end!
        if (direction == 0) {
            myLog("movebookmark update repeat to " + isRepeat);
            myLog("updateDbEntry call mb1 repeat set to " + isRepeat);
            SavedPlaylist.updateDbEntry(playListCursor, curPlayListId, myDB, position, isRepeat);
        } else if (SavedPlaylist.cursorTrackMotion(playListCursor, direction, isRepeat)) {
            myLog("updateDbEntry call mb2 repeat set to " + isRepeat);
            SavedPlaylist.updateDbEntry(playListCursor, curPlayListId, myDB, 0, isRepeat);
            setTrackInfo(playListCursor);
            songProgressBar.setProgress(0);
            songCurrentDurationLabel.setText(utils.milliSecondsToTimer(0));
        }
    }

    private void setTrackInfo(Cursor plC) {
        if (prevTitleLabel != null) {
            prevTitleLabel.setText("prevTitle");
            prevArtistLabel.setText("prevArtist");
            nextTitleLabel.setText("nextTitle");
            nextArtistLabel.setText("nextArtist");
        }
        Map<String, String> titles = SavedPlaylist.getTrackTitles(plC);

        prevTitleLabel.setText(titles.get(SavedPlaylist.PREV_TITLE_KEY));
        prevArtistLabel.setText(titles.get(SavedPlaylist.PREV_ARTIST_KEY));
        songTitleLabel.setText(titles.get(SavedPlaylist.CUR_TITLE_KEY));
        songArtistLabel.setText(titles.get(SavedPlaylist.CUR_ARTIST_KEY));
        nextTitleLabel.setText(titles.get(SavedPlaylist.NEXT_TITLE_KEY));
        nextArtistLabel.setText(titles.get(SavedPlaylist.NEXT_ARTIST_KEY));
        updateAlbumArtImage(null);
    }

    private void startPlaylistActivity() {
        //Intent i = new Intent(getApplicationContext(), PlayListActivity.class);
        //startActivityForResult(i, PLAYLIST_ACTIVITY);
    }

    private void startTrackChooserActivity() {
        Intent i = new Intent(getApplicationContext(), TrackChooserActivity.class);
        Bundle b = new Bundle();
        ArrayList<String> trackList = getTrackNameList();
        if (trackList == null) {
            return;
        }
        b.putStringArrayList("list", getTrackNameList());
        i.putExtra("bundle", (Parcelable) b);
        i.putExtra("position", Integer.toString(playListCursor.getPosition()));
        startActivityForResult(i, TRACK_CHOOSER_ACTIVITY);

    }

    void stopPlaying() {
        Intent stopIntent = new Intent(SaveMyPlace.this, PlayerService.class);
        getApplicationContext().stopService(stopIntent);
        btnPlay.setImageResource(R.drawable.ic_av_play);
        loadLastPlaylist();
        playingList = null;
    }

    void myLog(String logStr) {
        Log.w(this.getPackageName(), logStr);
    }

    private void updateAlbumArtImage(Long albumId) {
        if (playListCursor == null) {
            return;
        }
        if (albumId == null) {
            albumId = playListCursor.getLong(7);
        }
        Uri sArtworkUri = Uri.parse("content://media/external/audio/albumart");
        Uri albumArtUri = ContentUris.withAppendedId(sArtworkUri, albumId);
        Bitmap bitmap;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(getApplicationContext().getContentResolver(), albumArtUri);
            if (bitmap != null) {
                bitmap = Bitmap.createBitmap(bitmap);
            }
            albumArt.setImageBitmap(bitmap);
        } catch (IOException fnfe) {
            bitmap = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.no_album_art);
        }

        albumArt.setImageBitmap(bitmap);
    }

    private void loadLastPlaylist() {
        SQLiteStatement stmt = myDB.compileStatement("select value from settings where name='LastPlaylist'");

        try {
            String queryResult = stmt.simpleQueryForString();
            if (queryResult.equals("none")) {
                startPlaylistActivity();
            } else {
                curPlayListId = Long.parseLong(queryResult);
                try {
                    savedPlaylist = new SavedPlaylist.Builder(getApplicationContext(), curPlayListId, myDB).build();
                } catch (EmptyPlaylistException epe) {
                    startPlaylistActivity();
                    return;
                }
                Log.w(getPackageName(), "loadLastPlaylist isRepeat=" + savedPlaylist.isRepeat());
                if (savedPlaylist.isValid()) {
                    playListCursor = savedPlaylist.getPlaylistCursor();
                    if (savedPlaylist.isShuffle() != isShuffle) {
                        setShuffle(savedPlaylist.isShuffle());
                    }
                    if (savedPlaylist.isRepeat() != isRepeat) {
                        setRepeat(savedPlaylist.isRepeat());
                    }
                } else {
                    startPlaylistActivity();
                    return;
                }
                playListLabel.setText(savedPlaylist.getName());
                long duration = playListCursor.getLong(6);
                long position = savedPlaylist.getPosition();
                int progress = utils.getProgressPercentage(position, duration);
                songProgressBar.setProgress(progress);
                setTrackInfo(playListCursor);
                // need to set time labels
                songCurrentDurationLabel.setText(utils.milliSecondsToTimer(position));
                songTotalDurationLabel.setText(utils.milliSecondsToTimer(duration));
            }
        } catch (SQLiteDoneException e) {
            myDB.execSQL("INSERT INTO SETTINGS (name, value) values ('LastPlaylist','none')");
            startPlaylistActivity();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        // All player buttons
        btnPlay = findViewById(R.id.btnPlay);
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnNext = findViewById(R.id.btnNext);
        btnPrevious =  findViewById(R.id.btnPrevious);
        btnPlaylist =  findViewById(R.id.btnPlaylist);
        btnSync = findViewById(R.id.sync);
        btnRepeat =  findViewById(R.id.btnRepeat);
        btnShuffle =  findViewById(R.id.btnShuffle);
        albumArt =  findViewById(R.id.albumArt);
        songProgressBar =  findViewById(R.id.songProgressBar);
        songTitleLabel =  findViewById(R.id.curTrackTitle);
        songArtistLabel =  findViewById(R.id.curTrackArtist);
        prevTitleLabel =  findViewById(R.id.prevTrackTitle);
        prevArtistLabel =  findViewById(R.id.prevTrackArtist);
        nextTitleLabel =  findViewById(R.id.nextTrackTitle);
        nextArtistLabel =  findViewById(R.id.nextTrackArtist);
        playListLabel =  findViewById(R.id.playlistName);

        songCurrentDurationLabel =  findViewById(R.id.songCurrentDurationLabel);
        songTotalDurationLabel =  findViewById(R.id.songTotalDurationLabel);

        songProgressBar.setMax(100);

        // Mediaplayer
        utils = new Utilities();

        // Listeners
        songProgressBar.setOnSeekBarChangeListener(this); // Important
        myDB = DatabaseHelper.getInstance(getApplicationContext()).getWritableDatabase();

        // enable multicast
        WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = wm.createMulticastLock("SaveMyPlaceMulticast"); multicastLock.acquire();

        playerServiceConnection = new GenericServiceConnection(PlayerService.class, this, new PlayerIncomingHandler()) {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                super.onServiceConnected(className, service);
                sendMessage(PlayerService.START_PLAYING, 0, 0, null);
            }
        };

        syncServiceConnection = new GenericServiceConnection(SyncService.class, this, new SyncIncomingHandler()) {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
               super.onServiceConnected(className, service);
               sendMessage(SyncService.START_SYNC, 0, 0, null);
            }
        };
    }

    void startPlayerService(Map<String,Object> extrasMap) {
        if (playerServiceConnection.start(extrasMap)) {
            btnPlay.setImageResource(R.drawable.ic_av_pause);
        } else {
            btnPlay.setImageResource(R.drawable.ic_av_play);
        }
    }

    void setupUI() {

        btnPlay.setOnClickListener(arg0 -> {
            if (playerServiceConnection.isServiceRunning()) {
                stopPlaying();
                playerServiceConnection.doUnbind();
            } else {
                Map <String, Object> extrasMap = new HashMap<>();
                extrasMap.put("playListId", curPlayListId);
                extrasMap.put("isShuffle", isShuffle);
                startPlayerService(extrasMap);
            }
        });

        //		@Override
        btnForward.setOnClickListener(arg0 -> playerServiceConnection.sendMessage(PlayerService.SEEK_MESSAGE, seekForwardTime, 0, null));

        //			@Override
        btnBackward.setOnClickListener(arg0 -> playerServiceConnection.sendMessage(PlayerService.SEEK_MESSAGE, -1 * seekBackwardTime, 0, null));

        //			@Override
        btnNext.setOnClickListener(arg0 -> trackMotion(1));

        btnNext.setOnLongClickListener(arg0 -> {
            if (playerServiceConnection.isServiceRunning()) {
                Log.w(getPackageName(), "track chooser when playing");
                stopPlaying();
                playerServiceConnection.doUnbind();
            } else {
                Log.w(getPackageName(), "track chooser when not playing");
            }
            startTrackChooserActivity();
            return true;
        });

        //	@Override
        btnPrevious.setOnClickListener(arg0 -> trackMotion(-1));

        btnPrevious.setOnLongClickListener(arg0 -> {
            if (playerServiceConnection.isServiceRunning()) {
                Log.w(getPackageName(), "track chooser when playing");
                stopPlaying();
                playerServiceConnection.doUnbind();
            } else {
                Log.w(getPackageName(), "track chooser when not playing");
            }
            startTrackChooserActivity();
            return true;
        });

        //			@Override
        btnRepeat.setOnClickListener(arg0 -> {
            setRepeat(!isRepeat);
            // repeat doesn't matter unless we're playing
            if (playerServiceConnection.isServiceRunning()) {
                playerServiceConnection.sendMessage(PlayerService.REPEAT_TOGGLE, ((isRepeat) ? 1 : 0), 0, null);
            } else {
                moveBookmark(0, 0);
            }
        });

        btnShuffle.setOnClickListener(arg0 -> {
            setShuffle(!isShuffle);
            if (playerServiceConnection.isServiceRunning()) {
                playerServiceConnection.sendMessage(PlayerService.SHUFFLE_TOGGLE, ((isShuffle) ? 1 : 0), 0, null);
            } else {
                if (!isShuffle) {
                    myDB.execSQL("delete from shuffle_mapping where playlist_id=" + curPlayListId);
                }
                try {
                    playListCursor = new SavedPlaylist.Builder(getApplicationContext(), curPlayListId, myDB)
                            .shuffle(isShuffle)
                            .currentTrack(playListCursor.getInt(0))
                            .build().getPlaylistCursor();
                    setTrackInfo(playListCursor);
                } catch (EmptyPlaylistException epe) {
                    // this should be ok.
                    setShuffle(false);
                }
            }
        });

        //		@Override
        btnPlaylist.setOnClickListener(arg0 -> {
            if (playerServiceConnection.isServiceRunning()) {
                Log.w(getPackageName(), "playlist button when playing");
                stopPlaying();
                playerServiceConnection.doUnbind();
            } else {
                Log.w(getPackageName(), "playlist button when not playing");
            }
            startPlaylistActivity();
        });

        btnSync.setOnClickListener(arg0 -> syncServiceConnection.start(Collections.emptyMap()));


    }

    /**
     * Receiving song index from playlist view
     * and play the song
     */
    @Override
    protected void onActivityResult(int requestCode,
                                    int resultCode, Intent data) {
        // look up the songs in the playlist
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PLAYLIST_ACTIVITY) {
            if (resultCode != RESULT_OK) {
                noPlaylistsFound = true;
                return;
            }
            String id = data.getExtras().getString("playListId");
            curPlayListId = Long.parseLong(id);
            var extrasMap = new HashMap<String, Object>();
            extrasMap.put("playListId", curPlayListId);
            startPlayerService(extrasMap);
        } else if (requestCode == TRACK_CHOOSER_ACTIVITY) {
            if (resultCode != RESULT_OK) {
                return;
            }
            int nextPos = data.getExtras().getInt("trackMotion");
            moveToTrack(nextPos);
            var extrasMap = new HashMap<String, Object>();
            extrasMap.put("playListId", curPlayListId);
            startPlayerService(extrasMap);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        // nothing - compiler demands this to be overridden but nothing needs happen here
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // nothing - compiler demanded
    }

    /**
     * When user stops moving the progress handler
     */
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (playerServiceConnection.isServiceRunning()) {
            playerServiceConnection.sendMessage(PlayerService.SET_POSITION, seekBar.getProgress(), 0, null);
        } else {
            double progress = ((double) seekBar.getProgress()) / seekBar.getMax();
            double dPos = playListCursor.getLong(6) * progress;
            int iPos = (int) dPos;
            moveBookmark(0, iPos);
        }
    }

    private void moveToTrack(int nextPos) {
        if (playerServiceConnection.isServiceRunning()) {
            playerServiceConnection.sendMessage(PlayerService.MOVE_TO_TRACK, nextPos, 0, null);
            updateAlbumArtImage(null);
        } else {
            moveBookmark(nextPos - playListCursor.getPosition(), 0);
        }
    }

    private void trackMotion(int vector) {
        if (playerServiceConnection.isServiceRunning()) {
            playerServiceConnection.sendMessage(PlayerService.TRACK_MESSAGE, vector, 0, null);
        } else {
            moveBookmark(vector, 0);
        }
    }

    public void onPause() {
        super.onPause();
        playerServiceConnection.doUnbind();
    }

    public void onResume() {
        super.onResume();
        if (noPlaylistsFound) {
            return;
        }
        loadLastPlaylist(); // make sure that every time the UI becomes visible we reload the current state
        if (playerServiceConnection.isServiceRunning()) {
            playerServiceConnection.doBind();
        } else {
            btnPlay.setImageResource(R.drawable.ic_av_play);
        }
    }

    public void onStart() {
        super.onStart();
        setupUI();
    }

    private void tintButton(ImageButton button, boolean highlight) {
        int highlightColor = getResources().getColor(android.R.color.holo_blue_light);
        if (highlight) {
            button.setColorFilter(highlightColor);
        } else {
            button.setColorFilter(null);
        }
    }

    private void setRepeat(boolean newIsRepeat) {
        tintButton(btnRepeat, newIsRepeat);
        isRepeat = newIsRepeat;
    }

    private void setShuffle(boolean newIsShuffle) {
        tintButton(btnShuffle, newIsShuffle);
        isShuffle = newIsShuffle;
    }

    private ArrayList<String> getTrackNameList() {
        if (playerServiceConnection.isServiceRunning()) {
            return playingList;
        } else {
            return SavedPlaylist.getTitleList(playListCursor);
        }
    }

    private class SyncIncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SYNC_COMPLETE:
                    break;
                case SYNC_ERROR:
                    break;
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private class PlayerIncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PLAYER_CANNOT_START:
                    AlertDialog.Builder dialog = new AlertDialog.Builder(SaveMyPlace.this);
                    dialog.setMessage("That playlist is empty, please select another.")
                            .setTitle("Error")
                            .setPositiveButton("OK", (dialoginterface, i) -> {
                                dialoginterface.dismiss();
                                startPlaylistActivity();
                            }).show();
                    return;
                case END_OF_PLAYLIST:
                    // delete the db entry for this playlist
                    myDB.execSQL("delete from playlists where playlist_id = " + curPlayListId);
                    btnPlay.setImageResource(R.drawable.ic_av_play);
                    return;
                case PLAYBACK_LIST:
                    if (!(msg.obj instanceof Bundle)) {
                        return;
                    }
                    Bundle bundle = (Bundle) msg.obj;
                    playingList = bundle.getStringArrayList("list");
                    return;
                case PLAYBACK_STATUS:
                    if (!(msg.obj instanceof Bundle)) {
                        return;
                    }
                    Bundle bun = (Bundle) msg.obj;
                    ArrayList<String> msgMap = bun.getStringArrayList("list");
                    if (msgMap == null) {
                        return;
                    }
                    long duration = Long.parseLong(msgMap.get(1));
                    long position = Long.parseLong(msgMap.get(2));
                    String title = msgMap.get(0);
                    boolean newIsShuffle = Boolean.parseBoolean(msgMap.get(3));
                    boolean newIsRepeat = Boolean.parseBoolean(msgMap.get(4));
                    String playListName = msgMap.get(5);
                    String artist = msgMap.get(6);
                    prevArtistLabel.setText(msgMap.get(7));
                    prevTitleLabel.setText(msgMap.get(8));
                    nextArtistLabel.setText(msgMap.get(9));
                    nextTitleLabel.setText(msgMap.get(10));
                    long albumId = Long.parseLong(msgMap.get(11));
                    songTotalDurationLabel.setText(utils.milliSecondsToTimer(duration));
                    songCurrentDurationLabel.setText(utils.milliSecondsToTimer(position));
                    int progress = utils.getProgressPercentage(position, duration);
                    songProgressBar.setProgress(progress);
                    songTitleLabel.setText(title);
                    playListLabel.setText(playListName);
                    songArtistLabel.setText(artist);

                    if (newIsShuffle != isShuffle) {
                        setShuffle(newIsShuffle);
                    }
                    if (newIsRepeat != isRepeat) {
                        setRepeat(newIsRepeat);
                    }
                    updateAlbumArtImage(albumId);
                    return;
                case SERVICE_STOP:
                    btnPlay.setImageResource(R.drawable.ic_av_play);
                    playerServiceConnection.doUnbind();
                    stopPlaying();
                    return;

                default:
                    super.handleMessage(msg);
            }
        }
    }

}