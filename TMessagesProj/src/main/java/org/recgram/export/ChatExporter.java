package org.recgram.export;

import static org.recgram.RLocale.s;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.recgram.Protect;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Экспорт истории чата или канала в Documents/RecGram/Export — по образцу Telegram Desktop:
 * messages.html, messages2.html … (по 1000 сообщений), result.json и папки
 * photos/, video_files/, voice_messages/, round_video_messages/, stickers/, files/.
 * Работает в своём потоке; одновременно идёт только один экспорт.
 */
public final class ChatExporter implements NotificationCenter.NotificationCenterDelegate {

    public static final int PHOTOS = 1, VIDEOS = 2, VOICE = 4, ROUND = 8, STICKERS = 16, GIFS = 32, FILES = 64, MUSIC = 128;

    public static final class Options {
        public boolean html = true;
        public boolean json = true;
        public int media = PHOTOS | VOICE | ROUND | STICKERS | GIFS;
        /** Файлы больше этого размера не скачиваются (0 — без ограничения). */
        public long maxFileSize = 50L * 1024 * 1024;
    }

    private static final String CHANNEL = "recgram_export";
    private static final int NOTIFICATION_ID = 0x52454358;
    private static final int PAGE = 1000;
    private static final String NOT_INCLUDED = "(File not included. Change data exporting settings to download.)";

    private static volatile ChatExporter current;

    public static ChatExporter current() {
        return current;
    }

    public final int account;
    public final long dialogId;
    public final String title;
    private final Options opt;
    private final ExportOut out;

    public volatile int messages;
    public volatile int files;
    public volatile int filesSkipped;
    public volatile int filesFailed;
    public volatile boolean finished;
    public volatile boolean cancelled;
    public volatile String error;

