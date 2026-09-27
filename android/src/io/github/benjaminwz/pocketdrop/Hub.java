package io.github.benjaminwz.pocketdrop;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 連線和收發檔案都在這裡，整個 App 只有一份；畫面（MainActivity）只負責顯示和按鈕。
 * 跟電腦的約定：UDP 廣播 "POCKETDROP?" 找電腦，之後全部走 HTTP（對應電腦版 pocketdrop.pyw 的 Handler）。
 */
public class Hub {
    /** 介面文字：手機是中文就用中文，其他語言一律英文。 */
    public static final boolean ZH = "zh".equals(Locale.getDefault().getLanguage());

    public static String T(String zh, String en) {
        return ZH ? zh : en;
    }

    public static final int PORT = 47850;
    public static final int UDP_PORT = 47852;
    public static final String FOLDER = "PocketDrop";

    public static final int SEARCHING = 0, WAIT_APPROVE = 1, CONNECTED = 2, NOT_FOUND = 3, CHOOSE = 4, DENIED = 5;

    /** 紀錄裡的一筆：一個檔案或一段文字。 */
    public static class Entry {
        public static final int UP = 0, DOWN = 1, TEXT_IN = 2, TEXT_OUT = 3;
        public static final int WAITING = 0, RUNNING = 1, OK = 2, FAIL = 3;
        public final int kind;
        public volatile int state = WAITING;
        public volatile String name = "", text, error, mime;
        public volatile long done, total = -1, startedAt;
        public volatile Uri uri;

        Entry(int kind) { this.kind = kind; }
    }

    public static class Pc {
        public String id = "", name = T("電腦", "PC"), host;
        public int port = PORT;
        /** 是不是透過 USB 線（手機的 USB 網路共用）找到的。 */
        public boolean usb;
    }

    public interface Listener { void onHubChanged(); }

    /** 電腦說這支手機的鑰匙不對（被取消配對了）。 */
    static class Unauthorized extends Exception {}

    private static Hub instance;

    public static synchronized Hub get(Context c) {
        if (instance == null) instance = new Hub(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    /** 紀錄，新的在前面；只在主執行緒改。 */
    public final List<Entry> entries = new ArrayList<>();
    // 下面三個只有網路執行緒會碰
    private final Map<String, Entry> incoming = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Set<String> saved = new HashSet<>();

    public volatile int state = SEARCHING;
    public volatile String stateText = T("正在尋找電腦…", "Looking for your PC…");
    public volatile List<Pc> choices = new ArrayList<>();
    private volatile String host, key, pcId, pcName;
    private volatile int port;
    private volatile boolean active, userRetry;
    private volatile String manualHost;
    private volatile Pc chosen;
    private final Object wake = new Object();
    private final Object connLock = new Object();
    private int started;
    private boolean changePosted;

    private Hub(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("pocketdrop", Context.MODE_PRIVATE);
        host = prefs.getString("host", null);
        port = prefs.getInt("port", PORT);
        key = prefs.getString("key", null);
        pcId = prefs.getString("pc_id", "");
        pcName = prefs.getString("pc_name", T("電腦", "PC"));
        Thread t = new Thread(this::loop, "pocketdrop-net");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 給畫面用的

    public String pcName() { return pcName; }

    public String lastHost() { return host == null ? "" : host; }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** 畫面在前景時才跟電腦保持連線，關到背景就停下來省電。 */
    public void activityStarted() {
        started++;
        if (started == 1) {
            active = true;
            kick();
        }
    }

    public void activityStopped() {
        if (started > 0) started--;
        if (started == 0) active = false;
    }

    public void retry() {
        userRetry = true;
        setState(SEARCHING, T("正在尋找電腦…", "Looking for your PC…"));
        kick();
    }

    public void connectManual(String h) {
        manualHost = h.trim();
        kick();
    }

    public void choose(Pc pc) {
        chosen = pc;
        kick();
    }

    public void forgetPc() {
        key = null;
        host = null;
        pcId = "";
        prefs.edit().remove("key").remove("host").remove("pc_id").apply();
        retry();
    }

    public void sendUris(List<Uri> uris) {
        ContentResolver cr = app.getContentResolver();
        for (final Uri uri : uris) {
            final Entry e = new Entry(Entry.UP);
            e.name = "";
            try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = cur.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !cur.isNull(ni)) e.name = cur.getString(ni);
                    if (si >= 0 && !cur.isNull(si)) e.total = cur.getLong(si);
                }
            } catch (Exception ignored) {
            }
            if (e.name == null || e.name.isEmpty()) {
                String last = uri.getLastPathSegment();
                e.name = last != null ? last : T("檔案", "file");
            }
            addEntry(e);
            sender.execute(() -> upload(uri, e));
        }
    }

