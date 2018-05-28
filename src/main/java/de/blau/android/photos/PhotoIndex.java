package de.blau.android.photos;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

import org.acra.ACRA;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Log;
import de.blau.android.App;
import de.blau.android.contract.Paths;
import de.blau.android.osm.BoundingBox;
import de.blau.android.util.ACRAHelper;
import de.blau.android.util.rtree.BoundedObject;
import de.blau.android.util.rtree.RTree;

/**
 * Scan the system for geo ref photos and store is in a DB
 * 
 * @author simon
 *
 */
public class PhotoIndex extends SQLiteOpenHelper {

    private static final int    DATA_VERSION = 3;
    private static final String LOGTAG       = "PhotoIndex";

    private class JpgFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(Paths.FILE_EXTENSION_IMAGE);
        }
    }

    public PhotoIndex(Context context) {
        super(context, "PhotoIndex", null, DATA_VERSION);
    }

    @Override
    public synchronized void onCreate(SQLiteDatabase db) {
        Log.d(LOGTAG, "Creating photo index DB");
        db.execSQL("CREATE TABLE IF NOT EXISTS photos (lat int, lon int, direction int DEFAULT NULL, dir VARCHAR, name VARCHAR);");
        db.execSQL("CREATE INDEX latidx ON photos (lat)");
        db.execSQL("CREATE INDEX lonidx ON photos (lon)");
        db.execSQL("CREATE TABLE IF NOT EXISTS directories  (dir VARCHAR, last_scan int8);");
        db.execSQL("INSERT INTO directories VALUES ('DCIM', 0);");
        db.execSQL("INSERT INTO directories VALUES ('Vespucci', 0);");
        db.execSQL("INSERT INTO directories VALUES ('osmtracker', 0);");
    }

    @Override
    public synchronized void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(LOGTAG, "Upgrading photo index DB");
        if (oldVersion <= 2) {
            db.execSQL("ALTER TABLE photos ADD direction int DEFAULT NULL");
        }
    }

    /**
     * Create or update the index of images on the device
     */
    public synchronized void createOrUpdateIndex() {
        Log.d(LOGTAG, "starting scan");
        // determine at least a few of the possible mount points
        File sdcard = Environment.getExternalStorageDirectory();
        ArrayList<String> mountPoints = new ArrayList<>();
        mountPoints.add(sdcard.getAbsolutePath());
        mountPoints.add(sdcard.getAbsolutePath() + Paths.DIRECTORY_PATH_EXTERNAL_SD_CARD);
        File storageDir = new File(Paths.DIRECTORY_PATH_STORAGE);
        File[] list = storageDir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.exists() && f.isDirectory() && !sdcard.getAbsolutePath().equals(f.getAbsolutePath())) {
                    Log.d(LOGTAG, "Adding mount point " + f.getAbsolutePath());
                    mountPoints.add(f.getAbsolutePath());
                }
            }
        }

        try {
            SQLiteDatabase db = getWritableDatabase();
            Cursor dbresult = db.query("directories", new String[] { "dir", "last_scan" }, null, null, null, null, null, null);
            int dirCount = dbresult.getCount();
            dbresult.moveToFirst();
            // loop over the directories configured
            for (int i = 0; i < dirCount; i++) {
                String dir = dbresult.getString(0);
                long lastScan = dbresult.getLong(1);
                Log.d(LOGTAG, dbresult.getString(0) + " " + dbresult.getLong(1));
                // loop over all possible mount points
                for (String m : mountPoints) {
                    File indir = new File(m + "/" + dir);
                    Log.d(LOGTAG, "Scanning directory " + indir.getAbsolutePath());
                    if (indir.exists()) {
                        Cursor dbresult2 = db.query("photos", new String[] { "distinct dir" }, "dir LIKE '" + indir.getAbsolutePath() + "%'", null, null, null,
                                null, null);
                        int dirCount2 = dbresult2.getCount();
                        dbresult2.moveToFirst();
                        for (int j = 0; j < dirCount2; j++) {
                            String dir2 = dbresult2.getString(0);
                            Log.d(LOGTAG, "Checking dir " + dir2);
                            File pDir = new File(dir2);
                            if (!pDir.exists()) {
                                Log.d(LOGTAG, "Deleting entries for gone dir " + dir2);
                                db.delete("photos", "dir = ?", new String[] { dir2 });
                            }
                            dbresult2.moveToNext();
                        }
                        dbresult2.close();
                        scanDir(db, indir.getAbsolutePath(), lastScan);
                        ContentValues values = new ContentValues();
                        Log.d(LOGTAG, "updating last scan for " + indir.getName() + " to " + System.currentTimeMillis());
                        values.put("last_scan", System.currentTimeMillis());
                        db.update("directories", values, "dir = ?", new String[] { indir.getName() });
                    } else {
                        Log.d(LOGTAG, "Directory " + indir.getAbsolutePath() + " doesn't exist");
                        // remove all entries for this directory
                        db.delete("photos", "dir = ?", new String[] { indir.getAbsolutePath() });
                        db.delete("photos", "dir LIKE ?", new String[] { indir.getAbsolutePath() + "/%" });
                    }
                }
                dbresult.moveToNext();
            }
            dbresult.close();
            db.close();
        } catch (SQLiteException ex) {
            // Don't crash just report
            ACRAHelper.nocrashReport(ex, ex.getMessage());
        }
    }

    /**
     * Recursively scan directories and add images to index
     * 
     * @param db database containing the index
     * @param dir directory we are starting with
     * @param lastScan date we last scanned this directory tree
     */
    private void scanDir(SQLiteDatabase db, String dir, long lastScan) {
        // Log.d(LOGTAG,"directory " + dir + " last Scan " + (new Date(lastScan)).toString());
        File indir = new File(dir);
        boolean needsReindex = false;
        if (indir != null) {
            if (indir.lastModified() >= lastScan) { // directory was modified
                // remove all entries
                Log.d(LOGTAG, "deleteing refs for reindex");
                try {
                    db.delete("photos", "dir = ?", new String[] { indir.getAbsolutePath() });
                } catch (SQLiteException sqex) {
                    Log.d(LOGTAG, sqex.toString());
                    ACRAHelper.nocrashReport(sqex, sqex.getMessage());
                }
                needsReindex = true;
            }
            // now process
            File[] list = indir.listFiles();
            if (list == null) {
                return;
            }
            // check if we shouldn't process this directory, not the most efficient way likely
            for (File f : list) {
                if (f.getName().equals(".novespucci")) {
                    return;
                }
            }
            for (File f : list) {
                if (f.isDirectory()) {
                    // recursive decent
                    scanDir(db, f.getAbsolutePath(), lastScan);
                }
                if (needsReindex && f.getName().toLowerCase(Locale.US).endsWith(Paths.FILE_EXTENSION_IMAGE)) {
                    addPhoto(db, indir, f);
                }
            }
        }
    }

    /**
     * Add image to index
     * 
     * @param f the image file
     */
    public synchronized void addPhoto(File f) {
        SQLiteDatabase db = getWritableDatabase();
        // Log.i(LOGTAG,"Adding entry in " + f.getParent());
        Photo p = addPhoto(db, f.getParentFile(), f);
        db.close();
        RTree index = App.getPhotoIndex();
        if (p != null && index != null) { // if nothing is in the index the complete DB including this photo will be
                                          // added
            index.insert(p);
        }
    }

    /**
     * Add image to index
     * 
     * @param db database containing the image
     * @param dir directory the image is in
     * @param f the image file
     * @return a Photo object
     */
    private Photo addPhoto(SQLiteDatabase db, File dir, File f) {
        // Log.i(LOGTAG,"Adding entry for " + dir.getName() + " " + f.getName() + " abs " + dir.getAbsolutePath());
        try {
            Photo p = new Photo(dir, f);
            ContentValues values = new ContentValues();
            values.put("lat", p.getLat());
            values.put("lon", p.getLon());
            // Log.i(LOGTAG,"Lat: " + p.getLat() + " " + p.getLon());
            if (p.hasDirection()) {
                values.put("direction", p.getDirection());
            }
            values.put("dir", dir.getAbsolutePath());
            values.put("name", f.getName());
            db.insert("photos", null, values);
            return p;
        } catch (SQLiteException sqex) {
            Log.d(LOGTAG, sqex.toString());
            ACRAHelper.nocrashReport(sqex, sqex.getMessage());
        } catch (IOException ioex) {
            // ignore silently, broken pictures are not our business
        } catch (NumberFormatException bfex) {
            // ignore silently, broken pictures are not our business
        } catch (Exception ex) {
            Log.d(LOGTAG, ex.toString());
            ACRAHelper.nocrashReport(ex, ex.getMessage());
        } // ignore
        return null;
    }

    /**
     * Return all photographs in a given bounding box If necessary fill in-memory index first
     * 
     * @param box
     * @return
     */
    public Collection<Photo> getPhotos(BoundingBox box) {
        RTree index = App.getPhotoIndex();
        if (index == null) {
            return new ArrayList<>();
        }

        return getPhotosFromIndex(index, box);
    }

    public synchronized void fill(RTree index) {
        if (index == null) {
            App.resetPhotoIndex(); // allocate r-tree
            index = App.getPhotoIndex();
        }
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor dbresult = db.query("photos", new String[] { "lat", "lon", "direction", "dir", "name" }, null, null, null, null, null, null);
            int photoCount = dbresult.getCount();
            dbresult.moveToFirst();
            Log.i(LOGTAG, "Query returned " + photoCount + " photos");
            //
            for (int i = 0; i < photoCount; i++) {
                if (dbresult.isNull(2)) { // no direction
                    index.insert(new Photo(dbresult.getInt(0), dbresult.getInt(1), dbresult.getString(3) + "/" + dbresult.getString(4)));
                } else {
                    index.insert(new Photo(dbresult.getInt(0), dbresult.getInt(1), dbresult.getInt(2), dbresult.getString(3) + "/" + dbresult.getString(4)));
                }
                dbresult.moveToNext();
            }
            dbresult.close();
            db.close();
        } catch (SQLiteException ex) {
            // shoudn't happen (getReadableDatabase failed), simply report for now
            ACRA.getErrorReporter().handleException(ex);
        }
    }

    private ArrayList<Photo> getPhotosFromIndex(RTree index, BoundingBox box) {
        Collection<BoundedObject> queryResult = new ArrayList<>();
        index.query(queryResult, box.getBounds());
        Log.d(LOGTAG, "result count " + queryResult.size());
        ArrayList<Photo> result = new ArrayList<>();
        for (BoundedObject bo : queryResult) {
            result.add((Photo) bo);
        }
        return result;
    }

}