    private final Map<String, String> users = new HashMap<>();
    private final ConcurrentHashMap<String, Object[]> waiting = new ConcurrentHashMap<>();
    private final SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileFmt = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US);
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat fullFmt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);

    private ChatExporter(int account, long dialogId, String title, Options opt) {
        this.account = account;
        this.dialogId = dialogId;
        this.title = title;
        this.opt = opt;
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        this.out = new ExportOut("ChatExport_" + ExportOut.sanitize(title) + "_" + stamp);
    }

    /** Название чата для показа и имени папки. */
    public static String titleOf(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User u = mc.getUser(dialogId);
            return u == null ? String.valueOf(dialogId) : UserObject.isUserSelf(u) ? s("Избранное", "Saved Messages") : UserObject.getUserName(u);
        }
        TLRPC.Chat c = mc.getChat(-dialogId);
        return c == null ? String.valueOf(-dialogId) : c.title;
    }

    /** Защищённый чат можно экспортировать, только если в RecGram разрешено сохранение защищённого контента. */
    public static boolean isBlockedByProtection(int account, long dialogId) {
        if (dialogId >= 0 || Protect.copySave()) {
            return false;
        }
        TLRPC.Chat c = MessagesController.getInstance(account).getChat(-dialogId);
        return c != null && c.noforwards;
    }

    public static synchronized ChatExporter start(int account, long dialogId, Options opt) {
        if (current != null && !current.finished) {
            return null;
        }
        ChatExporter e = new ChatExporter(account, dialogId, titleOf(account, dialogId), opt);
        current = e;
        new Thread(e::run, "RecGramExport").start();
        return e;
    }

    public void cancel() {
        cancelled = true;
    }

    public String folder() {
        return out.relDir;
    }

    // ---------------------------------------------------------------- основной цикл

    private void run() {
        File tmpJson = null;
        BufferedWriter json = null;
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        });
        try {
            notifyProgress();
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
            if (peer == null) {
                throw new IllegalStateException("no peer");
            }
            if (opt.json) {
                tmpJson = new File(ApplicationLoader.applicationContext.getCacheDir(), "recgram_export.json");
                json = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpJson), StandardCharsets.UTF_8));
                JSONObject head = new JSONObject();
                head.put("name", title);
                head.put("type", chatType());
                head.put("id", Math.abs(dialogId));
                String h = head.toString(1);
                json.write(h.substring(0, h.lastIndexOf('}')).trim());
                json.write(",\n \"messages\": [");
            }
            Html html = opt.html ? new Html() : null;
            boolean firstJson = true;
            int lastId = 0;
            while (!cancelled) {
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.offset_id = lastId + 1;
                req.add_offset = -100;
                req.limit = 100;
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) request(req);
                if (res == null) {
                    break;
                }
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                for (TLRPC.User u : res.users) {
                    users.put("user" + u.id, UserObject.getUserName(u));
                }
                for (TLRPC.Chat c : res.chats) {
                    users.put((ChatObject.isChannel(c) ? "channel" : "chat") + c.id, c.title);
                }
                ArrayList<TLRPC.Message> batch = new ArrayList<>();
                for (TLRPC.Message m : res.messages) {
                    if (m.id > lastId && !(m instanceof TLRPC.TL_messageEmpty)) {
                        batch.add(m);
                    }
                }
                if (batch.isEmpty()) {
                    break;
                }
                Collections.sort(batch, (a, b) -> Integer.compare(a.id, b.id));
                for (TLRPC.Message m : batch) {
                    if (cancelled) {
                        break;
                    }
                    lastId = Math.max(lastId, m.id);
                    Item it = convert(m);
                    if (json != null) {
                        json.write(firstJson ? "\n  " : ",\n  ");
                        json.write(it.json.toString());
                        firstJson = false;
                    }
                    if (html != null) {
                        html.add(it);
                    }
                    messages++;
                    if (messages % 50 == 0) {
                        notifyProgress();
                    }
                }
            }
            if (html != null) {
                html.finish();
            }
            if (json != null) {
                json.write("\n ]\n}\n");
                json.close();
                json = null;
                out.copy(tmpJson, "", "result.json");
            }
        } catch (Throwable e) {
            FileLog.e(e);
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            try {
                if (json != null) {
                    json.close();
                }
            } catch (Throwable ignore) {
            }
            if (tmpJson != null) {
                tmpJson.delete();
            }
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
            });
            finished = true;
            notifyProgress();
        }
    }

    private String chatType() {
        if (dialogId > 0) {
            TLRPC.User u = MessagesController.getInstance(account).getUser(dialogId);
            if (u != null && UserObject.isUserSelf(u)) return "saved_messages";
            return u != null && u.bot ? "bot_chat" : "personal_chat";
        }
        TLRPC.Chat c = MessagesController.getInstance(account).getChat(-dialogId);
        if (c == null) return "private_group";
        boolean pub = ChatObject.isPublic(c);
        if (ChatObject.isChannelAndNotMegaGroup(c)) return pub ? "public_channel" : "private_channel";
        if (ChatObject.isChannel(c)) return pub ? "public_supergroup" : "private_supergroup";
        return "private_group";
    }

    /** Синхронный запрос с повтором при FLOOD_WAIT. */
    private TLObject request(TLObject req) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && !cancelled; attempt++) {
            final TLObject[] resp = new TLObject[1];
            final TLRPC.TL_error[] err = new TLRPC.TL_error[1];
            CountDownLatch latch = new CountDownLatch(1);
            ConnectionsManager.getInstance(account).sendRequest(req, (r, e) -> {
                resp[0] = r;
                err[0] = e;
                latch.countDown();
            });
            latch.await();
            if (err[0] == null) {
                return resp[0];
            }
            String t = err[0].text == null ? "" : err[0].text;
            if (t.startsWith("FLOOD_WAIT_")) {
                int sec = 5;
                try {
                    sec = Integer.parseInt(t.substring("FLOOD_WAIT_".length()));
                } catch (NumberFormatException ignore) {
                }
                Thread.sleep(Math.min(sec, 300) * 1000L + 500);
                continue;
            }
            throw new IllegalStateException(t);
        }
        return null;
    }

    // ---------------------------------------------------------------- сообщение → данные

    /** Одно сообщение в обоих форматах. */
    private static final class Item {
        TLRPC.Message m;
        JSONObject json;
        boolean service;
        String fromId;
        String fromName;
        String text;               // для службы — готовый текст
        ArrayList<JSONObject> entities = new ArrayList<>();
        String mediaKind;           // photo, video_file, voice_message, round_video_message, sticker, animation, audio_file, file
        String mediaPath;           // относительный путь или null
        String mediaName;
        String mediaNote;           // подпись для неподдерживаемых типов (опрос, геопозиция …)
        String forwardedFrom;
        int replyTo;
    }

    private Item convert(TLRPC.Message m) throws JSONException {
        Item it = new Item();
        it.m = m;
        JSONObject j = new JSONObject();
        it.json = j;
        j.put("id", m.id);
        it.service = m instanceof TLRPC.TL_messageService || m.action != null;
        j.put("type", it.service ? "service" : "message");
        Date d = new Date(m.date * 1000L);
        j.put("date", isoFmt.format(d));
        j.put("date_unixtime", String.valueOf(m.date));
        if (m.edit_date != 0 && !m.edit_hide) {
            j.put("edited", isoFmt.format(new Date(m.edit_date * 1000L)));
            j.put("edited_unixtime", String.valueOf(m.edit_date));
        }
        it.fromId = peerKey(m.from_id != null ? m.from_id : m.peer_id);
        it.fromName = nameOf(it.fromId);
        if (m.post_author != null && !m.post_author.isEmpty() && m.from_id == null) {
            j.put("author", m.post_author);
        }
        MessageObject mo = null;
        try {
            mo = new MessageObject(account, m, false, false);
        } catch (Throwable ignore) {
        }
        if (it.service) {
            j.put("actor", it.fromName);
            j.put("actor_id", it.fromId);
            it.text = mo != null && mo.messageText != null ? mo.messageText.toString() : "";
            j.put("action", it.text);
            return it;
        }
        j.put("from", it.fromName);
        j.put("from_id", it.fromId);
        if (m.fwd_from != null) {
            it.forwardedFrom = m.fwd_from.from_name != null ? m.fwd_from.from_name
                    : m.fwd_from.from_id != null ? nameOf(peerKey(m.fwd_from.from_id)) : null;
            if (it.forwardedFrom != null) {
                j.put("forwarded_from", it.forwardedFrom);
            }
        }
        if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
            it.replyTo = m.reply_to.reply_to_msg_id;
            j.put("reply_to_message_id", it.replyTo);
        }
        if (m.via_bot_id != 0) {
            String bot = nameOf("user" + m.via_bot_id);
            j.put("via_bot", bot);
        }
        // текст и разметка
        String text = m.message == null ? "" : m.message;
        it.text = text;
        buildEntities(text, m.entities, it.entities);
        JSONArray ents = new JSONArray();
        StringBuilder plain = new StringBuilder();
        for (JSONObject e : it.entities) {
            ents.put(e);
            plain.append(e.optString("text"));
        }
        j.put("text", plain.toString());
        j.put("text_entities", ents);
        // вложения
        media(m, mo, it);
        return it;
    }

    private String peerKey(TLRPC.Peer p) {
        if (p == null) return "";
        if (p.user_id != 0) return "user" + p.user_id;
        if (p.channel_id != 0) return "channel" + p.channel_id;
        return "chat" + p.chat_id;
    }

    private String nameOf(String key) {
        String n = users.get(key);
        if (n != null) return n;
        MessagesController mc = MessagesController.getInstance(account);
        try {
            if (key.startsWith("user")) {
                TLRPC.User u = mc.getUser(Long.parseLong(key.substring(4)));
                if (u != null) n = UserObject.getUserName(u);
            } else if (key.startsWith("channel")) {
                TLRPC.Chat c = mc.getChat(Long.parseLong(key.substring(7)));
                if (c != null) n = c.title;
            } else if (key.startsWith("chat")) {
                TLRPC.Chat c = mc.getChat(Long.parseLong(key.substring(4)));
                if (c != null) n = c.title;
            }
        } catch (NumberFormatException ignore) {
        }
        if (n == null) n = s("Удалённый аккаунт", "Deleted Account");
        users.put(key, n);
        return n;
    }

    /** Разметка Telegram → список кусков текста как в Desktop (plain, bold, link …); вложенные сущности упрощаются. */
    private void buildEntities(String text, ArrayList<TLRPC.MessageEntity> src, ArrayList<JSONObject> dst) throws JSONException {
        ArrayList<TLRPC.MessageEntity> list = new ArrayList<>();
        if (src != null) list.addAll(src);
        Collections.sort(list, (a, b) -> Integer.compare(a.offset, b.offset));
        int pos = 0;
        for (TLRPC.MessageEntity e : list) {
            int start = Math.max(e.offset, 0), end = Math.min(e.offset + e.length, text.length());
            if (start < pos || start >= end) {
                continue;
            }
            if (start > pos) {
                dst.add(part("plain", text.substring(pos, start)));
            }
            JSONObject p = part(entityType(e), text.substring(start, end));
            if (e instanceof TLRPC.TL_messageEntityTextUrl) p.put("href", e.url);
            if (e instanceof TLRPC.TL_messageEntityPre && !TextUtils.isEmpty(e.language)) p.put("language", e.language);
            if (e instanceof TLRPC.TL_messageEntityMentionName) p.put("user_id", ((TLRPC.TL_messageEntityMentionName) e).user_id);
            dst.add(p);
            pos = end;
        }
        if (pos < text.length()) {
            dst.add(part("plain", text.substring(pos)));
        }
    }

    private static JSONObject part(String type, String text) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("text", text);
        return o;
    }

    private static String entityType(TLRPC.MessageEntity e) {
        if (e instanceof TLRPC.TL_messageEntityBold) return "bold";
        if (e instanceof TLRPC.TL_messageEntityItalic) return "italic";
        if (e instanceof TLRPC.TL_messageEntityUnderline) return "underline";
        if (e instanceof TLRPC.TL_messageEntityStrike) return "strikethrough";
        if (e instanceof TLRPC.TL_messageEntityCode) return "code";
        if (e instanceof TLRPC.TL_messageEntityPre) return "pre";
        if (e instanceof TLRPC.TL_messageEntityTextUrl) return "text_link";
        if (e instanceof TLRPC.TL_messageEntityUrl) return "link";
        if (e instanceof TLRPC.TL_messageEntityMention) return "mention";
        if (e instanceof TLRPC.TL_messageEntityMentionName) return "mention_name";
        if (e instanceof TLRPC.TL_messageEntityHashtag) return "hashtag";
        if (e instanceof TLRPC.TL_messageEntityCashtag) return "cashtag";
        if (e instanceof TLRPC.TL_messageEntityBotCommand) return "bot_command";
        if (e instanceof TLRPC.TL_messageEntityEmail) return "email";
        if (e instanceof TLRPC.TL_messageEntityPhone) return "phone";
        if (e instanceof TLRPC.TL_messageEntitySpoiler) return "spoiler";
        if (e instanceof TLRPC.TL_messageEntityBlockquote) return "blockquote";
        if (e instanceof TLRPC.TL_messageEntityCustomEmoji) return "custom_emoji";
        if (e instanceof TLRPC.TL_messageEntityBankCard) return "bank_card";
        return "unknown";
    }

    // ---------------------------------------------------------------- вложения

    private void media(TLRPC.Message m, MessageObject mo, Item it) throws JSONException {
        JSONObject j = it.json;
        TLRPC.MessageMedia media = m.media;
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
            return;
        }
        String date = fileFmt.format(new Date(m.date * 1000L));
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 2560);
            it.mediaKind = "photo";
            if (size != null) {
                j.put("width", size.w);
                j.put("height", size.h);
                String name = out.uniqueName("photos", "photo_" + m.id + "@" + date + ".jpg");
                String path = save(m, mo, size, media.photo, PHOTOS, size.size, "photos", name);
                it.mediaPath = path;
                j.put("photo", path != null ? path : NOT_INCLUDED);
            }
            return;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document doc = media.document;
            String sub;
            int flag;
            String prefix;
            if (MessageObject.isVoiceDocument(doc)) {
                it.mediaKind = "voice_message"; sub = "voice_messages"; flag = VOICE; prefix = "audio";
            } else if (MessageObject.isRoundVideoDocument(doc)) {
                it.mediaKind = "video_message"; sub = "round_video_messages"; flag = ROUND; prefix = "file";
            } else if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true) || MessageObject.isVideoStickerDocument(doc)) {
                it.mediaKind = "sticker"; sub = "stickers"; flag = STICKERS; prefix = "sticker";
            } else if (MessageObject.isGifDocument(doc)) {
                it.mediaKind = "animation"; sub = "video_files"; flag = GIFS; prefix = "animation";
            } else if (MessageObject.isVideoDocument(doc)) {
                it.mediaKind = "video_file"; sub = "video_files"; flag = VIDEOS; prefix = "video";
            } else if (MessageObject.isMusicDocument(doc)) {
                it.mediaKind = "audio_file"; sub = "files"; flag = MUSIC; prefix = "audio";
            } else {
                it.mediaKind = "file"; sub = "files"; flag = FILES; prefix = "file";
            }
            String orig = FileLoader.getDocumentFileName(doc);
            String name = TextUtils.isEmpty(orig) ? null : orig;
            if (name == null || "voice_message".equals(it.mediaKind) || "video_message".equals(it.mediaKind) || "sticker".equals(it.mediaKind)) {
                name = prefix + "_" + m.id + "@" + date + "." + extension(doc);
            }
            name = out.uniqueName(sub, name);
            it.mediaName = TextUtils.isEmpty(orig) ? name : orig;
            if (!TextUtils.isEmpty(orig)) j.put("file_name", orig);
            j.put("media_type", it.mediaKind);
            if (doc.mime_type != null) j.put("mime_type", doc.mime_type);
            j.put("file_size", doc.size);
            for (TLRPC.DocumentAttribute a : doc.attributes) {
                if (a instanceof TLRPC.TL_documentAttributeVideo) {
                    j.put("duration_seconds", (int) a.duration);
                    j.put("width", a.w);
                    j.put("height", a.h);
                } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                    j.put("duration_seconds", (int) a.duration);
                    if (!TextUtils.isEmpty(a.performer)) j.put("performer", a.performer);
                    if (!TextUtils.isEmpty(a.title)) j.put("title", a.title);
                } else if (a instanceof TLRPC.TL_documentAttributeSticker && a.alt != null) {
                    j.put("sticker_emoji", a.alt);
                }
            }
            String path = save(m, mo, doc, null, flag, doc.size, sub, name);
            it.mediaPath = path;
            j.put("file", path != null ? path : NOT_INCLUDED);
            return;
        }
        // опросы, геопозиции, контакты и прочее — текстом
        String note = mo != null && mo.messageText != null ? mo.messageText.toString() : "";
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            if (media.geo != null) {
                JSONObject loc = new JSONObject();
                loc.put("latitude", media.geo.lat);
                loc.put("longitude", media.geo._long);
                j.put("location_information", loc);
                note = "📍 " + String.format(Locale.US, "%.6f, %.6f", media.geo.lat, media.geo._long)
                        + (TextUtils.isEmpty(media.title) ? "" : " — " + media.title);
            }
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            JSONObject c = new JSONObject();
            c.put("first_name", media.first_name);
            c.put("last_name", media.last_name);
            c.put("phone_number", media.phone_number);
            j.put("contact_information", c);
            note = "👤 " + (media.first_name + " " + media.last_name).trim() + " " + media.phone_number;
        }
        if (!TextUtils.isEmpty(note)) {
            it.mediaNote = note;
            j.put("media_note", note);
        }
    }

    private static String extension(TLRPC.Document doc) {
        String n = FileLoader.getDocumentFileName(doc);
        int dot = n == null ? -1 : n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) return n.substring(dot + 1);
        if (doc.mime_type != null) {
            switch (doc.mime_type) {
                case "audio/ogg": return "ogg";
                case "video/mp4": return "mp4";
                case "image/webp": return "webp";
                case "application/x-tgsticker": return "tgs";
                case "video/webm": return "webm";
            }
            String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(doc.mime_type);
            if (e != null) return e;
        }
        return "bin";
    }

    /** Скачивает вложение (если выбрано и не слишком большое) и копирует в папку экспорта. Возвращает относительный путь. */
    private String save(TLRPC.Message m, MessageObject mo, TLObject attach, TLRPC.Photo photo, int flag, long size,
                        String sub, String name) {
        if ((opt.media & flag) == 0 || opt.maxFileSize > 0 && size > opt.maxFileSize || Protect.nf(m) || mo == null) {
            filesSkipped++;
            return null;
        }
        try {
            File f = download(mo, attach, photo);
            if (f == null) {
                filesFailed++;
                return null;
            }
            out.copy(f, sub, name);
            files++;
            notifyProgress();
            return sub + "/" + name;
        } catch (Throwable e) {
            FileLog.e(e);
            filesFailed++;
            return null;
        }
    }

    private File download(MessageObject mo, TLObject attach, TLRPC.Photo photo) throws InterruptedException {
        FileLoader fl = FileLoader.getInstance(account);
        File f = existing(fl.getPathToAttach(attach, false));
        if (f == null) f = existing(fl.getPathToAttach(attach, true));
        if (f == null && photo == null) f = existing(fl.getPathToMessage(mo.messageOwner));
        if (f != null) {
            return f;
        }
        String key = FileLoader.getAttachFileName(attach);
        Object[] slot = new Object[]{new CountDownLatch(1), null};
        waiting.put(key, slot);
        AndroidUtilities.runOnUIThread(() -> {
            if (photo != null) {
                fl.loadFile(ImageLocation.getForPhoto((TLRPC.PhotoSize) attach, photo), mo, "jpg", FileLoader.PRIORITY_NORMAL, 0);
            } else {
                fl.loadFile((TLRPC.Document) attach, mo, FileLoader.PRIORITY_NORMAL, 0);
            }
        });
        CountDownLatch latch = (CountDownLatch) slot[0];
        long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
        while (!latch.await(1, TimeUnit.SECONDS)) {
            if (cancelled || System.currentTimeMillis() > deadline) {
                waiting.remove(key);
                return null;
            }
        }
        waiting.remove(key);
        if (slot[1] instanceof File) {
            return existing((File) slot[1]);
        }
        f = existing(fl.getPathToAttach(attach, false));
        return f != null ? f : existing(fl.getPathToAttach(attach, true));
    }

    private static File existing(File f) {
        return f != null && f.exists() && f.length() > 0 ? f : null;
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        Object[] slot = waiting.get((String) args[0]);
        if (slot == null) {
            return;
        }
        if (id == NotificationCenter.fileLoaded && args.length > 1) {
            slot[1] = args[1];
        }
        ((CountDownLatch) slot[0]).countDown();
    }

    // ---------------------------------------------------------------- HTML

    private final class Html {
        private StringBuilder page;
        private int pageNo = 1;
        private int onPage;
        private String lastDay;
        private String lastFrom;
        private int lastDate;

        Html() {
            begin();
        }

        private String pageName(int n) {
            return n == 1 ? "messages.html" : "messages" + n + ".html";
        }

        private void begin() {
            page = new StringBuilder(256 * 1024);
            page.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n<title>").append(esc(title))
                    .append("</title>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n<style>")
                    .append(CSS).append("</style>\n</head>\n<body>\n<div class=\"page_wrap\">\n<div class=\"page_header\"><div class=\"text bold\">")
                    .append(esc(title)).append("</div></div>\n<div class=\"history\">\n");
            if (pageNo > 1) {
                page.append("<a class=\"pagination\" href=\"").append(pageName(pageNo - 1)).append("\">")
                        .append(s("Предыдущие сообщения", "Previous messages")).append("</a>\n");
            }
            onPage = 0;
            lastFrom = null;
            lastDay = null;
        }

        void add(Item it) throws java.io.IOException {
            if (onPage >= PAGE) {
                flush(true);
                pageNo++;
                begin();
            }
            onPage++;
            Date d = new Date(it.m.date * 1000L);
            String day = dayFmt.format(d);
            if (!day.equals(lastDay)) {
                page.append("<div class=\"message service\"><div class=\"body details\">").append(esc(day)).append("</div></div>\n");
                lastDay = day;
                lastFrom = null;
            }
            if (it.service) {
                page.append("<div class=\"message service\" id=\"message").append(it.m.id).append("\"><div class=\"body details\">")
                        .append(esc(it.text)).append("</div></div>\n");
                lastFrom = null;
                return;
            }
            boolean joined = it.fromId.equals(lastFrom) && it.m.date - lastDate < 900 && it.forwardedFrom == null;
            page.append("<div class=\"message default clearfix").append(joined ? " joined" : "").append("\" id=\"message").append(it.m.id).append("\">\n");
            if (!joined) {
                String n = it.fromName == null ? "?" : it.fromName;
                page.append("<div class=\"userpic\" style=\"background:").append(color(it.fromId)).append("\">")
                        .append(esc(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()))).append("</div>\n");
            }
            page.append("<div class=\"body\">\n<div class=\"date details\" title=\"").append(fullFmt.format(d)).append("\">")
                    .append(timeFmt.format(d)).append("</div>\n");
            if (!joined) {
                page.append("<div class=\"from_name\">").append(esc(it.fromName));
                String author = it.json.optString("author", "");
                if (!author.isEmpty()) page.append(" <span class=\"details\">").append(esc(author)).append("</span>");
                page.append("</div>\n");
            }
            if (it.forwardedFrom != null) {
                page.append("<div class=\"forwarded\">").append(s("Переслано от ", "Forwarded from ")).append(esc(it.forwardedFrom)).append("</div>\n");
            }
            if (it.replyTo != 0) {
                page.append("<div class=\"reply_to details\">").append(s("В ответ на ", "In reply to ")).append("<a href=\"#message")
                        .append(it.replyTo).append("\">").append(s("это сообщение", "this message")).append("</a></div>\n");
            }
            mediaHtml(it);
            if (!it.entities.isEmpty()) {
                page.append("<div class=\"text\">");
                for (JSONObject p : it.entities) {
                    page.append(entityHtml(p));
                }
                page.append("</div>\n");
            }
            page.append("</div>\n</div>\n");
            lastFrom = it.fromId;
            lastDate = it.m.date;
        }

        private void mediaHtml(Item it) {
            if (it.mediaNote != null) {
                page.append("<div class=\"media details\">").append(esc(it.mediaNote)).append("</div>\n");
                return;
            }
            if (it.mediaKind == null) {
                return;
            }
            String p = it.mediaPath;
            if (p == null) {
                page.append("<div class=\"media details\">").append(esc(kindTitle(it.mediaKind)))
                        .append(it.mediaName != null ? " — " + esc(it.mediaName) : "")
                        .append(s(" (не выгружено)", " (not included)")).append("</div>\n");
                return;
            }
            String href = escAttr(p);
            switch (it.mediaKind) {
                case "photo":
                    page.append("<a class=\"photo_wrap\" href=\"").append(href).append("\"><img class=\"photo\" src=\"").append(href).append("\"/></a>\n");
                    break;
                case "sticker":
                    if (p.endsWith(".webp") || p.endsWith(".png")) {
                        page.append("<img class=\"sticker\" src=\"").append(href).append("\"/>\n");
                    } else {
                        page.append("<a class=\"media\" href=\"").append(href).append("\">").append(esc(kindTitle(it.mediaKind))).append("</a>\n");
                    }
                    break;
                case "video_file":
                case "animation":
                case "video_message":
                    page.append("<video class=\"video").append("video_message".equals(it.mediaKind) ? " round" : "")
                            .append("\" controls preload=\"metadata\"").append("animation".equals(it.mediaKind) ? " loop muted" : "")
                            .append(" src=\"").append(href).append("\"></video>\n");
                    break;
                case "voice_message":
                case "audio_file":
                    page.append("<div class=\"media\">").append(esc(it.mediaName != null && "audio_file".equals(it.mediaKind) ? it.mediaName : kindTitle(it.mediaKind)))
                            .append("<br/><audio controls preload=\"none\" src=\"").append(href).append("\"></audio></div>\n");
                    break;
                default:
                    page.append("<a class=\"media file\" href=\"").append(href).append("\">📄 ").append(esc(it.mediaName != null ? it.mediaName : p)).append("</a>\n");
            }
        }

        private String entityHtml(JSONObject p) {
            String t = esc(p.optString("text")).replace("\n", "<br/>");
            switch (p.optString("type")) {
                case "bold": return "<strong>" + t + "</strong>";
                case "italic": return "<em>" + t + "</em>";
                case "underline": return "<u>" + t + "</u>";
                case "strikethrough": return "<s>" + t + "</s>";
                case "code": return "<code>" + t + "</code>";
                case "pre": return "<pre>" + t + "</pre>";
                case "spoiler": return "<span class=\"spoiler\">" + t + "</span>";
                case "blockquote": return "<blockquote>" + t + "</blockquote>";
                case "text_link": return "<a href=\"" + escAttr(p.optString("href")) + "\">" + t + "</a>";
                case "link": {
                    String u = p.optString("text");
                    if (!u.contains("://")) u = "http://" + u;
                    return "<a href=\"" + escAttr(u) + "\">" + t + "</a>";
                }
                case "mention": return "<a href=\"https://t.me/" + escAttr(p.optString("text").replace("@", "")) + "\">" + t + "</a>";
                case "email": return "<a href=\"mailto:" + escAttr(p.optString("text")) + "\">" + t + "</a>";
                case "phone": return "<a href=\"tel:" + escAttr(p.optString("text")) + "\">" + t + "</a>";
                default: return t;
            }
        }

        void finish() throws java.io.IOException {
            flush(false);
        }

        private void flush(boolean hasNext) throws java.io.IOException {
            if (hasNext) {
                page.append("<a class=\"pagination\" href=\"").append(pageName(pageNo + 1)).append("\">")
                        .append(s("Следующие сообщения", "Next messages")).append("</a>\n");
            }
            page.append("</div>\n<div class=\"footer details\">").append(s("Экспортировано RecGram ", "Exported by RecGram "))
                    .append(esc(fullFmt.format(new Date()))).append("</div>\n</div>\n</body>\n</html>\n");
            out.writeText("", pageName(pageNo), page.toString());
            page = null;
        }
    }

    private static String kindTitle(String kind) {
        switch (kind) {
            case "photo": return s("Фото", "Photo");
            case "video_file": return s("Видео", "Video");
            case "animation": return "GIF";
            case "video_message": return s("Видеосообщение", "Video message");
            case "voice_message": return s("Голосовое сообщение", "Voice message");
            case "sticker": return s("Стикер", "Sticker");
            case "audio_file": return s("Аудио", "Audio");
            default: return s("Файл", "File");
        }
    }

    private static final String[] COLORS = {"#e17076", "#7bc862", "#65aadd", "#a695e7", "#ee7aae", "#6ec9cb", "#faa774"};

    private static String color(String key) {
        return COLORS[Math.abs(key.hashCode()) % COLORS.length];
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String escAttr(String s) {
        return esc(s).replace(" ", "%20");
    }

    private static final String CSS =
            "body{margin:0;font:14px/1.4 -apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:#e7ebf0;color:#000}"
            + ".page_wrap{max-width:760px;margin:0 auto;background:#fff;min-height:100vh}"
            + ".page_header{position:sticky;top:0;background:#fff;border-bottom:1px solid #e3e6e8;padding:14px 20px;z-index:1}"
            + ".bold{font-weight:bold;font-size:16px}.history{padding:6px 0}"
            + ".message{padding:6px 20px 6px 78px;position:relative}.message.joined{padding-top:0}"
            + ".message.service{padding:10px 20px;text-align:center}.message.service .body{display:inline-block;background:#f0f2f5;border-radius:12px;padding:4px 12px}"
            + ".userpic{position:absolute;left:20px;top:8px;width:42px;height:42px;border-radius:50%;color:#fff;font-weight:bold;font-size:18px;line-height:42px;text-align:center}"
            + ".from_name{color:#3892db;font-weight:bold;margin-bottom:2px}.date{float:right;margin-left:10px}"
            + ".details{color:#70777b;font-size:12px}.forwarded{color:#3892db;font-size:13px;border-left:2px solid #3892db;padding-left:8px;margin:2px 0}"
            + ".reply_to{margin:2px 0}.text{white-space:pre-wrap;word-wrap:break-word}"
            + ".photo{max-width:320px;max-height:320px;border-radius:6px;display:block;margin:4px 0}"
            + ".sticker{max-width:160px;max-height:160px;display:block;margin:4px 0}"
            + ".video{max-width:360px;max-height:360px;border-radius:6px;display:block;margin:4px 0}.video.round{width:240px;height:240px;border-radius:50%;object-fit:cover}"
            + ".media{display:block;margin:4px 0}a{color:#168acd;text-decoration:none}a:hover{text-decoration:underline}"
            + "pre,code{font-family:Menlo,Consolas,monospace;background:#f5f5f5;border-radius:4px;padding:1px 4px}pre{padding:8px;white-space:pre-wrap}"
            + "blockquote{border-left:3px solid #3892db;margin:4px 0;padding-left:8px}.spoiler{background:#ccc;color:transparent}.spoiler:hover{color:inherit}"
            + ".pagination{display:block;text-align:center;padding:12px;font-weight:bold}.footer{text-align:center;padding:16px}";

    // ---------------------------------------------------------------- уведомление и слушатели

    public interface Listener {
        void onExportProgress(ChatExporter e);
    }

    private static final ArrayList<Listener> listeners = new ArrayList<>();

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private long lastNotify;

    private void notifyProgress() {
        long now = System.currentTimeMillis();
        if (!finished && now - lastNotify < 500) {
            return;
        }
        lastNotify = now;
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onExportProgress(this);
            }
        });
        try {
            Context ctx = ApplicationLoader.applicationContext;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, s("Экспорт чатов", "Chat export"), NotificationManager.IMPORTANCE_LOW));
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(finished ? (error != null ? s("Экспорт не удался", "Export failed") : cancelled ? s("Экспорт остановлен", "Export stopped") : s("Экспорт завершён", "Export finished"))
                            : s("Экспорт: ", "Export: ") + title)
                    .setContentText(statusLine())
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(statusLine() + (finished && error == null ? "\n" + folder() : "")))
                    .setOngoing(!finished)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            if (!finished) {
                b.setProgress(0, 0, true);
            } else if (error == null) {
                // нажатие открывает папку с экспортом
                b.setContentIntent(OpenFolderActivity.pendingIntent(ctx, folder(), NOTIFICATION_ID)).setAutoCancel(true);
            }
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public String statusLine() {
        String line = s("Сообщений: ", "Messages: ") + messages + s(", файлов: ", ", files: ") + files;
        if (filesSkipped > 0) line += s(", пропущено: ", ", skipped: ") + filesSkipped;
        if (filesFailed > 0) line += s(", ошибок: ", ", failed: ") + filesFailed;
        if (error != null) line += "\n" + error;
        return line;
    }
}
