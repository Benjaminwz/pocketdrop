package io.github.benjaminwz.pocketdrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 網頁分享：手機自己開一個小網頁（跟電腦版的 /web 同一頁、同一套 API），
 * 同一個 Wi-Fi 或手機熱點裡的電腦、iPhone、別支手機用瀏覽器打開就能跟這支手機互傳。完全不用裝東西，也不用電腦版。
 */
final class WebShare {
    private static String T(String zh, String en) {
        return Hub.T(zh, en);
    }

    static final int PORT = 47850;
    /** 手機傳給網頁、對方一直沒來拿的東西，放 30 分鐘就算了。 */
    private static final long EXPIRE_MS = 30 * 60 * 1000;

    /** 連過這支手機的瀏覽器。 */
    static final class Client {
        String id, name, key;
        volatile long seen;

        boolean online() {
            return System.currentTimeMillis() - seen < 40000;
        }
    }

    /** 等網頁來拿的東西（手機 → 瀏覽器）。 */
    private static final class Item {
        int id;
        String to, text;
        Uri uri;
        Hub.Entry entry;
        long created;
    }

    /** 分段傳上來的檔案（手機收）。 */
    private static final class Upload {
        Uri uri;
        OutputStream out;
        String mime;
        long received, size;
        Hub.Entry entry;
        long touched;
    }

    /** 正在問使用者要不要讓某個瀏覽器連進來。 */
    static final class Ask {
        final String name;
        volatile Boolean answer;

        Ask(String name) { this.name = name; }
    }

    private final Hub hub;
    private final Context app;
    private final SharedPreferences prefs;
    private final Map<String, Client> clients = new LinkedHashMap<>();  // 用 this 鎖
    private final List<Item> outbox = new ArrayList<>();  // 用 this 鎖
    private final Map<String, Upload> uploads = new HashMap<>();  // 用 uploads 鎖
    private final Object askLock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();
    private int nextId = 1;
    private volatile ServerSocket server;
    /** 使用者有沒有打開網頁分享。 */
    private volatile boolean wanted;
    private volatile int port = PORT;
    private volatile List<String> addrCache = new ArrayList<>();
    private volatile long addrAt;
    private byte[] page;
    /** 開不起來（連接埠都被占用）時的說明。 */
    volatile String error;
    volatile Ask ask;

