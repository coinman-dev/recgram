package org.recgram;

import static org.recgram.RLocale.s;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * «Переотправлять»: сообщения из защищённых чатов уходят как новые (сервер не даёт их пересылать).
 * Текст отправляется заново, медиа — повторной загрузкой уже скачанного файла.
 */
public final class Resend {

    private Resend() {
    }

    /** Перехват пересылки. true — сообщения переотправлены, обычную пересылку делать не нужно. */
    public static boolean intercept(int account, ArrayList<MessageObject> messages, long peer, boolean notify, int scheduleDate) {
        if (!RecGramConfig.isAllowResendProtected() || messages == null || messages.isEmpty()) {
            return false;
        }
        boolean anyProtected = false;
        for (MessageObject m : messages) {
            if (isReallyProtected(account, m)) {
                anyProtected = true;
                break;
            }
        }
        if (!anyProtected) {
            return false;
        }
        int missing = 0;
        SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
        AccountInstance ai = AccountInstance.getInstance(account);
        for (MessageObject m : messages) {
            TLRPC.Message msg = m.messageOwner;
            if (msg == null) {
                continue;
            }
            String text = msg.message;
            boolean hasMedia = msg.media != null && !(msg.media instanceof TLRPC.TL_messageMediaEmpty)
                    && !(msg.media instanceof TLRPC.TL_messageMediaWebPage);
            if (!hasMedia) {
                if (!TextUtils.isEmpty(text)) {
                    SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(text, peer);
                    p.entities = msg.entities;
                    p.notify = notify;
                    p.scheduleDate = scheduleDate;
                    helper.sendMessage(p);
                }
                continue;
            }
            File f = FileLoader.getInstance(account).getPathToMessage(msg);
            if (f == null || !f.exists() || f.length() == 0) {
                missing++;
                continue;
            }
            String path = f.getAbsolutePath();
            if (m.isPhoto() || (m.isVideo() && !m.isRoundVideo() && !m.isGif())) {
                SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                info.path = path;
                info.isVideo = m.isVideo();
                info.caption = text;
                info.entities = msg.entities;
                ArrayList<SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
                list.add(info);
                SendMessagesHelper.prepareSendingMedia(ai, list, peer, null, null, null, null, false, false, null,
                        notify, scheduleDate, 0, 0, false, null, null, 0, false, 0, 0, null);
            } else {
                ArrayList<String> paths = new ArrayList<>();
                paths.add(path);
                SendMessagesHelper.prepareSendingDocuments(ai, paths, paths, null, text, m.getMimeType(), peer,
                        null, null, null, null, null, notify, scheduleDate, null, null, 0, false, 0);
            }
        }
        if (missing > 0) {
            final int n = missing;
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    s("Не отправлено медиа: " + n + ". Сначала откройте их, чтобы они скачались, и повторите.",
                            "Media not sent: " + n + ". Open them first so they download, then try again."),
                    Toast.LENGTH_LONG).show());
        }
        return true;
    }

    /** Настоящий запрет пересылки — без учёта разблокировки в интерфейсе RecGram. */
    private static boolean isReallyProtected(int account, MessageObject m) {
        if (m.messageOwner != null && m.messageOwner.noforwards) {
            return true;
        }
        long dialogId = m.getDialogId();
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId < 0) {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat != null && chat.migrated_to != null) {
                TLRPC.Chat to = mc.getChat(chat.migrated_to.channel_id);
                if (to != null) {
                    chat = to;
                }
            }
            return chat != null && chat.noforwards;
        }
        TLRPC.UserFull full = mc.getUserFull(dialogId);
        return full != null && (full.noforwards_peer_enabled || full.noforwards_my_enabled);
    }
}
