package org.meaninglessvanity;

import android.app.ListActivity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import androidx.loader.content.CursorLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PlayListActivity extends ListActivity {
	List<Map<String,String>> playlistList = new ArrayList<>();
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.playlist);
		Cursor cursor = null;

		String[] projection1 = {
				MediaStore.Audio.Playlists._ID,
				MediaStore.Audio.Playlists.NAME
		};

		CursorLoader cLoader = new CursorLoader(getApplicationContext(),
				MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, projection1,
				MediaStore.Audio.Playlists._ID, null, null);
		cursor = cLoader.loadInBackground();
		if (cursor != null ) {
			if (cursor.moveToFirst()) {
				do {
					HashMap<String, String> song = new HashMap<>();
					song.put("_ID", cursor.getString(0));
					song.put("NAME", cursor.getString(1));
					playlistList.add(song);
					Log.w(this.getPackageName(), "id: " + cursor.getString(0) + " name: " + cursor.getString(1));
				} while (cursor.moveToNext());
			}
			cursor.close();
		}

		if (playlistList.isEmpty()) {
			Toast.makeText(this, "No playlists found!", Toast.LENGTH_SHORT).show();
			Intent in = new Intent(getApplicationContext(),SaveMyPlace.class);
			setResult(RESULT_CANCELED);
			finish();
		}

		// selecting single ListView item
		ListView lv = new ListView(this);
		ListAdapter adapter = new SimpleAdapter(this, playlistList,
				R.layout.playlist_item, new String[] { "NAME" }, new int[] {
				R.id.playlistName });
		lv.setAdapter(adapter);

		// listening to single listitem click
		lv.setOnItemClickListener((parent, view, position, id) -> {
			Map<String,String> item = playlistList.get(position);

			Intent in = new Intent(getApplicationContext(),
					SaveMyPlace.class);
			in.putExtra("playListId", item.get("_ID"));

			setResult(RESULT_OK, in);
			finish();
		});

	}
}
