package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager Fixed:
 * Replaced Reflection-based logic with direct API calls to libtorrent4j (v2.1.0).
 * This fixes the "Failed to generate secure link" error.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events using direct AlertType checks
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                return null; // Listen to all alerts
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert.type() == AlertType.STATE_UPDATE) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_FINISHED) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alert.type() == AlertType.TORRENT_ERROR) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            String infoHex = status.infoHash().toHex();
            if (infoHex == null) continue;

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                // Safely cast progress to int for ProgressBar
                long totalDone = status.totalDone();
                long totalWanted = status.totalWanted();
                
                // Avoid divide by zero or overflow
                if (totalWanted > 0) {
                    intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) ((totalDone * 100) / totalWanted));
                    intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, 100);
                } else {
                    intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, 0);
                    intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, 100);
                }
                
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup the torrent from the session to stop seeding/downloading
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        cleanupTorrent(handle);
    }

    /**
     * Creates a torrent file and starts seeding it.
     * Returns the Magnet URI on success, or null on failure.
     */
    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            // 1. Create the .torrent file using direct API calls
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // 2. Add the torrent to the session for seeding
            // We save to the parent directory because the torrent file contains the relative path to the data file.
            TorrentHandle handle = sessionManager.download(torrentInfo, dataFile.getParentFile());

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = handle.infoHash().toHex();
                hashToIdMap.put(infoHex, dropRequestId);
                
                // 3. Generate Magnet Link
                String magnetLink = handle.makeMagnetUri();
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            // Clean up the temporary .torrent file
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();
            }
        }
    }

    /**
     * Helper to create a .torrent file from a source file using libtorrent4j APIs directly.
     */
    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        
        // Direct API call to add the file to storage layout
        libtorrent.add_files(fs, dataFile.getAbsolutePath());

        // Create torrent object
        create_torrent ct = new create_torrent(fs);
        ct.set_creator("HFM Drop");
        ct.set_priv(true); // Private torrent (DHT disabled for this torrent usually, but useful for 1-on-1)

        // Generate and bencode the data
        entry e = ct.generate();
        byte_vector bencoded = e.bencode();
        
        // Convert to Java byte array
        byte[] torrentBytes = Vectors.byte_vector2bytes(bencoded);

        // Save to temporary file
        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    /**
     * Starts downloading a file from a magnet link.
     */
    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) saveDirectory.mkdirs();

        try {
            // Direct API call to download from magnet link
            // Using null for the Resume Data params as this is a fresh download
            TorrentHandle handle = sessionManager.download(magnetLink, saveDirectory);

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = handle.infoHash().toHex();
                hashToIdMap.put(infoHex, dropRequestId);
                
                // Prioritize the download
                handle.setSequentialDownload(true);
                
                Log.d(TAG, "Started download for request ID: " + dropRequestId);
            } else {
                Log.e(TAG, "Failed to start download: Invalid handle returned.");
                // Broadcast error
                Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
                errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Failed to initialize download session.");
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Download Error: " + e.getMessage());
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        String infoHex = handle.infoHash().toHex();
        String dropRequestId = hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }

        // Direct API call to remove
        sessionManager.remove(handle);

        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null;
    }
}