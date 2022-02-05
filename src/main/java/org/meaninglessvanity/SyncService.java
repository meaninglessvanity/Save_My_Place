package org.meaninglessvanity;

import static java.lang.Thread.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Message;
import android.util.Log;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import org.meaninglessvanity.ssh.ConnectionStatusListener;
import org.meaninglessvanity.ssh.SessionController;
import org.meaninglessvanity.ssh.SessionUserInfo;
import org.meaninglessvanity.ssh.SftpController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SyncService extends AbstractService {
    public static final int STOP_SYNC = 1;
    public static final int START_SYNC = 2;
    ServerSocket ss;
    enum SyncStatus {
        INIT, CONNECTED, DESC_FILE_DOWNLOAD, MUSIC_DOWNLOAD, COMPLETE;
    }

    SyncStatus current = SyncStatus.INIT;

    public SyncService() {
        super();
        log("creating service object!");
        messageWhats.add(STOP_SYNC);
        messageWhats.add(START_SYNC);
    }

    @Override
    public void handleIncomingMessage(Message msg) {
        int newPos;
        log( "got message number " + msg.what);
        switch (msg.what) {
            case START_SYNC:
                startSync();
                break;
            case STOP_SYNC:
                if (current == SyncStatus.DESC_FILE_DOWNLOAD || current == SyncStatus.MUSIC_DOWNLOAD) {
                    // TODO commit suicide
                }
                break;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("OnStartCommand!");
        cleanMediaStorageDir(); // BJT TODO remove this once sync is implemented fully
        super.onStartCommand(intent, flags, startId);
        if (ss == null || !ss.isBound()) {
            try {
                ss = new ServerSocket(42425);
            } catch (IOException e) {
                log( "Unable to create server socket"+ e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        Thread t = new Thread(() -> startSync());
        t.start();
        return START_STICKY;
    }

    private void startSync() {
        log("Waiting for debugger...");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        current = SyncStatus.INIT;
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("SaveMyPlaceMulticast");
        lock.acquire();

        if (!lock.isHeld()) {
            log("multicast lock not held");
            return;
        }

        String multiaddr = "224.0.0.1";
        InetAddress multiAddress;
        try {
            multiAddress = InetAddress.getByName(multiaddr);
        } catch (Exception e) {
            log("Error getting multicast address"+e);
            return;
        }

        Enumeration<NetworkInterface> netIfs;
        try {
            netIfs = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        NetworkInterface outboundIf = null;
        InetAddress outboundAddr = null;
        while (netIfs.hasMoreElements()) {
            NetworkInterface intf = netIfs.nextElement();
            InetAddress addr;
            Enumeration<InetAddress> addrs = intf.getInetAddresses();
            while (addrs.hasMoreElements()) {
                addr = addrs.nextElement();
                if (!addr.isLoopbackAddress() && addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                    outboundIf = intf;
                    outboundAddr = addr;
                }
            }
        }
        if (outboundIf == null) {
            throw new RuntimeException("No network interface found.");
        }

        String fileLoc;
        String remoteAddr;
        try (MulticastSocket ms = new MulticastSocket(42424)){
            ms.joinGroup(multiAddress);
            byte address[] = InetAddress.getLocalHost().getAddress();
            ByteBuffer bb = ByteBuffer.allocate(1024);
            bb.put(outboundAddr.getAddress());
            DatagramPacket dp = new DatagramPacket(bb.array(), bb.array().length, multiAddress, 42424);
            ms.send(dp);
            String received;
            do {
                ms.receive(dp);
                received = new String(dp.getData(),StandardCharsets.UTF_8);
                log( "received " + received.length() + " character answer from " + multiAddress.getAddress() + ": ["+received+"]");
            } while (!received.contains(","));
            int fileLocPos = received.lastIndexOf(",")+1;
            String fields[] = received.split(",");

            fields[0] = fields[0].trim();
            int totalLength = Integer.parseInt(fields[0]);
            fileLoc = received.substring(fileLocPos,totalLength);
            remoteAddr = fields[1].replace("/","");
            fileLoc = fileLoc.replace("\\","/");
            fileLoc = fileLoc.replace("C:","");
            log("fileloc = "+fileLoc+" remoteAddr="+remoteAddr);

            File songDescsFile = createFile("songDescs.txt",false);

            if (remoteAddr != null) {
                String dir = getApplicationContext().getFilesDir().getAbsolutePath();
                System.setProperty("user.home",dir);
                var userInfo = new SessionUserInfo("music",remoteAddr, "password",42422);
                String finalFileLoc = fileLoc;
                SessionController.getSessionController().setConnectionStatusListener(new ConnectionStatusListener() {
                    @Override
                    public void onDisconnected() {
                        log("ssh disconnected");
                    }

                    @Override
                    public void onConnected() {
                        if (current == SyncStatus.INIT) {
                            current = SyncStatus.CONNECTED;
                            log("sending download request");

                            try {
                                SessionController.getSessionController().downloadFile(finalFileLoc, songDescsFile.getAbsolutePath(), new ProgressModel());
                            } catch (JSchException e) {
                                log("download failed"+e);
                            } catch (SftpException e) {
                                log("download failed"+e);
                            }
                            current = SyncStatus.DESC_FILE_DOWNLOAD;
                        }
                    }
                });
                SessionController.getSessionController().setUserInfo(userInfo);
                SessionController.getSessionController().connect();
            }
        } catch (Exception e) {
            log("Multicast Error:"+e);
        } finally {
            lock.release();
        }

        while (true) {
            try {
                sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //stopSelf();
        //log("stopping service!");
    }

    private File getMediaStorageDir() {
        File myDir = new File(Environment.getExternalStorageDirectory().toString()+"/Music/SaveMyPlace");
        log("storage dir is: "+myDir.getAbsolutePath());
        if (!myDir.exists()) {
            myDir.mkdirs();
        }
        return myDir;
    }

    private File getFileDir() {
        return getApplicationContext().getFilesDir();
    }

    private void cleanMediaStorageDir() {
        File storageDir = getMediaStorageDir();
        if (storageDir != null) {
            File localFiles[] = storageDir.listFiles();
            if (localFiles != null && localFiles.length > 0) {
                for (File f : localFiles) {
                    deleteRecursive(f);
                }
            }
        }
    }

    void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles())
                deleteRecursive(child);
        }
        fileOrDirectory.delete();
    }

    /**
     *
     * @param fileName - relative path to file
     * @return - the file
     */
    private File getFile(String fileName, boolean isMedia) {
        File currentDir = (isMedia) ? getMediaStorageDir() : getFileDir();
        String newPath = currentDir + "/" + fileName;
        if (fileName.contains("/")) {
            List<String> dirNames = Arrays.asList(fileName.split("/"));
            for (int i=0; i<dirNames.size(); i++) {
                String dir = dirNames.get(i);
                newPath = currentDir.getAbsolutePath() + "/" + dir;
                File newFile = new File(newPath);
                if (i < dirNames.size()-1) {
                    if (newFile.exists() && !newFile.isDirectory()) {
                        newFile.delete();
                    }
                    if (!newFile.exists()) {
                        try {
                            newFile.mkdirs();
                            newFile.createNewFile();
                        } catch (IOException ioe) {
                            // TODO
                        }
                    }
                    currentDir = newFile;
                }
            }
        }
        return new File(newPath);
    }

    private File createFile(String fileName, boolean isMedia) {
        File outfile = getFile(fileName, isMedia);
        if (outfile.exists() && outfile.isDirectory()) {
            outfile.delete();
        }
        if (!outfile.exists()) {
            try {
                outfile.createNewFile();
            } catch (IOException ioe) {
                log( "can't create new file: " + ioe);
            }
        }

        return outfile;
    }
    
    private void log(String s) {
        Log.e("org.meaninglessvanity", s);
    }

    // BJT TODO: use bookmarked link to add playlists to mediastores.
    private class ProgressModel implements SftpProgressMonitor {
        ArrayList<String> serverList = new ArrayList<>();
        Iterator<String> serverIter;
        Iterator<String> localPathIter;
        List<String> pathList = new ArrayList<>();
        int index = 0;
        @Override
        public void init(int op, String src, String dest, long max) {
            log("Transferring "+src+" to "+dest+" max size: "+max);
        }

        @Override
        public boolean count(long count) {
            return true;
        }

        @Override
        public void end() {
            if (current == SyncStatus.DESC_FILE_DOWNLOAD) {
                parseSongDescs();
                current = SyncStatus.MUSIC_DOWNLOAD;
            }
            if (index < serverList.size()) {
                readNextSong();
            } else {
                for (String path : pathList) {
                    scanMedia(path);
                }
                current = SyncStatus.COMPLETE;
            }
        }

        private void scanMedia(String path) {
            File file = new File(path);
            Uri uri = Uri.fromFile(file);
            Intent scanFileIntent = new Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri);
            sendBroadcast(scanFileIntent);
        }

        private void readNextSong() {
            String curFile = serverIter.next();
            try {
                List<String> levels = Arrays.asList(curFile.split("/"));
                int depth = levels.size();
                String fileName = levels.get(depth-3)+"/"+levels.get(depth-2)+"/"+levels.get(depth-1);
                File destination = getFile(fileName,true);
                pathList.add(destination.getAbsolutePath());
                if (!destination.exists()) {
                    SessionController.getSessionController().downloadFile(curFile, destination.getAbsolutePath(), this);
                }
                index++;
            } catch (JSchException e) {
                log("jsch exception: "+e);
            } catch (SftpException e) {
                log("sftp exception: "+e);
            }
        }

        private void parseSongDescs() {
            File toRead = getFile("songDescs.txt",false);
            try (FileReader fr = new FileReader(toRead)) {
                BufferedReader br = new BufferedReader(fr);
                StringBuffer sb = new StringBuffer();
                String line;
                while ((line = br.readLine()) != null && !line.equals("PLAYLISTS")) {
                    String fields[] = line.split(",");
                    serverList.add(fields[2].replace("\\",","));
                }
                serverIter = serverList.iterator();
                while ((line = br.readLine()) != null) {
                    line.replace("[","");
                    line.replace("]","");
                    String fields[] = line.split(",");
                    String plName = fields[0];
                    List<String> plUuids = Arrays.asList(Arrays.copyOfRange(fields,1,fields.length));
                }
            } catch (FileNotFoundException fnfe) {
                log("file not found: "+toRead.getAbsolutePath());
            } catch (IOException ioe) {
                log("io exception reading file: "+ioe);
            }
        }
    }


}
