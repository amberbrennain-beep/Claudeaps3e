package com.wewaffles.onelineage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public enum Flavor {
        VANILLA,
        GAPPS
    }

    // Which LineageOS line to install. The Android 16 line is what the
    // patched framework/services jars shipped in assets/ were built against;
    // the Android 17 line (LineageOS 24) runs without those patches until a
    // matching set is produced (see README "Android 17").
    public enum Channel {
        LINEAGE_23_A16,
        LINEAGE_24_A17,
        CUSTOM
    }

    // One resolved download: where it lives, how big it is, and which Android
    // generation it is, so the takeover scripts can gate the Android-16-only
    // patches on it.
    static final class Release {
        final String name;
        final String url;
        final long bytes;
        final int android;   // 16, 17, or 0 when unknown
        final String source;

        Release(String name, String url, long bytes, int android, String source) {
            this.name = name;
            this.url = url;
            this.bytes = bytes;
            this.android = android;
            this.source = source;
        }
    }

    private TextView tvRootStatus;
    private TextView tvOsStatus;
    private TextView tvImageStatus;
    private TextView tvHeaderSubtitle;
    private TextView tvDownloadDesc;
    private TextView tvDownloadProgress;
    private TextView tvChannelDesc;
    private ProgressBar pbDownload;
    private TextView tvConsoleLog;
    private TextView tvLogStatus;

    private LinearLayout cardFlavorVanilla;
    private LinearLayout cardFlavorGapps;
    private RadioButton rbVanilla;
    private RadioButton rbGapps;

    private LinearLayout cardChannelA16;
    private LinearLayout cardChannelA17;
    private LinearLayout cardChannelCustom;
    private RadioButton rbChannelA16;
    private RadioButton rbChannelA17;
    private RadioButton rbChannelCustom;
    private TextView tvChannelCustomDesc;

    private Button btnDownloadLineage;
    private Button btnDeleteLineage;
    private Button btnBootLineage;
    private Button btnReturnOneUI;
    private Button btnBackupLauncher;
    private Button btnRestoreLauncher;
    private Button btnImportGsi;
    private Button btnLocalGsi;
    private TextView tvLocalGsiDesc;

    private static final int REQ_PICK_GSI = 4201;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRootGranted = false;
    private String suBinary = "su";
    private Flavor selectedFlavor = Flavor.VANILLA;
    private Channel selectedChannel = Channel.LINEAGE_23_A16;
    private String customUrl = "";

    private static final String PREFS = "onelineage";
    private static final String PREF_CHANNEL = "channel";
    private static final String PREF_FLAVOR = "flavor";
    private static final String PREF_CUSTOM_URL = "custom_url";

    private static final String STAGE_DIR = "/data/local/tmp/lineage_stage";
    private static final String SYSTEM_RAW = STAGE_DIR + "/system.raw";
    // Written next to system.raw after every install: the takeover scripts read
    // it to learn which Android generation the image is and which filesystem
    // it uses (ext4 or erofs).
    private static final String IMAGE_INFO = STAGE_DIR + "/image_info";

    // Android SDK levels of the two LineageOS lines this app knows about.
    private static final int SDK_ANDROID_16 = 36;
    private static final int SDK_ANDROID_17 = 37;

    // --- Release discovery -------------------------------------------------
    // SourceForge publishes a per-folder RSS feed with the download link and
    // the byte size of every file. Discovery walks those feeds and picks the
    // newest build matching the chosen flavor, so a new upload (or the first
    // Android 17 upload) is picked up without rebuilding the app.
    private static final String SF_RSS = "https://sourceforge.net/projects/%s/rss?path=%s";

    // File-name patterns of the GSI builders this app knows. Group 1 is always
    // the LineageOS major, group 2 the minor, group 3 the YYYYMMDD stamp and
    // group 4 the flavor token.
    // MisterZtr: LineageOS-23.2-20260524-VANILLA-EXT4-GSI.7z
    private static final Pattern MISTERZTR_FILE =
            Pattern.compile("LineageOS-(2[34])\\.(\\d+)-(\\d{8})-(VANILLA|GAPPS)-(EXT4|EROFS)-GSI\\.7z");
    // MisterZtr "(native)" folder: LineageOS-23.2-20260124-UNOFFICIAL-gsi_arm64_bvNE.7z (bv = vanilla, bg = gapps)
    private static final Pattern MISTERZTR_NATIVE_FILE =
            Pattern.compile("LineageOS-(2[34])\\.(\\d+)-(\\d{8})-UNOFFICIAL-gsi_arm64_b([vg])N[A-Za-z]*\\.7z");
    // Andy Yan: lineage-22.2-20250621-UNOFFICIAL-gsi_arm64_vN.img.gz, ..._gN-signed.img.gz
    // ("-personal" builds are deliberately not matched).
    private static final Pattern ANDYYAN_FILE =
            Pattern.compile("lineage-(2[34])\\.(\\d+)-(\\d{8})-UNOFFICIAL-gsi_arm64_([vg])N(?:-signed)?\\.img\\.(?:gz|xz)");
    private static final Pattern[] KNOWN_FILES = {MISTERZTR_FILE, MISTERZTR_NATIVE_FILE, ANDYYAN_FILE};

    // Last-resort fallback for the Android 16 line if SourceForge's feed is
    // unreachable: the build the shipped patches were made against.
    private static final String URL_VANILLA_A16 = "https://downloads.sourceforge.net/project/misterztr-gsi/LineageOS/Android%2016/LineageOS-23.2-20260524-VANILLA-EXT4-GSI.7z?use_mirror=autoselect";
    private static final String URL_GAPPS_A16 = "https://downloads.sourceforge.net/project/misterztr-gsi/LineageOS/Android%2016/LineageOS-23.2-20260524-GAPPS-EXT4-GSI.7z?use_mirror=autoselect";
    private static final long ESTIMATED_VANILLA_BYTES = 922022276L;
    private static final long ESTIMATED_GAPPS_BYTES = 1167859370L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvOsStatus = findViewById(R.id.tv_os_status);
        tvImageStatus = findViewById(R.id.tv_image_status);
        tvHeaderSubtitle = findViewById(R.id.tv_header_subtitle);
        tvDownloadDesc = findViewById(R.id.tv_download_desc);
        tvDownloadProgress = findViewById(R.id.tv_download_progress);
        tvChannelDesc = findViewById(R.id.tv_channel_desc);
        pbDownload = findViewById(R.id.pb_download);
        tvConsoleLog = findViewById(R.id.tv_console_log);
        tvLogStatus = findViewById(R.id.tv_log_status);

        cardFlavorVanilla = findViewById(R.id.card_flavor_vanilla);
        cardFlavorGapps = findViewById(R.id.card_flavor_gapps);
        rbVanilla = findViewById(R.id.rb_vanilla);
        rbGapps = findViewById(R.id.rb_gapps);

        cardChannelA16 = findViewById(R.id.card_channel_a16);
        cardChannelA17 = findViewById(R.id.card_channel_a17);
        cardChannelCustom = findViewById(R.id.card_channel_custom);
        rbChannelA16 = findViewById(R.id.rb_channel_a16);
        rbChannelA17 = findViewById(R.id.rb_channel_a17);
        rbChannelCustom = findViewById(R.id.rb_channel_custom);
        tvChannelCustomDesc = findViewById(R.id.tv_channel_custom_desc);

        btnDownloadLineage = findViewById(R.id.btn_download_lineage);
        btnDeleteLineage = findViewById(R.id.btn_delete_lineage);
        btnBootLineage = findViewById(R.id.btn_boot_lineage);
        btnReturnOneUI = findViewById(R.id.btn_return_oneui);
        btnBackupLauncher = findViewById(R.id.btn_backup_launcher);
        btnRestoreLauncher = findViewById(R.id.btn_restore_launcher);
        btnImportGsi = findViewById(R.id.btn_import_gsi);
        btnLocalGsi = findViewById(R.id.btn_local_gsi);
        tvLocalGsiDesc = findViewById(R.id.tv_local_gsi_desc);

        loadPrefs();
        setupChannelSelection();
        setupFlavorSelection();
        extractAssets();
        checkRootAndSystem();

        btnDownloadLineage.setOnClickListener(v -> downloadLineage());
        btnDeleteLineage.setOnClickListener(v -> confirmDeleteLineage());
        btnBootLineage.setOnClickListener(v -> bootLineage());
        btnReturnOneUI.setOnClickListener(v -> returnToOneUI());
        btnBackupLauncher.setOnClickListener(v -> backupLauncher());
        btnRestoreLauncher.setOnClickListener(v -> restoreLauncher());
        btnImportGsi.setOnClickListener(v -> pickGsiFile());
        btnLocalGsi.setOnClickListener(v -> chooseGsiOnDevice());

        appendLog("Host: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + " [" + Build.DEVICE + "]");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkRootAndSystem();
    }

    // --- Preferences -------------------------------------------------------

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        customUrl = p.getString(PREF_CUSTOM_URL, "");
        try {
            selectedChannel = Channel.valueOf(p.getString(PREF_CHANNEL, defaultChannel().name()));
        } catch (Exception e) {
            selectedChannel = defaultChannel();
        }
        try {
            selectedFlavor = Flavor.valueOf(p.getString(PREF_FLAVOR, Flavor.GAPPS.name()));
        } catch (Exception e) {
            selectedFlavor = Flavor.GAPPS;
        }
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_CHANNEL, selectedChannel.name())
                .putString(PREF_FLAVOR, selectedFlavor.name())
                .putString(PREF_CUSTOM_URL, customUrl)
                .apply();
    }

    // Default to the LineageOS line that matches the host's Android version:
    // a One UI 9 (Android 17) host gets the LineageOS 24 channel, everything
    // else gets the proven Android 16 line.
    private Channel defaultChannel() {
        return Build.VERSION.SDK_INT >= SDK_ANDROID_17 ? Channel.LINEAGE_24_A17 : Channel.LINEAGE_23_A16;
    }

    // --- Channel / flavor selection -----------------------------------------

    private void setupChannelSelection() {
        cardChannelA16.setOnClickListener(v -> selectChannel(Channel.LINEAGE_23_A16));
        cardChannelA17.setOnClickListener(v -> selectChannel(Channel.LINEAGE_24_A17));
        cardChannelCustom.setOnClickListener(v -> promptCustomUrl());
        selectChannel(selectedChannel);
    }

    private void selectChannel(Channel channel) {
        selectedChannel = channel;
        rbChannelA16.setChecked(channel == Channel.LINEAGE_23_A16);
        rbChannelA17.setChecked(channel == Channel.LINEAGE_24_A17);
        rbChannelCustom.setChecked(channel == Channel.CUSTOM);
        cardChannelA16.setBackgroundResource(channel == Channel.LINEAGE_23_A16 ? R.drawable.radio_item_selected : R.drawable.radio_item_unselected);
        cardChannelA17.setBackgroundResource(channel == Channel.LINEAGE_24_A17 ? R.drawable.radio_item_selected : R.drawable.radio_item_unselected);
        cardChannelCustom.setBackgroundResource(channel == Channel.CUSTOM ? R.drawable.radio_item_selected : R.drawable.radio_item_unselected);

        if (tvChannelCustomDesc != null) {
            tvChannelCustomDesc.setText(customUrl.isEmpty()
                    ? "Tap to paste a direct link to a GSI (.7z, .img.xz, .zip or .img)"
                    : customUrl);
        }

        boolean flavorApplies = channel != Channel.CUSTOM;
        cardFlavorVanilla.setAlpha(flavorApplies ? 1.0f : 0.4f);
        cardFlavorGapps.setAlpha(flavorApplies ? 1.0f : 0.4f);

        if (tvChannelDesc != null) {
            switch (channel) {
                case LINEAGE_23_A16:
                    tvChannelDesc.setText("LineageOS 23.x on Android 16 • the line the bundled framework patches were made for"
                            + (Build.VERSION.SDK_INT >= SDK_ANDROID_17 ? "\n⚠ This phone runs Android " + Build.VERSION.RELEASE + "; expect APEX/HAL mismatches" : ""));
                    break;
                case LINEAGE_24_A17:
                    tvChannelDesc.setText("LineageOS 24 on Android 17 • newest build is looked up on SourceForge at download time; bundled Android 16 patches are skipped");
                    break;
                case CUSTOM:
                    tvChannelDesc.setText("Any arm64 system-as-root GSI from a direct URL");
                    break;
            }
        }
        savePrefs();
    }

    private void promptCustomUrl() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…/lineage-24.0-…-arm64_bvN.img.xz");
        input.setText(customUrl);
        new AlertDialog.Builder(this)
                .setTitle("Custom GSI URL")
                .setMessage("Direct download link to an arm64 system-as-root GSI. Supported: .7z, .img.xz, .zip or a raw .img (ext4 or erofs, not sparse).")
                .setView(input)
                .setPositiveButton("Use", (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (s.isEmpty()) {
                        appendLog("Custom URL left empty - keeping current channel.");
                        return;
                    }
                    customUrl = s;
                    selectChannel(Channel.CUSTOM);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setupFlavorSelection() {
        View.OnClickListener clickVanilla = v -> selectFlavor(Flavor.VANILLA);
        View.OnClickListener clickGapps = v -> selectFlavor(Flavor.GAPPS);

        cardFlavorVanilla.setOnClickListener(clickVanilla);
        cardFlavorGapps.setOnClickListener(clickGapps);

        selectFlavor(selectedFlavor);
    }

    private void selectFlavor(Flavor flavor) {
        selectedFlavor = flavor;
        rbVanilla.setChecked(flavor == Flavor.VANILLA);
        rbGapps.setChecked(flavor == Flavor.GAPPS);

        cardFlavorVanilla.setBackgroundResource(flavor == Flavor.VANILLA ? R.drawable.radio_item_selected : R.drawable.radio_item_unselected);
        cardFlavorGapps.setBackgroundResource(flavor == Flavor.GAPPS ? R.drawable.radio_item_selected : R.drawable.radio_item_unselected);

        switch (flavor) {
            case VANILLA:
                tvDownloadDesc.setText("Vanilla: The lightweight option");
                break;
            case GAPPS:
                tvDownloadDesc.setText("GApps: LineageOS + Google Play Store & Services.");
                break;
        }
        savePrefs();
    }

    private void appendLog(final String text) {
        mainHandler.post(() -> {
            if (tvConsoleLog != null) {
                tvConsoleLog.append(text + "\n");
                final int scrollAmount = tvConsoleLog.getLayout() != null ?
                        tvConsoleLog.getLayout().getLineTop(tvConsoleLog.getLineCount()) - tvConsoleLog.getHeight() : 0;
                if (scrollAmount > 0) {
                    tvConsoleLog.scrollTo(0, scrollAmount);
                }
            }
        });
    }

    // Runs a root shell command with stdout+stderr merged and continuously drained.
    // Process.waitFor() deadlocks forever if the child fills its stdout/stderr pipe
    // (~64KB) without anyone reading it - e.g. "rm -rf" spewing a "Device or resource
    // busy" line per file when rootfs still has stale bind/loop mounts underneath it.
    private int runRootCmdDrained(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(suBinary, "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (reader.readLine() != null) {
                // drain only; callers that need the output use their own reader.
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    // Same as runRootCmdDrained but returns the merged output (trimmed).
    private String runRootCmdOutput(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(suBinary, "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    private void setLogStatus(final String status) {
        mainHandler.post(() -> {
            if (tvLogStatus != null) {
                tvLogStatus.setText(status);
            }
        });
    }

    private void checkRootAndSystem() {
        executor.execute(() -> {
            // Attempt auto-activation of ksud if present
            try {
                File dfKsud = new File("/data/user_de/0/df.root/ksud");
                if (dfKsud.exists() && dfKsud.canExecute()) {
                    Runtime.getRuntime().exec(new String[]{dfKsud.getAbsolutePath(), "install"}).waitFor();
                }
            } catch (Exception ignored) {}

            String[] candidatePaths = {"/system/bin/su", "/data/adb/ksu/bin/su", "/system/xbin/su", "su"};
            boolean foundRoot = false;

            for (String path : candidatePaths) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{path, "-c", "id"});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    int exitCode = p.waitFor();

                    if (exitCode == 0 && line != null && line.contains("uid=0")) {
                        foundRoot = true;
                        suBinary = path;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            isRootGranted = foundRoot;
            if (isRootGranted) {
                extractAssets();
            }
            mainHandler.post(() -> {
                if (isRootGranted) {
                    tvRootStatus.setText("✓ Root: KernelSU Active (uid=0)");
                    tvRootStatus.setTextColor(Color.parseColor("#A5D6A7"));
                } else {
                    tvRootStatus.setText("✗ Root: Access Denied (KernelSU Required)");
                    tvRootStatus.setTextColor(Color.parseColor("#FFB4AB"));
                }
            });

            // Check active userspace
            try {
                Process pOs = Runtime.getRuntime().exec(new String[]{suBinary, "-c", "getprop ro.lineage.version"});
                BufferedReader osReader = new BufferedReader(new InputStreamReader(pOs.getInputStream()));
                String osVer = osReader.readLine();
                pOs.waitFor();

                mainHandler.post(() -> {
                    if (osVer != null && !osVer.trim().isEmpty()) {
                        tvOsStatus.setText("Current Userspace: LineageOS " + osVer.trim());
                        tvOsStatus.setTextColor(Color.parseColor("#78D9EC"));
                        if (tvHeaderSubtitle != null) {
                            tvHeaderSubtitle.setText("Dual Userspace Switcher • LineageOS");
                        }
                    } else {
                        tvOsStatus.setText("Current Userspace: Samsung One UI (Android " + Build.VERSION.RELEASE + ")");
                        tvOsStatus.setTextColor(Color.parseColor("#FFD54F"));
                        if (tvHeaderSubtitle != null) {
                            tvHeaderSubtitle.setText("Dual Userspace Switcher • One UI");
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvOsStatus.setText("Current Userspace: Samsung One UI"));
            }

            checkImageStatus();
        });
    }

    private void checkImageStatus() {
        executor.execute(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", "[ -f " + SYSTEM_RAW + " ] && ls -lh " + SYSTEM_RAW + " || echo 'MISSING'"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String out = reader.readLine();
                p.waitFor();

                final String info = runRootCmdOutput("cat " + IMAGE_INFO + " 2>/dev/null");
                final String imgName = infoField(info, "name");
                final String imgAndroid = infoField(info, "android");
                final String imgFs = infoField(info, "fstype");

                mainHandler.post(() -> {
                    if (out != null && !out.contains("MISSING") && !out.trim().isEmpty()) {
                        String label = imgName.isEmpty() ? "" : imgName;
                        if (!imgAndroid.isEmpty() && !"0".equals(imgAndroid)) label += " • Android " + imgAndroid;
                        if (!imgFs.isEmpty()) label += " • " + imgFs;
                        tvImageStatus.setText("Image: Installed & Ready" + (label.isEmpty() ? "" : " (" + label.trim() + ")"));
                        tvImageStatus.setTextColor(Color.parseColor("#A5D6A7"));
                        btnDownloadLineage.setText("Reinstall Image");
                        btnBootLineage.setEnabled(true);
                        btnBootLineage.setAlpha(1.0f);
                    } else {
                        tvImageStatus.setText("Image: Not Installed (Missing system.raw)");
                        tvImageStatus.setTextColor(Color.parseColor("#FFB4AB"));
                        btnDownloadLineage.setText("Download & Install");
                        btnBootLineage.setEnabled(false);
                        btnBootLineage.setAlpha(0.5f);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvImageStatus.setText("Image Check Error: " + e.getMessage()));
            }
        });
    }

    private static String infoField(String info, String key) {
        if (info == null) return "";
        for (String line : info.split("\n")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return "";
    }

    private void extractAssets() {
        executor.execute(() -> {
            String[] assets = {
                "switch_to_lineage.sh", "return_to_oneui.sh",
                "master_prepare.sh", "master_inner.sh",
                "zygote_exec", "clean_apex_info_list.xml",
                "services_patched.jar", "framework_patched.jar",
                "7zz", "PermissionController_patched.apk"
            };
            File filesDir = getFilesDir();
            for (String asset : assets) {
                File target = new File(filesDir, asset);
                if (target.exists() && target.length() > 0 && asset.endsWith(".jar")) {
                    continue; // Skip re-extracting large jars if already present
                }
                try (InputStream in = getAssets().open(asset);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    target.setExecutable(true, false);
                    target.setReadable(true, false);
                } catch (Exception e) {
                    appendLog("Error unpacking " + asset + ": " + e.getMessage());
                }
            }
            if (isRootGranted) {
                try {
                    Runtime.getRuntime().exec(new String[]{
                        suBinary, "-c",
                        "mkdir -p " + STAGE_DIR + " && " +
                        "for f in " + filesDir.getAbsolutePath() + "/*; do " +
                        "  base=$(basename \"$f\"); " +
                        "  cp -f \"$f\" \"" + STAGE_DIR + "/$base\"; " +
                        "done && " +
                        "cp -f " + filesDir.getAbsolutePath() + "/7zz /data/local/tmp/7zz 2>/dev/null; " +
                        "chmod 755 " + STAGE_DIR + "/*.sh " + STAGE_DIR + "/zygote_exec " + STAGE_DIR + "/7zz /data/local/tmp/7zz 2>/dev/null"
                    }).waitFor();
                } catch (Exception ignored) {}
            }
            appendLog("OneLineage engine assets unpacked & verified.");
        });
    }

    private void downloadLineage() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to set up LineageOS!");
            return;
        }
        if (selectedChannel == Channel.CUSTOM && customUrl.isEmpty()) {
            promptCustomUrl();
            return;
        }
        startNetworkDownload(selectedChannel, selectedFlavor);
    }

    // --- HTTP helpers ------------------------------------------------------

    private HttpURLConnection openWithRedirects(String initialUrl) throws Exception {
        String currentUrl = initialUrl;
        HttpURLConnection conn = null;
        int redirectCount = 0;
        final int MAX_REDIRECTS = 10;

        while (redirectCount < MAX_REDIRECTS) {
            URL url = new URL(currentUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Wget/1.21.3");
            conn.setRequestProperty("Accept", "*/*");
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308) {

                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.trim().isEmpty()) {
                    throw new Exception("Redirect code " + code + " without Location header");
                }
                currentUrl = loc.trim();
                conn.disconnect();
                redirectCount++;
                appendLog("Following mirror redirect [" + redirectCount + "]...");
                continue;
            }

            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP server error code: " + code);
            }
            return conn;
        }
        throw new Exception("Too many redirects: " + redirectCount);
    }

    // Small text fetch (RSS feeds), capped at 4 MB.
    private String httpGetText(String url) throws Exception {
        HttpURLConnection conn = openWithRedirects(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 4L * 1024 * 1024) break;
            }
            return bos.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFileWithRedirects(String initialUrl, File targetFile, long expectedBytes) throws Exception {
        HttpURLConnection conn = openWithRedirects(initialUrl);

        long contentLength = -1;
        try {
            contentLength = Long.parseLong(conn.getHeaderField("Content-Length"));
        } catch (Exception ignored) {}
        final long totalBytes = (contentLength > 0) ? contentLength : expectedBytes;

        mainHandler.post(() -> {
            pbDownload.setMax(100);
            pbDownload.setProgress(0);
        });

        long startMs = System.currentTimeMillis();
        long lastUpdateMs = 0;
        long currentBytes = 0;

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(targetFile)) {

            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                currentBytes += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdateMs > 250) {
                    lastUpdateMs = now;
                    final int pct = (totalBytes > 0) ? (int) Math.min(100, (currentBytes * 100) / totalBytes) : 0;
                    final long elapsedSec = Math.max(1, (now - startMs) / 1000);
                    final double speedMb = (currentBytes / 1048576.0) / elapsedSec;
                    final String progStr = String.format("Downloading: %d MB / %d MB (%d%%) • %.1f MB/s",
                            currentBytes / 1048576, totalBytes / 1048576, pct, speedMb);
                    mainHandler.post(() -> {
                        pbDownload.setProgress(pct);
                        tvDownloadProgress.setText(progStr);
                    });
                }
            }
            out.flush();
        } finally {
            conn.disconnect();
        }

        if (targetFile.length() < 1000000) {
            throw new Exception("Downloaded file is too small (" + targetFile.length() + " bytes).");
        }
    }

    // --- Release discovery -------------------------------------------------

    // Parses a SourceForge folder RSS feed into (link, filesize) pairs and keeps
    // the ones whose file name matches one of the known builder patterns.
    static List<Release> parseSourceForgeRss(String xml, String source) {
        List<Release> out = new ArrayList<>();
        if (xml == null) return out;
        Pattern item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
        Pattern link = Pattern.compile("<link>\\s*([^<\\s]+)\\s*</link>");
        Pattern size = Pattern.compile("filesize=\"(\\d+)\"");
        Matcher mi = item.matcher(xml);
        while (mi.find()) {
            String block = mi.group(1);
            Matcher ml = link.matcher(block);
            if (!ml.find()) continue;
            String url = ml.group(1);
            Matcher mf = matchKnown(url);
            if (mf == null) continue;
            long bytes = 0;
            Matcher ms = size.matcher(block);
            if (ms.find()) {
                try { bytes = Long.parseLong(ms.group(1)); } catch (Exception ignored) {}
            }
            int major = Integer.parseInt(mf.group(1));
            out.add(new Release(mf.group(), url, bytes, lineageMajorToAndroid(major), source));
        }
        return out;
    }

    // Returns a successful matcher of the first known pattern, or null.
    private static Matcher matchKnown(String name) {
        if (name == null) return null;
        for (Pattern p : KNOWN_FILES) {
            Matcher m = p.matcher(name);
            if (m.find()) return m;
        }
        return null;
    }

    private static int lineageMajorToAndroid(int lineageMajor) {
        switch (lineageMajor) {
            case 23: return 16;
            case 24: return 17;
            default: return 0;
        }
    }

    private static String encodePath(String folder) {
        return folder.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
    }

    // Newest build (by the YYYYMMDD stamp in the file name) that matches the
    // flavor. Among MisterZtr builds EXT4 is preferred over EROFS when both
    // exist for the same date, since ext4 is the format the takeover has been
    // exercised with (erofs is supported, see master_prepare.sh).
    static Release pickNewest(List<Release> candidates, Flavor flavor) {
        Release best = null;
        String bestKey = "";
        for (Release r : candidates) {
            Matcher m = matchKnown(r.name);
            if (m == null) continue;
            String date = m.group(3);
            String tok = m.group(4);
            boolean isGapps = "GAPPS".equals(tok) || "g".equals(tok);
            if ((flavor == Flavor.GAPPS) != isGapps) continue;
            String fsRank = (m.pattern() == MISTERZTR_FILE && !"EXT4".equals(m.group(5))) ? "0" : "1";
            String key = date + fsRank;
            if (best == null || key.compareTo(bestKey) > 0) {
                best = r;
                bestKey = key;
            }
        }
        return best;
    }

    private Release discoverMisterZtr(String folder, Flavor flavor) {
        try {
            String url = String.format(SF_RSS, "misterztr-gsi", encodePath(folder));
            appendLog("Looking up " + folder + " on SourceForge (MisterZtr)...");
            String xml = httpGetText(url);
            Release r = pickNewest(parseSourceForgeRss(xml, "MisterZtr " + folder), flavor);
            if (r == null) appendLog("  no " + flavor.name() + " build in " + folder);
            return r;
        } catch (Exception e) {
            appendLog("  lookup failed: " + e.getMessage());
            return null;
        }
    }

    private Release discoverAndyYan(String folder, Flavor flavor) {
        try {
            String url = String.format(SF_RSS, "andyyan-gsi", encodePath(folder));
            appendLog("Looking up " + folder + " on SourceForge (Andy Yan)...");
            String xml = httpGetText(url);
            Release r = pickNewest(parseSourceForgeRss(xml, "AndyYan " + folder), flavor);
            if (r == null) appendLog("  no " + flavor.name() + " build in " + folder);
            return r;
        } catch (Exception e) {
            appendLog("  lookup failed: " + e.getMessage());
            return null;
        }
    }

    private Release resolveRelease(Channel channel, Flavor flavor) throws Exception {
        switch (channel) {
            case LINEAGE_23_A16: {
                Release r = discoverMisterZtr("/LineageOS/Android 16", flavor);
                if (r != null) return r;
                appendLog("Falling back to the pinned LineageOS 23.2 (2026-05-24) build.");
                return flavor == Flavor.GAPPS
                        ? new Release("LineageOS-23.2-20260524-GAPPS-EXT4-GSI.7z", URL_GAPPS_A16, ESTIMATED_GAPPS_BYTES, 16, "pinned")
                        : new Release("LineageOS-23.2-20260524-VANILLA-EXT4-GSI.7z", URL_VANILLA_A16, ESTIMATED_VANILLA_BYTES, 16, "pinned");
            }
            case LINEAGE_24_A17: {
                // Folder names follow the maintainers' existing conventions
                // ("Android 16", "Android 16(native)", "lineage-21-td" ...).
                String[] misterztrFolders = {"/LineageOS/Android 17", "/LineageOS/Android 17(native)"};
                for (String f : misterztrFolders) {
                    Release r = discoverMisterZtr(f, flavor);
                    if (r != null && r.android == 17) return r;
                }
                String[] andyFolders = {"/lineage-24", "/lineage-24-td", "/lineage-24-light"};
                for (String f : andyFolders) {
                    Release r = discoverAndyYan(f, flavor);
                    if (r != null && r.android == 17) return r;
                }
                throw new Exception("No LineageOS 24 (Android 17) GSI is published yet on the MisterZtr or Andy Yan SourceForge projects. "
                        + "Check back later, or choose 'Custom URL' and paste a link to an Android 17 arm64 GSI.");
            }
            case CUSTOM:
            default: {
                String name = customUrl;
                int q = name.indexOf('?');
                if (q >= 0) name = name.substring(0, q);
                name = name.substring(name.lastIndexOf('/') + 1);
                if (name.endsWith("/download")) name = name.substring(0, name.length() - "/download".length());
                return localRelease(name.isEmpty() ? "custom.img" : name, "custom", customUrl);
            }
        }
    }

    static String fileExtension(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".xz")) return "xz";
        if (n.endsWith(".gz")) return "gz";
        if (n.endsWith(".7z")) return "7z";
        if (n.endsWith(".zip")) return "zip";
        if (n.endsWith(".img")) return "img";
        return "7z";
    }

    // Shell that turns whatever was downloaded or picked into STAGE_DIR/system.raw,
    // then records what it is. Format sniffing is done by magic bytes rather than
    // trusting the file name:
    //   sparse image  (3a ff 26 ed at 0)       -> converted to raw (simg2img or SparseTool)
    //   super.img     (67 44 6c 61 at 0x1000)  -> rejected, it is a whole dynamic partition
    //   ext4          (53 ef at 0x438)         -> fstype=ext4
    //   erofs         (e2 e1 f5 e0 at 0x400)   -> fstype=erofs
    // deleteSource is false for files the user keeps on their storage.
    private String extractCmd(String archivePath, String ext, Release rel, boolean deleteSource) {
        return buildExtractCmd(STAGE_DIR, SYSTEM_RAW, IMAGE_INFO, archivePath, ext,
                rel.name, rel.android, rel.source, rel.url, deleteSource, sparseToolCmd());
    }

    // Converter invocation, used as `$cmd <in> <out>`. Prefers a device simg2img
    // when one exists, otherwise runs SparseTool from this APK through app_process
    // as root (the app process itself cannot open the staged files).
    private String sparseToolCmd() {
        String apk = getApplicationInfo().sourceDir;
        return "unsparse() { if command -v simg2img >/dev/null 2>&1; then simg2img \"$1\" \"$2\"; "
                + "else CLASSPATH='" + shellQuote(apk) + "' /system/bin/app_process /system/bin "
                + SparseTool.class.getName() + " \"$1\" \"$2\"; fi; }";
    }

    static String buildExtractCmd(String stageDir, String systemRaw, String imageInfo, String archivePath,
                                  String ext, String relName, int relAndroid, String relSource, String relUrl,
                                  boolean deleteSource, String sparseFn) {
        String extractDir = stageDir + "/extract";
        String sz = stageDir + "/7zz";
        StringBuilder sb = new StringBuilder();
        sb.append("set -e; ");
        sb.append("rm -rf ").append(extractDir).append(" ").append(stageDir).append("/*.img ").append(systemRaw).append("; ");
        sb.append("mkdir -p ").append(extractDir).append("; ");
        sb.append(sparseFn).append("; ");
        String src = "'" + shellQuote(archivePath) + "'";
        if ("img".equals(ext)) {
            // A picked .img is moved when it is our own temp copy, copied otherwise.
            sb.append(deleteSource ? "mv -f " : "cp -f ").append(src).append(" ").append(extractDir).append("/system.img; ");
        } else {
            // 7z, zip, xz and gz are all handled by the bundled 7zz
            sb.append(sz).append(" x -y -o").append(extractDir).append(" ").append(src).append(" >/dev/null; ");
        }
        // Largest *.img anywhere in the extraction is the system image.
        sb.append("IMG=$(find ").append(extractDir).append(" -type f \\( -name '*.img' -o -name '*.raw' \\) 2>/dev/null | while read f; do echo \"$(stat -c %s \"$f\") $f\"; done | sort -n | tail -n 1 | cut -d' ' -f2-); ");
        sb.append("[ -n \"$IMG\" ] || { echo 'ERROR: no .img inside the download'; exit 2; }; ");
        sb.append("magic=$(od -An -tx1 -N4 \"$IMG\" | tr -d ' \\n'); ");
        sb.append("if [ \"$magic\" = \"3aff26ed\" ]; then ");
        sb.append("echo 'Android sparse image detected, converting to raw...'; ");
        sb.append("unsparse \"$IMG\" \"$IMG.raw\" || { echo 'ERROR: sparse to raw conversion failed'; exit 3; }; ");
        sb.append("rm -f \"$IMG\"; IMG=\"$IMG.raw\"; fi; ");
        sb.append("if [ \"$(od -An -tx1 -j 4096 -N4 \"$IMG\" | tr -d ' \\n')\" = \"67446c61\" ]; then ");
        sb.append("echo 'ERROR: this is a super.img (a whole dynamic partition), not a GSI. Extract system.img from it with lpunpack first.'; exit 5; fi; ");
        sb.append("fs=unknown; ");
        sb.append("[ \"$(od -An -tx1 -j 1080 -N2 \"$IMG\" | tr -d ' \\n')\" = \"53ef\" ] && fs=ext4; ");
        sb.append("[ \"$(od -An -tx1 -j 1024 -N4 \"$IMG\" | tr -d ' \\n')\" = \"e2e1f5e0\" ] && fs=erofs; ");
        sb.append("if [ \"$fs\" = unknown ]; then echo 'ERROR: image is neither ext4 nor erofs'; exit 4; fi; ");
        sb.append("mv -f \"$IMG\" ").append(systemRaw).append("; ");
        sb.append("chmod 644 ").append(systemRaw).append("; ");
        // A picked .img moved out of the app cache keeps its app_data_file label.
        sb.append("restorecon ").append(systemRaw).append(" 2>/dev/null || true; ");
        sb.append("rm -rf ").append(extractDir).append("; ");
        if (deleteSource) sb.append("rm -f ").append(src).append("; ");
        sb.append("printf 'name=%s\\nandroid=%s\\nfstype=%s\\nsource=%s\\nurl=%s\\n' '")
          .append(shellQuote(relName)).append("' '").append(relAndroid).append("' \"$fs\" '")
          .append(shellQuote(relSource)).append("' '").append(shellQuote(relUrl)).append("' > ").append(imageInfo).append("; ");
        sb.append("chmod 644 ").append(imageInfo).append("; ");
        sb.append("echo \"fstype=$fs\"");
        return sb.toString();
    }

    static String shellQuote(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    private void startNetworkDownload(final Channel channel, final Flavor flavor) {
        executor.execute(() -> {
            setLogStatus("Preparing…");
            mainHandler.post(() -> {
                pbDownload.setVisibility(View.VISIBLE);
                pbDownload.setIndeterminate(true);
                tvDownloadProgress.setVisibility(View.VISIBLE);
                tvDownloadProgress.setText("Looking up the newest release…");
                btnDownloadLineage.setEnabled(false);
                setLocalButtonsEnabled(false);
            });

            appendLog("\n[SETUP] Initializing stage directory: " + STAGE_DIR);
            try {
                runRootCmdDrained("mkdir -p " + STAGE_DIR + " && chmod 777 " + STAGE_DIR);

                final Release rel = resolveRelease(channel, flavor);
                appendLog("[RELEASE] " + rel.name + " (" + rel.source + ")" + (rel.bytes > 0 ? " • " + (rel.bytes / 1048576) + " MB" : ""));
                logReleaseNotes(rel);

                appendLog("[SETUP] Wiping existing userdata for a fresh OOBE...");
                runRootCmdDrained(wipeUserDataCmd());

                final String ext = fileExtension(rel.name);
                // Download into the app's own cache dir, NOT STAGE_DIR directly: this
                // process writes as the unprivileged app, and /data/local/tmp paths
                // created by root are labeled shell_data_file, which SELinux blocks
                // untrusted_app from writing to no matter what chmod says (this is the
                // actual cause of "lineage_dl.7z: open failed: EACCES"). su (used below
                // for extraction) can read the app's cache dir fine, same as
                // extractAssets() already does for the other staged files.
                final File targetArchive = new File(getCacheDir(), "lineage_dl." + ext);

                appendLog("[NETWORK DOWNLOAD] Fetching " + flavor.name() + " edition directly via native HTTP engine...");
                appendLog("URL: " + rel.url);
                setLogStatus("Downloading…");
                mainHandler.post(() -> pbDownload.setIndeterminate(false));

                downloadFileWithRedirects(rel.url, targetArchive, rel.bytes);

                appendLog("✓ Download completed successfully! (" + (targetArchive.length() / 1048576) + " MB)");
                installStagedImage(targetArchive.getAbsolutePath(), ext, rel, true);

            } catch (Exception e) {
                appendLog("Download / setup failed: " + e.getMessage());
                setLogStatus("Error");
                mainHandler.post(() -> {
                    pbDownload.setVisibility(View.GONE);
                    tvDownloadProgress.setText("Setup failed: " + e.getMessage());
                    btnDownloadLineage.setEnabled(true);
                    setLocalButtonsEnabled(true);
                });
            }
        });
    }

    private void logReleaseNotes(Release rel) {
        if (rel.android != 0 && rel.android != hostAndroidMajor()) {
            appendLog("⚠ Image is Android " + rel.android + " but this phone runs Android " + Build.VERSION.RELEASE
                    + ". The guest shares the host's HALs, APEXes and vendor; mismatched generations are untested.");
        }
        if (rel.android == 0) {
            appendLog("ℹ Android version unknown from the file name; it is read from the image's build.prop at switch time, "
                    + "and the bundled Android 16 patches are only used if it is SDK 36.");
        } else if (rel.android != 16) {
            appendLog("ℹ The bundled framework/services patches target Android 16 and will NOT be bound for this image.");
        }
    }

    // Common tail of every install: unpack/convert the archive into system.raw as
    // root, report, and refresh the status card. Runs on the executor thread.
    private void installStagedImage(String archivePath, String ext, Release rel, boolean deleteSource) throws Exception {
        setLogStatus("Extracting…");
        mainHandler.post(() -> {
            pbDownload.setVisibility(View.VISIBLE);
            pbDownload.setIndeterminate(true);
            tvDownloadProgress.setVisibility(View.VISIBLE);
            tvDownloadProgress.setText("img".equals(ext) ? "Checking and staging image…" : "Unpacking image (" + ext + ") with ARM64 7-Zip…");
        });

        appendLog("Staging " + archivePath + ("img".equals(ext) ? "" : " via native 7zz") + "...");
        String out = runRootCmdOutput(extractCmd(archivePath, ext, rel, deleteSource));
        for (String line : out.split("\n")) {
            if (!line.trim().isEmpty()) appendLog("  " + line);
        }
        // The app process cannot reliably stat /data/local/tmp (SELinux), so ask root.
        boolean present = "yes".equals(runRootCmdOutput("[ -s " + SYSTEM_RAW + " ] && echo yes"));
        if (!present || out.contains("ERROR:")) {
            String err = "system.raw was not produced";
            for (String line : out.split("\n")) {
                if (line.startsWith("ERROR:")) err = line.substring(6).trim();
            }
            throw new Exception(err);
        }

        appendLog("✓ " + rel.name + " -> system.raw ready!");
        mainHandler.post(() -> {
            pbDownload.setVisibility(View.GONE);
            tvDownloadProgress.setText(rel.name + " installed successfully!");
            btnDownloadLineage.setEnabled(true);
            setLocalButtonsEnabled(true);
        });
        setLogStatus("Ready");
        checkImageStatus();
    }

    // --- Other GSI files: install from the phone's own storage -------------

    private void setLocalButtonsEnabled(boolean on) {
        if (btnImportGsi != null) btnImportGsi.setEnabled(on);
        if (btnLocalGsi != null) btnLocalGsi.setEnabled(on);
    }

    // Opens the system file picker. Works for internal storage, SD cards, USB
    // drives and cloud providers alike; no storage permission is needed.
    private void pickGsiFile() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to install a GSI!");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_GSI);
        } catch (Exception e) {
            appendLog("No file picker available: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_GSI || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String name = "picked.img";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {}
        final String pickedName = fileNameOnly(name);
        final long pickedSize = size;
        confirmLocalInstall(pickedName, pickedSize, () -> importFromUri(uri, pickedName, pickedSize));
    }

    private static String fileNameOnly(String name) {
        String n = name == null ? "" : name;
        n = n.substring(n.lastIndexOf('/') + 1);
        return n.isEmpty() ? "picked.img" : n;
    }

    private void confirmLocalInstall(String name, long size, Runnable go) {
        String sz = size > 0 ? String.format(" (%.2f GB)", size / 1073741824.0) : "";
        new AlertDialog.Builder(this)
            .setTitle("Install this GSI?")
            .setMessage(name + sz + "\n\nThis replaces the current LineageOS image and wipes its user data (One UI is not touched). "
                    + "The file must be an arm64 system-as-root GSI: a .img (raw or sparse) or an archive containing one (.7z, .zip, .xz, .gz).")
            .setPositiveButton("Install", (d, w) -> go.run())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static Release localRelease(String name, String source, String location) {
        int android = 0;
        Matcher m = matchKnown(name);
        if (m != null) android = lineageMajorToAndroid(Integer.parseInt(m.group(1)));
        else if (name.contains("-24.") || name.contains("lineage-24")) android = 17;
        else if (name.contains("-23.") || name.contains("lineage-23")) android = 16;
        return new Release(name, location, 0, android, source);
    }

    // Copies the picked document into the app cache (the content URI is only
    // readable by this app), then stages it as root and deletes the copy.
    private void importFromUri(final Uri uri, final String name, final long size) {
        setLocalButtonsEnabled(false);
        executor.execute(() -> {
            final String ext = fileExtension(name);
            final File tmp = new File(getCacheDir(), "local_gsi." + ext);
            try {
                long free = getCacheDir().getUsableSpace();
                if (size > 0 && free < size + 512L * 1048576) {
                    throw new Exception(String.format("not enough free space to copy it (%.1f GB free, %.1f GB needed). "
                            + "Use 'Find on device' instead, which reads the file in place.",
                            free / 1073741824.0, (size + 512L * 1048576) / 1073741824.0));
                }
                Release rel = localRelease(name, "local file", uri.toString());
                appendLog("\n[LOCAL GSI] " + name);
                logReleaseNotes(rel);
                appendLog("[SETUP] Wiping existing userdata for a fresh OOBE...");
                runRootCmdDrained("mkdir -p " + STAGE_DIR + " && chmod 777 " + STAGE_DIR);
                runRootCmdDrained(wipeUserDataCmd());

                setLogStatus("Copying…");
                mainHandler.post(() -> {
                    pbDownload.setVisibility(View.VISIBLE);
                    pbDownload.setIndeterminate(size <= 0);
                    pbDownload.setMax(100);
                    pbDownload.setProgress(0);
                    tvDownloadProgress.setVisibility(View.VISIBLE);
                    tvDownloadProgress.setText("Copying " + name + "…");
                });
                long copied = 0, last = 0;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new Exception("could not open the selected file");
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        copied += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 250) {
                            last = now;
                            final long c = copied;
                            mainHandler.post(() -> {
                                if (size > 0) pbDownload.setProgress((int) Math.min(100, c * 100 / size));
                                tvDownloadProgress.setText(String.format("Copying: %d MB%s", c / 1048576,
                                        size > 0 ? " / " + (size / 1048576) + " MB" : ""));
                            });
                        }
                    }
                }
                appendLog("✓ Copied " + (copied / 1048576) + " MB");
                tmp.setReadable(true, false);
                installStagedImage(tmp.getAbsolutePath(), ext, rel, true);
            } catch (Exception e) {
                tmp.delete();
                localInstallFailed(e);
            }
        });
    }

    // Lists GSI-looking files (>= 300 MB) on internal storage and in the usual
    // download/ROM folders, plus a manual path option. Root reads them in place,
    // so nothing is copied and very large files work too.
    private void chooseGsiOnDevice() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to install a GSI!");
            return;
        }
        setLocalButtonsEnabled(false);
        appendLog("\n[LOCAL GSI] Scanning storage for GSI files...");
        executor.execute(() -> {
            String out = runRootCmdOutput(
                "for d in /data/media/0 /storage/*-*; do [ -d \"$d\" ] && find \"$d\" -maxdepth 3 -type f -size +300M "
                + "\\( -iname '*.img' -o -iname '*.img.xz' -o -iname '*.img.gz' -o -iname '*.7z' -o -iname '*.zip' -o -iname '*.xz' -o -iname '*.gz' \\) "
                + "-not -path '*/Android/*' 2>/dev/null; done | while read f; do echo \"$(stat -c %s \"$f\")|$f\"; done");
            final List<String> paths = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (String line : out.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0) continue;
                long sz;
                try { sz = Long.parseLong(line.substring(0, bar).trim()); } catch (Exception e) { continue; }
                String path = line.substring(bar + 1);
                paths.add(path);
                labels.add(path.substring(path.lastIndexOf('/') + 1) + String.format("  (%.2f GB)", sz / 1073741824.0)
                        + "\n" + displayPath(path.substring(0, path.lastIndexOf('/'))));
            }
            appendLog("  found " + paths.size() + " candidate file(s)");
            mainHandler.post(() -> {
                setLocalButtonsEnabled(true);
                labels.add("Enter a path manually…");
                new AlertDialog.Builder(this)
                    .setTitle("GSI files on this phone")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        if (which == paths.size()) {
                            promptLocalPath();
                        } else {
                            String path = paths.get(which);
                            confirmLocalInstall(fileNameOnly(path), -1, () -> installFromPath(path));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        });
    }

    private void promptLocalPath() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText("/sdcard/Download/");
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
            .setTitle("Path to GSI file")
            .setMessage("Full path to a .img or an archive containing one. Read as root, left in place.")
            .setView(input)
            .setPositiveButton("Install", (d, w) -> {
                String path = rootPath(input.getText().toString().trim());
                if (path.isEmpty() || path.endsWith("/")) {
                    appendLog("No file given.");
                    return;
                }
                installFromPath(path);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // /sdcard is a per-app FUSE view; root shells may not have it mounted, so
    // map it to the backing directory.
    static String rootPath(String p) {
        if (p.startsWith("/sdcard/")) return "/data/media/0/" + p.substring(8);
        if (p.startsWith("/storage/emulated/0/")) return "/data/media/0/" + p.substring(20);
        if (p.startsWith("/storage/self/primary/")) return "/data/media/0/" + p.substring(22);
        return p;
    }

    private static String displayPath(String p) {
        return p.startsWith("/data/media/0") ? "/sdcard" + p.substring(13) : p;
    }

    private void installFromPath(final String path) {
        setLocalButtonsEnabled(false);
        executor.execute(() -> {
            try {
                String q = "'" + shellQuote(path) + "'";
                String size = runRootCmdOutput("[ -f " + q + " ] && stat -c %s " + q);
                if (size.isEmpty()) throw new Exception("file not found: " + path);
                String name = fileNameOnly(path);
                Release rel = localRelease(name, "local file", path);
                appendLog("\n[LOCAL GSI] " + displayPath(path) + " (" + (Long.parseLong(size.trim()) / 1048576) + " MB)");
                logReleaseNotes(rel);
                appendLog("[SETUP] Wiping existing userdata for a fresh OOBE...");
                runRootCmdDrained("mkdir -p " + STAGE_DIR + " && chmod 777 " + STAGE_DIR);
                runRootCmdDrained(wipeUserDataCmd());
                installStagedImage(path, fileExtension(name), rel, false);
            } catch (Exception e) {
                localInstallFailed(e);
            }
        });
    }

    private void localInstallFailed(Exception e) {
        appendLog("Local GSI install failed: " + e.getMessage());
        setLogStatus("Error");
        mainHandler.post(() -> {
            pbDownload.setVisibility(View.GONE);
            tvDownloadProgress.setVisibility(View.VISIBLE);
            tvDownloadProgress.setText("Setup failed: " + e.getMessage());
            setLocalButtonsEnabled(true);
        });
        checkImageStatus();
    }

    private static int hostAndroidMajor() {
        try {
            String r = Build.VERSION.RELEASE;
            int dot = r.indexOf('.');
            return Integer.parseInt(dot > 0 ? r.substring(0, dot) : r);
        } catch (Exception e) {
            return 0;
        }
    }

    // Unmounts the container rootfs and detaches any loop device backed by a file
    // under STAGE_DIR. Bounded retry loop (not an unbounded "while mount|grep" loop)
    // so it can never hang forever, and retries losetup -d once since EBUSY is often
    // transient right after pkill -9 (kernel hasn't finished releasing the dying
    // container's private mount namespace yet).
    private String containerUnmountCmd() {
        String rootfs = STAGE_DIR + "/rootfs";
        return "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
               "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
               "umount -l " + rootfs + "/apex/* 2>/dev/null || true; " +
               "umount -l " + rootfs + "/* 2>/dev/null || true; " +
               "umount -l " + rootfs + " 2>/dev/null || true; " +
               "n=0; while [ $n -lt 50 ] && mount | grep -q '" + rootfs + "'; do " +
               "  umount -l " + rootfs + " 2>/dev/null || true; " +
               "  n=$((n+1)); sleep 0.1; " +
               "done; " +
               "if [ -x \"$BB\" ]; then " +
               "  for l in $($BB losetup -a | grep -F '" + STAGE_DIR + "' | cut -d: -f1); do " +
               "    $BB losetup -d \"$l\" 2>/dev/null || { sleep 0.3; $BB losetup -d \"$l\" 2>/dev/null || true; }; " +
               "  done; " +
               "fi";
    }

    private String wipeUserDataCmd() {
        return containerUnmountCmd() + "; " +
               "rm -rf " + STAGE_DIR + "/data " + STAGE_DIR + "/rootfs " + STAGE_DIR + "/*_payload.img; mkdir -p " + STAGE_DIR + "/rootfs";
    }

    private void confirmDeleteLineage() {
        new AlertDialog.Builder(this)
            .setTitle("Delete LineageOS Files?")
            .setMessage("This will cleanly unmount the container, detach loop devices, and remove system.raw to free up ~3.5GB. You can reinstall anytime.")
            .setPositiveButton("Delete", (dialog, which) -> deleteLineage())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteLineage() {
        executor.execute(() -> {
            setLogStatus("Cleaning…");
            appendLog("\n[CLEANUP] Stopping running container processes & unmounting...");
            try {
                // Only kill zygote64/surfaceflinger/vold/system_server by name if the
                // LineageOS container's rootfs is actually mounted. If it isn't (e.g.
                // we're sitting on stock One UI with no switch in progress), those
                // pidof lookups resolve to the HOST's own real daemons - killing them
                // crashes/reboots the device instead of cleaning up a container.
                String cleanupCmd =
                    "if mount | grep -q '" + STAGE_DIR + "/rootfs '; then " +
                    "  pkill -9 -f 'ctl.stop zygote' 2>/dev/null || true; " +
                    "  pkill -9 -f 'ui_activator' 2>/dev/null || true; " +
                    "  pkill -9 -f 'zygote_exec' 2>/dev/null || true; " +
                    "  pkill -9 -f 'system_server' 2>/dev/null || true; " +
                    "  pkill -9 -f org.lineageos 2>/dev/null || true; " +
                    "  pkill -9 -x zygote64 2>/dev/null || true; " +
                    "  kill -9 $(pidof surfaceflinger) 2>/dev/null || true; " +
                    "  kill -9 $(pidof bootanimation) 2>/dev/null || true; " +
                    // Matched by the container's distinctive vold launch flags, never
                    // "pidof vold" bare - that would kill the HOST's real vold too if
                    // both happened to be alive, crashing the device (confirmed live).
                    "  pkill -9 -f 'blkid_context=u:r:blkid:s0' 2>/dev/null || true; " +
                    "  sleep 0.3; " +
                    "fi; " +
                    containerUnmountCmd() + "; " +
                    // If a loop device is still stuck busy (namespace teardown lagging),
                    // truncating frees the backing blocks immediately even though rm -f
                    // alone would leave them held open by that loop device.
                    "truncate -s 0 " + SYSTEM_RAW + " 2>/dev/null || true; " +
                    "rm -f " + SYSTEM_RAW + " " + IMAGE_INFO + " " + STAGE_DIR + "/*.img " + STAGE_DIR + "/*.7z " + STAGE_DIR + "/*.xz " + STAGE_DIR + "/*.img.tmp; " +
                    "rm -rf " + STAGE_DIR + "/data " + STAGE_DIR + "/rootfs " + STAGE_DIR + "/extract; " +
                    "mkdir -p " + STAGE_DIR + "/rootfs; " +
                    "if [ -f " + SYSTEM_RAW + " ]; then echo 'WARNING: system.raw still present!'; else echo 'Container unmounted, data wiped, and storage freed.'; fi";

                Process p = new ProcessBuilder(suBinary, "-c", cleanupCmd).redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                boolean stillPresent = false;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                    if (line.contains("WARNING: system.raw still present")) {
                        stillPresent = true;
                    }
                }
                p.waitFor();
                if (stillPresent) {
                    appendLog("✗ system.raw could not be fully removed (a loop device is still holding it busy). Try again, or reboot and retry.");
                    setLogStatus("Error");
                } else {
                    appendLog("✓ LineageOS files deleted cleanly. All loop mounts detached!");
                    setLogStatus("Idle");
                }
                checkImageStatus();
            } catch (Exception e) {
                appendLog("Delete failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void bootLineage() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to boot LineageOS!");
            return;
        }
        appendLog("\n[START] Preparing LineageOS takeover...");
        setLogStatus("Switching…");
        executor.execute(() -> {
            try {
                File filesDir = getFilesDir();
                String syncCmd = "for f in " + filesDir.getAbsolutePath() + "/*; do " +
                                 "  base=$(basename \"$f\"); " +
                                 "  cp -f \"$f\" \"" + STAGE_DIR + "/$base\"; " +
                                 "done && " +
                                 "chmod 755 " + STAGE_DIR + "/*.sh " + STAGE_DIR + "/zygote_exec " + STAGE_DIR + "/7zz";
                Runtime.getRuntime().exec(new String[]{suBinary, "-c", syncCmd}).waitFor();

                String stageScript = STAGE_DIR + "/switch_to_lineage.sh";
                String cmd = "stageScript=" + STAGE_DIR + "/switch_to_lineage.sh; " +
                             "chmod 755 $stageScript; " +
                             "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
                             "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
                             "if [ -n \"$BB\" ] && [ -x \"$BB\" ]; then " +
                             "  $BB setsid /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/takeover_app.log 2>&1 & " +
                             "else " +
                             "  nohup /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/takeover_app.log 2>&1 & " +
                             "fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                p.waitFor();
                appendLog("Takeover daemon spawned successfully. Display switching in progress...");
            } catch (Exception e) {
                appendLog("Launch error: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void returnToOneUI() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to restore One UI!");
            return;
        }
        appendLog("\n[START] Restoring One UI and resetting display...");
        setLogStatus("Restoring…");
        executor.execute(() -> {
            try {
                String stageScript = STAGE_DIR + "/return_to_oneui.sh";
                String cmd = "stageScript=" + STAGE_DIR + "/return_to_oneui.sh; " +
                             "chmod 755 $stageScript; " +
                             "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
                             "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
                             "if [ -n \"$BB\" ] && [ -x \"$BB\" ]; then " +
                             "  $BB setsid /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/restore_app.log 2>&1 & " +
                             "else " +
                             "  nohup /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/restore_app.log 2>&1 & " +
                             "fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                p.waitFor();
                appendLog("One UI restore triggered successfully.");
            } catch (Exception e) {
                appendLog("Restore error: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void backupLauncher() {
        executor.execute(() -> {
            setLogStatus("Backing up…");
            appendLog("\nBacking up One UI Home Screen database...");
            try {
                String cmd = "mkdir -p /data/local/tmp/lineage_backups && " +
                             "cp -a /data/user/0/com.sec.android.app.launcher/databases/* /data/local/tmp/lineage_backups/ 2>/dev/null && " +
                             "ls -la /data/local/tmp/lineage_backups/";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                }
                p.waitFor();
                appendLog("✓ Home screen database successfully snapshotted!");
                setLogStatus("Idle");
            } catch (Exception e) {
                appendLog("Backup failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void restoreLauncher() {
        executor.execute(() -> {
            setLogStatus("Restoring…");
            appendLog("\nRestoring One UI Home Screen layout...");
            try {
                String cmd = "if [ -d /data/local/tmp/lineage_backups ]; then " +
                             "LAUNCHER_DATA=/data/user/0/com.sec.android.app.launcher; " +
                             "L_UID=$(stat -c %u $LAUNCHER_DATA 2>/dev/null || echo 10160); " +
                             "L_GID=$(stat -c %g $LAUNCHER_DATA 2>/dev/null || echo 10160); " +
                             "cp -a /data/local/tmp/lineage_backups/* $LAUNCHER_DATA/databases/ && " +
                             "chown -R ${L_UID}:${L_GID} $LAUNCHER_DATA/databases && " +
                             "chmod 771 $LAUNCHER_DATA/databases && " +
                             "chmod 660 $LAUNCHER_DATA/databases/* && " +
                             "am force-stop com.sec.android.app.launcher && " +
                             "am start -a android.intent.action.MAIN -c android.intent.category.HOME && " +
                             "echo 'Restored launcher successfully with UID '${L_UID}; else echo 'No backup found!'; fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                }
                p.waitFor();
                setLogStatus("Idle");
            } catch (Exception e) {
                appendLog("Restore failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }
}
