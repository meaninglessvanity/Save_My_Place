package org.meaninglessvanity;

import java.util.ArrayList;
import java.util.HashMap;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;

public class TrackChooserActivity extends ListActivity {
	private ArrayList<HashMap<String, String>> playlistList = new ArrayList<>();

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// I may need to make a new layout .. nuts.
		setContentView(R.layout.playlist);
		Intent myIntent = getIntent();
		Bundle b = myIntent.getParcelableExtra("bundle");
		ArrayList<String> curPlaylist = b.getStringArrayList("list");
		String startPosStr = myIntent.getStringExtra("position");
		int startPos = Integer.parseInt(startPosStr);
		
		int i = 0;
		for (String name : curPlaylist) {
			HashMap<String,String> curEntry = new HashMap<>();
			curEntry.put("_ID",Integer.toString(i));
			curEntry.put("NAME",name);
			playlistList.add(curEntry);
			i++;
		}  
		
		// Adding menuItems to ListView
		ListAdapter adapter = new SimpleAdapter(this, playlistList,
				R.layout.playlist_item, new String[] { "NAME" }, new int[] {
						R.id.songTitle });

		setListAdapter(adapter);
		// selecting single ListView item
		ListView lv = getListView();
		lv.setSelection(startPos);
		// listening to single listitem click
		lv.setOnItemClickListener((parent,view,position,id) ->  {
			//HashMap<String,String> item = playlistList.get(position);

			// Starting new intent
			Intent in = new Intent(getApplicationContext(),
					SaveMyPlace.class);
			// Sending songIndex to PlayerActivity
			in.putExtra("trackMotion", position);

			setResult(RESULT_OK, in);
			// Closing PlayListView
			finish();
		});
	}
}


