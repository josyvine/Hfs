package com.hfs.security.ui.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.hfs.security.R;
import com.hfs.security.adapters.IntruderLogAdapter;
import com.hfs.security.databinding.FragmentIntruderHistoryBinding;
import com.hfs.security.models.IntruderLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screen for viewing Intruder Evidence (Phase 6 / UI Option 4).
 * Scans the hidden internal directory for captured photos and intrusion logs.
 * Displays data in a grid for easy identification of intruders.
 */
public class IntruderHistoryFragment extends Fragment implements IntruderLogAdapter.OnLogActionListener {

    private FragmentIntruderHistoryBinding binding;
    private IntruderLogAdapter adapter;
    private List<IntruderLog> intruderLogList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the history layout
        binding = FragmentIntruderHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        intruderLogList = new ArrayList<>();
        setupRecyclerView();
        loadIntrusionLogs();

        // Manual refresh button logic
        binding.btnRefreshLogs.setOnClickListener(v -> loadIntrusionLogs());

        // Clear All button logic
        binding.btnClearAll.setOnClickListener(v -> showClearAllConfirmation());
    }

    private void setupRecyclerView() {
        // Use a Grid Layout (2 columns) to show intruder photos clearly
        binding.rvIntruderLogs.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        adapter = new IntruderLogAdapter(intruderLogList, this);
        binding.rvIntruderLogs.setAdapter(adapter);
    }

    /**
     * Scans the HFS internal storage directory for .jpg files.
     * Path: /Android/data/com.hfs.security/files/intruders/
     */
    private void loadIntrusionLogs() {
        binding.progressBar.setVisibility(View.VISIBLE);
        intruderLogList.clear();

        // Reference the secure intruders directory
        File intrudersDir = new File(requireContext().getExternalFilesDir(null), "intruders");
        
        if (intrudersDir.exists() && intrudersDir.isDirectory()) {
            File[] photoFiles = intrudersDir.listFiles((dir, name) -> 
                    name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".png"));
            
            if (photoFiles != null) {
                for (File file : photoFiles) {
                    intruderLogList.add(new IntruderLog(file));
                }
            }
        }

        // Sort by timestamp: Newest intrusion attempts at the top
        Collections.sort(intruderLogList, (log1, log2) -> 
                Long.compare(log2.getTimestamp(), log1.getTimestamp()));

        binding.progressBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();

        // Toggle Empty State UI
        if (intruderLogList.isEmpty()) {
            binding.tvNoIntruders.setVisibility(View.VISIBLE);
            binding.rvIntruderLogs.setVisibility(View.GONE);
            binding.btnClearAll.setVisibility(View.GONE);
        } else {
            binding.tvNoIntruders.setVisibility(View.GONE);
            binding.rvIntruderLogs.setVisibility(View.VISIBLE);
            binding.btnClearAll.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Implementation of the Adapter Listener for clicking a log entry.
     * Opens the intruder photo in full screen using the system viewer.
     */
    @Override
    public void onLogClicked(IntruderLog log) {
        File file = new File(log.getFilePath());
        Uri uri = FileProvider.getUriForFile(requireContext(), 
                requireContext().getPackageName() + ".fileprovider", file);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "No image viewer found", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handles the deletion of a specific intrusion record.
     */
    @Override
    public void onDeleteClicked(IntruderLog log) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Evidence?")
                .setMessage("This will permanently remove this intruder photo.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    File file = new File(log.getFilePath());
                    if (file.delete()) {
                        Toast.makeText(requireContext(), "Log deleted", Toast.LENGTH_SHORT).show();
                        loadIntrusionLogs();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showClearAllConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Clear All Logs?")
                .setMessage("Are you sure you want to delete ALL intruder history?")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    for (IntruderLog log : intruderLogList) {
                        new File(log.getFilePath()).delete();
                    }
                    loadIntrusionLogs();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}