    WebShare(Hub hub, Context app, SharedPreferences prefs) {
        this.hub = hub;
        this.app = app;
        this.prefs = prefs;
        try {
            JSONArray a = new JSONArray(prefs.getString("web_clients", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Client c = new Client();
                c.id = o.getString("id");
                c.name = o.optString("name", T("瀏覽器", "Browser"));
                c.key = o.getString("key");
                clients.put(c.id, c);
            }
        } catch (JSONException ignored) {
        }
        if (prefs.getBoolean("web_share", false)) setOn(true);
    }

    // ------------------------------------------------------------ 給畫面用的

    boolean isOn() {
        return wanted;
    }

    /** 開關網頁分享（開的動作放到背景執行緒，主執行緒不能碰網路）。 */
    void setOn(boolean on) {
        prefs.edit().putBoolean("web_share", on).apply();
        wanted = on;
        if (on) {
            Thread t = new Thread(this::start, "pocketdrop-web-start");
            t.setDaemon(true);
            t.start();
        } else {
            stop();
        }
        hub.changed();
    }

    /** 瀏覽器要打的網址（Wi-Fi、熱點、USB 各一個）。 */
    List<String> addresses() {
        long now = System.currentTimeMillis();
        if (now - addrAt < 3000) return addrCache;
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    // 有廣播位址的才是區網（行動網路沒有），而且只要私人網段
                    if (a instanceof Inet4Address && ia.getBroadcast() != null && a.isSiteLocalAddress()) {
                        out.add("http://" + a.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        addrCache = out;
        addrAt = now;
        return out;
    }

    synchronized List<Client> clients() {
        return new ArrayList<>(clients.values());
    }

    synchronized Client client(String id) {
        return clients.get(id);
    }

    void forgetAll() {
        synchronized (this) {
            clients.clear();
            for (Item it : outbox) hub.failEntry(it.entry, T("已取消瀏覽器的配對", "The browser was unpaired"));
            outbox.clear();
            notifyAll();
        }
        save();
        if (hub.sendTo != null && hub.sendTo.startsWith("web:")) hub.setSendTo(null, null);
        hub.changed();
    }

    /** 手機要傳給某個瀏覽器的檔案：排著等那個網頁來拿（網頁每次問的時候就會拿到）。 */
    void queueFile(String clientId, Uri uri, Hub.Entry e) {
        Item it = new Item();
        it.to = clientId;
        it.uri = uri;
        it.entry = e;
        e.mime = app.getContentResolver().getType(uri);
        add(it);
    }

    void queueText(String clientId, String text, Hub.Entry e) {
        Item it = new Item();
        it.to = clientId;
        it.text = text;
        it.entry = e;
        add(it);
    }

    private void add(Item it) {
        synchronized (this) {
            it.id = nextId++;
            it.created = System.currentTimeMillis();
            outbox.add(it);
            notifyAll();
        }
    }

    // ------------------------------------------------------------ 伺服器

    private synchronized void start() {
        if (server != null || !wanted) return;
        error = null;
        for (int p = PORT; p < PORT + 5; p++) {
            try {
                final ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                port = p;
                addrAt = 0;
                server = s;
                Thread t = new Thread(() -> accept(s), "pocketdrop-web");
                t.setDaemon(true);
                t.start();
                hub.changed();
                return;
            } catch (IOException ignored) {
            }
        }
        wanted = false;
        error = T("網頁分享開不起來（連接埠被占用了），請重開 App 再試一次", "Couldn't start web sharing (the port is busy). Restart the app and try again");
        hub.changed();
    }

    private synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        notifyAll();  // 叫醒等著的長輪詢
    }

    private void accept(ServerSocket s) {
        while (server == s) {
            try {
                final Socket c = s.accept();
                pool.execute(() -> serve(c));
            } catch (IOException e) {
                if (server != s) return;
            }
        }
    }

    /** 一條連線只處理一個請求（回完就關），簡單又不會卡住。 */
    private static final class Req {
        String method, path;
        final Map<String, String> q = new HashMap<>(), h = new HashMap<>();
        InputStream in;
        OutputStream out;

        String q(String k) {
            String v = q.get(k);
            return v == null ? "" : v;
        }

        long num(String k, long def) {
            try {
                return Long.parseLong(q(k));
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            if (!allowed(s.getInetAddress())) return;
            s.setSoTimeout(120000);
            Req r = new Req();
            r.in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            r.out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String line = readLine(r.in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            r.method = parts[0];
            String target = parts[1];
            int qi = target.indexOf('?');
            r.path = qi < 0 ? target : target.substring(0, qi);
            if (qi >= 0) {
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.q.put(decode(kv.substring(0, e)), decode(kv.substring(e + 1)));
                }
            }
            for (String l; (l = readLine(r.in)) != null && !l.isEmpty(); ) {
                int c = l.indexOf(':');
                if (c > 0) r.h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
            }
            handle(r);
            r.out.flush();
        } catch (Exception ignored) {
        }
    }

    /** 只讓區網（Wi-Fi、熱點、USB）裡的裝置連；純有線模式時只讓 USB 線另一頭的電腦連。 */
    private boolean allowed(InetAddress a) {
        if (a == null) return false;
        if (hub.usbOnly) return Hub.isUsbHost(a.getHostAddress());
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    private void handle(Req r) throws IOException, JSONException {
        switch (r.path) {
            case "/":
            case "/web":
                reply(r, 200, "text/html; charset=utf-8", page());
                return;
            case "/icon.png":
                reply(r, 200, "image/png", asset("icon.png"));
                return;
            case "/api/hello":
                hello(r);
                return;
        }
        Client c = auth(r);
        if (c == null) {
            json(r, 401, new JSONObject().put("ok", false).put("error", "not paired"));
            return;
        }
        switch (r.path) {
            case "/api/ping":
                json(r, 200, base());
                return;
            case "/api/poll":
                poll(r, c);
                return;
            case "/api/done":
                done(r, c);
                return;
            case "/api/file":
                file(r, c);
                return;
            case "/api/upload":
                upload(r, c);
                return;
            case "/api/text":
                text(r, c);
                return;
        }
        json(r, 404, new JSONObject().put("ok", false));
    }

    private JSONObject base() throws JSONException {
        String v = "";
        try {
            v = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return new JSONObject().put("ok", true).put("name", hub.deviceName()).put("id", "phone-" + hub.deviceId())
                .put("version", v).put("host", "phone");
    }

    private Client auth(Req r) {
        String k = r.h.get("x-key");
        if (k == null || k.isEmpty()) k = r.q("k");
        if (k.isEmpty()) return null;
        byte[] kb = k.getBytes(StandardCharsets.UTF_8);
        Client found = null;
        synchronized (this) {
            for (Client c : clients.values()) {
                if (MessageDigest.isEqual(kb, c.key.getBytes(StandardCharsets.UTF_8))) found = c;
            }
        }
        if (found == null) return null;
        boolean was = found.online();
        found.seen = System.currentTimeMillis();
        if (!was) {
            autoTarget(found);
            hub.changed();
        }
        return found;
    }

    /** 手機沒連著電腦時，網頁一連上就把「傳給」改成它，省得再選一次。 */
    private void autoTarget(Client c) {
        if (hub.state != Hub.CONNECTED && hub.sendTo == null) hub.setSendTo("web:" + c.id, c.name);
    }

    /** 瀏覽器第一次來：在手機上問要不要允許（一次問一個）。 */
    private void hello(Req r) throws IOException, JSONException {
        String did = r.h.get("x-device-id");
        String name = decode(r.h.containsKey("x-device-name") ? r.h.get("x-device-name") : "").trim();
        if (did == null || did.trim().isEmpty()) {
            json(r, 400, new JSONObject().put("ok", false));
            return;
        }
        did = did.trim();
        if (did.length() > 64) did = did.substring(0, 64);
        if (name.isEmpty()) name = T("瀏覽器", "Browser");
        if (name.length() > 40) name = name.substring(0, 40);
        Client c = client(did);
        if (c == null) {
            synchronized (askLock) {
                c = client(did);
                if (c == null) {
                    Ask a = new Ask(name);
                    ask = a;
                    hub.changed();
                    long end = System.currentTimeMillis() + 60000;
                    while (a.answer == null && System.currentTimeMillis() < end && server != null) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    ask = null;
                    hub.changed();
                    if (!Boolean.TRUE.equals(a.answer)) {
                        json(r, 403, new JSONObject().put("ok", false).put("error", "denied"));
                        return;
                    }
                    c = new Client();
                    c.id = did;
                    c.name = name;
                    byte[] b = new byte[24];
                    random.nextBytes(b);
                    StringBuilder sb = new StringBuilder();
                    for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
                    c.key = sb.toString();
                    synchronized (this) {
                        clients.put(did, c);
                    }
                    save();
                }
            }
        } else if (!name.equals(c.name)) {
            c.name = name;
            save();
        }
        c.seen = System.currentTimeMillis();
        autoTarget(c);
        hub.changed();
        json(r, 200, base().put("key", c.key));
    }

    /** 網頁一直來問「有沒有東西給我」：有就馬上回，沒有就等最多 25 秒。 */
    private void poll(Req r, Client c) throws IOException, JSONException {
        long wait = Math.max(0, Math.min(25, r.num("wait", 0))) * 1000;
        long after = r.num("after", 0);
        long end = System.currentTimeMillis() + wait;
        JSONArray items = new JSONArray();
        synchronized (this) {
            while (true) {
                expire();
                for (Item it : outbox) {
                    if (!it.to.equals(c.id) || it.id <= after) continue;
                    JSONObject o = new JSONObject().put("id", it.id);
                    if (it.text != null) {
                        o.put("type", "text").put("text", it.text);
                    } else {
                        o.put("type", "file").put("name", it.entry.name).put("size", it.entry.total);
                    }
                    items.put(o);
                }
                long left = end - System.currentTimeMillis();
                if (items.length() > 0 || left <= 0 || server == null) break;
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        c.seen = System.currentTimeMillis();
        json(r, 200, base().put("items", items));
    }

    /** 放太久沒人拿的就不等了。（要拿著 this 鎖） */
    private void expire() {
        long now = System.currentTimeMillis();
        for (Iterator<Item> i = outbox.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (now - it.created > EXPIRE_MS) {
                i.remove();
                hub.failEntry(it.entry, T("對方的網頁一直沒有來接收", "The browser never picked it up"));
            }
        }
    }

    private synchronized Item take(Client c, long id, boolean remove) {
        for (Iterator<Item> i = outbox.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (it.id == id && it.to.equals(c.id)) {
                if (remove) i.remove();
                return it;
            }
        }
        return null;
    }

    private void done(Req r, Client c) throws IOException, JSONException {
        Item it = take(c, r.num("id", -1), true);
        if (it != null) {
            if ("1".equals(r.q("ok"))) hub.okEntry(it.entry);
            else hub.failEntry(it.entry, T("對方沒有收成功", "The browser didn't get it"));
        }
        json(r, 200, new JSONObject().put("ok", true));
    }

    /** 網頁按「下載」：把手機上的檔案傳過去；傳完整了（而且不是預覽）才算送達。 */
    private void file(Req r, Client c) throws IOException, JSONException {
        Item it = take(c, r.num("id", -1), false);
        if (it == null || it.uri == null) {
            json(r, 404, new JSONObject().put("ok", false));
            return;
        }
        Hub.Entry e = it.entry;
        boolean inline = "1".equals(r.q("inline"));
        String name = e.name.replaceAll("[\\r\\n\"]", "_");
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_");
        StringBuilder head = new StringBuilder("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(e.mime != null ? e.mime : "application/octet-stream").append("\r\n");
        if (e.total >= 0) head.append("Content-Length: ").append(e.total).append("\r\n");
        head.append("Content-Disposition: ").append(inline ? "inline" : "attachment").append("; filename=\"").append(ascii)
                .append("\"; filename*=UTF-8''").append(URLEncoder.encode(name, "UTF-8").replace("+", "%20")).append("\r\n");
        head.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        InputStream in;
        try {
            in = app.getContentResolver().openInputStream(it.uri);
        } catch (Exception ex) {
            in = null;
        }
        if (in == null) {
            take(c, it.id, true);
            hub.failEntry(e, T("讀不到這個檔案了", "Can't read this file any more"));
            json(r, 404, new JSONObject().put("ok", false));
            return;
        }
        long sent = 0;
        try (InputStream src = in) {
            r.out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (!inline) hub.startEntry(e);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = src.read(buf)) > 0) {
                r.out.write(buf, 0, n);
                sent += n;
                if (!inline) {
                    e.done = sent;
                    hub.changed();
                }
            }
            r.out.flush();
        } catch (IOException ex) {
            if (!inline) hub.waitEntry(e, T("對方的下載中斷了，網頁上可以再按一次「下載」", "The download stopped; tap Download again on the web page"));
            throw ex;
        }
        if (!inline && "1".equals(r.q("done"))) {
            take(c, it.id, true);
            hub.okEntry(e);
        }
    }

    /** 網頁傳檔案過來：分段（一段最多 32 MB）接起來，存到「下載/PocketDrop」。 */
    private void upload(Req r, Client c) throws IOException, JSONException {
        long len;
        try {
            len = Long.parseLong(r.h.containsKey("content-length") ? r.h.get("content-length") : "-1");
        } catch (NumberFormatException e) {
            len = -1;
        }
        if (len < 0) {
            json(r, 411, new JSONObject().put("ok", false));
            return;
        }
        String uid = r.q("uid");
        long size = r.num("size", -1), offset = r.num("offset", 0);
        Upload u;
        synchronized (uploads) {
            purgeUploads();
            u = uid.isEmpty() ? null : uploads.get(uid);
            if (u == null) {
                if (offset != 0) {
                    json(r, 409, new JSONObject().put("ok", false).put("error", "unknown upload"));
                    return;
                }
                u = newUpload(r.q("name"), r.q("dir"), size, c);
                if (u == null) {
                    json(r, 507, new JSONObject().put("ok", false).put("error", "can't save"));
                    return;
                }
                if (!uid.isEmpty()) uploads.put(uid, u);
            }
        }
        if (u.received != offset) {
            json(r, 409, new JSONObject().put("ok", false).put("error", "offset"));
            return;
        }
        Hub.Entry e = u.entry;
        try {
            byte[] buf = new byte[256 * 1024];
            long left = len;
            while (left > 0) {
                int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("short");
                u.out.write(buf, 0, n);
                left -= n;
                u.received += n;
                e.done = u.received;
                hub.changed();
            }
            u.touched = System.currentTimeMillis();
        } catch (IOException ex) {
            synchronized (uploads) {
                uploads.remove(uid);
            }
            drop(u);
            hub.failEntry(e, T("傳到一半斷掉了", "The connection dropped"));
            throw ex;
        }
        if (size < 0 || u.received >= size) {
            synchronized (uploads) {
                uploads.remove(uid);
            }
            try {
                u.out.close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                app.getContentResolver().update(u.uri, v, null, null);
                e.uri = u.uri;
                e.mime = u.mime;
                e.total = u.received;
                hub.okEntry(e);
            } catch (Exception ex) {
                drop(u);
                hub.failEntry(e, T("手機存不下這個檔案", "Couldn't save the file on this phone"));
                json(r, 500, new JSONObject().put("ok", false));
                return;
            }
        }
        json(r, 200, new JSONObject().put("ok", true));
    }

    private Upload newUpload(String name, String dir, long size, Client c) {
        StringBuilder sub = new StringBuilder();
        for (String s : dir.split("[/\\\\]")) {
            s = Hub.clean(s);
            if (!s.isEmpty() && !s.equals(".") && !s.equals("..")) sub.append('/').append(s);
        }
        String fileName = Hub.clean(name.replaceAll(".*[/\\\\]", ""));
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) fileName = T("未命名", "untitled");
        int dot = fileName.lastIndexOf('.');
        String mime = dot >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        if (mime == null) mime = "application/octet-stream";
        ContentResolver cr = app.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Hub.FOLDER + sub);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Upload u = new Upload();
        try {
            u.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u.uri == null) return null;
            u.out = cr.openOutputStream(u.uri);
            if (u.out == null) {
                cr.delete(u.uri, null, null);
                return null;
            }
        } catch (Exception ex) {
            return null;
        }
        u.mime = mime;
        u.size = size;
        u.touched = System.currentTimeMillis();
        Hub.Entry e = new Hub.Entry(Hub.Entry.DOWN);
        e.name = (sub.length() > 0 ? sub.substring(1) + "/" : "") + fileName;
        e.peer = c.name;
        e.total = size;
        e.web = true;
        u.entry = e;
        hub.addEntry(e);
        hub.startEntry(e);
        return u;
    }

    /** 傳到一半就沒下文的（30 分鐘），刪掉沒傳完的檔案。（要拿著 uploads 鎖） */
    private void purgeUploads() {
        long now = System.currentTimeMillis();
        for (Iterator<Upload> i = uploads.values().iterator(); i.hasNext(); ) {
            Upload u = i.next();
            if (now - u.touched > EXPIRE_MS) {
                i.remove();
                drop(u);
                hub.failEntry(u.entry, T("對方沒有傳完", "The browser didn't finish sending"));
            }
        }
    }

    private void drop(Upload u) {
        try {
            u.out.close();
        } catch (Exception ignored) {
        }
        try {
            app.getContentResolver().delete(u.uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private void text(Req r, Client c) throws IOException, JSONException {
        long len;
        try {
            len = Long.parseLong(r.h.containsKey("content-length") ? r.h.get("content-length") : "-1");
        } catch (NumberFormatException e) {
            len = -1;
        }
        if (len < 0 || len > 1024 * 1024) {
            json(r, 413, new JSONObject().put("ok", false));
            return;
        }
        byte[] b = new byte[(int) len];
        int got = 0;
        while (got < len) {
            int n = r.in.read(b, got, (int) len - got);
            if (n < 0) throw new IOException("short");
            got += n;
        }
        String text = new String(b, StandardCharsets.UTF_8);
        Hub.Entry e = new Hub.Entry(Hub.Entry.TEXT_IN);
        e.text = text;
        e.peer = c.name;
        e.web = true;
        e.state = Hub.Entry.OK;
        hub.addEntry(e);
        hub.copyToClipboard(text);
        json(r, 200, new JSONObject().put("ok", true));
    }

    // ------------------------------------------------------------ 小工具

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Client c : clients()) a.put(new JSONObject().put("id", c.id).put("name", c.name).put("key", c.key));
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("web_clients", a.toString()).apply();
    }

    /** 網頁跟電腦版的 /web 是同一頁，只是告訴它「對方是手機」。 */
    private synchronized byte[] page() throws IOException {
        if (page == null) {
            String html = new String(asset("web.html"), StandardCharsets.UTF_8);
            page = html.replace("const ON_PHONE = false;", "const ON_PHONE = true;").getBytes(StandardCharsets.UTF_8);
        }
        return page;
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (ch < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void json(Req r, int code, JSONObject o) throws IOException {
        reply(r, code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(Req r, int code, String ctype, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + ctype + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        r.out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        r.out.write(body);
    }
}
