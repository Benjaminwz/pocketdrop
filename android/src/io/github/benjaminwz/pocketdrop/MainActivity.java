package io.github.benjaminwz.pocketdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 唯一的畫面：上面是連線狀態，中間是「傳到電腦」，下面是紀錄。 */
public class MainActivity extends Activity implements Hub.Listener {
    private static String T(String zh, String en) {
        return Hub.T(zh, en);
    }

    static final int NAVY = 0xFF1A2656, NAVY_TEXT = 0xFFC1CBEE, ACCENT = 0xFF3558D4, ACCENT_SOFT = 0xFFE8EEFF;
    static final int BG = 0xFFF4F6FC, CARD = 0xFFFFFFFF, TEXT = 0xFF1F2A3D, MUTED = 0xFF6B7688, BORDER = 0xFFDDE2F1;
    static final int GREEN = 0xFF22A35A, GREEN_LIGHT = 0xFF7BE0A4, ORANGE_LIGHT = 0xFFFFC069, RED = 0xFFD64545;
    static final int PICK_MEDIA = 1, PICK_FILES = 2;
    static final int SHOW_ENTRIES = 30;

    private Hub hub;
    private TextView status;
    private LinearLayout actions, list;
    private EditText input;
    private LinearLayout updateCard;
    private TextView updateText;
    private Button updateBtn;
    private int shownState = -1;
    private List<Hub.Pc> choiceShown;
    private boolean keepOn;
    private final List<Hub.Entry> shownEntries = new ArrayList<>();
    private final Map<Hub.Entry, Row> rows = new HashMap<>();

    /** 紀錄裡一列的畫面。 */
    private static class Row {
        LinearLayout box;
        TextView title, body, sub;
        ProgressBar bar;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        hub = Hub.get(this);
        getWindow().setStatusBarColor(NAVY);
        setContentView(buildUi());
        if (saved == null && !handleInstallStatus(getIntent())) handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleInstallStatus(intent)) handleShare(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hub.addListener(this);
        hub.activityStarted();
        render();
    }

    @Override
    protected void onStop() {
        hub.removeListener(this);
        hub.activityStopped();
        super.onStop();
    }

    @Override
    public void onHubChanged() {
        render();
    }

