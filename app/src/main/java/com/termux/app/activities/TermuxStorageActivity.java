package com.termux.app.activities;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TermuxStorageActivity extends AppCompatActivity {

    private static final String LOG_TAG = "TermuxStorageActivity";

    private File mCurrentDir;
    private File mHomeDir;
    private File mStorageDir;
    private File mSharedStorageDir;

    private RecyclerView mRecyclerView;
    private FileAdapter mAdapter;
    private TextView mTextCurrentPath;
    private ImageButton mButtonNavUp;
    private View mViewEmptyState;
    private View mViewPermissionBanner;
    private View mViewClipboardBar;
    private TextView mTextClipboardInfo;

    // Clipboard for Copy / Move
    private File mClipboardFile = null;
    private boolean mIsMoveOperation = false;

    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_manager);

        mHomeDir = TermuxConstants.TERMUX_HOME_DIR;
        mStorageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
        mSharedStorageDir = new File(mStorageDir, "shared");

        mCurrentDir = mHomeDir;

        initViews();
        setupToolbar();
        setupChips();
        setupRecyclerView();

        navigateTo(mCurrentDir);
    }

    private void initViews() {
        mTextCurrentPath = findViewById(R.id.text_current_path);
        mButtonNavUp = findViewById(R.id.button_nav_up);
        mViewEmptyState = findViewById(R.id.view_empty_state);
        mViewPermissionBanner = findViewById(R.id.view_permission_banner);
        mViewClipboardBar = findViewById(R.id.view_clipboard_bar);
        mTextClipboardInfo = findViewById(R.id.text_clipboard_info);

        mButtonNavUp.setOnClickListener(v -> navigateUp());

        findViewById(R.id.button_grant_storage_permission).setOnClickListener(v -> runStorageSetup());

        findViewById(R.id.button_paste_clipboard).setOnClickListener(v -> pasteClipboardFile());
        findViewById(R.id.button_cancel_clipboard).setOnClickListener(v -> clearClipboard());

        FloatingActionButton fabAdd = findViewById(R.id.fab_storage_add);
        fabAdd.setOnClickListener(v -> showCreateFolderDialog());
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.storage_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_storage_manager);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_create_folder_menu) {
            showCreateFolderDialog();
            return true;
        } else if (id == R.id.action_refresh_menu) {
            loadFiles();
            return true;
        } else if (id == R.id.action_setup_storage_menu) {
            runStorageSetup();
            return true;
        }
        return false;
    }

    private void setupChips() {
        Chip chipHome = findViewById(R.id.chip_home);
        Chip chipShared = findViewById(R.id.chip_shared);
        Chip chipStorageRoot = findViewById(R.id.chip_storage_root);

        chipHome.setOnClickListener(v -> navigateTo(mHomeDir));
        chipShared.setOnClickListener(v -> {
            if (!mSharedStorageDir.exists()) {
                promptSetupStorage();
            } else {
                navigateTo(mSharedStorageDir);
            }
        });
        chipStorageRoot.setOnClickListener(v -> {
            if (!mStorageDir.exists()) {
                promptSetupStorage();
            } else {
                navigateTo(mStorageDir);
            }
        });
    }

    private void setupRecyclerView() {
        mRecyclerView = findViewById(R.id.recycler_files);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new FileAdapter();
        mRecyclerView.setAdapter(mAdapter);
    }

    public void navigateTo(File dir) {
        if (dir == null) return;

        // Resolve symlinks if needed
        try {
            dir = dir.getCanonicalFile();
        } catch (IOException e) {
            dir = dir.getAbsoluteFile();
        }

        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "Directory does not exist: " + dir.getName(), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!dir.canRead()) {
            Toast.makeText(this, getString(R.string.err_permission_denied), Toast.LENGTH_SHORT).show();
            if (isPathOnExternalStorage(dir)) {
                checkSharedStorageSetup();
            }
            return;
        }

        mCurrentDir = dir;
        updatePathDisplay();
        loadFiles();

        if (isPathOnExternalStorage(dir)) {
            checkSharedStorageSetup();
        } else {
            mViewPermissionBanner.setVisibility(View.GONE);
        }
    }

    private boolean isPathOnExternalStorage(File file) {
        String path = file.getAbsolutePath();
        return path.startsWith("/sdcard") || path.startsWith("/storage") || path.contains("/shared");
    }

    private void updatePathDisplay() {
        String path = mCurrentDir.getAbsolutePath();
        String homePath = mHomeDir.getAbsolutePath();
        String storagePath = mStorageDir.getAbsolutePath();

        String displayPath;
        if (path.startsWith(homePath)) {
            displayPath = "~" + path.substring(homePath.length());
        } else if (path.startsWith(storagePath)) {
            displayPath = "~/storage" + path.substring(storagePath.length());
        } else {
            displayPath = path;
        }

        mTextCurrentPath.setText(displayPath);
        mButtonNavUp.setEnabled(mCurrentDir.getParentFile() != null && mCurrentDir.getParentFile().canRead());
    }

    private void navigateUp() {
        if (mCurrentDir != null) {
            File parent = mCurrentDir.getParentFile();
            if (parent != null && parent.exists() && parent.canRead()) {
                navigateTo(parent);
            } else {
                Toast.makeText(this, "Cannot navigate further up", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadFiles() {
        if (mCurrentDir == null || !mCurrentDir.canRead()) {
            mAdapter.setFiles(Collections.emptyList());
            mViewEmptyState.setVisibility(View.VISIBLE);
            return;
        }

        File[] files = mCurrentDir.listFiles();
        if (files == null || files.length == 0) {
            mAdapter.setFiles(Collections.emptyList());
            mViewEmptyState.setVisibility(View.VISIBLE);
        } else {
            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            // Sort: directories first, then alphabetical (case insensitive)
            Collections.sort(fileList, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });
            mAdapter.setFiles(fileList);
            mViewEmptyState.setVisibility(View.GONE);
        }
    }

    private void checkSharedStorageSetup() {
        boolean hasPermission = PermissionUtils.checkStoragePermission(this, PermissionUtils.isLegacyExternalStoragePossible(this));
        if (!hasPermission) {
            mViewPermissionBanner.setVisibility(View.VISIBLE);
        } else {
            mViewPermissionBanner.setVisibility(View.GONE);
            if (!mSharedStorageDir.exists()) {
                promptSetupStorage();
            }
        }
    }

    private void promptSetupStorage() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.title_setup_storage_prompt)
            .setMessage(R.string.msg_setup_storage_prompt)
            .setPositiveButton(R.string.action_setup_storage, (d, w) -> runStorageSetup())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    public void runStorageSetup() {
        PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
            this, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION, true);
        TermuxInstaller.setupStorageSymlinks(this);
        mRecyclerView.postDelayed(() -> {
            checkSharedStorageSetup();
            loadFiles();
        }, 1200);
    }

    private void showCreateFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.prompt_folder_name);
        input.setSingleLine();

        new AlertDialog.Builder(this)
            .setTitle(R.string.action_create_folder)
            .setView(input)
            .setPositiveButton(R.string.action_create_folder, (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (!TextUtils.isEmpty(name)) {
                    createFolder(name);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void createFolder(String name) {
        File newFolder = new File(mCurrentDir, name);
        if (newFolder.exists()) {
            Toast.makeText(this, "Folder already exists", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newFolder.mkdirs()) {
            Toast.makeText(this, getString(R.string.msg_folder_created), Toast.LENGTH_SHORT).show();
            loadFiles();
        } else {
            Toast.makeText(this, getString(R.string.err_file_op_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(File file) {
        final EditText input = new EditText(this);
        input.setText(file.getName());
        input.setSingleLine();

        new AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(R.string.action_rename, (dialog, which) -> {
                String newName = input.getText().toString().trim();
                if (!TextUtils.isEmpty(newName) && !newName.equals(file.getName())) {
                    renameFile(file, newName);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void renameFile(File file, String newName) {
        File target = new File(mCurrentDir, newName);
        if (target.exists()) {
            Toast.makeText(this, "File already exists with that name", Toast.LENGTH_SHORT).show();
            return;
        }

        if (file.renameTo(target)) {
            Toast.makeText(this, getString(R.string.msg_renamed), Toast.LENGTH_SHORT).show();
            loadFiles();
        } else {
            Toast.makeText(this, getString(R.string.err_file_op_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteDialog(File file) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, file.getName()))
            .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteFile(file))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void deleteFile(File file) {
        boolean deleted = deleteRecursive(file);
        if (deleted) {
            Toast.makeText(this, getString(R.string.msg_file_deleted), Toast.LENGTH_SHORT).show();
            loadFiles();
        } else {
            Toast.makeText(this, getString(R.string.err_file_op_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    private void setClipboardFile(File file, boolean isMove) {
        mClipboardFile = file;
        mIsMoveOperation = isMove;
        mViewClipboardBar.setVisibility(View.VISIBLE);
        mTextClipboardInfo.setText((isMove ? "Move: " : "Copy: ") + file.getName());
    }

    private void clearClipboard() {
        mClipboardFile = null;
        mViewClipboardBar.setVisibility(View.GONE);
    }

    private void pasteClipboardFile() {
        if (mClipboardFile == null || !mClipboardFile.exists()) {
            clearClipboard();
            return;
        }

        File target = new File(mCurrentDir, mClipboardFile.getName());
        if (target.exists()) {
            Toast.makeText(this, "Item already exists in destination", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (mIsMoveOperation) {
                if (mClipboardFile.renameTo(target)) {
                    Toast.makeText(this, getString(R.string.msg_pasted), Toast.LENGTH_SHORT).show();
                } else {
                    // Fallback to copy and delete
                    copyRecursive(mClipboardFile, target);
                    deleteRecursive(mClipboardFile);
                    Toast.makeText(this, getString(R.string.msg_pasted), Toast.LENGTH_SHORT).show();
                }
            } else {
                copyRecursive(mClipboardFile, target);
                Toast.makeText(this, getString(R.string.msg_pasted), Toast.LENGTH_SHORT).show();
            }
            clearClipboard();
            loadFiles();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Paste failed", e);
            Toast.makeText(this, "Paste failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("Failed to create directory " + dest.getAbsolutePath());
            }
            File[] files = src.listFiles();
            if (files != null) {
                for (File child : files) {
                    copyRecursive(child, new File(dest, child.getName()));
                }
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    private void openFile(File file) {
        if (file == null || !file.exists()) return;

        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = (dot >= 0) ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mimeType == null) {
            mimeType = "*/*";
        }

        Uri uri = Uri.fromFile(file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (Exception e) {
            showTextPreviewDialog(file);
        }
    }

    private void showTextPreviewDialog(File file) {
        if (file.length() > 512 * 1024) {
            Toast.makeText(this, "File too large for preview: " + file.getName(), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setMessage(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Exception ex) {
            Toast.makeText(this, "Cannot preview file: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (mCurrentDir != null && !mCurrentDir.equals(mHomeDir)) {
            File parent = mCurrentDir.getParentFile();
            if (parent != null && parent.exists() && parent.canRead()) {
                navigateTo(parent);
                return;
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            runStorageSetup();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            runStorageSetup();
        }
    }

    // RecyclerView Adapter
    class FileAdapter extends RecyclerView.Adapter<FileViewHolder> {

        private List<File> mFileList = new ArrayList<>();

        @SuppressLint("NotifyDataSetChanged")
        public void setFiles(List<File> files) {
            this.mFileList = files;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_list, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            File file = mFileList.get(position);
            holder.bind(file);
        }

        @Override
        public int getItemCount() {
            return mFileList.size();
        }
    }

    class FileViewHolder extends RecyclerView.ViewHolder {

        final ImageView mIconView;
        final TextView mNameView;
        final TextView mDetailsView;
        final ImageButton mMenuButton;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            mIconView = itemView.findViewById(R.id.image_file_icon);
            mNameView = itemView.findViewById(R.id.text_file_name);
            mDetailsView = itemView.findViewById(R.id.text_file_details);
            mMenuButton = itemView.findViewById(R.id.button_file_menu);
        }

        public void bind(File file) {
            boolean isDir = file.isDirectory();
            mNameView.setText(file.getName());

            if (isDir) {
                mIconView.setImageResource(R.drawable.ic_folder);
                mIconView.setImageTintList(ContextCompat.getColorStateList(TermuxStorageActivity.this, R.color.m3_dark_secondary));
                File[] children = file.listFiles();
                int itemCount = children != null ? children.length : 0;
                mDetailsView.setText(itemCount + (itemCount == 1 ? " item" : " items") + " • " + mDateFormat.format(new Date(file.lastModified())) + " • " + formatPermissions(file));
            } else {
                mIconView.setImageResource(R.drawable.ic_file);
                mIconView.setImageTintList(ContextCompat.getColorStateList(TermuxStorageActivity.this, R.color.m3_dark_primary));
                mDetailsView.setText(formatFileSize(file.length()) + " • " + mDateFormat.format(new Date(file.lastModified())) + " • " + formatPermissions(file));
            }

            itemView.setOnClickListener(v -> {
                if (isDir) {
                    navigateTo(file);
                } else {
                    openFile(file);
                }
            });

            mMenuButton.setOnClickListener(v -> showItemPopupMenu(v, file));
        }

        private void showItemPopupMenu(View anchor, File file) {
            PopupMenu popup = new PopupMenu(TermuxStorageActivity.this, anchor);
            popup.getMenu().add(0, 1, 0, R.string.action_copy);
            popup.getMenu().add(0, 2, 1, R.string.action_move);
            popup.getMenu().add(0, 3, 2, R.string.action_rename);
            popup.getMenu().add(0, 4, 3, R.string.action_delete);

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) {
                    setClipboardFile(file, false);
                    return true;
                } else if (id == 2) {
                    setClipboardFile(file, true);
                    return true;
                } else if (id == 3) {
                    showRenameDialog(file);
                    return true;
                } else if (id == 4) {
                    showDeleteDialog(file);
                    return true;
                }
                return false;
            });
            popup.show();
        }
    }

    private String formatFileSize(long length) {
        if (length < 1024) return length + " B";
        int z = (63 - Long.numberOfLeadingZeros(length)) / 10;
        return String.format(Locale.getDefault(), "%.1f %sB", (double) length / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    private String formatPermissions(File file) {
        return (file.isDirectory() ? "d" : "-") +
            (file.canRead() ? "r" : "-") +
            (file.canWrite() ? "w" : "-") +
            (file.canExecute() ? "x" : "-");
    }
}
