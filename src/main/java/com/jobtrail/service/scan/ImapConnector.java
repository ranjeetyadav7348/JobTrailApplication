package com.jobtrail.service.scan;

import com.jobtrail.domain.AppSettings;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * The one place that knows how to open the user's mailbox. Both reply detection
 * and the application scanner read the same account, so the connection details
 * live here rather than being spelled out twice.
 */
@Component
public class ImapConnector {

    public Store connect(AppSettings s) throws Exception {
        if (s.getImapHost() == null || s.getImapHost().isBlank()
                || s.getImapUsername() == null || s.getImapUsername().isBlank()) {
            throw new IllegalStateException("IMAP host and username are required");
        }

        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "30000");

        Store store = Session.getInstance(p).getStore("imaps");
        store.connect(s.getImapHost(), s.getImapPort(), s.getImapUsername(), s.getImapPassword());
        return store;
    }

    /** Opens a folder read-only. Never modifies the mailbox — not even read flags. */
    public Folder openReadOnly(Store store, String name) throws Exception {
        String folderName = (name == null || name.isBlank()) ? "INBOX" : name;
        Folder folder = store.getFolder(folderName);
        if (!folder.exists()) {
            throw new IllegalStateException("Mail folder \"" + folderName + "\" does not exist");
        }
        folder.open(Folder.READ_ONLY);
        return folder;
    }

    /** Closes quietly — a dead connection is not worth reporting to the user. */
    public void closeQuietly(Store store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (Exception ignored) {
            // nothing useful to do here
        }
    }
}