    /** 系統安裝更新的結果：要使用者確認的話，打開系統的「要更新嗎」畫面。 */
    private boolean handleInstallStatus(Intent in) {
        if (in == null || !Hub.ACTION_INSTALL.equals(in.getAction())) return false;
        int status = in.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm != null) startActivity(confirm);
            } catch (Exception e) {
                toast(T("打不開系統的安裝畫面", "Couldn't open the system installer"));
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast(T("更新沒有完成", "The update didn't finish") + (msg != null ? T("：", ": ") + msg : ""));
        }
        setIntent(new Intent(this, MainActivity.class));
        return true;
    }

    private void onUpdateClick() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast(T("請允許口袋快傳「安裝不明應用程式」，再回來按一次「更新 App」", "Allow PocketDrop to install apps, then come back and tap Update again"));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        hub.startUpdate();
    }

    /** 從別的 App 按「分享 → 口袋快傳」進來。 */
    private void handleShare(Intent in) {
        if (in == null || (in.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
        String action = in.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        List<Uri> uris = new ArrayList<>();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else {
                ArrayList<Uri> us = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (us != null) uris.addAll(us);
            }
        } catch (Exception ignored) {
        }
        if (uris.isEmpty()) {
            ClipData clip = in.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            }
        }
        if (!uris.isEmpty()) {
            hub.sendUris(uris);
        } else {
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) hub.sendText(t.toString());
        }
        setIntent(new Intent(this, MainActivity.class));  // 避免畫面重建時又傳一次
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) hub.sendUris(uris);
    }

    private void pickMedia() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startPicker(i, PICK_MEDIA);
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startPicker(i, PICK_FILES);
    }

    private void startPicker(Intent i, int code) {
        try {
            startActivityForResult(i, code);
        } catch (ActivityNotFoundException e) {
            toast(T("手機上找不到選檔案的畫面", "No file picker found on this phone"));
        }
    }

    // ------------------------------------------------------------ 版面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = vertical();
        scroll.addView(root);

        LinearLayout head = vertical();
        head.setBackgroundColor(NAVY);
        head.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.addView(head);
        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(titleRow);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = label(T("口袋快傳", "PocketDrop"), 22, Color.WHITE, true);
        title.setPadding(dp(12), 0, 0, 0);
        titleRow.addView(title);
        status = label("", 15, NAVY_TEXT, false);
        status.setPadding(0, dp(12), 0, 0);
        head.addView(status);
        actions = horizontal();
        actions.setPadding(0, dp(12), 0, 0);
        head.addView(actions);

        updateCard = card(root, T("有新版", "Update available"));
        updateText = label("", 14, TEXT, false);
        updateCard.addView(updateText);
        updateBtn = button(T("更新 App", "Update app"), true, v -> onUpdateClick());
        LinearLayout.LayoutParams ub = new LinearLayout.LayoutParams(-1, dp(48));
        ub.topMargin = dp(10);
        updateCard.addView(updateBtn, ub);
        updateCard.setVisibility(View.GONE);

        LinearLayout send = card(root, T("傳到電腦", "Send to PC"));
        LinearLayout row = horizontal();
        send.addView(row);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(62), 1);
        left.rightMargin = dp(10);
        row.addView(button(T("選照片／影片", "Photos / videos"), true, v -> pickMedia()), left);
        row.addView(button(T("選檔案", "Files"), true, v -> pickFiles()), new LinearLayout.LayoutParams(0, dp(62), 1));
        input = new EditText(this);
        input.setHint(T("輸入文字或網址…", "Type text or a link…"));
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setBackground(round(0xFFF6F8FE, 12, BORDER));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = dp(14);
        send.addView(input, ip);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48));
        bp.topMargin = dp(10);
        send.addView(button(T("傳文字到電腦", "Send text to PC"), false, v -> {
            String t = input.getText().toString();
            if (t.trim().isEmpty()) {
                toast(T("先輸入要傳的文字", "Type something first"));
                return;
            }
            hub.sendText(t);
            input.setText("");
        }), bp);

        LinearLayout logCard = card(root, T("紀錄", "Activity"));
        list = vertical();
        logCard.addView(list);

        LinearLayout priv = card(root, T("隱私", "Privacy"));
        Switch usbOnly = new Switch(this);
        usbOnly.setText(T("純有線模式", "Cable-only mode"));
        usbOnly.setTextSize(15);
        usbOnly.setTextColor(TEXT);
        usbOnly.setChecked(hub.usbOnly);
        int[][] states = {{android.R.attr.state_checked}, {}};
        usbOnly.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xFFF4F4F4}));
        usbOnly.setTrackTintList(new ColorStateList(states, new int[]{0x883558D4, 0x44000000}));
        usbOnly.setOnCheckedChangeListener((b, on) -> hub.setUsbOnly(on));
        priv.addView(usbOnly, new LinearLayout.LayoutParams(-1, -2));
        TextView privText = label(T("只用傳輸線（USB 網路共用）連電腦，完全不走 Wi-Fi，也不會在 Wi-Fi 上找電腦。電腦版也有同名的開關，兩邊都開最安全。",
                "Only connect over the USB cable (USB tethering). Nothing goes over Wi-Fi, and the phone won't look for PCs on Wi-Fi. The PC app has the same switch; turn on both for the most privacy."), 13, MUTED, false);
        privText.setPadding(0, dp(6), 0, 0);
        priv.addView(privText);

        TextView foot = label(T("收到的檔案存在「下載／PocketDrop」資料夾\n電腦傳東西過來時，這個 App 要開著", "Received files are saved to Download/PocketDrop\nKeep this app open to receive from the PC"), 12, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(16), dp(14), dp(16), dp(26));
        root.addView(foot);
        return scroll;
    }

    // ------------------------------------------------------------ 更新畫面

    private void render() {
        if (status == null) return;
        int s = hub.state;
        String dot = s == Hub.CONNECTED ? "● " : (s == Hub.NOT_FOUND || s == Hub.DENIED) ? "○ " : "◌ ";
        status.setText(dot + hub.stateText);
        status.setTextColor(s == Hub.CONNECTED ? GREEN_LIGHT : (s == Hub.NOT_FOUND || s == Hub.DENIED) ? ORANGE_LIGHT : NAVY_TEXT);
        if (s != shownState) {  // 只有狀態變了才換按鈕，免得按到一半按鈕被換掉
            shownState = s;
            actions.removeAllViews();
            if (s == Hub.NOT_FOUND || s == Hub.DENIED || s == Hub.CHOOSE) {
                addAction(T("重新搜尋", "Search again"), v -> hub.retry());
                addAction(T("輸入電腦 IP", "Enter PC IP"), v -> askIp());
            }
            if (s != Hub.CONNECTED) addAction(T("用 USB 線連", "Use USB cable"), v -> showUsbHelp()); else if (s == Hub.CONNECTED) {
                addAction(T("換一台電腦", "Switch PC"), v -> confirmSwitch());
            }
            actions.setVisibility(actions.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }
        if (s == Hub.CHOOSE && hub.choices != choiceShown) showChoose();
        boolean showUpdate = hub.updating || hub.updateAvailable() || hub.updateError != null;
        updateCard.setVisibility(showUpdate ? View.VISIBLE : View.GONE);
        if (showUpdate) {
            updateText.setText(hub.updating ? T("正在從電腦下載新版…", "Downloading the new version from the PC…")
                    : hub.updateError != null ? T("更新沒有成功：", "The update failed: ") + hub.updateError
                    : T("電腦上有新版的口袋快傳（v", "PocketDrop v") + hub.pcVersion
                      + T("），按下面的按鈕就能更新。", " is on your PC. Tap below to update."));
            updateBtn.setEnabled(!hub.updating);
            updateBtn.setAlpha(hub.updating ? 0.5f : 1f);
        }
        renderList();

        boolean busy = false;
        for (Hub.Entry e : hub.entries) {
            if (e.state == Hub.Entry.RUNNING || e.state == Hub.Entry.WAITING) busy = true;
        }
        if (busy != keepOn) {  // 傳檔時不要讓螢幕關掉，不然會傳到一半停住
            keepOn = busy;
            if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderList() {
        List<Hub.Entry> now = hub.entries.subList(0, Math.min(SHOW_ENTRIES, hub.entries.size()));
        if (!now.equals(shownEntries)) {  // 有新的一筆：整個重排；平常只更新文字，才不會點不到
            shownEntries.clear();
            shownEntries.addAll(now);
            list.removeAllViews();
            Map<Hub.Entry, Row> keep = new HashMap<>();
            for (Hub.Entry e : shownEntries) {
                Row r = rows.get(e);
                if (r == null) r = makeRow(e);
                keep.put(e, r);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                list.addView(r.box, lp);
            }
            rows.clear();
            rows.putAll(keep);
            if (shownEntries.isEmpty()) {
                TextView empty = label(T("還沒有傳過東西。\n在電腦版口袋快傳拖檔案進視窗，就會出現在這裡。", "Nothing here yet.\nDrag files into PocketDrop on your PC and they'll show up here."), 14, MUTED, false);
                empty.setPadding(0, dp(6), 0, dp(6));
                list.addView(empty);
            }
        }
        for (Hub.Entry e : shownEntries) bind(rows.get(e), e);
    }

    private Row makeRow(final Hub.Entry e) {
        Row r = new Row();
        r.box = vertical();
        r.box.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.box.setBackground(ripple(round(0xFFF7F9FC, 12, BORDER), 0x223558D4));
        r.title = label("", 15, TEXT, true);
        r.title.setSingleLine(true);
        r.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.box.addView(r.title);
        r.body = label("", 14, TEXT, false);
        r.body.setMaxLines(5);
        r.body.setEllipsize(TextUtils.TruncateAt.END);
        r.body.setPadding(0, dp(4), 0, 0);
        r.box.addView(r.body);
        r.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        r.bar.setMax(1000);
        r.bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8));
        lp.topMargin = dp(6);
        r.box.addView(r.bar, lp);
        r.sub = label("", 13, MUTED, false);
        r.sub.setPadding(0, dp(4), 0, 0);
        r.box.addView(r.sub);
        r.box.setOnClickListener(v -> onEntryClick(e));
        return r;
    }

    private void bind(Row r, Hub.Entry e) {
        boolean up = e.kind == Hub.Entry.UP || e.kind == Hub.Entry.TEXT_OUT;
        boolean isText = e.kind == Hub.Entry.TEXT_IN || e.kind == Hub.Entry.TEXT_OUT;
        String arrow = up ? "↑ " : "↓ ";
        r.title.setText(arrow + (isText ? (up ? T("傳給電腦的文字", "Text sent to PC") : T("電腦傳來的文字", "Text from PC")) : e.name));
        r.body.setVisibility(isText ? View.VISIBLE : View.GONE);
        if (isText) r.body.setText(e.text);

        String sub;
        int color = MUTED;
        boolean showBar = false;
        switch (e.state) {
            case Hub.Entry.WAITING:
                sub = e.error != null ? e.error : up ? T("等待連上電腦…", "Waiting for the PC…") : T("準備接收…", "Getting ready…");
                break;
            case Hub.Entry.RUNNING:
                if (isText) {
                    sub = T("傳送中…", "Sending…");
                    break;
                }
                showBar = true;
                long secs = Math.max(1, System.currentTimeMillis() - e.startedAt);
                String speed = size(e.done * 1000 / secs) + "/s";
                if (e.total > 0) {
                    int pct = (int) Math.min(100, e.done * 100 / e.total);
                    r.bar.setIndeterminate(false);
                    r.bar.setProgress((int) Math.min(1000, e.done * 1000 / e.total));
                    sub = (up ? T("傳送中 ", "Sending ") : T("接收中 ", "Receiving ")) + pct + T("%　", "%  ") + size(e.done) + " / " + size(e.total) + T("　", "  ") + speed;
                } else {
                    r.bar.setIndeterminate(true);
                    sub = (up ? T("傳送中 ", "Sending ") : T("接收中 ", "Receiving ")) + size(e.done) + T("　", "  ") + speed;
                }
                break;
            case Hub.Entry.OK:
                color = GREEN;
                if (e.kind == Hub.Entry.DOWN) sub = T("✓ 已存到 下載/PocketDrop（", "✓ Saved to Download/PocketDrop (") + size(e.total) + T("）・點一下打開", ") · tap to open");
                else if (e.kind == Hub.Entry.TEXT_IN) sub = T("✓ 已複製・點一下再複製", "✓ Copied · tap to copy again");
                else sub = T("✓ 已傳到電腦", "✓ Sent to PC") + (isText ? "" : T("（", " (") + size(e.total) + T("）", ")"));
                break;
            default:
                color = RED;
                sub = "✗ " + (e.error != null ? e.error : T("失敗", "Failed"));
        }
        r.bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        r.sub.setText(sub);
        r.sub.setTextColor(color);
    }

    private void onEntryClick(Hub.Entry e) {
        if (e.kind == Hub.Entry.DOWN && e.state == Hub.Entry.OK && e.uri != null) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(e.uri, e.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ex) {
                toast(T("手機上沒有能打開這種檔案的 App", "No app on this phone can open this file"));
            } catch (Exception ex) {
                toast(T("打不開這個檔案", "Can't open this file"));
            }
        } else if (e.text != null) {
            hub.copyToClipboard(e.text);
            toast(T("已複製", "Copied"));
        }
    }

    // ------------------------------------------------------------ 對話框

    private void askIp() {
        final EditText et = new EditText(this);
        et.setHint(T("例如 192.168.1.23", "e.g. 192.168.1.23"));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(hub.lastHost());
        et.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(22), dp(4), dp(22), 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(T("輸入電腦的 IP", "Enter the PC's IP"))
                .setMessage(T("電腦版口袋快傳視窗上方有寫「本機 IP」。", "It's shown at the top of the PocketDrop window on your PC."))
                .setView(wrap)
                .setPositiveButton(T("連線", "Connect"), (d, w) -> {
                    String h = et.getText().toString().trim();
                    if (!h.isEmpty()) hub.connectManual(h);
                })
                .setNegativeButton(T("取消", "Cancel"), null)
                .show();
    }

    /** USB 線連線：手機開「USB 網路共用」，電腦看到是從 USB 網卡進來的就自動配對。 */
    private void showUsbHelp() {
        new AlertDialog.Builder(this)
                .setTitle(T("用 USB 線連線", "Connect with a USB cable"))
                .setMessage(T("不用 Wi-Fi，也不用在電腦上按「允許」：\n\n", "No Wi-Fi needed, and no need to click \"Allow\" on the PC:\n\n")
                        + T("1. 用傳輸線把手機接到電腦\n", "1. Plug the phone into the PC with a USB cable\n")
                        + T("2. 按「打開設定」，開啟「USB 網路共用」\n", "2. Tap \"Open settings\" and turn on \"USB tethering\"\n")
                        + T("3. 回到口袋快傳，會自動連上電腦\n\n", "3. Come back to PocketDrop. It connects automatically\n\n")
                        + T("配對過一次之後，拔掉線改用 Wi-Fi 也會自動連。\n\n", "After this first pairing, it also reconnects over Wi-Fi without the cable.\n\n")
                        + T("※ 開著 USB 網路共用時，電腦可能會用手機的網路上網，傳完可以關掉。\n", "* While USB tethering is on, the PC may use the phone's mobile data. Turn it off when you're done.\n")
                        + T("※ 還是連不上的話，電腦的 Windows 防火牆要允許口袋快傳使用「公用網路」。", "* Still can't connect? Allow PocketDrop on \"Public networks\" in Windows Firewall."))
                .setPositiveButton(T("打開設定", "Open settings"), (d, w) -> openTetherSettings())
                .setNegativeButton(T("關閉", "Close"), null)
                .show();
    }

    private void openTetherSettings() {
        String[] pages = {"com.android.settings.TetherSettings", "com.android.settings.Settings$TetherSettingsActivity"};
        for (String page : pages) {
            try {
                startActivity(new Intent().setClassName("com.android.settings", page));
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (Exception e) {
            toast(T("請到「設定 → 網路 → 熱點與網路共用」開啟 USB 網路共用", "Turn on USB tethering in Settings → Network → Hotspot & tethering"));
        }
    }

    private void confirmSwitch() {
        new AlertDialog.Builder(this)
                .setTitle(T("換一台電腦？", "Switch to another PC?"))
                .setMessage(T("會忘記目前這台「", "This forgets \"") + hub.pcName() + T("」，重新搜尋 Wi-Fi 裡開著口袋快傳的電腦。", "\" and searches Wi-Fi again for PCs running PocketDrop."))
                .setPositiveButton(T("換", "Switch"), (d, w) -> hub.forgetPc())
                .setNegativeButton(T("取消", "Cancel"), null)
                .show();
    }

    private void showChoose() {
        final List<Hub.Pc> pcs = hub.choices;
        choiceShown = pcs;
        String[] names = new String[pcs.size()];
        for (int i = 0; i < pcs.size(); i++) names[i] = pcs.get(i).name + T("（", " (") + pcs.get(i).host + T("）", ")");
        new AlertDialog.Builder(this)
                .setTitle(T("要連哪一台電腦？", "Which PC?"))
                .setItems(names, (d, w) -> hub.choose(pcs.get(w)))
                .setNegativeButton(T("取消", "Cancel"), null)
                .show();
    }

    // ------------------------------------------------------------ 小工具

    private void addAction(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(ripple(round(0x33FFFFFF, 18, 0), 0x44FFFFFF));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(10);
        actions.addView(b, lp);
    }

    private Button button(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : ACCENT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(ripple(round(primary ? ACCENT : ACCENT_SOFT, 14, 0), primary ? 0x44FFFFFF : 0x223558D4));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = vertical();
        c.setBackground(round(CARD, 18, BORDER));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(14), dp(14), dp(14), 0);
        parent.addView(c, lp);
        TextView t = label(title, 17, TEXT, true);
        t.setPadding(0, 0, 0, dp(12));
        c.addView(t);
        return c;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    static String size(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        double v = n / 1024.0;
        String[] units = {"KB", "MB", "GB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, "%.1f %s", v, units[u]);
    }
}
