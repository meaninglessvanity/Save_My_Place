package org.meaninglessvanity;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.provider.MediaStore;
import androidx.loader.content.CursorLoader;
import android.util.Log;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SavedPlaylist {

	public static final String PREV_TITLE_KEY = "prevTitle";
	public static final String PREV_ARTIST_KEY = "prevArtist";
	public static final String CUR_TITLE_KEY = "curTitle";
	public static final String CUR_ARTIST_KEY = "curArtist";
	public static final String NEXT_TITLE_KEY = "nextTitle";
	public static final String NEXT_ARTIST_KEY = "nextArtist";
	private static final String[] projection = {
			MediaStore.Audio.Playlists.Members.AUDIO_ID,
			MediaStore.Audio.Playlists.Members.ARTIST,
			MediaStore.Audio.Playlists.Members.TITLE,
			MediaStore.Audio.Playlists.Members._ID,
			android.provider.MediaStore.MediaColumns.DATA,
			MediaStore.Audio.AudioColumns.BOOKMARK,
			MediaStore.Audio.AudioColumns.DURATION,
			MediaStore.Audio.Media.ALBUM_ID
	};
	private Cursor playListCursor=null;
	private int position=0;
	private boolean isValid = false;
	private long myPlaylistId;
	private String playListName;
	private Context context;
	private boolean isShuffle = false;
	private SQLiteDatabase myDB;
	private int curTrackId;
	private boolean isRepeat;
	
	private SavedPlaylist(Context context, long playlistId, SQLiteDatabase myDB, boolean isShuffleIn, int currentTrackId) throws EmptyPlaylistException {
		this.curTrackId = currentTrackId;
		this.myDB = myDB;
		this.isShuffle = isShuffleIn;
		this.context = context;
		// load the playlist
		String[] projection1 = {
				MediaStore.Audio.Playlists._ID,
				MediaStore.Audio.Playlists.NAME
		};
		CursorLoader cLoader = new CursorLoader(context,
				MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, projection1,
				MediaStore.Audio.Playlists._ID, null, null);
		Cursor cursor = cLoader.loadInBackground();
		if (cursor != null ) {
			if (cursor.moveToFirst()) {
				do {
					if (cursor.getString(0).equals(Long.toString(playlistId))) {
						playListName = cursor.getString(1);
						break;
					}
				} while (cursor.moveToNext());
			}
			cursor.close();
		}

		// if shuffle is set, check to see if we can make a cursor object from the
		myPlaylistId = playlistId;
		Cursor checkCursor = getShuffleDbCursor();
		if (checkCursor.getCount() > 0) {
			isShuffle = true;
			Log.w(context.getPackageName(), "found shuffled list");
		}
		checkCursor.close();
		if (isShuffle) {
			playListCursor = getShuffledCursor();
		} else {
			playListCursor = getMemberCursor();
			if (currentTrackId != -1) {
				int newPosition = getPositionMap(playListCursor).get(currentTrackId);
				playListCursor.moveToPosition(newPosition);
			}
		}

		if (playListCursor == null)
		{
			throw new EmptyPlaylistException();
		}

		// if we are playing and setting shuffle, we don't want to go back to the beginning or use the bookmark!
		if (currentTrackId == -1) {
			playListCursor.moveToFirst();
			// query for the start audio id
			// if it's there, advance to that track
			Cursor queryCursor = myDB.rawQuery("select member_id,position,is_repeat from playlists where playlist_id = "+playlistId,null);
			if (queryCursor != null && queryCursor.getCount() == 1) {
				queryCursor.moveToFirst();
				position = Integer.parseInt(queryCursor.getString(1));
				String searchMemberId = queryCursor.getString(0);
			    int isRepeatInt = queryCursor.getInt(2);
			    Log.w(context.getPackageName(),"isRepeatInt = *"+isRepeatInt+"*");
			    isRepeat = (isRepeatInt == 1);
				while (!(searchMemberId.equals(playListCursor.getString(0)))) {
					if (playListCursor.isLast()) {
						playListCursor.moveToFirst();
						position = 0;
						break;
					} else {
						playListCursor.moveToNext();
					}
				}
                queryCursor.close();
			}
		}
		isValid = true;
	}
	
	public static void updateDbEntry(Cursor playListCursor, long curPlayListId, SQLiteDatabase myDB, int position, boolean isRepeat)
	{
		// don't do this if we're not ready
		if ((playListCursor == null) ||
			(curPlayListId == 0) ||
			(myDB == null) ||
			(playListCursor.isAfterLast()))
		{
			return;
		}
		String plStr = Long.toString(curPlayListId);
		String memStr = playListCursor.getString(0);
		String positionStr = Integer.toString(position);
		int isRepeatInt = (isRepeat) ? 1 : 0;
		myDB.execSQL("delete from playlists where playlist_id = "+ plStr);
		myDB.execSQL("insert into playlists (playlist_id, member_id, position, is_repeat) values ('"+plStr+"','"+memStr+"','"+positionStr+"',"+isRepeatInt+")");
	}

	public static boolean cursorTrackMotion(Cursor plC, int motionVector, boolean isRepeat)
	{
    	int curPos = plC.getPosition();
    	int curLen = plC.getCount();
    	int curRes = curPos + motionVector;
    	int newPos;

    	Log.w("simplestuffmusicplayer","curPos="+curPos+" motionVector="+motionVector+" curLen="+curLen+" curRes="+curRes);

    	if ((curRes >= 0) && (curRes < curLen)) {
    		newPos = curRes;
    		Log.w("simplestuffmusicplayer", "basic move");
    	} else if (curRes < 0) {
			newPos = 0;
		} else { // move to after the end
    		if (isRepeat) {
    			Log.w("simplestuffmusicplayer","isrepeat true");
    			newPos = curRes-curLen;
    		} else {
    			Log.w("simplestuffmusicplayer","isrepeat false");
    			newPos = curLen-1;
    		}
    	}
       	Log.w("simplestuffmusicplayer","curPos="+curPos+" motionVector="+motionVector+" curLen="+curLen+" newPos="+newPos);

    	plC.moveToPosition(newPos);

		return true;
	}

	public static Map<String,String> getTrackTitles(Cursor plC)
	{
		Map<String,String> rv = new HashMap<>();
		if (plC.isFirst()) {
			rv.put(PREV_TITLE_KEY, "Start of Playlist");
			rv.put(PREV_ARTIST_KEY, " ");
		} else {
			plC.moveToPrevious();
			rv.put(PREV_TITLE_KEY, plC.getString(2));
			rv.put(PREV_ARTIST_KEY, plC.getString(1));
			plC.moveToNext();
		}
		rv.put(CUR_TITLE_KEY, plC.getString(2));
		rv.put(CUR_ARTIST_KEY, plC.getString(1));
		if (plC.isLast()) {
			rv.put(NEXT_TITLE_KEY, "End of Playlist");
			rv.put(NEXT_ARTIST_KEY, " ");
		} else {
			plC.moveToNext();
			rv.put(NEXT_TITLE_KEY, plC.getString(2));
			rv.put(NEXT_ARTIST_KEY, plC.getString(1));
			plC.moveToPrevious();
		}

		return rv;
	}

	public static ArrayList<String> getTitleList(Cursor playListCursor) {
		if (playListCursor == null) {
			return null;
		}
		int curPos = playListCursor.getPosition();
		ArrayList<String> rv = new ArrayList<>();
		playListCursor.moveToFirst();
		do {
			rv.add(playListCursor.getString(2));
		} while ((!playListCursor.isLast()) && (playListCursor.moveToNext()));
		playListCursor.moveToPosition(curPos);
		return rv;
	}

	public Cursor getPlaylistCursor() {
		return playListCursor;
	}

	public String getName() {
		return playListName;
	}

	public int getPosition() {
		return position;
	}

	public boolean isValid() {
		return isValid;
	}

	public boolean isShuffle() {
		return isShuffle;
	}

	public boolean isRepeat() {
		return isRepeat;
	}
	
	private Cursor getMemberCursor() {
		Cursor rv;

		context.fileList();
		
		String sortOrder = (isShuffle) ? "RANDOM()" : null;
		rv = context.getContentResolver().query(
				MediaStore.Audio.Playlists.Members.getContentUri("external",myPlaylistId ),
				projection,
				MediaStore.Audio.Media.IS_MUSIC +" != 0 ",
				null,
				sortOrder);
		if ((rv==null) || (rv.getCount() <= 0)) {
			if (rv != null) {
				Log.w(context.getPackageName(),"Cursor count is "+rv.getCount());
			} else {
				Log.w(context.getPackageName(),"Cursor is null trying to get playlist members.");
			}
			return null; // invalid!
		}
		return rv;
	}
	
	private void constructShuffleMapping(Cursor shuffledMemberCursor) {
		myDB.execSQL("delete from shuffle_mapping where playlist_id="+myPlaylistId);
		String query = "insert into shuffle_mapping (playlist_id, track_index, track_id) values (?,?,?)";
		SQLiteStatement statement = myDB.compileStatement(query);
		shuffledMemberCursor.moveToFirst();
		myDB.beginTransaction();
		try {
			do {
				statement.bindLong(1,myPlaylistId);
				statement.bindLong(2, shuffledMemberCursor.getPosition());
				statement.bindLong(3,shuffledMemberCursor.getInt(0));
				statement.execute();
			} while ((!shuffledMemberCursor.isLast()) && (shuffledMemberCursor.moveToNext()));
			myDB.setTransactionSuccessful();
		} finally {
			myDB.endTransaction();
		}
	}

    @SuppressWarnings("StatementWithEmptyBody")
	private Cursor getShuffledCursor() {
		// check the database for a shuffled list
		Cursor memberCursor = getMemberCursor();
		Cursor shuffleCursor = getShuffleDbCursor();

        if (memberCursor == null || shuffleCursor == null) {
            return null;
        }
		// need to load both and compare them in case the underlying list has changed!
		// this only checks by count, it should probably check by content too...
		if ((shuffleCursor.getCount() == 0) || (shuffleCursor.getCount() != memberCursor.getCount())) {
			constructShuffleMapping(memberCursor);
			shuffleCursor = getShuffleDbCursor();
		}
		
		shuffleCursor.moveToFirst();
		
		int curTrackPos = -1;
		if (curTrackId != -1) {
			while ((shuffleCursor.moveToNext()) &&((shuffleCursor.getInt(0) != curTrackId))) {
				// nothing!
			}
			if (shuffleCursor.isAfterLast()) {
				return null;
			} else {
				curTrackPos = shuffleCursor.getPosition();
			}
		}
		
		SparseIntArray idMap = getPositionMap(memberCursor);
		// save our shuffle mapping as a MatrixCursor
		MatrixCursor rv = new MatrixCursor(projection);
		do {
			if (shuffleCursor.isAfterLast()) {
				break;
			}
			memberCursor.moveToPosition(idMap.get(shuffleCursor.getInt(0)));
			rv.addRow(new Object[] {memberCursor.getInt(0),memberCursor.getString(1),memberCursor.getString(2),
					                memberCursor.getString(3),memberCursor.getString(4),memberCursor.getInt(5),memberCursor.getInt(6),memberCursor.getString(7)});
		} while (shuffleCursor.moveToNext());
		if (curTrackId != -1) {
			shuffleCursor.moveToFirst();
			do {
				if (shuffleCursor.isAfterLast()) {
					break;
				}
				memberCursor.moveToPosition(idMap.get(shuffleCursor.getInt(0)));
				rv.addRow(new Object[] {memberCursor.getInt(0),memberCursor.getString(1),memberCursor.getString(2),
		                memberCursor.getString(3),memberCursor.getString(4),memberCursor.getInt(5),memberCursor.getInt(6),memberCursor.getString(7)});
                shuffleCursor.moveToNext();
			} while (shuffleCursor.getPosition() < curTrackPos);
		}
		memberCursor.close();
		shuffleCursor.close();
		rv.moveToFirst();
		
		return rv;
	}
	
	private SparseIntArray getPositionMap(Cursor memberCursor)
	{
		SparseIntArray rv = new SparseIntArray();
		memberCursor.moveToFirst();
		do {
			rv.put(memberCursor.getInt(0), memberCursor.getPosition());
		} while (memberCursor.moveToNext());
		return rv;
	}
	
	private Cursor getShuffleDbCursor()
	{
		return myDB.rawQuery("select track_id from shuffle_mapping where playlist_id = "+myPlaylistId+" order by track_index;",null);
	}

	public static class Builder {
		boolean isShuffle = false;
		Context context;
		long playlistId;
		SQLiteDatabase myDB;
		int currentTrack = -1;

		public Builder(Context context, long playlistId, SQLiteDatabase myDB) {
			this.context = context;
			this.playlistId = playlistId;
			this.myDB = myDB;
		}

		public Builder shuffle(boolean isShuffle) {
			this.isShuffle = isShuffle;
			return this;
		}

		public Builder currentTrack(int trackId) {
			this.currentTrack = trackId;
			return this;
		}

		public SavedPlaylist build() throws EmptyPlaylistException {
			return new SavedPlaylist(context, playlistId, myDB, isShuffle, currentTrack);
		}
	}
}