    public void sendText(final String text) {
        final Entry e = new Entry(Entry.TEXT_OUT);
        e.text = text;
        addEntry(e);
        sender.execute(() -> {
            if (!waitConnected(60000)) {
                fail(e, T("沒有連上電腦", "Not connected to a PC"));
                return;
            }
            HttpURLConnection c = null;
            try {
                byte[] data = text.getBytes("UTF-8");
                c = open(host, port, "/api/text", key);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                e.state = Entry.RUNNING;
                changed();
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
                int code = c.getResponseCode();
                if (code == 200) {
                    e.state = Entry.OK;
                    changed();
                } else {
                    fail(e, T("電腦回應 ", "PC replied ") + code);
                }
            } catch (IOException ex) {
                fail(e, T("傳送失敗，請再試一次", "Couldn't send. Please try again"));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public void copyToClipboard(final String text) {
        main.post(() -> {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(FOLDER, text));
        });
    }

    // ------------------------------------------------------------ 連線

    private void loop() {
        while (true) {
            synchronized (wake) {
                while (!active) {
                    try {
                        wake.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            try {
                if (state != CONNECTED) {
                    connect();
                } else {
                    // 插了線、開了 USB 網路共用，但還在走 Wi-Fi：試著改走傳輸線；在切過去之前問得勤一點
                    boolean usbWaiting = !isUsbHost(host) && !usbAddresses().isEmpty();
                    if (usbWaiting) trySwitchToUsb();
                    poll(usbWaiting && !isUsbHost(host) ? 5 : 25);
                }
            } catch (Unauthorized e) {
                clearKey();
                setState(SEARCHING, T("電腦取消了配對，重新連線…", "The PC unpaired this phone. Reconnecting…"));
            } catch (Exception e) {
                if (state == CONNECTED) setState(SEARCHING, T("跟電腦斷線了，重新尋找…", "Lost the connection. Searching again…"));
                sleep(1500);
            }
        }
    }

    private void connect() throws Exception {
        String manual = manualHost;
        manualHost = null;
        Pc pick = chosen;
        chosen = null;
        boolean retryNow = userRetry;
        userRetry = false;

        if (manual != null) {
            Pc p = parseHost(manual);
            if (p == null) {
                setState(NOT_FOUND, T("IP 格式不對，例如 192.168.1.23", "That IP doesn't look right, e.g. 192.168.1.23"));
                return;
            }
            pair(p);
            return;
        }
        if (pick != null) {
            pair(pick);
            return;
        }
        // 被拒絕或要選電腦的時候，等使用者按按鈕，不要一直煩電腦
        if ((state == DENIED || state == CHOOSE) && !retryNow) {
            sleep(30000);
            return;
        }
        if (state != NOT_FOUND) setState(SEARCHING, T("正在尋找電腦…", "Looking for your PC…"));

        // 1. 上次那台電腦還在原本的 IP（手機開著 USB 網路共用時跳過，直接去找走傳輸線的路）
        if (key != null && host != null && (usbAddresses().isEmpty() || isUsbHost(host))) {
            int code = ping(host, port);
            if (code == 200) {
                setConnected();
                return;
            }
            if (code == 401) throw new Unauthorized();
        }
        // 2. 在 Wi-Fi 裡喊一聲，看哪台電腦開著口袋快傳
        List<Pc> found = discover();
        if (key != null) {
            for (Pc p : found) {
                if (p.id.equals(pcId)) {
                    int code = ping(p.host, p.port);
                    if (code == 200) {
                        saveHost(p);
                        setConnected();
                        return;
                    }
                    if (code == 401) throw new Unauthorized();
                }
            }
            // 配對過的電腦沒開：不自動去連別台，等它開
            setState(NOT_FOUND, T("找不到電腦「", "Can't find \"") + pcName + T("」。請確認電腦開著口袋快傳，而且跟手機連同一個 Wi-Fi。", "\". Make sure PocketDrop is open on the PC and both are on the same Wi-Fi."));
            sleep(4000);
            return;
        }
        for (Pc p : found) {
            if (p.usb) {
                pair(p);
                return;
            }
        }
        if (found.isEmpty()) {
            setState(NOT_FOUND, T("找不到電腦。請確認電腦開著口袋快傳，而且跟手機連同一個 Wi-Fi。", "No PC found. Make sure PocketDrop is open on the PC and both are on the same Wi-Fi."));
            sleep(4000);
            return;
        }
        if (found.size() == 1) {
            pair(found.get(0));
            return;
        }
        choices = found;
        setState(CHOOSE, T("找到好幾台電腦，請選一台", "Found several PCs. Pick one"));
    }

    /** 跟電腦打招呼；第一次連的手機，電腦那邊要按「允許」。 */
    private void pair(Pc p) {
        if (p.usb) setState(WAIT_APPROVE, T("透過 USB 線連線中…", "Connecting over USB…"));
        else setState(WAIT_APPROVE, T("請到電腦", "Click \"Allow\" on the PC") + (p.name.equals(T("電腦", "PC")) ? "" : T("「", " (") + p.name + T("」", ")")) + T("上按「允許」", ""));
        HttpURLConnection c = null;
        try {
            c = open(p.host, p.port, "/api/hello", null);
            c.setRequestMethod("POST");
            c.setReadTimeout(130000);
            c.setRequestProperty("X-Device-Id", deviceId());
            c.setRequestProperty("X-Device-Name", URLEncoder.encode(deviceName(), "UTF-8"));
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code == 200) {
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                key = j.getString("key");
                pcId = j.optString("id", "");
                pcName = j.optString("name", T("電腦", "PC"));
                prefs.edit().putString("key", key).putString("pc_id", pcId).putString("pc_name", pcName).apply();
                saveHost(p);
                setConnected();
            } else if (code == 403) {
                setState(DENIED, T("電腦沒有允許連線。要再試一次請按「重新搜尋」。", "The PC declined. Tap \"Search again\" to retry."));
            } else if (code == 409) {
                setState(SEARCHING, T("電腦正在確認另一支手機，稍等…", "The PC is approving another phone. Please wait…"));
                sleep(3000);
            } else {
                throw new IOException("HTTP " + code);
            }
        } catch (IOException | JSONException e) {
            setState(NOT_FOUND, T("連不上 ", "Can't reach ") + p.host + T("。請確認電腦開著口袋快傳，而且跟手機連同一個 Wi-Fi。", ". Make sure PocketDrop is open on the PC and both are on the same Wi-Fi."));
            sleep(3000);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void saveHost(Pc p) {
        host = p.host;
        port = p.port;
        prefs.edit().putString("host", host).putInt("port", port).apply();
    }

    private void clearKey() {
        key = null;
        prefs.edit().remove("key").apply();
    }

    private void setConnected() {
        setState(CONNECTED, T("已連上：", "Connected: ") + pcName + (isUsbHost(host) ? T("（USB 線）", " (USB)") : ""));
    }

    private int ping(String h, int p) {
        HttpURLConnection c = null;
        try {
            c = open(h, p, "/api/ping", key);
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private List<Pc> discover() {
        List<Pc> found = new ArrayList<>();
        Map<String, Pc> byId = new HashMap<>();
        List<InterfaceAddress> usbNets = usbAddresses();
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] msg = "POCKETDROP?".getBytes("UTF-8");
            List<InetAddress> targets = broadcastTargets();
            byte[] buf = new byte[2048];
            long start = System.currentTimeMillis();
            int round = 0;
            while (true) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > 2000 || (!found.isEmpty() && elapsed > 900)) break;
                if (round < 3) {
                    for (InetAddress a : targets) {
                        try {
                            s.send(new DatagramPacket(msg, msg.length, a, UDP_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    round++;
                }
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    s.receive(pkt);
                    JSONObject j = new JSONObject(new String(pkt.getData(), 0, pkt.getLength(), "UTF-8"));
                    if (!"pocketdrop".equals(j.optString("app"))) continue;
                    Pc pc = new Pc();
                    pc.id = j.optString("id", "");
                    pc.name = j.optString("name", T("電腦", "PC"));
                    pc.port = j.optInt("port", PORT);
                    pc.host = pkt.getAddress().getHostAddress();
                    pc.usb = inNets(pkt.getAddress(), usbNets);
                    Pc old = byId.get(pc.id);
                    if (old == null) {
                        byId.put(pc.id, pc);
                        found.add(pc);
                    } else if (pc.usb && !old.usb) {
                        found.set(found.indexOf(old), pc);
                        byId.put(pc.id, pc);
                    }
                } catch (SocketTimeoutException | JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (s != null) s.close();
        }
        return found;
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> t = new ArrayList<>();
        try {
            t.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !t.contains(b)) t.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    /** 手機開「USB 網路共用」時會多一個 rndis0 / ncm0 / usb0 網路介面，這裡找出它們的位址和網段。 */
    private static List<InterfaceAddress> usbAddresses() {
        List<InterfaceAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (!ni.isUp() || !(n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb"))) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) out.add(ia);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean inNets(InetAddress a, List<InterfaceAddress> nets) {
        byte[] x = a.getAddress();
        for (InterfaceAddress ia : nets) {
            byte[] y = ia.getAddress().getAddress();
            if (x.length != y.length) continue;
            int prefix = ia.getNetworkPrefixLength();
            boolean same = true;
            for (int i = 0; i < x.length && prefix > 0; i++, prefix -= 8) {
                int m = prefix >= 8 ? 0xFF : (0xFF << (8 - prefix)) & 0xFF;
                if ((x[i] & m) != (y[i] & m)) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static boolean isUsbHost(String h) {
        try {
            // 只接受數字 IP，不會去查 DNS
            return h != null && h.matches("[0-9.]+") && inNets(InetAddress.getByName(h), usbAddresses());
        } catch (IOException e) {
            return false;
        }
    }

    private static Pc parseHost(String s) {
        s = s.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty()) return null;
        Pc p = new Pc();
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            try {
                p.port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            s = s.substring(0, colon);
        }
        p.host = s;
        return p;
    }

    // ------------------------------------------------------------ 電腦 → 手機

    /** 問電腦有沒有東西要給手機；沒有的話電腦會等最多 25 秒，一有新東西就馬上回。 */
    private long lastUsbTry;

    /** 已經用 Wi-Fi 連著，手機又開了 USB 網路共用：同一台電腦如果從傳輸線那邊也找得到，就改走線（最多每 5 秒試一次）。 */
    private void trySwitchToUsb() {
        long now = System.currentTimeMillis();
        if (now - lastUsbTry < 5000) return;
        lastUsbTry = now;
        for (Pc p : discover()) {
            if (p.usb && p.id.equals(pcId) && ping(p.host, p.port) == 200) {
                saveHost(p);
                setConnected();
                return;
            }
        }
    }

    private void poll(int wait) throws Exception {
        HttpURLConnection c = open(host, port, "/api/poll?wait=" + wait, key);
        c.setReadTimeout(40000);
        JSONArray items;
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new Unauthorized();
            if (code != 200) throw new IOException("HTTP " + code);
            items = new JSONObject(readAll(c.getInputStream())).getJSONArray("items");
        } finally {
            c.disconnect();
        }
        for (int i = 0; i < items.length(); i++) handleItem(items.getJSONObject(i));
    }

    private void handleItem(JSONObject it) throws Exception {
        String id = it.optString("id");
        if ("text".equals(it.optString("type"))) {
            if (!saved.contains(id)) {
                String text = it.optString("text");
                Entry e = new Entry(Entry.TEXT_IN);
                e.text = text;
                e.state = Entry.OK;
                addEntry(e);
                copyToClipboard(text);
                saved.add(id);
            }
            done(id, true);
            saved.remove(id);
            return;
        }
        if (saved.contains(id)) {  // 已經存好了，只是上次沒來得及跟電腦說
            done(id, true);
            saved.remove(id);
            return;
        }
        Entry e = incoming.get(id);
        if (e == null) {
            e = new Entry(Entry.DOWN);
            e.name = it.optString("name", T("檔案", "file"));
            e.total = it.optLong("size", -1);
            incoming.put(id, e);
            addEntry(e);
        }
        boolean ok;
        try {
            ok = download(id, e);
        } catch (IOException ex) {
            Integer n = failures.get(id);
            n = n == null ? 1 : n + 1;
            failures.put(id, n);
            if (n < 3) {
                e.state = Entry.WAITING;
                e.error = T("中斷了，重新接收中…", "Interrupted. Retrying…");
                changed();
                throw ex;
            }
            ok = false;
            e.error = T("一直收不完整，請再傳一次", "Kept failing. Please send it again");
        }
        incoming.remove(id);
        failures.remove(id);
        e.state = ok ? Entry.OK : Entry.FAIL;
        changed();
        if (ok) saved.add(id);
        done(id, ok);
        saved.remove(id);
    }

    /** 把電腦的檔案存到「下載/PocketDrop」。回傳 false = 這個檔案不用再試了。 */
    private boolean download(String id, Entry e) throws Exception {
        String[] parts = e.name.split("/");
        StringBuilder sub = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            String s = clean(parts[i]);
            if (!s.isEmpty() && !s.equals(".") && !s.equals("..")) sub.append('/').append(s);
        }
        String fileName = clean(parts[parts.length - 1]);
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) fileName = T("未命名", "untitled");
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "application/octet-stream";

        ContentResolver cr = app.getContentResolver();
        HttpURLConnection c = open(host, port, "/api/file?id=" + id, key);
        Uri uri = null;
        boolean ok = false;
        try {
            int code = c.getResponseCode();
            if (code == 404) {
                e.error = T("電腦上的檔案不見了", "The file is no longer on the PC");
                return false;
            }
            if (code == 401) throw new Unauthorized();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            if (total >= 0) e.total = total;

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + sub);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            try {
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            } catch (Exception ex) {
                uri = null;
            }
            if (uri == null) {
                e.error = T("手機存不下這個檔案", "Couldn't save the file on this phone");
                return false;
            }
            e.state = Entry.RUNNING;
            e.done = 0;
            e.error = null;
            e.startedAt = System.currentTimeMillis();
            changed();
            try (InputStream in = c.getInputStream(); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException(T("沒辦法寫入", "Can't write"));
                copy(in, out, e);
            }
            if (e.total >= 0 && e.done != e.total) throw new IOException(T("檔案沒收完整", "Incomplete file"));
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            e.uri = uri;
            e.mime = mime;
            ok = true;
            return true;
        } finally {
            c.disconnect();
            if (!ok && uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void done(String id, boolean ok) throws IOException {
        HttpURLConnection c = open(host, port, "/api/done?id=" + id + "&ok=" + (ok ? 1 : 0), key);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ 手機 → 電腦

    private void upload(Uri uri, Entry e) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!waitConnected(60000)) {
                fail(e, T("沒有連上電腦", "Not connected to a PC"));
                return;
            }
            HttpURLConnection c = null;
            try {
                // 第二次改用不指定大小的方式傳，以免手機回報的檔案大小不準
                long size = attempt == 0 ? e.total : -1;
                c = open(host, port, "/api/upload?name=" + URLEncoder.encode(e.name, "UTF-8") + "&size=" + size, key);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setReadTimeout(120000);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                if (size >= 0) c.setFixedLengthStreamingMode(size);
                else c.setChunkedStreamingMode(256 * 1024);
                e.state = Entry.RUNNING;
                e.done = 0;
                e.error = null;
                e.startedAt = System.currentTimeMillis();
                changed();
                try (InputStream in = app.getContentResolver().openInputStream(uri); OutputStream out = c.getOutputStream()) {
                    if (in == null) throw new IOException(T("讀不到檔案", "Can't read the file"));
                    copy(in, out, e);
                }
                int code = c.getResponseCode();
                if (code == 200) {
                    e.state = Entry.OK;
                    changed();
                    return;
                }
                if (code == 401) {
                    clearKey();
                    setState(SEARCHING, T("電腦取消了配對，重新連線…", "The PC unpaired this phone. Reconnecting…"));
                    kick();
                    fail(e, T("電腦取消了配對，請重新傳一次", "The PC unpaired this phone. Please send it again"));
                    return;
                }
                e.error = T("電腦回應 ", "PC replied ") + code;
            } catch (SecurityException ex) {
                fail(e, T("沒有權限讀這個檔案", "No permission to read this file"));
                return;
            } catch (IOException ex) {
                e.error = T("傳到一半斷掉了", "The connection dropped");
            } finally {
                if (c != null) c.disconnect();
            }
            sleep(1000);
        }
        e.state = Entry.FAIL;
        changed();
    }

    // ------------------------------------------------------------ 小工具

    private void copy(InputStream in, OutputStream out, Entry e) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            e.done += n;
            changed();
        }
    }

    private void fail(Entry e, String why) {
        e.error = why;
        e.state = Entry.FAIL;
        changed();
    }

    private HttpURLConnection open(String h, int p, String path, String k) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http", h, p, path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        if (k != null) c.setRequestProperty("X-Key", k);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch < 32 || "\\:*?\"<>|".indexOf(ch) >= 0 ? '_' : ch);
        return b.toString().trim();
    }

    private String deviceId() {
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String deviceName() {
        String n = null;
        try {
            n = Settings.Global.getString(app.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n;
    }

    private void addEntry(final Entry e) {
        main.post(() -> {
            entries.add(0, e);
            while (entries.size() > 60) entries.remove(entries.size() - 1);
        });
        changed();
    }

    private void setState(int s, String text) {
        state = s;
        stateText = text;
        if (s == CONNECTED) {
            synchronized (connLock) {
                connLock.notifyAll();
            }
        }
        changed();
    }

    private boolean waitConnected(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (connLock) {
            while (state != CONNECTED) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    connLock.wait(left);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private void kick() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private void sleep(long ms) {
        synchronized (wake) {
            try {
                wake.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** 通知畫面更新；傳檔時會一直叫，所以合併成最多每 0.12 秒一次。 */
    void changed() {
        synchronized (this) {
            if (changePosted) return;
            changePosted = true;
        }
        main.postDelayed(() -> {
            synchronized (Hub.this) {
                changePosted = false;
            }
            for (Listener l : new ArrayList<>(listeners)) l.onHubChanged();
        }, 120);
    }
}
