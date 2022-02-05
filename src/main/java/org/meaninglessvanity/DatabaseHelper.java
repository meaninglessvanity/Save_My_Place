package org.meaninglessvanity;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static DatabaseHelper instance = null;
    private DatabaseHelper(Context context)
    {
        super(context, "SaveMyPlace", null, 1);
    }

    public static DatabaseHelper getInstance(Context context)
    {
        if (instance == null) {
            instance = new DatabaseHelper(context);
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase myDB) {
		/* Create a Table in the Database. */
        myDB.execSQL("CREATE TABLE IF NOT EXISTS Playlists (playlist_id INTEGER PRIMARY KEY,  member_id INTEGER, position INTEGER, is_repeat integer);");
        myDB.execSQL("CREATE TABLE IF NOT EXISTS SETTINGS (name varchar(100) PRIMARY KEY, value varchar(100));");
        myDB.execSQL("CREATE TABLE IF NOT EXISTS SHUFFLE_MAPPING (playlist_id INTEGER, track_id integer, track_index integer, primary key(playlist_id, track_index));");
        myDB.execSQL("INSERT INTO SETTINGS (name, value) values ('db_version','1')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
        // do nothing no upgrade required
    }
}
