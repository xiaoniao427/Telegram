package org.telegram.onebot;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OneBot-specific minimal types — removes dependency on org.telegram.tgnet.TLRPC.
 *
 * ponytail: just the fields OneBotDelegate actually uses. Add fields as needed.
 */
public final class OneBotTypes {

    public static class OBUser {
        public long id;
        public String first_name;
        public String last_name;
        public boolean contact;
        public boolean bot;
        public boolean sex; // true=male, false=female in TLRPC convention
    }

    public static class OBChat {
        public long id;
        public String title;
        public int participants_count;
        public boolean megagroup;
    }

    public static class OBInputPeer {
        public long dialogId;
        public long accessHash;

        public OBInputPeer(long dialogId, long accessHash) {
            this.dialogId = dialogId;
            this.accessHash = accessHash;
        }
    }

    // ── Message ID allocator ─────────────────────────────────────

    private static final AtomicLong msgIdCounter = new AtomicLong(1);

    /** ponytail: local monotonic ID. Good enough for local echo until real server IDs arrive. */
    public static long nextMessageId() {
        return msgIdCounter.incrementAndGet();
    }
}